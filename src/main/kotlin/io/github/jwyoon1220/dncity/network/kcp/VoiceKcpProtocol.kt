package io.github.jwyoon1220.dncity.network.kcp

import io.github.jwyoon1220.dncity.network.RadioRangeInfo
import io.github.jwyoon1220.dncity.network.RadioSenderPosition
import io.github.jwyoon1220.dncity.radio.RadioMode
import java.nio.ByteBuffer

/**
 * The application-level message format carried inside each [Kcp] message (itself carried inside
 * [VoiceKcpServer]/[VoiceKcpClient]'s UDP datagrams) -- everything close-range voice/radio/phone-
 * call audio used to encode via [net.minecraft.network.codec.StreamCodec] now packs by hand into a
 * plain [ByteArray] instead, since none of this rides Minecraft's own packet channel any more. One
 * leading type-tag byte selects the layout; every audio-carrying message also carries an 8-byte
 * send timestamp so the receiver can drop a frame that arrived more than [STALE_MS] late after a
 * KCP retransmit, bounding worst-case audio latency (KCP is a *reliable* protocol -- see [Kcp]'s
 * doc comment -- so without this, a burst of loss could otherwise cause a backlog of stale audio
 * to arrive all at once).
 */
object VoiceKcpProtocol {
    const val TYPE_HELLO: Byte = 0
    const val TYPE_VOICE_AUDIO: Byte = 1
    const val TYPE_VOICE_AUDIO_RELAY: Byte = 2
    const val TYPE_RADIO_AUDIO: Byte = 3
    const val TYPE_RADIO_AUDIO_RELAY: Byte = 4
    const val TYPE_PHONE_CALL_AUDIO: Byte = 5
    const val TYPE_PHONE_CALL_AUDIO_RELAY: Byte = 6

    /** Opaque bulk data (e.g. Distant Horizons LOD/terrain streaming payloads) riding the same
     * per-player [NativeKcpSession] as voice/radio/phone audio, demuxed by this same leading
     * type-tag byte -- see [VoiceKcpServer.sendLodData]/[VoiceKcpClient.sendLodData]. Unlike the
     * audio types above this carries no send timestamp: LOD data isn't perishable the way a stale
     * audio frame is, so there's nothing to drop on a late/retransmitted arrival. */
    const val TYPE_LOD_DATA: Byte = 7

    const val STALE_MS = 250L

    /** Same cap the old [net.minecraft.network.codec.StreamCodec]-based payloads enforced (see git
     * history's `VoicePayloads`/`RadioVoicePayloads`/`PhonePayloads`) -- without it, a message
     * claiming a huge `buf.remaining()` would force an unbounded server- or client-side allocation
     * per packet. */
    private const val MAX_AUDIO_FRAME_BYTES = 2048

    /** Distant Horizons LOD/terrain payloads are legitimately much larger than an audio frame, but
     * still need *some* cap so a malformed/hostile message can't force an unbounded allocation. */
    private const val MAX_LOD_PAYLOAD_BYTES = 1 shl 20

    private class MalformedMessageException(message: String) : Exception(message)

    private fun requireRemaining(buf: ByteBuffer, bytes: Int, what: String) {
        if (buf.remaining() < bytes) throw MalformedMessageException("truncated $what: need $bytes more bytes, have ${buf.remaining()}")
    }

    private fun readBoundedPayload(buf: ByteBuffer, max: Int, what: String): ByteArray {
        val size = buf.remaining()
        if (size > max) throw MalformedMessageException("$what too large: $size > $max bytes")
        val payload = ByteArray(size)
        buf.get(payload)
        return payload
    }

    // ---- HELLO: the first message a client sends on a fresh UDP session, proving it holds the
    // secret the server handed it over Minecraft's own (already encrypted/authenticated) channel
    // -- see network.VoiceKcpHandshakePayload. ----

    fun encodeHello(conv: Int, secret: Long): ByteArray =
        ByteBuffer.allocate(1 + 4 + 8).put(TYPE_HELLO).putInt(conv).putLong(secret).array()

    class Hello(val conv: Int, val secret: Long)

    fun decodeHello(data: ByteArray): Hello {
        val buf = ByteBuffer.wrap(data)
        buf.get() // type tag, already dispatched on
        requireRemaining(buf, 4 + 8, "HELLO")
        return Hello(buf.int, buf.long)
    }

    // ---- Plain audio frames (close-range uplink, radio uplink, phone-call up/downlink -- all
    // just a timestamp plus opaque codec bytes, no extra addressing needed). ----

    fun encodeAudio(type: Byte, audio: ByteArray): ByteArray =
        ByteBuffer.allocate(1 + 8 + audio.size).put(type).putLong(System.currentTimeMillis()).put(audio).array()

    class TimedAudio(sentAtMs: Long, val audio: ByteArray) {
        val isStale = System.currentTimeMillis() - sentAtMs > STALE_MS
    }

    fun decodeAudio(data: ByteArray): TimedAudio {
        val buf = ByteBuffer.wrap(data)
        buf.get()
        requireRemaining(buf, 8, "audio frame")
        val sentAt = buf.long
        val audio = readBoundedPayload(buf, MAX_AUDIO_FRAME_BYTES, "audio frame")
        return TimedAudio(sentAt, audio)
    }

    // ---- LOD/terrain data (opaque bulk payload, either direction -- see TYPE_LOD_DATA's doc
    // comment). No addressing or timestamp: the payload's own format (e.g. Distant Horizons'
    // already-compressed FullDataPayload bytes) is meaningless to this transport layer. ----

    fun encodeLodData(payload: ByteArray): ByteArray =
        ByteBuffer.allocate(1 + payload.size).put(TYPE_LOD_DATA).put(payload).array()

    fun decodeLodData(data: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(data)
        buf.get()
        return readBoundedPayload(buf, MAX_LOD_PAYLOAD_BYTES, "LOD payload")
    }

    // ---- Close-range voice relay (server -> one in-range listener): needs the speaker's entity
    // id so the client can key its per-speaker decoder and look up their live position for gain. ----

    fun encodeVoiceRelay(senderEntityId: Int, audio: ByteArray): ByteArray =
        ByteBuffer.allocate(1 + 8 + 4 + audio.size)
            .put(TYPE_VOICE_AUDIO_RELAY).putLong(System.currentTimeMillis()).putInt(senderEntityId).put(audio)
            .array()

    class VoiceRelay(sentAtMs: Long, val senderEntityId: Int, val audio: ByteArray) {
        val isStale = System.currentTimeMillis() - sentAtMs > STALE_MS
    }

    fun decodeVoiceRelay(data: ByteArray): VoiceRelay {
        val buf = ByteBuffer.wrap(data)
        buf.get()
        requireRemaining(buf, 8 + 4, "voice relay")
        val sentAt = buf.long
        val senderEntityId = buf.int
        val audio = readBoundedPayload(buf, MAX_AUDIO_FRAME_BYTES, "voice relay")
        return VoiceRelay(sentAt, senderEntityId, audio)
    }

    // ---- Radio relay (server -> one qualifying listener, handheld or station broadcast alike --
    // see io.github.jwyoon1220.dncity.voice.RadioRelay): the richest message, carrying everything
    // io.github.jwyoon1220.dncity.voice.ClientRadioReceiver.handleRelayedFrame needs. ----

    fun encodeRadioRelay(
        senderEntityId: Int,
        audio: ByteArray,
        frequencyKhz: Double,
        modeName: String,
        senderPosition: RadioSenderPosition,
        rangeInfo: RadioRangeInfo,
    ): ByteArray {
        val modeCode = RadioMode.entries.indexOfFirst { it.name.equals(modeName, ignoreCase = true) }
        require(modeCode >= 0) { "Unknown RadioMode: $modeName" }
        return ByteBuffer.allocate(1 + 8 + 4 + 8 + 1 + 8 + 8 + 8 + 8 + 1 + audio.size)
            .put(TYPE_RADIO_AUDIO_RELAY)
            .putLong(System.currentTimeMillis())
            .putInt(senderEntityId)
            .putDouble(frequencyKhz)
            .put(modeCode.toByte())
            .putDouble(senderPosition.x).putDouble(senderPosition.y).putDouble(senderPosition.z)
            .putDouble(rangeInfo.effectiveMaxRangeBlocks)
            .put(RadioRangeInfo.flagsOf(rangeInfo).toByte())
            .put(audio)
            .array()
    }

    class RadioRelay(
        sentAtMs: Long,
        val senderEntityId: Int,
        val audio: ByteArray,
        val frequencyKhz: Double,
        val modeName: String,
        val senderPosition: RadioSenderPosition,
        val rangeInfo: RadioRangeInfo,
    ) {
        val isStale = System.currentTimeMillis() - sentAtMs > STALE_MS
    }

    fun decodeRadioRelay(data: ByteArray): RadioRelay {
        val buf = ByteBuffer.wrap(data)
        buf.get()
        requireRemaining(buf, 8 + 4 + 8 + 1 + 8 + 8 + 8 + 8 + 1, "radio relay")
        val sentAt = buf.long
        val senderEntityId = buf.int
        val frequencyKhz = buf.double
        val modeCode = buf.get().toInt()
        val x = buf.double; val y = buf.double; val z = buf.double
        val effectiveMaxRangeBlocks = buf.double
        val flags = buf.get().toInt()
        val audio = readBoundedPayload(buf, MAX_AUDIO_FRAME_BYTES, "radio relay")
        val mode = RadioMode.entries.getOrNull(modeCode)
            ?: throw MalformedMessageException("radio relay: mode code $modeCode out of range")
        return RadioRelay(
            sentAt, senderEntityId, audio, frequencyKhz, mode.name,
            RadioSenderPosition(x, y, z),
            RadioRangeInfo.of(effectiveMaxRangeBlocks, (flags and 1) != 0, (flags and 2) != 0),
        )
    }
}
