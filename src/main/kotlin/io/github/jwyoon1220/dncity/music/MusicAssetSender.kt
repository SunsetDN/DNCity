package io.github.jwyoon1220.dncity.music

import io.github.jwyoon1220.dncity.Dncity
import io.github.jwyoon1220.dncity.network.MusicAssetChunkPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import org.apache.logging.log4j.Level
import java.io.File
import kotlin.math.min

/**
 * Server-side chunked file transfer for the music feature -- see
 * [io.github.jwyoon1220.dncity.network.MusicAssetChunkPayload] and [MusicClientReceiver] (the
 * receiving end). Files are read fresh off disk for every send (soundfonts are sent once per
 * player session on login -- see [MusicServerEvents] -- and MIDI files are small and infrequent),
 * not worth caching in memory.
 */
object MusicAssetSender {
    // Comfortably under any reasonable custom-payload size limit -- see
    // MusicAssetChunkPayload.MAX_CHUNK_BYTES for the receiving side's matching cap.
    private const val CHUNK_SIZE = 250_000

    private var nextTransferId = 0

    fun sendSoundfont(player: ServerPlayer, file: File) {
        if (!file.isFile) {
            Dncity.LOGGER.log(Level.WARN, "[Music] Soundfont file not found: {}", file)
            return
        }
        send(player, MusicAssetChunkPayload.KIND_SOUNDFONT, file)
    }

    fun sendMidi(player: ServerPlayer, file: File) {
        if (!file.isFile) {
            Dncity.LOGGER.log(Level.WARN, "[Music] MIDI file not found: {}", file)
            return
        }
        send(player, MusicAssetChunkPayload.KIND_MIDI, file)
    }

    /** Sends a direct-playback audio file (OGG/FLAC/MP3/Opus) -- see [MusicCommand]'s extension
     * dispatch for how [MusicClientReceiver]/[AudioPlayer] end up playing it. */
    fun sendAudio(player: ServerPlayer, file: File) {
        if (!file.isFile) {
            Dncity.LOGGER.log(Level.WARN, "[Music] Audio file not found: {}", file)
            return
        }
        send(player, MusicAssetChunkPayload.KIND_AUDIO, file)
    }

    private fun send(player: ServerPlayer, kind: Int, file: File) {
        val bytes = file.readBytes()
        if (bytes.isEmpty()) {
            Dncity.LOGGER.log(Level.WARN, "[Music] Refusing to send empty file: {}", file)
            return
        }

        val extension = file.extension
        val totalChunks = ((bytes.size - 1) / CHUNK_SIZE) + 1
        val transferId = nextTransferId++

        var offset = 0
        var chunkIndex = 0
        while (offset < bytes.size) {
            val end = min(offset + CHUNK_SIZE, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            PacketDistributor.sendToPlayer(
                player,
                MusicAssetChunkPayload(kind, transferId, chunkIndex, totalChunks, extension, chunk),
            )
            offset = end
            chunkIndex++
        }
    }
}
