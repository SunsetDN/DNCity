// AGENT-DONE(claude): feature-mods
package io.github.jwyoon1220.dncity.radio.network

import io.github.jwyoon1220.dncity.network.kcp.VoiceKcpProtocol.MAX_AUDIO_FRAME_BYTES
import io.github.jwyoon1220.dncity.network.kcp.VoiceKcpProtocol.MalformedMessageException
import io.github.jwyoon1220.dncity.network.kcp.VoiceKcpProtocol.STALE_MS
import io.github.jwyoon1220.dncity.network.kcp.VoiceKcpProtocol.TYPE_RADIO_AUDIO_RELAY
import io.github.jwyoon1220.dncity.network.kcp.VoiceKcpProtocol.readBoundedPayload
import io.github.jwyoon1220.dncity.network.kcp.VoiceKcpProtocol.requireRemaining
import io.github.jwyoon1220.dncity.network.kcp.VoiceKcpServer
import io.github.jwyoon1220.dncity.radio.RadioMode
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Radio relay (server -> one qualifying listener, handheld or station broadcast alike -- see
 * [io.github.jwyoon1220.dncity.voice.RadioRelay]): the richest audio message, carrying everything
 * [io.github.jwyoon1220.dncity.voice.ClientRadioReceiver.handleRelayedFrame] needs. Lives in `dncity_comms` because it
 * speaks radio types; the transport ([io.github.jwyoon1220.dncity.network.kcp.VoiceKcpServer]) only moves bytes.
 */
object RadioKcpMessages {
    /** Encodes and sends one relayed radio frame to [playerUuid] over the raw audio lane. */
    fun send(
        playerUuid: UUID,
        senderEntityId: Int,
        audio: ByteArray,
        frequencyKhz: Double,
        modeName: String,
        senderPosition: RadioSenderPosition,
        rangeInfo: RadioRangeInfo,
    ) = VoiceKcpServer.sendAudio(playerUuid, encodeRadioRelay(senderEntityId, audio, frequencyKhz, modeName, senderPosition, rangeInfo))

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
