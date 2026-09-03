package io.github.jwyoon1220.dncity.network.kcp

/**
 * A from-scratch Kotlin implementation of the essential mechanics of the KCP ARQ protocol
 * (github.com/skywind3000/kcp): per-segment selective ACK, adaptive RTO, and fast retransmit on
 * top of a sliding send/receive window -- the parts that make it a useful low-latency alternative
 * to raw unreliable UDP for this mod's voice traffic (see [VoiceKcpServer]/[VoiceKcpClient]).
 *
 * Deliberately **not** a full port of the reference algorithm: there is no congestion-control
 * window (slow start/ssthresh) and no window-probe (WASK/WINS) handshake -- this mod's own
 * transport tuning always runs in "no congestion control" mode (`nc=1` in upstream terms) since
 * voice traffic is small, bursty, and only ever crosses a private/LAN-scale link (see
 * `RadioBand`'s own doc comment on the map's ~7.5km² scale), so the extra machinery upstream needs
 * for internet-scale fairness would only add complexity without changing behavior here. `sn`/`una`
 * sequence numbers are tracked as unwrapped [Long]s rather than the reference's 32-bit wraparound
 * arithmetic -- at this protocol's realistic frame rate (tens of segments/second) a session would
 * need to run for months before that matters, comfortably longer than this mod's actual server
 * uptime between restarts.
 *
 * One instance is one logical connection ("conv"). Message-oriented, not stream-oriented: each
 * [send] call is reassembled into exactly one [recv] on the peer, regardless of how many segments
 * it had to be split across. Knows nothing about sockets or threads -- [output] is invoked with
 * the exact bytes to put on the wire for this conv, and [update]/[input] are meant to be driven by
 * the caller's own timer/event loop (see [VoiceKcpServer]/[VoiceKcpClient], which drive it from a
 * dedicated Netty event-loop-scheduled tick rather than Minecraft's own tick rate, so voice
 * latency isn't bounded by the game's 50ms tick).
 */
class Kcp(private val conv: Int, private val output: (ByteArray) -> Unit) {

    private class Segment(var data: ByteArray = EMPTY) {
        var frg: Int = 0
        var sn: Long = 0
        var resendTs: Long = 0
        var rto: Long = RTO_DEFAULT
        var fastAck: Int = 0
        var xmit: Int = 0
    }

    // ---- send side ----
    private var sndUna = 0L
    private var sndNxt = 0L
    private val sndQueue = ArrayDeque<Segment>()
    private val sndBuf = ArrayDeque<Segment>()

    // ---- receive side ----
    private var rcvNxt = 0L
    private val rcvBuf = ArrayDeque<Segment>()
    private val rcvQueue = ArrayDeque<Segment>()
    private val ackList = ArrayList<LongArray>() // pending (sn, ts) pairs to acknowledge

    // ---- RTT/RTO estimation (RFC 6298-style) ----
    private var rxSrtt = 0L
    private var rxRttval = 0L
    private var rxRto = RTO_DEFAULT

    private var rmtWnd = RCV_WND
    private var current = 0L
    private var started = false

    private var outChunk = ByteArray(0)
    private var outChunkLen = 0

    /** Queues [data] for delivery, fragmenting across multiple segments if it exceeds one
     * segment's payload capacity ([MSS]). Actual sending happens later, from [update]/[flush]. */
    fun send(data: ByteArray) {
        if (data.isEmpty()) return
        val count = (data.size + MSS - 1) / MSS
        var offset = 0
        for (i in 0 until count) {
            val size = minOf(MSS, data.size - offset)
            val seg = Segment(data.copyOfRange(offset, offset + size))
            seg.frg = count - i - 1
            sndQueue.addLast(seg)
            offset += size
        }
    }

    /** Returns the next fully-reassembled message, or `null` if none is complete yet. */
    fun recv(): ByteArray? {
        val size = peekSize() ?: return null
        val result = ByteArray(size)
        var offset = 0
        while (true) {
            val seg = rcvQueue.removeFirst()
            System.arraycopy(seg.data, 0, result, offset, seg.data.size)
            offset += seg.data.size
            if (seg.frg == 0) break
        }
        return result
    }

    private fun peekSize(): Int? {
        if (rcvQueue.isEmpty()) return null
        var len = 0
        for (seg in rcvQueue) {
            len += seg.data.size
            if (seg.frg == 0) return len
        }
        return null // first message isn't fully reassembled yet
    }

    /** Feeds one raw incoming datagram (which may contain several concatenated segments, per
     * [flush]'s own packing) into the protocol state machine. */
    fun input(data: ByteArray) {
        var offset = 0
        while (data.size - offset >= OVERHEAD) {
            var p = offset
            val segConv = decode32(data, p); p += 4
            if (segConv != conv) return // not for this session -- shouldn't happen, already demuxed by conv
            val cmd = decode8(data, p); p += 1
            val frg = decode8(data, p); p += 1
            decode16(data, p); p += 2 // peer's advertised window -- read for wire-format parity, unused (see class doc: no probe/congestion-control)
            val ts = decode32(data, p).toLong() and 0xFFFFFFFFL; p += 4
            val sn = decode32(data, p).toLong() and 0xFFFFFFFFL; p += 4
            val una = decode32(data, p).toLong() and 0xFFFFFFFFL; p += 4
            val len = decode32(data, p); p += 4
            offset = p
            if (data.size - offset < len) return

            parseUna(una)

            when (cmd) {
                CMD_PUSH -> {
                    // Ack regardless of whether this is new data -- if sn < rcvNxt, it's already
                    // been delivered and the peer's own earlier ack for it was likely lost.
                    ackList.add(longArrayOf(sn, ts))
                    if (sn >= rcvNxt) {
                        val seg = Segment(data.copyOfRange(offset, offset + len))
                        seg.sn = sn
                        seg.frg = frg
                        insertIntoRcvBuf(seg) // de-dupes internally
                    }
                }
                CMD_ACK -> {
                    if (current >= ts) updateRto(current - ts)
                    parseAck(sn)
                }
            }
            offset += len
        }
        moveRcvBufToQueue()
    }

    private fun parseUna(una: Long) {
        while (sndBuf.isNotEmpty() && sndBuf.first().sn < una) sndBuf.removeFirst()
        if (una > sndUna) sndUna = una
    }

    private fun parseAck(sn: Long) {
        if (sn < sndUna || sn >= sndNxt) return
        val it = sndBuf.iterator()
        while (it.hasNext()) {
            val seg = it.next()
            when {
                seg.sn == sn -> { it.remove(); return }
                seg.sn < sn -> seg.fastAck++
                else -> return // sndBuf is sn-ordered; nothing further can match or need incrementing
            }
        }
    }

    private fun updateRto(rtt: Long) {
        if (rxSrtt == 0L) {
            rxSrtt = rtt
            rxRttval = rtt / 2
        } else {
            val delta = kotlin.math.abs(rtt - rxSrtt)
            rxRttval = (3 * rxRttval + delta) / 4
            rxSrtt = (7 * rxSrtt + rtt) / 8
            if (rxSrtt < 1) rxSrtt = 1
        }
        rxRto = (rxSrtt + maxOf(INTERVAL_MS, 4 * rxRttval)).coerceIn(RTO_MIN, RTO_MAX)
    }

    private fun insertIntoRcvBuf(newSeg: Segment) {
        var index = rcvBuf.size
        for (i in rcvBuf.indices.reversed()) {
            val seg = rcvBuf[i]
            if (seg.sn == newSeg.sn) return // duplicate
            if (seg.sn < newSeg.sn) { index = i + 1; break }
            index = i
        }
        rcvBuf.add(index, newSeg)
    }

    private fun moveRcvBufToQueue() {
        while (rcvBuf.isNotEmpty()) {
            val seg = rcvBuf.first()
            if (seg.sn == rcvNxt && rcvQueue.size < RCV_WND) {
                rcvBuf.removeFirst()
                rcvQueue.addLast(seg)
                rcvNxt++
            } else {
                break
            }
        }
    }

    /** Drives retransmission/flush timing -- call at a fixed short interval (see [VoiceKcpServer]/
     * [VoiceKcpClient]'s own scheduled tick), passing the caller's own monotonic millisecond
     * clock. */
    fun update(currentMs: Long) {
        current = currentMs
        started = true
        flush()
    }

    private fun flush() {
        if (!started) return

        for ((sn, ts) in ackList) appendSegment(CMD_ACK, sn, ts, 0, EMPTY)
        ackList.clear()

        val window = minOf(SND_WND, rmtWnd).coerceAtLeast(1)
        while (sndNxt < sndUna + window && sndQueue.isNotEmpty()) {
            val seg = sndQueue.removeFirst()
            seg.sn = sndNxt++
            seg.rto = rxRto
            seg.xmit = 0
            seg.resendTs = current
            sndBuf.addLast(seg)
        }

        for (seg in sndBuf) {
            val due = seg.xmit == 0 || current >= seg.resendTs || seg.fastAck >= FAST_RESEND_LIMIT
            if (!due) continue
            seg.xmit++
            seg.fastAck = 0
            if (seg.xmit > 1) seg.rto = (seg.rto + seg.rto / 2).coerceAtMost(RTO_MAX)
            seg.resendTs = current + seg.rto
            appendSegment(CMD_PUSH, seg.sn, current, seg.frg, seg.data)
        }

        flushOutChunk()
    }

    private fun appendSegment(cmd: Int, sn: Long, ts: Long, frg: Int, data: ByteArray) {
        val segSize = OVERHEAD + data.size
        if (outChunkLen + segSize > MTU) flushOutChunk()
        if (outChunk.size < outChunkLen + segSize) outChunk = outChunk.copyOf(maxOf(MTU, outChunkLen + segSize))
        var p = outChunkLen
        p = encode32(outChunk, p, conv)
        p = encode8(outChunk, p, cmd)
        p = encode8(outChunk, p, frg)
        p = encode16(outChunk, p, (RCV_WND - rcvQueue.size).coerceAtLeast(0))
        p = encode32(outChunk, p, ts.toInt())
        p = encode32(outChunk, p, sn.toInt())
        p = encode32(outChunk, p, rcvNxt.toInt())
        p = encode32(outChunk, p, data.size)
        System.arraycopy(data, 0, outChunk, p, data.size)
        outChunkLen = p + data.size
    }

    private fun flushOutChunk() {
        if (outChunkLen == 0) return
        output(outChunk.copyOf(outChunkLen))
        outChunkLen = 0
    }

    companion object {
        private val EMPTY = ByteArray(0)

        private const val MTU = 1200
        private const val OVERHEAD = 24
        private const val MSS = MTU - OVERHEAD

        private const val SND_WND = 128
        private const val RCV_WND = 128

        private const val INTERVAL_MS = 10L
        private const val RTO_MIN = 30L
        private const val RTO_DEFAULT = 200L
        private const val RTO_MAX = 5000L

        /** Fast-retransmit threshold ("resend=2" in upstream terms): a segment is resent as soon
         * as this many later segments have been acked ahead of it, without waiting for its own
         * RTO to expire. */
        private const val FAST_RESEND_LIMIT = 2

        private const val CMD_PUSH = 81
        private const val CMD_ACK = 82

        private fun encode8(buf: ByteArray, offset: Int, v: Int): Int {
            buf[offset] = v.toByte()
            return offset + 1
        }

        private fun decode8(buf: ByteArray, offset: Int): Int = buf[offset].toInt() and 0xFF

        private fun encode16(buf: ByteArray, offset: Int, v: Int): Int {
            buf[offset] = v.toByte()
            buf[offset + 1] = (v ushr 8).toByte()
            return offset + 2
        }

        private fun decode16(buf: ByteArray, offset: Int): Int =
            (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

        private fun encode32(buf: ByteArray, offset: Int, v: Int): Int {
            buf[offset] = v.toByte()
            buf[offset + 1] = (v ushr 8).toByte()
            buf[offset + 2] = (v ushr 16).toByte()
            buf[offset + 3] = (v ushr 24).toByte()
            return offset + 4
        }

        private fun decode32(buf: ByteArray, offset: Int): Int =
            (buf[offset].toInt() and 0xFF) or
                ((buf[offset + 1].toInt() and 0xFF) shl 8) or
                ((buf[offset + 2].toInt() and 0xFF) shl 16) or
                ((buf[offset + 3].toInt() and 0xFF) shl 24)
    }
}
