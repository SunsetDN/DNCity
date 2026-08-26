package io.github.jwyoon1220.dncity.music

import io.github.jwyoon1220.dncity.Dncity
import io.github.jwyoon1220.dncity.network.MusicAssetChunkPayload
import org.apache.logging.log4j.Level
import java.io.File
import java.nio.file.Files

/**
 * Client-side reassembly for [MusicAssetChunkPayload] transfers -- buffers chunks per
 * [MusicAssetChunkPayload.transferId] until all have arrived, then either caches the soundfont
 * to a temp file (for [MidiPlayer.soundbankFile]) or starts playback (for a completed MIDI
 * transfer).
 */
object MusicClientReceiver {
    private val transfers = HashMap<Int, Array<ByteArray?>>()

    fun handleChunk(payload: MusicAssetChunkPayload) {
        val chunks = transfers.getOrPut(payload.transferId) { arrayOfNulls(payload.totalChunks) }
        chunks[payload.chunkIndex] = payload.data
        if (chunks.any { it == null }) return

        transfers.remove(payload.transferId)
        val fullData = ByteArray(chunks.sumOf { it!!.size })
        var offset = 0
        for (chunk in chunks) {
            val bytes = chunk!!
            System.arraycopy(bytes, 0, fullData, offset, bytes.size)
            offset += bytes.size
        }

        when (payload.kind) {
            MusicAssetChunkPayload.KIND_SOUNDFONT -> cacheSoundfont(fullData)
            MusicAssetChunkPayload.KIND_MIDI -> MidiPlayer.play(fullData)
            MusicAssetChunkPayload.KIND_AUDIO -> playAudio(fullData, payload.extension)
        }
    }

    private fun cacheSoundfont(data: ByteArray) {
        try {
            val file = File.createTempFile("dncity-soundfont", ".sf2")
            file.deleteOnExit()
            Files.write(file.toPath(), data)
            MidiPlayer.soundbankFile = file
            Dncity.LOGGER.log(Level.INFO, "[Music] Soundfont received ({} bytes)", data.size)
        } catch (e: Exception) {
            Dncity.LOGGER.log(Level.WARN, "[Music] Failed to cache received soundfont", e)
        }
    }

    private fun playAudio(data: ByteArray, extension: String) {
        try {
            // Real extension matters here (unlike the soundfont above) -- FMOD's createSound
            // sniffs the format from the file, and a wrong/missing extension can make it guess
            // the wrong decoder.
            val suffix = if (extension.isNotBlank()) ".$extension" else ".tmp"
            val file = File.createTempFile("dncity-track", suffix)
            file.deleteOnExit()
            Files.write(file.toPath(), data)
            Dncity.LOGGER.log(Level.INFO, "[Music] Audio track received ({} bytes, .{})", data.size, extension)
            AudioPlayer.play(file)
        } catch (e: Exception) {
            Dncity.LOGGER.log(Level.WARN, "[Music] Failed to cache received audio track", e)
        }
    }

    /** Called on leaving a world/disconnecting -- drops any in-flight transfer state. */
    fun reset() {
        transfers.clear()
    }
}
