package io.github.jwyoon1220.dncity.audio;

/**
 * JNI bridge to codec2 (vendor/codec2, vendored as a git submodule -- see CMakeLists.txt and
 * src/main/cpp/jni_codec2.cpp), used for the radio-voice tiers: {@link #MODE_2400} for VHF/UHF
 * handhelds, {@link #MODE_1200} for HF/MF large radios (see the radio voice-chat design plan's
 * tier table). codec2 is fixed at 8kHz mono -- resampling to/from the rest of the mod's 48kHz
 * pipeline is the caller's job (see {@code voice/Resampler.kt}), not this class's.
 *
 * <p>Handle-based, not object-oriented: {@link #create} allocates one native {@code struct
 * CODEC2*} and returns it as an opaque {@code long}; every handle obtained from {@link #create}
 * must be passed to exactly one {@link #destroy} call once its stream ends, or the native
 * allocation leaks. A handle is *not* thread-safe -- codec2's encoder/decoder state is mutated
 * in place by every {@link #encode}/{@link #decode} call, so concurrent use of the same handle
 * from multiple threads corrupts that state; one handle per stream, used from one thread, is the
 * only supported pattern (matches how {@code OpusEncoder}/{@code OpusDecoder} are used
 * elsewhere in the mod).
 */
public final class Codec2 {

    /** ~2.4kb/s -- see {@code codec2.h}'s {@code CODEC2_MODE_2400}. 160 samples/frame, 20ms. */
    public static final int MODE_2400 = 1;

    /** ~1.2kb/s -- see {@code codec2.h}'s {@code CODEC2_MODE_1200}. 320 samples/frame, 40ms. */
    public static final int MODE_1200 = 5;

    static {
        NativeLibrary.load();
    }

    private Codec2() {
    }

    /** Allocates a new codec2 state for [mode] (one of {@link #MODE_2400}/{@link #MODE_1200}). */
    public static native long create(int mode);

    /** Releases a handle obtained from {@link #create}. Safe to call at most once per handle. */
    public static native void destroy(long handle);

    /** How many 8kHz samples one {@link #encode}/{@link #decode} call consumes/produces. */
    public static native int samplesPerFrame(long handle);

    /** How many bytes one {@link #encode} call produces / {@link #decode} call consumes. */
    public static native int bytesPerFrame(long handle);

    /**
     * Encodes exactly {@link #samplesPerFrame} 8kHz samples (only the first that many of
     * [samples] are read) into {@link #bytesPerFrame} packed bytes.
     */
    public static native byte[] encode(long handle, short[] samples);

    /**
     * Decodes exactly {@link #bytesPerFrame} packed bytes (only the first that many of [bytes]
     * are read) into {@link #samplesPerFrame} 8kHz samples.
     */
    public static native short[] decode(long handle, byte[] bytes);
}
