package io.github.jwyoon1220.dncity.audio;

/**
 * JNI bridge to the native miniaudio-based capture/playback engine (engine/audio's
 * src/main/cpp, built from CMakeLists.txt against vendor/miniaudio.h). Local device I/O only --
 * there is no network relay here, that's a separate concern for whatever consumes this API.
 *
 * <p>Plain Java (not Kotlin) deliberately: this class is loaded into whatever module
 * layer/classpath NeoForge's ModDevGradle {@code additionalRuntimeClasspath} mechanism puts a
 * plain non-mod dependency on for dev runs, which is not guaranteed to have kotlin-stdlib
 * visible to it (that caused a {@code NoClassDefFoundError} on {@code kotlin.jvm.internal.Intrinsics}
 * in practice) -- avoiding the Kotlin runtime entirely sidesteps that whole class of problem.
 *
 * <p>Fixed format: mono, 16-bit signed PCM, 48kHz ({@link #SAMPLE_RATE}/{@link #CHANNELS}) --
 * matches the 20ms/960-sample framing used elsewhere in the mod, so callers don't need to
 * resample.
 *
 * <p>{@link #readCapture} and {@link #writePlayback} are non-blocking, ring-buffer-backed
 * pulls/pushes: the native audio device thread only ever touches the ring buffer memory
 * directly, never the JVM, so there's no JNI attach/detach cost or risk on the realtime audio
 * thread. Call them frequently (e.g. once per game tick) rather than expecting them to block
 * until data is available -- a short read/write means there simply wasn't more to drain/queue
 * yet.
 *
 * <p>Captured audio also runs through a native noise gate (see jni_audio.cpp's
 * {@code applyNoiseGate}) before it ever reaches the ring buffer: below
 * {@link #setNoiseGateThresholdDb}, samples are attenuated to silence with a fast attack / slow
 * release envelope, which both suppresses steady background noise and doubles as mic input/
 * voice-activity detection via {@link #isVoiceActive} and {@link #getInputLevelDb} -- both cheap
 * to poll (they just read a native field, no JNI array marshalling) once per tick alongside
 * {@link #readCapture}.
 */
public final class NativeAudio {

    public static final int SAMPLE_RATE = 48000;
    public static final int CHANNELS = 1;

    static {
        NativeLibrary.load();
    }

    private NativeAudio() {
    }

    /** Must be called once before any other function. Cheap; does not open any device. */
    public static native boolean init();

    /** Stops and releases both devices (if running) and their ring buffers. */
    public static native void shutdown();

    /** Opens the default capture device and starts filling the internal capture ring buffer. */
    public static native boolean startCapture();

    public static native void stopCapture();

    /**
     * Drains up to {@code buffer}'s length worth of captured samples into it (starting at index
     * 0). Returns the number of samples actually read, which may be less than {@code buffer}'s
     * length (including 0) if the device hasn't produced that much yet.
     */
    public static native int readCapture(short[] buffer);

    /** Opens the default playback device and starts draining the internal playback ring buffer. */
    public static native boolean startPlayback();

    public static native void stopPlayback();

    /**
     * Queues up to {@code count} samples from {@code buffer} (starting at index 0) for playback.
     * Returns the number of samples actually queued, which may be less than {@code count} if the
     * ring buffer is full (a slow/absent consumer) -- callers should retry the remainder rather
     * than assume every sample was accepted.
     */
    public static native int writePlayback(short[] buffer, int count);

    /**
     * Sets the noise gate's open threshold in dBFS (default -50dB); the close threshold trails
     * it by a fixed 3dB of hysteresis on the native side. Lower (more negative) values let
     * quieter sound through; raise it in a noisy room to gate out more background hiss. Takes
     * effect on the next capture callback -- safe to call while capture is running.
     */
    public static native void setNoiseGateThresholdDb(float thresholdDb);

    /**
     * Whether the noise gate is currently open, i.e. the mic is picking up sound above the
     * configured threshold -- a cheap native voice-activity-detection signal (e.g. for a
     * "speaking" indicator) that costs nothing beyond what {@link #readCapture} already computes.
     * Meaningless (always false) unless {@link #startCapture} has been called.
     */
    public static native boolean isVoiceActive();

    /**
     * The most recent capture callback's peak input level in dBFS (silence floor at -100dB) --
     * pair with {@link #setNoiseGateThresholdDb} to pick a sensible threshold, or drive an
     * input-level meter in the radio UI. Meaningless (reads the silence floor) unless
     * {@link #startCapture} has been called.
     */
    public static native float getInputLevelDb();

    /**
     * Names of every capture (microphone) device the OS currently exposes, in display order.
     * {@link #selectCaptureDevice} indexes into this same list -- call this again after a device
     * is plugged/unplugged before selecting, since indices aren't guaranteed stable across calls.
     * Requires {@link #init} to have been called first; returns an empty array otherwise.
     */
    public static native String[] listCaptureDevices();

    /** Names of every playback (speaker) device the OS currently exposes -- see {@link #listCaptureDevices}. */
    public static native String[] listPlaybackDevices();

    /**
     * Selects which capture device {@link #startCapture} should open by index into the most
     * recent {@link #listCaptureDevices} result, or -1 (the default) to let the OS pick its
     * configured default microphone. Takes effect on the next {@link #startCapture} call --
     * restart capture (stop then start) to apply this while already running.
     */
    public static native void selectCaptureDevice(int index);

    /** Selects which playback device {@link #startPlayback} should open -- see {@link #selectCaptureDevice}. */
    public static native void selectPlaybackDevice(int index);

    /** The index last passed to {@link #selectCaptureDevice}, or -1 for the OS default (the initial value). */
    public static native int getSelectedCaptureDevice();

    /** The index last passed to {@link #selectPlaybackDevice}, or -1 for the OS default (the initial value). */
    public static native int getSelectedPlaybackDevice();
}
