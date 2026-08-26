package io.github.jwyoon1220.dncity.network

import io.github.jwyoon1220.dncity.Dncity
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/**
 * One chunk of a soundfont/MIDI/audio file transfer (server -> client) -- see
 * [io.github.jwyoon1220.dncity.music.MusicAssetSender] (sender) and
 * [io.github.jwyoon1220.dncity.music.MusicClientReceiver] (reassembly). [kind] is
 * [KIND_SOUNDFONT] (sent once per player session, on login -- see
 * [io.github.jwyoon1220.dncity.music.MusicServerEvents] -- and cached client-side for
 * [io.github.jwyoon1220.dncity.music.MidiPlayer]'s whole session), [KIND_MIDI], or [KIND_AUDIO]
 * (OGG/FLAC/MP3/Opus, played via [io.github.jwyoon1220.dncity.music.AudioPlayer]) -- the latter
 * two sent per `/music play`, triggering playback once fully received. [extension] carries the
 * source file's extension (without the dot) so the client-side temp file it's cached to has one
 * FMOD/Java Sound can sniff the format from; empty for [KIND_SOUNDFONT] (fixed `.sf2`). Chunked
 * rather than sent as one packet since soundfonts/tracks can be tens of megabytes.
 */
class MusicAssetChunkPayload(
    val kind: Int,
    val transferId: Int,
    val chunkIndex: Int,
    val totalChunks: Int,
    val extension: String,
    val data: ByteArray,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        const val KIND_SOUNDFONT = 0
        const val KIND_MIDI = 1
        const val KIND_AUDIO = 2

        // Comfortably above MusicAssetSender's CHUNK_SIZE -- a hard cap so a malformed/hostile
        // server payload can't make the client allocate an unbounded buffer per packet.
        private const val MAX_CHUNK_BYTES = 1 shl 20 // 1 MiB
        private const val MAX_EXTENSION_LENGTH = 16

        val TYPE: CustomPacketPayload.Type<MusicAssetChunkPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "music_asset_chunk"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, MusicAssetChunkPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MusicAssetChunkPayload::kind,
            ByteBufCodecs.VAR_INT, MusicAssetChunkPayload::transferId,
            ByteBufCodecs.VAR_INT, MusicAssetChunkPayload::chunkIndex,
            ByteBufCodecs.VAR_INT, MusicAssetChunkPayload::totalChunks,
            ByteBufCodecs.stringUtf8(MAX_EXTENSION_LENGTH), MusicAssetChunkPayload::extension,
            ByteBufCodecs.byteArray(MAX_CHUNK_BYTES), MusicAssetChunkPayload::data,
            ::MusicAssetChunkPayload,
        )
    }
}

/** Tells the client to stop whatever track is currently playing (MIDI or direct audio file).
 * No fields -- just a signal. */
class MusicStopPayload : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<MusicStopPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(Dncity.ID, "music_stop"))

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, MusicStopPayload> =
            StreamCodec.unit(MusicStopPayload())
    }
}
