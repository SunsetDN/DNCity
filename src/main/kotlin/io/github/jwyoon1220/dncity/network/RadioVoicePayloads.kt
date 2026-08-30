package io.github.jwyoon1220.dncity.network

import io.github.jwyoon1220.dncity.Dncity
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/**
 * One ~20ms radio-voice frame, sent from a transmitting (PTT-held) client to the server. Which
 * codec encoded it depends on the active slot's [io.github.jwyoon1220.dncity.radio.RadioMode] --
 * AM/FM use Opus, USB uses codec2 (see [io.github.jwyoon1220.dncity.voice.RadioTransmitter]) --
 * not carried in the payload itself, since the server/receiving client both already know the
 * mode from the sender's tuned slot. Carries no frequency/mode either -- the server reads those
 * authoritatively off the sender's held radio's active slot (see
 * [io.github.jwyoon1220.dncity.voice.RadioRelay]), the same way
 * [io.github.jwyoon1220.dncity.radio.RadioActions] never trusts client-declared state.
 */
class RadioAudioPayload(val audioData: ByteArray) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        // Comfortably above both codecs' largest per-frame size (codec2: a handful of bytes;
        // Opus: bounded by OpusCodec's 1024-byte mtuSize) -- a hard cap so a malformed/hostile
        // client can't make the server allocate an unbounded buffer per packet.
        private const val MAX_AUDIO_FRAME_BYTES = 2048

        val TYPE: CustomPacketPayload.Type<RadioAudioPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "radio_audio"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, RadioAudioPayload> = StreamCodec.composite(
            ByteBufCodecs.byteArray(MAX_AUDIO_FRAME_BYTES), RadioAudioPayload::audioData,
            ::RadioAudioPayload,
        )
    }
}

/** The transmitter's world position at the moment it sent this frame, as of [RadioRelay.relay]'s
 * own snapshot of it -- bundled into its own small codec so [RadioAudioRelayPayload]'s outer
 * composite codec doesn't need one field per coordinate. */
data class RadioSenderPosition(val x: Double, val y: Double, val z: Double) {
    companion object {
        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, RadioSenderPosition> = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, RadioSenderPosition::x,
            ByteBufCodecs.DOUBLE, RadioSenderPosition::y,
            ByteBufCodecs.DOUBLE, RadioSenderPosition::z,
            ::RadioSenderPosition,
        )
    }
}

/**
 * The same frame relayed by the server to one listener tuned to the transmitter's frequency and
 * within its band's range. [mode] tells the client which codec to decode with (see
 * [io.github.jwyoon1220.dncity.radio.RadioMode.codec]) and, together with [frequencyKhz]'s
 * derived [io.github.jwyoon1220.dncity.radio.RadioBand], drives static-noise coloring locally,
 * same as [VoiceAudioRelayPayload] leaves gain-from-distance to the client rather than sending a
 * precomputed value -- except the *range* that gain is computed against is not a fixed band
 * constant any more: [effectiveMaxRangeBlocks] is this specific transmission's server-computed
 * range for this specific listener (terrain/elevation for line-of-sight bands, day/night ground-
 * wave-vs-skywave for LW/MW/SW -- see [io.github.jwyoon1220.dncity.voice.RadioRelay] for the
 * computation and [io.github.jwyoon1220.dncity.radio.RadioVoice] for how the client applies it),
 * since only the server has the terrain/time-of-day data needed to derive it. [obstructed] and
 * [stormNoise] are extra static-only flags for the same reason (see
 * [io.github.jwyoon1220.dncity.radio.RadioVoice.staticLevel]).
 *
 * [senderPosition] is carried explicitly rather than left for the client to look up via
 * [senderEntityId] (`Level.getEntity`) -- that only resolves for entities Minecraft's normal
 * entity-tracking has actually synced to this specific client, which is a much shorter radius
 * than an unlimited-range radio band (SW-and-below, see [io.github.jwyoon1220.dncity.radio.RadioBand])
 * is supposed to reach; relying on it silently dropped audio (and its static) the moment the
 * sender left vanilla tracking range, well short of the band's real range. [senderEntityId] is
 * kept only to key per-speaker decoder/mixer state and to filter out the local player's own
 * transmission.
 */
/** [effectiveMaxRangeBlocks] and the obstructed/storm flags bundled into their own small codec so
 * [RadioAudioRelayPayload]'s outer composite codec stays within [StreamCodec.composite]'s 6-field
 * arity limit (same reason [RadioSenderPosition] exists as its own type). */
data class RadioRangeInfo(val effectiveMaxRangeBlocks: Double, private val flags: Int) {
    val obstructed: Boolean get() = (flags and FLAG_OBSTRUCTED) != 0
    val stormNoise: Boolean get() = (flags and FLAG_STORM_NOISE) != 0

    companion object {
        private const val FLAG_OBSTRUCTED = 1
        private const val FLAG_STORM_NOISE = 2

        fun of(effectiveMaxRangeBlocks: Double, obstructed: Boolean, stormNoise: Boolean) = RadioRangeInfo(
            effectiveMaxRangeBlocks,
            (if (obstructed) FLAG_OBSTRUCTED else 0) or (if (stormNoise) FLAG_STORM_NOISE else 0),
        )

        private fun flagsValueOf(info: RadioRangeInfo): Int =
            (if (info.obstructed) FLAG_OBSTRUCTED else 0) or (if (info.stormNoise) FLAG_STORM_NOISE else 0)

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, RadioRangeInfo> = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, RadioRangeInfo::effectiveMaxRangeBlocks,
            ByteBufCodecs.VAR_INT, ::flagsValueOf,
            ::RadioRangeInfo,
        )
    }
}

class RadioAudioRelayPayload(
    val senderEntityId: Int,
    val audioData: ByteArray,
    val frequencyKhz: Double,
    val mode: String,
    val senderPosition: RadioSenderPosition,
    val rangeInfo: RadioRangeInfo,
) : CustomPacketPayload {
    val effectiveMaxRangeBlocks: Double get() = rangeInfo.effectiveMaxRangeBlocks
    val obstructed: Boolean get() = rangeInfo.obstructed
    val stormNoise: Boolean get() = rangeInfo.stormNoise

    override fun type() = TYPE

    companion object {
        private const val MAX_AUDIO_FRAME_BYTES = 2048

        val TYPE: CustomPacketPayload.Type<RadioAudioRelayPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "radio_audio_relay"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, RadioAudioRelayPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RadioAudioRelayPayload::senderEntityId,
            ByteBufCodecs.byteArray(MAX_AUDIO_FRAME_BYTES), RadioAudioRelayPayload::audioData,
            ByteBufCodecs.DOUBLE, RadioAudioRelayPayload::frequencyKhz,
            ByteBufCodecs.STRING_UTF8, RadioAudioRelayPayload::mode,
            RadioSenderPosition.STREAM_CODEC, RadioAudioRelayPayload::senderPosition,
            RadioRangeInfo.STREAM_CODEC, RadioAudioRelayPayload::rangeInfo,
            ::RadioAudioRelayPayload,
        )
    }
}
