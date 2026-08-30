package io.github.jwyoon1220.dncity.voice

import com.plasmoverse.opus.OpusDecoder
import com.plasmoverse.opus.OpusEncoder
import com.plasmoverse.opus.OpusMode

/**
 * Opus codec for the close-range voice chat tier ([CloseRangeVoice.MAX_RANGE_BLOCKS] blocks,
 * full-duplex, no PTT -- see the radio voice-chat design plan's tier table). Backed by
 * opus-jni-rust
 * (github.com/plasmoapp/opus-jni-rust, com.plasmoverse:opus-jni-rust), a prebuilt Rust/jni-rs
 * JNI binding for libopus -- used as-is rather than hand-writing new JNI bindings, unlike the
 * radio tiers' CODEC2 codec which has no equivalent off-the-shelf JNI library.
 *
 * Fixed format: mono, 48kHz, [FRAME_SIZE]-sample (20ms) frames -- matches engine/audio's
 * `NativeAudio` capture/playback format (not yet a dependency of this module -- see CLAUDE.md),
 * so PCM read via that module's `readCapture` can be handed to [createEncoder] directly with no
 * resampling once it's wired in.
 */
object OpusCodec {

    // No explicit OpusLibrary.load() here -- OpusEncoder.create/OpusDecoder.create both call it
    // themselves (idempotently) before constructing an instance. See build.gradle.kts's `runs`
    // block for why `java.io.tmpdir` is redirected for dev runs (OpusLibrary.load()'s own
    // extraction path gets silently blocked by this dev machine's AppLocker/WDAC policy
    // otherwise).
    const val SAMPLE_RATE = 48_000
    const val FRAME_SIZE = 960
    private const val STEREO = false

    /**
     * A new encoder for one voice stream. Opus encoders are stateful (they carry prediction
     * history across frames), so every distinct stream (e.g. one per speaking player) needs its
     * own instance -- never share one encoder across multiple speakers. [mtuSize] bounds the
     * largest encoded packet Opus will produce; 1024 comfortably covers a single 20ms/48kHz mono
     * frame at any bitrate this tier would plausibly use. Caller must [OpusEncoder.close] the
     * result when the stream ends to release the native encoder state.
     */
    fun createEncoder(bitrate: Int = DEFAULT_BITRATE, mtuSize: Int = 1024): OpusEncoder {
        val encoder = OpusEncoder.create(SAMPLE_RATE, STEREO, mtuSize, OpusMode.VOIP)
        encoder.setBitrate(bitrate)
        return encoder
    }

    /**
     * A new decoder for one incoming voice stream -- same one-instance-per-stream rule as
     * [createEncoder]. Caller must [OpusDecoder.close] the result when the stream ends.
     */
    fun createDecoder(): OpusDecoder = OpusDecoder.create(SAMPLE_RATE, STEREO, FRAME_SIZE)

    // 32kb/s is comfortably within Opus's recommended VoIP range for wideband mono speech
    // (https://wiki.xiph.org/Opus_Recommended_Settings) -- a starting point, not a tuned value.
    private const val DEFAULT_BITRATE = 32_000
}
