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

    const val STALE_MS = 250L

    // ---- HELLO: the first message a client sends on a fresh UDP session, proving it holds the
    // secret the server handed it over Minecraft's own (already encrypted/authenticated) channel
    // -- see network.VoiceKcpHandshakePayload. ----

    fun encodeHello(conv: Int, secret: Long): ByteArray =
        ByteBuffer.allocate(1 + 4 + 8).put(TYPE_HELLO).putInt(conv).putLong(secret).array()

    class Hello(val conv: Int, val secret: Long)

    fun decodeHello(data: ByteArray): Hello {
        val buf = ByteBuffer.wrap(data)
        buf.get() // type tag, already dispatched on
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
        val sentAt = buf.long
        val audio = ByteArray(buf.remaining())
        buf.get(audio)
        return TimedAudio(sentAt, audio)
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
        val sentAt = buf.long
        val senderEntityId = buf.int
        val audio = ByteArray(buf.remaining())
        buf.get(audio)
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
        val sentAt = buf.long
        val senderEntityId = buf.int
        val frequencyKhz = buf.double
        val modeCode = buf.get().toInt()
        val x = buf.double; val y = buf.double; val z = buf.double
        val effectiveMaxRangeBlocks = buf.double
        val flags = buf.get().toInt()
        val audio = ByteArray(buf.remaining())
        buf.get(audio)
        return RadioRelay(
            sentAt, senderEntityId, audio, frequencyKhz, RadioMode.entries[modeCode].name,
            RadioSenderPosition(x, y, z),
            RadioRangeInfo.of(effectiveMaxRangeBlocks, (flags and 1) != 0, (flags and 2) != 0),
        )
    }
}
