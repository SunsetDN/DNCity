package io.github.jwyoon1220.dncity.voice

import com.plasmoverse.opus.OpusDecoder
import io.github.jwyoon1220.dncity.network.RadioSenderPosition
import io.github.jwyoon1220.dncity.radio.RadioBand
import io.github.jwyoon1220.dncity.radio.RadioMode
import io.github.jwyoon1220.dncity.radio.RadioVoice
import io.github.jwyoon1220.dncity.radio.VoiceCodec
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3

/**
 * Client-side handling for relayed radio-voice frames: one decoder (Opus for AM/FM, [Codec2Decoder]
 * for USB -- see [RadioTransmitter]'s doc comment for why the codec is keyed off [RadioMode], not
 * the band) plus one [RadioChannel] per speaker, kept in maps separate from
 * [ClientVoiceReceiver]'s (a player could in principle be relayed on both channels), then handed
 * off to [VoiceAudioMixer]. Shares that mixer with the close-range tier via [radioSourceId] --
 * entity IDs are always non-negative, so the negated key can never collide with a close-range
 * source.
 *
 * Two independent "mode" concepts feed into a single decoded frame here: [Codec2Codec]'s bitrate
 * mode (2400/1200, from the transmit band -- decides codec2 frame size/decoder, only relevant for
 * USB) and [RadioMode] (AM/FM/USB, from the transmit slot -- decides which codec to use at all,
 * and [RadioChannel]'s bandpass/noise character). Either can change independently when the
 * sender retunes, so both are tracked and checked separately before reusing a cached
 * decoder/channel.
 */
object ClientRadioReceiver {
    private val opusDecoders = Int2ObjectOpenHashMap<OpusDecoder>()
    private val codec2Decoders = Int2ObjectOpenHashMap<Codec2Decoder>()
    private val codec2DecoderModes = Int2IntOpenHashMap().apply { defaultReturnValue(-1) }
    private val channels = Int2ObjectOpenHashMap<RadioChannel>()
    private val channelRadioModes = Int2ObjectOpenHashMap<RadioMode>()

    /** Below this carrier gain, a real radio's squelch would just mute rather than pass through
     * near-total static -- avoids a wall of noise right at the edge of range. */
    private const val SQUELCH_GAIN_THRESHOLD = 0.04f

    private fun radioSourceId(senderEntityId: Int) = -(senderEntityId + 1)

    fun handleRelayedFrame(
        senderEntityId: Int,
        audioData: ByteArray,
        frequencyKhz: Double,
        modeName: String,
        senderPosition: RadioSenderPosition,
        effectiveMaxRangeBlocks: Double,
        obstructed: Boolean,
        stormNoise: Boolean,
    ) {
        val localPlayer = Minecraft.getInstance().player ?: return
        if (senderEntityId == localPlayer.id) return

        val band = RadioBand.fromFrequencyKhz(frequencyKhz) ?: return
        val radioMode = RadioMode.entries.firstOrNull { it.name.equals(modeName, ignoreCase = true) } ?: return

        // Deliberately not resolved via Level.getEntity(senderEntityId): that only finds
        // entities Minecraft's normal entity tracking has actually synced to this client, a much
        // shorter radius than an unlimited-range radio band is supposed to reach -- the server
        // already put the sender's position straight in the payload for exactly this reason (see
        // RadioAudioRelayPayload's doc comment).
        val senderPos = Vec3(senderPosition.x, senderPosition.y, senderPosition.z)
        val distance = senderPos.distanceTo(localPlayer.position())
        val gain = RadioVoice.gain(distance, effectiveMaxRangeBlocks)
        if (gain < SQUELCH_GAIN_THRESHOLD) return

        val samples48k = when (radioMode.codec) {
            VoiceCodec.OPUS -> {
                val decoder = opusDecoders.getOrPut(senderEntityId) { OpusCodec.createDecoder() }
                runCatching { decoder.decode(audioData) }.getOrNull() ?: return
            }
            VoiceCodec.CODEC2 -> {
                val codec2Mode = Codec2Codec.modeForBand(band)
                val decoder = if (codec2DecoderModes[senderEntityId] == codec2Mode) {
                    codec2Decoders[senderEntityId]
                } else {
                    codec2Decoders[senderEntityId]?.close()
                    Codec2Decoder(codec2Mode).also {
                        codec2Decoders[senderEntityId] = it
                        codec2DecoderModes[senderEntityId] = codec2Mode
                    }
                }
                val samples8k = runCatching { decoder.decode(audioData) }.getOrNull() ?: return
                Resampler.upsample(samples8k, Codec2Codec.RESAMPLE_RATIO)
            }
        }

        // Swap the channel out (not reuse) on a RadioMode change -- its filters/noise generators
        // are built for one specific mode's characteristics, not adjustable in place.
        val channel = if (channelRadioModes[senderEntityId] == radioMode) {
            channels[senderEntityId]
        } else {
            RadioChannel(radioMode).also {
                channels[senderEntityId] = it
                channelRadioModes[senderEntityId] = radioMode
            }
        }

        val staticLevel = RadioVoice.staticLevel(radioMode, gain, obstructed, stormNoise)
        val shaped = channel.process(samples48k, gain, staticLevel)

        // codec2 MODE_1200's 40ms frame upsamples to 1920 samples -- longer than
        // VoiceAudioMixer's fixed 960-sample (20ms) frame width, so split rather than let it get
        // silently truncated; Opus and codec2 MODE_2400 both produce exactly 960 samples, passing
        // through as a single chunk.
        val sourceId = radioSourceId(senderEntityId)
        var offset = 0
        while (offset < shaped.size) {
            val end = minOf(offset + OpusCodec.FRAME_SIZE, shaped.size)
            VoiceAudioMixer.submit(sourceId, shaped.copyOfRange(offset, end), gain = 1f)
            offset = end
        }
    }

    /** Called when leaving a world/disconnecting -- drops all per-speaker decoder and queue state. */
    fun releaseAll() {
        val sourceIds = (opusDecoders.keys.asSequence() + codec2Decoders.keys.asSequence()).toSet()
        sourceIds.forEach { VoiceAudioMixer.releaseSource(radioSourceId(it)) }
        opusDecoders.values.forEach { it.close() }
        opusDecoders.clear()
        codec2Decoders.values.forEach { it.close() }
        codec2Decoders.clear()
        codec2DecoderModes.clear()
        channels.clear()
        channelRadioModes.clear()
    }
}
