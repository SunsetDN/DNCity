#pragma once

// Direct C++17 translation of network/kcp/Kcp.kt's algorithm (see that file's doc comment for the
// full rationale) -- per-segment selective ACK, adaptive RTO, and fast retransmit on top of a
// sliding send/receive window, deliberately *not* a full port of the reference KCP algorithm
// (github.com/skywind3000/kcp): no congestion-control window (slow start/ssthresh) and no
// window-probe (WASK/WINS) handshake, matching this mod's own private/LAN-scale voice/LOD traffic
// assumption. Wire format is byte-for-byte identical to the Kotlin version it replaces, so this is
// a drop-in backend swap, not a protocol change.
//
// Header-only, no OS-specific calls (sockets, threads, timers) at all -- input()/send()/recv()/
// update() are pure state-machine transitions driven by the caller (see jni_kcp.cpp), same
// division of responsibility as the Kotlin version had with VoiceKcpServer/VoiceKcpClient's own
// Netty event loop.
//
// One instance is one logical connection ("conv"). Not thread-safe -- a handle must only ever be
// used from one thread at a time (same contract as engine/audio's Codec2 JNI handles).

#include <cstdint>
#include <cstring>
#include <deque>
#include <vector>
#include <algorithm>

class DncityKcp {
public:
    explicit DncityKcp(int32_t conv) : conv_(conv) {}

    // Queues `data` for delivery, fragmenting across multiple segments if it exceeds one
    // segment's payload capacity (kMss). Actual sending happens later, from update()/flush().
    void send(const uint8_t* data, size_t len) {
        if (len == 0) return;
        size_t count = (len + kMss - 1) / kMss;
        size_t offset = 0;
        for (size_t i = 0; i < count; i++) {
            size_t size = std::min<size_t>(kMss, len - offset);
            Segment seg;
            seg.data.assign(data + offset, data + offset + size);
            seg.frg = static_cast<int32_t>(count - i - 1);
            sndQueue_.push_back(std::move(seg));
            offset += size;
        }
    }

    // Returns the next fully-reassembled message, or false if none is complete yet.
    bool recv(std::vector<uint8_t>& out) {
        int64_t size = peekSize();
        if (size < 0) return false;
        out.clear();
        out.reserve(static_cast<size_t>(size));
        while (true) {
            Segment seg = std::move(rcvQueue_.front());
            rcvQueue_.pop_front();
            out.insert(out.end(), seg.data.begin(), seg.data.end());
            if (seg.frg == 0) break;
        }
        return true;
    }

    // Feeds one raw incoming datagram (which may contain several concatenated segments, per
    // flush()'s own packing) into the protocol state machine.
    void input(const uint8_t* data, size_t len) {
        size_t offset = 0;
        while (len - offset >= kOverhead) {
            size_t p = offset;
            int32_t segConv = decode32(data, p); p += 4;
            if (segConv != conv_) return; // not for this session -- already demuxed by conv
            int32_t cmd = decode8(data, p); p += 1;
            int32_t frg = decode8(data, p); p += 1;
            decode16(data, p); p += 2; // peer's advertised window -- unused, see class doc
            int64_t ts = static_cast<uint32_t>(decode32(data, p)); p += 4;
            int64_t sn = static_cast<uint32_t>(decode32(data, p)); p += 4;
            int64_t una = static_cast<uint32_t>(decode32(data, p)); p += 4;
            int32_t segLen = decode32(data, p); p += 4;
            offset = p;
            if (len - offset < static_cast<size_t>(segLen)) return;

            parseUna(una);

            if (cmd == kCmdPush) {
                // Ack regardless of whether this is new data -- if sn < rcvNxt, it's already
                // been delivered and the peer's own earlier ack for it was likely lost.
                ackList_.push_back({sn, ts});
                if (sn >= rcvNxt_) {
                    Segment seg;
                    seg.data.assign(data + offset, data + offset + segLen);
                    seg.sn = sn;
                    seg.frg = frg;
                    insertIntoRcvBuf(std::move(seg));
                }
            } else if (cmd == kCmdAck) {
                if (current_ >= ts) updateRto(current_ - ts);
                parseAck(sn);
            }
            offset += segLen;
        }
        moveRcvBufToQueue();
    }

    // Drives retransmission/flush timing -- call at a fixed short interval, passing the caller's
    // own monotonic millisecond clock. Any bytes that need to go on the wire are pushed onto the
    // output queue, drained via pollOutput().
    void update(int64_t currentMs) {
        current_ = currentMs;
        started_ = true;
        flush();
    }

    // Pops the next pending outbound datagram, or false if none is queued.
    bool pollOutput(std::vector<uint8_t>& out) {
        if (outputQueue_.empty()) return false;
        out = std::move(outputQueue_.front());
        outputQueue_.pop_front();
        return true;
    }

private:
    struct Segment {
        std::vector<uint8_t> data;
        int32_t frg = 0;
        int64_t sn = 0;
        int64_t resendTs = 0;
        int64_t rto = kRtoDefault;
        int32_t fastAck = 0;
        int32_t xmit = 0;
    };

    int64_t peekSize() const {
        if (rcvQueue_.empty()) return -1;
        int64_t len = 0;
        for (const auto& seg : rcvQueue_) {
            len += static_cast<int64_t>(seg.data.size());
            if (seg.frg == 0) return len;
        }
        return -1; // first message isn't fully reassembled yet
    }

    void parseUna(int64_t una) {
        while (!sndBuf_.empty() && sndBuf_.front().sn < una) sndBuf_.pop_front();
        if (una > sndUna_) sndUna_ = una;
    }

    void parseAck(int64_t sn) {
        if (sn < sndUna_ || sn >= sndNxt_) return;
        for (auto it = sndBuf_.begin(); it != sndBuf_.end(); ++it) {
            if (it->sn == sn) { sndBuf_.erase(it); return; }
            if (it->sn < sn) { it->fastAck++; }
            else { return; } // sndBuf is sn-ordered; nothing further can match or need incrementing
        }
    }

    void updateRto(int64_t rtt) {
        if (rxSrtt_ == 0) {
            rxSrtt_ = rtt;
            rxRttval_ = rtt / 2;
        } else {
            int64_t delta = std::llabs(rtt - rxSrtt_);
            rxRttval_ = (3 * rxRttval_ + delta) / 4;
            rxSrtt_ = (7 * rxSrtt_ + rtt) / 8;
            if (rxSrtt_ < 1) rxSrtt_ = 1;
        }
        int64_t rto = rxSrtt_ + std::max<int64_t>(kIntervalMs, 4 * rxRttval_);
        rxRto_ = std::clamp<int64_t>(rto, kRtoMin, kRtoMax);
    }

    void insertIntoRcvBuf(Segment&& newSeg) {
        size_t index = rcvBuf_.size();
        for (size_t i = rcvBuf_.size(); i-- > 0;) {
            if (rcvBuf_[i].sn == newSeg.sn) return; // duplicate
            if (rcvBuf_[i].sn < newSeg.sn) { index = i + 1; break; }
            index = i;
        }
        rcvBuf_.insert(rcvBuf_.begin() + static_cast<long>(index), std::move(newSeg));
    }

    void moveRcvBufToQueue() {
        while (!rcvBuf_.empty()) {
            Segment& seg = rcvBuf_.front();
            if (seg.sn == rcvNxt_ && rcvQueue_.size() < kRcvWnd) {
                rcvQueue_.push_back(std::move(rcvBuf_.front()));
                rcvBuf_.pop_front();
                rcvNxt_++;
            } else {
                break;
            }
        }
    }

    void flush() {
        if (!started_) return;

        for (const auto& [sn, ts] : ackList_) appendSegment(kCmdAck, sn, ts, 0, nullptr, 0);
        ackList_.clear();

        int64_t window = std::max<int64_t>(1, std::min<int64_t>(kSndWnd, rmtWnd_));
        while (sndNxt_ < sndUna_ + window && !sndQueue_.empty()) {
            Segment seg = std::move(sndQueue_.front());
            sndQueue_.pop_front();
            seg.sn = sndNxt_++;
            seg.rto = rxRto_;
            seg.xmit = 0;
            seg.resendTs = current_;
            sndBuf_.push_back(std::move(seg));
        }

        for (auto& seg : sndBuf_) {
            bool due = seg.xmit == 0 || current_ >= seg.resendTs || seg.fastAck >= kFastResendLimit;
            if (!due) continue;
            seg.xmit++;
            seg.fastAck = 0;
            if (seg.xmit > 1) seg.rto = std::min<int64_t>(seg.rto + seg.rto / 2, kRtoMax);
            seg.resendTs = current_ + seg.rto;
            appendSegment(kCmdPush, seg.sn, current_, seg.frg, seg.data.data(), seg.data.size());
        }

        flushOutChunk();
    }

    void appendSegment(int32_t cmd, int64_t sn, int64_t ts, int32_t frg, const uint8_t* data, size_t dataLen) {
        size_t segSize = kOverhead + dataLen;
        if (outChunk_.size() + segSize > kMtu) flushOutChunk();
        size_t p = outChunk_.size();
        outChunk_.resize(p + segSize);
        p = encode32(outChunk_, p, conv_);
        p = encode8(outChunk_, p, cmd);
        p = encode8(outChunk_, p, frg);
        p = encode16(outChunk_, p, static_cast<int32_t>(std::max<int64_t>(0, static_cast<int64_t>(kRcvWnd) - static_cast<int64_t>(rcvQueue_.size()))));
        p = encode32(outChunk_, p, static_cast<int32_t>(ts));
        p = encode32(outChunk_, p, static_cast<int32_t>(sn));
        p = encode32(outChunk_, p, static_cast<int32_t>(rcvNxt_));
        p = encode32(outChunk_, p, static_cast<int32_t>(dataLen));
        if (dataLen > 0) std::memcpy(outChunk_.data() + p, data, dataLen);
    }

    void flushOutChunk() {
        if (outChunk_.empty()) return;
        outputQueue_.push_back(std::move(outChunk_));
        outChunk_.clear();
    }

    // ---- little-endian encode/decode helpers, matching Kcp.kt's own manual layout exactly ----

    static size_t encode8(std::vector<uint8_t>& buf, size_t offset, int32_t v) {
        buf[offset] = static_cast<uint8_t>(v);
        return offset + 1;
    }
    static int32_t decode8(const uint8_t* buf, size_t offset) { return buf[offset]; }

    static size_t encode16(std::vector<uint8_t>& buf, size_t offset, int32_t v) {
        buf[offset] = static_cast<uint8_t>(v);
        buf[offset + 1] = static_cast<uint8_t>(v >> 8);
        return offset + 2;
    }
    static int32_t decode16(const uint8_t* buf, size_t offset) {
        return static_cast<int32_t>(buf[offset]) | (static_cast<int32_t>(buf[offset + 1]) << 8);
    }

    static size_t encode32(std::vector<uint8_t>& buf, size_t offset, int32_t v) {
        buf[offset] = static_cast<uint8_t>(v);
        buf[offset + 1] = static_cast<uint8_t>(v >> 8);
        buf[offset + 2] = static_cast<uint8_t>(v >> 16);
        buf[offset + 3] = static_cast<uint8_t>(v >> 24);
        return offset + 4;
    }
    static int32_t decode32(const uint8_t* buf, size_t offset) {
        return static_cast<int32_t>(
            (static_cast<uint32_t>(buf[offset])) |
            (static_cast<uint32_t>(buf[offset + 1]) << 8) |
            (static_cast<uint32_t>(buf[offset + 2]) << 16) |
            (static_cast<uint32_t>(buf[offset + 3]) << 24));
    }

    static constexpr size_t kMtu = 1200;
    static constexpr size_t kOverhead = 24;
    static constexpr size_t kMss = kMtu - kOverhead;

    static constexpr int64_t kSndWnd = 128;
    static constexpr size_t kRcvWnd = 128;

    static constexpr int64_t kIntervalMs = 10;
    static constexpr int64_t kRtoMin = 30;
    static constexpr int64_t kRtoDefault = 200;
    static constexpr int64_t kRtoMax = 5000;

    // Fast-retransmit threshold ("resend=2" in upstream terms): a segment is resent as soon as
    // this many later segments have been acked ahead of it, without waiting for its own RTO.
    static constexpr int32_t kFastResendLimit = 2;

    static constexpr int32_t kCmdPush = 81;
    static constexpr int32_t kCmdAck = 82;

    int32_t conv_;

    // ---- send side ----
    int64_t sndUna_ = 0;
    int64_t sndNxt_ = 0;
    std::deque<Segment> sndQueue_;
    std::deque<Segment> sndBuf_;

    // ---- receive side ----
    int64_t rcvNxt_ = 0;
    std::deque<Segment> rcvBuf_;
    std::deque<Segment> rcvQueue_;
    std::deque<std::pair<int64_t, int64_t>> ackList_; // pending (sn, ts) pairs to acknowledge

    // ---- RTT/RTO estimation (RFC 6298-style) ----
    int64_t rxSrtt_ = 0;
    int64_t rxRttval_ = 0;
    int64_t rxRto_ = kRtoDefault;

    int64_t rmtWnd_ = kRcvWnd;
    int64_t current_ = 0;
    bool started_ = false;

    std::vector<uint8_t> outChunk_;
    std::deque<std::vector<uint8_t>> outputQueue_;
};
