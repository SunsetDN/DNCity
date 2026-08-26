package com.iwei20.fmod.studio;

/**
 * Java wrapper over FMOD's <b>Core</b> {@code System} API (as opposed to {@link FMODSystem},
 * which wraps {@code Studio::System} and authored events/banks) -- for directly playing a sound
 * file (OGG/FLAC/MP3/Opus/WAV, all natively decoded by FMOD Core) by path, with no Studio project
 * or bank involved.
 *
 * <p>This is a second, independent FMOD system instance, not a view onto {@link FMODSystem}'s
 * underlying core system (reachable via {@code Studio::System::getCoreSystem}, used elsewhere
 * only to load codec plugins for TACZ's Studio banks) -- callers that want both direct file
 * playback and Studio events need one instance of each class.
 */
public class FMODCoreSystem implements AutoCloseable {

    /** Default playback: fully decoded into memory up front. Fine for short sounds. */
    public static final int MODE_DEFAULT = 0x00000000;

    /** Decodes progressively from disk instead of loading the whole file into memory first --
     * the right choice for music-length tracks. */
    public static final int MODE_CREATESTREAM = 0x00000080;

    private final long systemPtr;

    /** Calls {@code System::create}.
     * @throws FMODException if the call to System::create fails. */
    public FMODCoreSystem() throws FMODException {
        long[] out = new long[1];
        FMODException.errCheck(nCreate(out));
        systemPtr = out[0];
    }

    /** Calls {@code System::init} with 32 channels and no special flags.
     * @throws FMODException if the call to System::init fails. */
    public void init() {
        FMODException.errCheck(nInit(systemPtr, 32, 0));
    }

    /** Calls {@code System::release}, freeing this system and everything created under it.
     * @throws FMODException if the call to System::release fails. */
    @Override
    public void close() {
        FMODException.errCheck(nRelease(systemPtr));
    }

    /** Calls {@code System::update}. Must be called regularly (e.g. once per tick) for
     * streamed sounds to actually progress.
     * @throws FMODException if the call to System::update fails. */
    public void update() {
        FMODException.errCheck(nUpdate(systemPtr));
    }

    /** Calls {@code System::createSound} to load a sound from a local file path, in the given
     * mode (see {@link #MODE_DEFAULT}/{@link #MODE_CREATESTREAM}). The file's format is detected
     * by FMOD itself from its contents/extension.
     * @throws FMODException if the call to System::createSound fails. */
    public Sound createSound(String path, int mode) {
        long[] out = new long[1];
        FMODException.errCheck(nCreateSound(systemPtr, path, mode, out));
        return new Sound(out[0]);
    }

    /** Calls {@code System::playSound} to begin playback of the given sound on the master
     * channel group.
     * @throws FMODException if the call to System::playSound fails. */
    public Channel play(Sound sound) {
        long[] out = new long[1];
        FMODException.errCheck(nPlaySound(systemPtr, sound.ptr, out));
        return new Channel(out[0]);
    }

    /** A loaded sound, as returned by {@link #createSound}. */
    public static final class Sound implements AutoCloseable {
        private final long ptr;

        private Sound(long ptr) {
            this.ptr = ptr;
        }

        /** Calls {@code Sound::release}.
         * @throws FMODException if the call to Sound::release fails. */
        @Override
        public void close() {
            FMODException.errCheck(nSoundRelease(ptr));
        }
    }

    /** A single playing instance of a {@link Sound}, as returned by {@link #play}. */
    public static final class Channel {
        private final long ptr;

        private Channel(long ptr) {
            this.ptr = ptr;
        }

        /** Calls {@code Channel::stop}.
         * @throws FMODException if the call to Channel::stop fails. */
        public void stop() {
            FMODException.errCheck(nChannelStop(ptr));
        }

        /** Calls {@code Channel::setVolume} (0.0-1.0).
         * @throws FMODException if the call to Channel::setVolume fails. */
        public void setVolume(float volume) {
            FMODException.errCheck(nChannelSetVolume(ptr, volume));
        }

        /** Calls {@code Channel::isPlaying}.
         * @throws FMODException if the call to Channel::isPlaying fails. */
        public boolean isPlaying() {
            int[] out = new int[1];
            FMODException.errCheck(nChannelIsPlaying(ptr, out));
            return out[0] != 0;
        }
    }

    // ---- native declarations (see jni_fmod.cpp) ----

    private static native int nCreate(long[] outSystem);

    private static native int nRelease(long system);

    private static native int nInit(long system, int maxChannels, int flags);

    private static native int nUpdate(long system);

    private static native int nCreateSound(long system, String path, int mode, long[] outSound);

    private static native int nPlaySound(long system, long sound, long[] outChannel);

    private static native int nSoundRelease(long sound);

    private static native int nChannelStop(long channel);

    private static native int nChannelSetVolume(long channel, float volume);

    private static native int nChannelIsPlaying(long channel, int[] outPlaying);
}
