package io.github.jwyoon1220.dncity.voice

import com.plasmoverse.opus.OpusDecoder
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.client.Minecraft

/**
 * Client-side handling for relayed close-range voice frames: one [OpusDecoder] per speaker
 * (Opus decoders are stateful, so frames from different speakers can't share one), gain-scaled
 * by distance to the local player and handed to [VoiceAudioMixer].
 */
object ClientVoiceReceiver {
    private val decoders = Int2ObjectOpenHashMap<OpusDecoder>()

    fun handleRelayedFrame(senderEntityId: Int, opusData: ByteArray) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val localPlayer = minecraft.player ?: return
        val sender = level.getEntity(senderEntityId) ?: return
        if (sender === localPlayer) return

        val distance = sender.position().distanceTo(localPlayer.position())
        val gain = CloseRangeVoice.gain(distance)
        if (gain <= 0f) return

        val decoder = decoders.getOrPut(senderEntityId) { OpusCodec.createDecoder() }
        val samples = runCatching { decoder.decode(opusData) }.getOrNull() ?: return
        VoiceAudioMixer.submit(senderEntityId, samples, gain)
    }

    /** Called when leaving a world/disconnecting -- drops all per-speaker decoder and queue state. */
    fun releaseAll() {
        decoders.values.forEach { it.close() }
        decoders.clear()
        VoiceAudioMixer.clear()
    }
}
