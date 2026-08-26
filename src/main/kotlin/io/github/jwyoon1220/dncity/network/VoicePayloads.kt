package io.github.jwyoon1220.dncity.network

import io.github.jwyoon1220.dncity.Dncity
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/** One ~20ms Opus-encoded close-range voice frame, sent from a speaking client to the server. */
class VoiceAudioPayload(val opusData: ByteArray) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        // Comfortably above OpusCodec's 1024-byte mtuSize; a hard cap so a malformed/hostile
        // client can't make the server allocate an unbounded buffer per packet.
        private const val MAX_OPUS_FRAME_BYTES = 2048

        val TYPE: CustomPacketPayload.Type<VoiceAudioPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "voice_audio"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, VoiceAudioPayload> = StreamCodec.composite(
            ByteBufCodecs.byteArray(MAX_OPUS_FRAME_BYTES), VoiceAudioPayload::opusData,
            ::VoiceAudioPayload,
        )
    }
}

/**
 * The same frame relayed by the server to one listener within
 * [io.github.jwyoon1220.dncity.voice.CloseRangeVoice.MAX_RANGE_BLOCKS] of the speaker.
 * [senderEntityId] lets the client look up the speaker's current (interpolated) position for
 * the distance-based gain calculation, rather than trusting a position embedded in the packet.
 */
class VoiceAudioRelayPayload(val senderEntityId: Int, val opusData: ByteArray) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        private const val MAX_OPUS_FRAME_BYTES = 2048

        val TYPE: CustomPacketPayload.Type<VoiceAudioRelayPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "voice_audio_relay"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, VoiceAudioRelayPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, VoiceAudioRelayPayload::senderEntityId,
            ByteBufCodecs.byteArray(MAX_OPUS_FRAME_BYTES), VoiceAudioRelayPayload::opusData,
            ::VoiceAudioRelayPayload,
        )
    }
}
