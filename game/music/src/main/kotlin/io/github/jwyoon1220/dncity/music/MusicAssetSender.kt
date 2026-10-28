// AGENT-DONE(claude): network-lanes
package io.github.jwyoon1220.dncity.music

import io.github.jwyoon1220.dncity.Dncity
import io.github.jwyoon1220.dncity.music.network.MusicAssetChunkPayload
import io.github.jwyoon1220.dncity.network.kcp.VoiceKcpProtocol
import io.github.jwyoon1220.dncity.network.kcp.VoiceKcpServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import org.apache.logging.log4j.Level
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.min

/**
 * Server-side file transfer for the music feature -- see
 * [io.github.jwyoon1220.dncity.music.network.MusicAssetChunkPayload] and [MusicClientReceiver] (the
 * receiving end). Files are read fresh off disk for every send (soundfonts are sent once per
 * player session on login -- see [MusicServerEvents] -- and MIDI files are small and infrequent),
 * not worth caching in memory.
 *
 * Paced, so a soundfont (often tens of MB) no longer sits in front of the player's game traffic. This
 * used to queue every 250 KB chunk of a file onto the player's Minecraft connection in one loop: TCP
 * delivers in order, so movement, block and entity packets waited behind the whole file. Now each
 * player has a queue of [CHUNK_BYTES] chunks and at most [WINDOW_CHUNKS] of them are unacknowledged
 * at a time; the client acks every chunk ([io.github.jwyoon1220.dncity.music.network.MusicAssetAckPayload]),
 * so the transfer runs at whatever rate the link drains and never builds a backlog. Chunks go over the
 * player's UDP session ([VoiceKcpServer.sendAssetChunk], off the game's TCP connection entirely) when
 * there is one, over the Minecraft connection otherwise. Transfers to one player run one after another.
 */
object MusicAssetSender {
    /** Bytes per chunk; also [VoiceKcpProtocol.ASSET_CHUNK_BYTES], the reliable UDP lane's per-message budget. */
    private const val CHUNK_BYTES = VoiceKcpProtocol.ASSET_CHUNK_BYTES

    /** Unacknowledged chunks per player (128 KiB in flight). DESIGN. */
    private const val WINDOW_CHUNKS = 4

    /** A transfer that has had no ack for this long is dropped (client gone quiet, or never had the mod). */
    private const val STALL_TIMEOUT_MS = 30_000L

    private class Transfer(val kind: Int, val transferId: Int, val extension: String, val bytes: ByteArray) {
        val totalChunks = ((bytes.size - 1) / CHUNK_BYTES) + 1
        var nextChunk = 0
    }

    private class PlayerQueue {
        val transfers = ArrayDeque<Transfer>()
        var inFlight = 0
        var lastProgressMs = System.currentTimeMillis()
    }

    private var nextTransferId = 0
    private val queues = HashMap<UUID, PlayerQueue>()

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

    /** The client got chunk [chunkIndex] of [transferId]: one more may go. Server thread. */
    fun onAck(player: ServerPlayer, transferId: Int, chunkIndex: Int) {
        val queue = queues[player.uuid] ?: return
        if (queue.inFlight > 0) queue.inFlight--
        queue.lastProgressMs = System.currentTimeMillis()
        pump(player, queue)
    }

    fun onServerTick(event: ServerTickEvent.Post) {
        if (queues.isEmpty()) return
        val now = System.currentTimeMillis()
        val iterator = queues.entries.iterator()
        while (iterator.hasNext()) {
            val (uuid, queue) = iterator.next()
            val player = event.server.playerList.getPlayer(uuid)
            if (player == null || now - queue.lastProgressMs > STALL_TIMEOUT_MS) {
                if (player != null) Dncity.LOGGER.warn("[Music] Transfer to {} stalled, dropping it", player.gameProfile.name)
                iterator.remove()
                continue
            }
            pump(player, queue)
            if (queue.transfers.isEmpty() && queue.inFlight == 0) iterator.remove()
        }
    }

    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        queues.remove(event.entity.uuid)
    }

    private fun send(player: ServerPlayer, kind: Int, file: File) {
        val bytes = file.readBytes()
        if (bytes.isEmpty()) {
            Dncity.LOGGER.log(Level.WARN, "[Music] Refusing to send empty file: {}", file)
            return
        }
        val queue = queues.getOrPut(player.uuid) { PlayerQueue() }
        if (queue.transfers.isEmpty()) queue.lastProgressMs = System.currentTimeMillis()
        queue.transfers.addLast(Transfer(kind, nextTransferId++, file.extension, bytes))
        pump(player, queue)
    }

    private fun pump(player: ServerPlayer, queue: PlayerQueue) {
        while (queue.inFlight < WINDOW_CHUNKS) {
            val transfer = queue.transfers.peekFirst() ?: return
            val index = transfer.nextChunk++
            if (transfer.nextChunk >= transfer.totalChunks) queue.transfers.pollFirst()
            val start = index * CHUNK_BYTES
            val data = transfer.bytes.copyOfRange(start, min(start + CHUNK_BYTES, transfer.bytes.size))
            val chunk = VoiceKcpProtocol.AssetChunk(transfer.kind, transfer.transferId, index, transfer.totalChunks, transfer.extension, data)
            if (!VoiceKcpServer.sendAssetChunk(player.uuid, chunk)) {
                PacketDistributor.sendToPlayer(
                    player,
                    MusicAssetChunkPayload(chunk.kind, chunk.transferId, chunk.chunkIndex, chunk.totalChunks, chunk.extension, chunk.data),
                )
            }
            queue.inFlight++
        }
    }
}
