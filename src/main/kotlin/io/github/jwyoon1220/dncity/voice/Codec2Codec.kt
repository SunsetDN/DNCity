package io.github.jwyoon1220.dncity.voice

import io.github.jwyoon1220.dncity.audio.Codec2
import io.github.jwyoon1220.dncity.radio.RadioBand

/**
 * codec2 (engine/audio's `Codec2` JNI bridge, see CLAUDE.md/engine/audio/CMakeLists.txt for the
 * clang+MinGW toolchain that build requires) for the radio-voice tiers -- unlike close-range
 * voice's Opus ([OpusCodec]), which stays a full-quality wideband codec. Fixed at [SAMPLE_RATE]
 * (8kHz) regardless of mode; callers resample to/from the rest of the mod's 48kHz pipeline via
 * [Resampler] using [RESAMPLE_RATIO].
 */
object Codec2Codec {
    /** VHF/UHF handheld tier -- 2.4kb/s, 160 samples (20ms @ 8kHz) per frame. */
    const val MODE_2400 = Codec2.MODE_2400

    /** HF/MF large-radio tier -- 1.2kb/s, 320 samples (40ms @ 8kHz) per frame. */
    const val MODE_1200 = Codec2.MODE_1200

    const val SAMPLE_RATE = 8000
    val RESAMPLE_RATIO = OpusCodec.SAMPLE_RATE / SAMPLE_RATE

    /** Bands with unlimited [RadioBand.maxRangeBlocks] (ground-wave/HF-and-below) get the
     * HF/MF-style low-bitrate mode; line-of-sight VHF-and-up bands get the higher-bitrate one. */
    fun modeForBand(band: RadioBand): Int = if (band.maxRangeBlocks == null) MODE_1200 else MODE_2400
}

/** One codec2 encode stream. Not thread-safe -- see [Codec2]'s class doc. Must be [close]d. */
class Codec2Encoder(mode: Int) : AutoCloseable {
    private val handle = Codec2.create(mode)
    val samplesPerFrame: Int = Codec2.samplesPerFrame(handle)

    /** Encodes exactly [samplesPerFrame] 8kHz samples into codec2's packed-bit frame. */
    fun encode(samples8k: ShortArray): ByteArray = Codec2.encode(handle, samples8k)

    override fun close() = Codec2.destroy(handle)
}

/** One codec2 decode stream. Not thread-safe -- see [Codec2]'s class doc. Must be [close]d. */
class Codec2Decoder(mode: Int) : AutoCloseable {
    private val handle = Codec2.create(mode)
    val samplesPerFrame: Int = Codec2.samplesPerFrame(handle)

    /** Decodes one codec2 packed-bit frame into [samplesPerFrame] 8kHz samples. */
    fun decode(bytes: ByteArray): ShortArray = Codec2.decode(handle, bytes)

    override fun close() = Codec2.destroy(handle)
}
