package io.github.jwyoon1220.dncity.voice

import io.github.jwyoon1220.dncity.radio.RadioMode
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Simple one-pole IIR low-pass, in the classic RC-filter form. Stateful across calls -- one
 * instance per continuous signal, never shared or reset mid-stream or every filtered sample
 * after a reset clicks.
 */
internal class OnePoleLowPass(cutoffHz: Double, sampleRate: Int) {
    private val alpha: Double
    private var y = 0.0

    init {
        val rc = 1.0 / (2.0 * PI * cutoffHz)
        val dt = 1.0 / sampleRate
        alpha = dt / (rc + dt)
    }

    fun process(x: Double): Double {
        y += alpha * (x - y)
        return y
    }
}

/** One-pole IIR high-pass, same statefulness caveat as [OnePoleLowPass]. */
private class OnePoleHighPass(cutoffHz: Double, sampleRate: Int) {
    private val alpha: Double
    private var y = 0.0
    private var xPrev = 0.0

    init {
        val rc = 1.0 / (2.0 * PI * cutoffHz)
        val dt = 1.0 / sampleRate
        alpha = rc / (rc + dt)
    }

    fun process(x: Double): Double {
        y = alpha * (y + x - xPrev)
        xPrev = x
        return y
    }
}

/**
 * Per-speaker, per-[RadioMode] channel DSP for the radio-voice tier: narrows decoded PCM to
 * [RadioMode.lowCutoffHz]/[RadioMode.highCutoffHz] (a real receiver's IF passband) and mixes in a
 * noise texture shaped like that mode's real static rather than plain white noise -- AM/SAM get
 * occasional atmospheric crackle (SAM's rarer/softer, reflecting its fade immunity -- see
 * [RadioMode]'s doc comment), NBFM gets bright differentiated hiss, USB/LSB get a slow heterodyne
 * warble, and CW gets only a thin, quiet noise floor (its narrow filter is *why* it's quiet --
 * see [RadioMode.CW]'s doc). Both the recovered signal and the injected noise pass through the
 * same bandpass, since a real receiver's noise floor is shaped by its own IF filter too. One
 * instance lives per (speaker, mode) in [ClientRadioReceiver]; swap it out (not reuse) if the
 * speaker retunes to a different mode, since the filters and noise generators are built for one
 * specific mode's characteristics.
 */
class RadioChannel(private val mode: RadioMode, sampleRate: Int = OpusCodec.SAMPLE_RATE) {
    private val lowPass = OnePoleLowPass(mode.highCutoffHz, sampleRate)
    private val highPass = OnePoleHighPass(mode.lowCutoffHz, sampleRate)
    private val random = Random(System.nanoTime())

    // FM hiss: differentiating white noise gives it a brighter, high-frequency-weighted texture
    // instead of flat white noise -- closer to what FM static actually sounds like.
    private var hissPrev = 0.0

    // AM crackle: sparse random impulses layered on the noise floor, standing in for atmospheric
    // static (lightning, etc.) rather than continuous hiss. Cooldown is in samples so bursts
    // stay spaced out instead of clumping.
    private var crackleCooldownSamples = 0

    // USB warble: a slow amplitude wobble on the noise floor, standing in for the receiver's
    // beat-frequency oscillator drifting slightly off the transmitted sideband (a real,
    // characteristic SSB "birdie" effect).
    private var warblePhase = 0.0

    /**
     * Runs [samples] through this channel: gain-scales the recovered signal, mixes in
     * mode-flavored static at [staticLevel] (0f..1f, see [io.github.jwyoon1220.dncity.radio.RadioVoice.staticLevel]),
     * then bandpasses the combined signal to this mode's passband.
     */
    fun process(samples: ShortArray, gain: Float, staticLevel: Float): ShortArray {
        return ShortArray(samples.size) { i ->
            val signal = samples[i] * gain
            val noise = noiseSample(staticLevel)
            val shaped = highPass.process(lowPass.process(signal + noise))
            shaped.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun noiseSample(staticLevel: Float): Double {
        if (staticLevel <= 0f) return 0.0
        val amplitude = Short.MAX_VALUE * staticLevel * NOISE_SCALE
        return when (mode) {
            RadioMode.NBFM, RadioMode.FM -> {
                val white = random.nextDouble(-1.0, 1.0)
                val hiss = white - hissPrev
                hissPrev = white
                hiss * amplitude
            }
            RadioMode.AM -> crackle(amplitude, staticLevel, CRACKLE_CHANCE_PER_SAMPLE, CRACKLE_BURST_AMPLITUDE)
            // Synchronous detection resists the selective fading that drives AM's crackle --
            // rarer, quieter bursts on the same noise floor.
            RadioMode.SAM -> crackle(amplitude, staticLevel, CRACKLE_CHANCE_PER_SAMPLE * 0.35, CRACKLE_BURST_AMPLITUDE * 0.5)
            RadioMode.USB, RadioMode.LSB -> {
                warblePhase += WARBLE_STEP
                val warbleEnvelope = 0.6 + 0.4 * sin(warblePhase)
                random.nextDouble(-1.0, 1.0) * amplitude * warbleEnvelope
            }
            // CW's real-world quiet comes from its filter being narrow, not from a special noise
            // shape -- plain (low-amplitude, since staticLevel/amplitude are already small for
            // this mode -- see RadioMode.CW's baseNoiseLevel) white noise is enough; the sharp
            // low/high cutoffs in RadioMode.CW do the rest.
            RadioMode.CW -> random.nextDouble(-1.0, 1.0) * amplitude
        }
    }

    /** AM-family crackle: sparse random impulses layered on the noise floor, standing in for
     * atmospheric static (lightning, etc.) rather than continuous hiss. [cooldownSamples] keeps
     * bursts spaced out instead of clumping. */
    private fun crackle(amplitude: Double, staticLevel: Float, chancePerSample: Double, burstAmplitude: Double): Double {
        var n = random.nextDouble(-1.0, 1.0) * amplitude
        if (crackleCooldownSamples > 0) {
            crackleCooldownSamples--
        } else if (random.nextFloat() < staticLevel * chancePerSample) {
            n += (if (random.nextBoolean()) 1.0 else -1.0) * Short.MAX_VALUE * burstAmplitude
            crackleCooldownSamples = CRACKLE_COOLDOWN_SAMPLES
        }
        return n
    }

    companion object {
        private const val NOISE_SCALE = 0.06
        private const val CRACKLE_CHANCE_PER_SAMPLE = 0.0006
        private const val CRACKLE_BURST_AMPLITUDE = 0.5
        private const val CRACKLE_COOLDOWN_SAMPLES = 2400 // ~50ms at 48kHz
        private const val WARBLE_STEP = 2.0 * PI * 3.0 / OpusCodec.SAMPLE_RATE // ~3Hz wobble
    }
}
