package io.github.jwyoon1220.dncity.voice

import com.plasmoverse.opus.OpusEncoder
import io.github.jwyoon1220.dncity.network.kcp.VoiceKcpClient
import io.github.jwyoon1220.dncity.radio.RadioActions
import io.github.jwyoon1220.dncity.radio.RadioBand
import io.github.jwyoon1220.dncity.radio.RadioMode
import io.github.jwyoon1220.dncity.radio.VoiceCodec
import net.minecraft.client.Minecraft

/**
 * Client-side TX pump for the radio-voice tier, driven by [VoiceClientLoop] while
 * [io.github.jwyoon1220.dncity.client.ModKeyMappings.RADIO_PTT] is held. Which codec is used
 * depends on the active slot's [RadioMode.codec], not the band: OPUS modes (AM/SAM/CW/NBFM) use
 * Opus (full-quality, matching close-range voice's [OpusCodec] exactly -- their "radio" character
 * comes entirely from [RadioChannel]'s bandpass/static DSP on the receive side, not from lossy
 * transmission), while CODEC2 modes (USB/LSB) use codec2 (the actual low-bitrate digital-voice
 * codec real SSB radios carry, in this project's model -- see [Codec2Codec]).
 *
 * Opus frames line up exactly with [VoiceClientLoop]'s 960-sample capture chunking, same as the
 * close-range tier, so no buffering is needed there. codec2's frame size depends on which mode
 * the currently-tuned band uses (MODE_2400: 160 8kHz samples = 960 48kHz samples/20ms; MODE_1200:
 * 320 8kHz samples = 1920 48kHz samples/40ms), so mic audio accumulates across possibly more than
 * one capture frame before there's enough to encode.
 */
object RadioTransmitter {
    private var opusEncoder: OpusEncoder? = null

    private var codec2Encoder: Codec2Encoder? = null
    private var codec2EncoderMode = -1
    private var downsampler: AntiAliasDownsampler? = null

    // Sized for the largest mode's 48kHz-equivalent frame (MODE_1200: 320 samples * ratio).
    private val accumBuffer = ShortArray(320 * Codec2Codec.RESAMPLE_RATIO)
    private var accumFill = 0

    /**
     * Feeds one [VoiceClientLoop] capture frame (960 48kHz samples) into the current
     * transmission. If the player has joined a [io.github.jwyoon1220.dncity.block.RadioStationBlockEntity]
     * (see [RadioStationClientState]), PTT audio routes there -- station modes are always
     * [io.github.jwyoon1220.dncity.radio.RadioMode.AM]/[io.github.jwyoon1220.dncity.radio.RadioMode.FM],
     * both OPUS, so no codec-selection logic is needed for that path. Otherwise, only actually
     * sends anything once the local player is confirmed to be carrying a powered radio with an
     * enabled active slot somewhere in the hotbar -- it doesn't need to be held in hand (see
     * [RadioActions.transmittableHotbarRadio]) -- otherwise PTT is a silent no-op. This is just a
     * client-side bandwidth short-circuit either way; [RadioRelay] re-derives the same state
     * authoritatively on the server.
     */
    fun submit(frame48k: ShortArray) {
        val player = Minecraft.getInstance().player ?: return

        if (RadioStationClientState.joined != null) {
            submitOpus(frame48k)
            return
        }

        val (radio, stack) = RadioActions.transmittableHotbarRadio(player) ?: return
        val data = radio.dataOf(stack)
        val activeSlot = data.slots[data.activeSlot]
        val band = RadioBand.fromFrequencyKhz(activeSlot.frequencyKhz) ?: return

        when (activeSlot.mode.codec) {
            VoiceCodec.OPUS -> submitOpus(frame48k)
            VoiceCodec.CODEC2 -> submitCodec2(frame48k, band)
        }
    }

    private fun submitOpus(frame48k: ShortArray) {
        val enc = opusEncoder ?: OpusCodec.createEncoder().also { opusEncoder = it }
        VoiceKcpClient.sendRadioAudio(enc.encode(frame48k))
    }

    private fun submitCodec2(frame48k: ShortArray, band: RadioBand) {
        val enc = ensureCodec2Encoder(Codec2Codec.modeForBand(band))
        val neededSamples48k = enc.samplesPerFrame * Codec2Codec.RESAMPLE_RATIO

        if (accumFill + frame48k.size > accumBuffer.size) {
            // Shouldn't happen given the buffer's sizing, but don't overflow if it somehow does.
            accumFill = 0
        }
        System.arraycopy(frame48k, 0, accumBuffer, accumFill, frame48k.size)
        accumFill += frame48k.size

        while (accumFill >= neededSamples48k) {
            val chunk = accumBuffer.copyOfRange(0, neededSamples48k)
            val remainder = accumFill - neededSamples48k
            System.arraycopy(accumBuffer, neededSamples48k, accumBuffer, 0, remainder)
            accumFill = remainder

            val samples8k = downsampler!!.downsample(chunk)
            VoiceKcpClient.sendRadioAudio(enc.encode(samples8k))
        }
    }

    private fun ensureCodec2Encoder(mode: Int): Codec2Encoder {
        var enc = codec2Encoder
        if (enc == null || codec2EncoderMode != mode) {
            enc?.close()
            enc = Codec2Encoder(mode)
            codec2Encoder = enc
            codec2EncoderMode = mode
            downsampler = AntiAliasDownsampler(Codec2Codec.RESAMPLE_RATIO)
            // A stale partial frame from a different mode's 48kHz-equivalent framing would
            // decode as garbage if carried over into this mode's accumulation.
            accumFill = 0
        }
        return enc
    }

    /**
     * Called on PTT release -- drops any partial accumulated codec2 frame so the next
     * transmission starts clean instead of splicing unrelated audio together across PTT presses.
     */
    fun reset() {
        accumFill = 0
    }

    /** Called on [VoiceClientLoop.stop]. */
    fun shutdown() {
        opusEncoder?.close()
        opusEncoder = null
        codec2Encoder?.close()
        codec2Encoder = null
        codec2EncoderMode = -1
        downsampler = null
        accumFill = 0
    }
}
