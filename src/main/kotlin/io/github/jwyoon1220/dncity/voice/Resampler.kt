package io.github.jwyoon1220.dncity.voice

/**
 * Anti-aliased downsampler from the mod's fixed 48kHz pipeline ([OpusCodec.SAMPLE_RATE]) to
 * codec2's fixed 8kHz, used on the transmit side before encoding (see [RadioTransmitter]). Two
 * cascaded one-pole low-passes below codec2's ~4kHz Nyquist filter the signal before decimating,
 * so the 6x downsample doesn't fold high-frequency content back down as audible aliasing --
 * plain box-averaging (this class's predecessor) is itself a weak, ringy filter that added its
 * own metallic coloration on top of codec2's already-lossy low-bitrate character, worse than
 * necessary even at codec2's better 2400 mode (confirmed by ear: VHF/UHF still sounded roboty
 * even though it isn't using the heavily-degraded 1200 mode).
 *
 * Stateful (the filter carries history across calls) -- one instance lives per continuous
 * transmission; [RadioTransmitter] creates a new one alongside each new [Codec2Encoder].
 */
class AntiAliasDownsampler(private val ratio: Int) {
    private val stage1 = OnePoleLowPass(ANTI_ALIAS_CUTOFF_HZ, OpusCodec.SAMPLE_RATE)
    private val stage2 = OnePoleLowPass(ANTI_ALIAS_CUTOFF_HZ, OpusCodec.SAMPLE_RATE)

    fun downsample(input: ShortArray): ShortArray {
        val outLen = input.size / ratio
        return ShortArray(outLen) { i ->
            var filtered = 0.0
            val base = i * ratio
            for (j in 0 until ratio) {
                filtered = stage2.process(stage1.process(input[base + j].toDouble()))
            }
            filtered.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private companion object {
        // Standard telephone-band voice ceiling -- safely under codec2's 8kHz/2 = 4kHz Nyquist,
        // with headroom for the low-pass's own gentle rolloff above the cutoff.
        const val ANTI_ALIAS_CUTOFF_HZ = 3400.0
    }
}

/**
 * Linear-interpolation upsampler, used on the receive side (see [ClientRadioReceiver]) to bring
 * codec2's decoded 8kHz audio back up to the mixer/playback's 48kHz. Stateless -- a weak
 * reconstruction filter is enough here since this is filling in samples between ones codec2
 * actually produced, not removing content the way the transmit-side anti-aliasing filter is.
 */
object Resampler {
    fun upsample(input: ShortArray, ratio: Int): ShortArray {
        if (input.isEmpty()) return ShortArray(0)
        return ShortArray(input.size * ratio) { i ->
            val srcPos = i.toDouble() / ratio
            val i0 = srcPos.toInt().coerceIn(0, input.size - 1)
            val i1 = (i0 + 1).coerceIn(0, input.size - 1)
            val frac = srcPos - i0
            (input[i0] + (input[i1] - input[i0]) * frac).toInt().toShort()
        }
    }
}
