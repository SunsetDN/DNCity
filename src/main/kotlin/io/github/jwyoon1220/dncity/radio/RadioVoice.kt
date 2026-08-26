package io.github.jwyoon1220.dncity.radio

import kotlin.math.pow

/**
 * Signal-quality math for the radio voice tier (PTT, half-duplex -- see the radio voice-chat
 * design plan's tier table). Distance falloff is per-band: bands with a `null`
 * [RadioBand.maxRangeBlocks] (ground-wave/HF-and-below) are effectively unlimited range within a
 * Minecraft world -- full carrier strength regardless of distance, just [RadioMode.baseNoiseLevel]
 * static -- while VHF-and-up bands fade out toward their hard range cutoff.
 */
object RadioVoice {

    /**
     * Carrier gain for a listener [distanceBlocks] from the transmitter, in `0f..1f`. Same
     * quadratic falloff shape as [CloseRangeVoice.gain] for line-of-sight bands; always `1f` for
     * unlimited-range bands (ground wave doesn't get quieter with distance in this model, only
     * noisier -- see [staticLevel]).
     */
    fun gain(band: RadioBand, distanceBlocks: Double): Float {
        val maxRange = band.maxRangeBlocks ?: return 1f
        if (distanceBlocks <= 0.0) return 1f
        if (distanceBlocks >= maxRange) return 0f
        val linear = 1.0 - (distanceBlocks / maxRange)
        return (linear * linear).toFloat()
    }

    /**
     * How much static to mix under the decoded signal, in `0f..1f`. Always at least
     * [RadioMode.baseNoiseLevel] (real radio modes are never perfectly clean), rising further as
     * [gain] drops toward the edge of range -- the shape of that rise is mode-specific, since
     * real AM/FM/SSB receivers degrade differently rather than all sharing one static curve.
     */
    fun staticLevel(mode: RadioMode, gain: Float): Float {
        val weak = (1f - gain).coerceIn(0f, 1f)
        val base = mode.baseNoiseLevel.toFloat()
        val fromRange = when (mode) {
            // FM's "capture effect": a demodulator locks onto whichever signal is strongest, so
            // quality stays near-clean until the carrier gets quite weak, then static ramps up
            // sharply rather than gradually.
            RadioMode.FM -> weak.pow(3) * 1.4f
            // AM has no capture effect -- amplitude (and therefore noise) tracks signal strength
            // close to linearly, so it fades gradually rather than holding clean then collapsing.
            RadioMode.AM -> weak * 0.6f
            // USB carries no carrier/sidebands to begin with, so its clean-signal noise floor is
            // already the lowest of the three; degrades a bit more gently than AM.
            RadioMode.USB -> weak * 0.45f
        }
        return (base + fromRange).coerceIn(0f, 1f)
    }
}
