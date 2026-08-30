package com.iwei20.fmod.studio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Java wrapper over FMOD Studio's C API, backed by hand-written JNI (see
 * {@code engine/fmod/src/main/cpp/jni_fmod.cpp} and {@code fmod_min.h}) rather than the
 * jextract-generated {@code java.lang.foreign} bindings this class used to be built on -- see
 * {@code fmod_min.h}'s file comment for why (JDK 24+ floor from the FFM approach, incompatible
 * with players running Java 21). Public API kept identical to that previous version on purpose,
 * so callers (TACZ's {@code com.tacz.guns.client.sound.fmod} package) needed no changes.
 *
 * <p>Every wrapper object here ({@link Bank}, {@link EventDescription}, {@link EventInstance})
 * is just an opaque native pointer smuggled through Java as a {@code long}; none of them are
 * ever dereferenced on the Java side, only passed back into native calls.
 */
public class FMODSystem implements AutoCloseable {

    private final long systemPtr;

    /**
     * Calls Studio::System::create.
     *
     * {@link FMODSystem#FMODSystem} and {@link FMODSystem#close} are not thread-safe. Calling
     * either of these functions concurrently with any Studio API function (including
     * these two functions) may cause undefined behavior. External synchronization
     * must be used if calls to {@link FMODSystem#FMODSystem} or {@link FMODSystem#close} could
     * overlap other Studio API calls. All other Studio API functions are thread safe
     * and may be called freely from any thread unless otherwise documented.
     *
     * @throws FMODException if the call the Studio::System::create fails.
     */
    public FMODSystem() throws FMODException {
        long[] out = new long[1];
        FMODException.errCheck(nCreate(out));
        systemPtr = out[0];
    }

    /**
     * This function will free the memory used by the Studio System object and everything
     * created under it.
     *
     * @throws FMODException if the call to Studio::System::release fails.
     */
    @Override
    public void close() throws FMODException {
        FMODException.errCheck(nRelease(systemPtr));
    }

    /**
     * <ul>
     * <li>InitNormal - Use defaults for all initialization options.
     * <li>LiveUpdate - Enable live update.
     * <li>AllowMissingPlugins - Load banks even if they reference plug-ins
     * that have not been loaded.
     * <li>SynchronousUpdate - Disable asynchronous processing and perform
     * all processing on the calling thread instead.
     * <li>DeferredCallbacks - Defer timeline callbacks until the main update.
     * <li>LoadFromUpdate - No additional threads are created for bank and
     * resource loading. Loading is driven from System#update.
     * <li>MemoryTracking - Enables detailed memory usage statistics.
     * </ul>
     *
     * @see FMODSystem#initialize
     */
    public enum InitFlags {
        Normal(0x00000000),
        LiveUpdate(0x00000001),
        AllowMissingPlugins(0x00000002),
        SynchronousUpdate(0x00000004),
        DeferredCallbacks(0x00000008),
        LoadFromUpdate(0x00000010),
        MemoryTracking(0x00000020);

        private final int constant;

        InitFlags(int constant) {
            this.constant = constant;
        }

        public int getConstant() {
            return constant;
        }
    }

    /** Calls Studio::System::initialize with the given options.
     * @throws FMODException if the call to Studio::System::initialize fails. */
    public void initialize(int maxChannels, int studioFlags, int flags) {
        FMODException.errCheck(nInitialize(systemPtr, maxChannels, studioFlags, flags));
    }

    /** Calls Studio::System::initialize with reasonable defaults: 1024 channels, no special
     * studio or core init flags.
     * @throws FMODException if the call to Studio::System::initialize fails. */
    public void initialize() {
        initialize(1024, InitFlags.Normal.getConstant(), 0);
    }

    /** Calls Studio::System::initialize with 1024 channels, no special core init flags, and
     * the given studio flags (built from {@link InitFlags}).
     * @throws FMODException if the call to Studio::System::initialize fails. */
    public void initialize(int studioFlags) {
        initialize(1024, studioFlags, 0);
    }

    /** Calls Studio::System::update. Must be called regularly (e.g. once per frame) for
     * scheduled sounds, streaming, callbacks, and file loading to progress.
     * @throws FMODException if the call to Studio::System::update fails. */
    public void update() {
        FMODException.errCheck(nUpdate(systemPtr));
    }

    /** Loads the bank at the given file path.
     * @throws FMODException if the call to Studio::System::loadBankFile fails. */
    public Bank loadBankFile(String filename, int flags) {
        long[] out = new long[1];
        FMODException.errCheck(nLoadBankFile(systemPtr, filename, flags, out));
        return new Bank(out[0]);
    }

    /** Loads the bank at the given file path with default flags.
     * @throws FMODException if the call to Studio::System::loadBankFile fails. */
    public Bank loadBankFile(String filename) {
        return loadBankFile(filename, 0);
    }

    /**
     * Calls Studio::System::getEvent to retrieve an event description by path or ID string
     * (e.g. {@code "event:/UI/Cancel"} or {@code "{guid}"}).
     * @throws FMODException if the call to Studio::System::getEvent fails. */
    public EventDescription getEvent(String pathOrId) {
        long[] out = new long[1];
        FMODException.errCheck(nGetEvent(systemPtr, pathOrId, out));
        return new EventDescription(out[0]);
    }

    /**
     * Calls System::loadPlugin (on the core system underlying this Studio system) to register a
     * DSP/codec/output plugin from a dynamic library, by absolute file path. Must be called
     * <b>before</b> loading any bank that references the plugin -- otherwise
     * {@link FMODSystem#loadBankFile} fails with {@link FMODResult#FMOD_ERR_DSP_NOTFOUND}.
     * @throws FMODException if the call to Studio::System::getCoreSystem or System::loadPlugin fails. */
    public void loadPlugin(String filename) {
        long[] outCoreSystem = new long[1];
        FMODException.errCheck(nGetCoreSystem(systemPtr, outCoreSystem));
        FMODException.errCheck(nLoadPlugin(outCoreSystem[0], filename, 0));
    }

    // FMOD_DSP_TYPE_LOWPASS_SIMPLE and FMOD_DSP_LOWPASS_SIMPLE_CUTOFF -- stable values from FMOD's
    // public fmod_dsp.h enums (unchanged across FMOD versions; this is the single-parameter,
    // cheap one-pole lowpass FMOD ships as a built-in DSP type, not a custom/plugin effect).
    private static final int FMOD_DSP_TYPE_LOWPASS_SIMPLE = 20;
    private static final int FMOD_DSP_LOWPASS_SIMPLE_CUTOFF = 0;

    /**
     * A single Core API DSP effect instance, as returned by {@link #createLowpassDSP}. Attach it
     * to an event instance's mix via {@link EventInstance#attachDSP}.
     */
    public static final class DSP {
        private final long ptr;

        private DSP(long ptr) {
            this.ptr = ptr;
        }

        /** Calls DSP::release once this effect is no longer needed (e.g. after the event instance it was attached to has stopped).
         * @throws FMODException if the call to DSP::release fails. */
        public void release() {
            FMODException.errCheck(nDspRelease(ptr));
        }
    }

    /**
     * Creates a lowpass filter DSP (on the core system underlying this Studio system) with the
     * given cutoff frequency in Hz, for faking a muffled/suppressed timbre on events that have no
     * dedicated suppressed take authored in the bank -- see
     * {@code com.tacz.guns.client.sound.fmod.FmodWeaponSoundManager}'s suppressed-shot handling.
     * Attach the result to a specific event instance's mix via {@link EventInstance#attachDSP}.
     * @throws FMODException if the call to Studio::System::getCoreSystem or System::createDSPByType fails.
     */
    public DSP createLowpassDSP(float cutoffHz) {
        long[] outCoreSystem = new long[1];
        FMODException.errCheck(nGetCoreSystem(systemPtr, outCoreSystem));
        long[] outDsp = new long[1];
        FMODException.errCheck(nCreateDSPByType(outCoreSystem[0], FMOD_DSP_TYPE_LOWPASS_SIMPLE, outDsp));
        DSP dsp = new DSP(outDsp[0]);
        FMODException.errCheck(nDspSetParameterFloat(dsp.ptr, FMOD_DSP_LOWPASS_SIMPLE_CUTOFF, cutoffHz));
        return dsp;
    }

    /** A 3D vector, as used by {@link Attributes3D}. */
    public record Vector3(float x, float y, float z) {
        public static final Vector3 ZERO = new Vector3(0, 0, 0);
    }

    /** 3D position, velocity, and orientation, as used by {@link FMODSystem#setListenerAttributes}
     * and {@link EventInstance#set3DAttributes}. */
    public record Attributes3D(Vector3 position, Vector3 velocity, Vector3 forward, Vector3 up) {
        /** A stationary point with an arbitrary (but valid) orientation and no velocity, for
         * sounds that only need positional attenuation/panning, not doppler or directionality. */
        public static Attributes3D stationary(float x, float y, float z) {
            return new Attributes3D(new Vector3(x, y, z), Vector3.ZERO, new Vector3(0, 0, 1), new Vector3(0, 1, 0));
        }
    }

    /** Calls Studio::System::setListenerAttributes to set the position/orientation of the given
     * listener, used to pan and attenuate 3D event instances. Must be called at least once
     * (and updated regularly, e.g. once per frame, if the listener moves) for 3D events to be
     * audible with correct panning/attenuation.
     * @throws FMODException if the call to Studio::System::setListenerAttributes fails. */
    public void setListenerAttributes(int listenerIndex, Attributes3D attributes) {
        FMODException.errCheck(nSetListenerAttributes(
                systemPtr,
                listenerIndex,
                attributes.position().x(), attributes.position().y(), attributes.position().z(),
                attributes.velocity().x(), attributes.velocity().y(), attributes.velocity().z(),
                attributes.forward().x(), attributes.forward().y(), attributes.forward().z(),
                attributes.up().x(), attributes.up().y(), attributes.up().z()));
    }

    /** A loaded FMOD Studio bank, as returned by {@link FMODSystem#loadBankFile}. */
    public static final class Bank {
        private final long ptr;

        private Bank(long ptr) {
            this.ptr = ptr;
        }

        /** Calls Studio::Bank::loadSampleData to preload the sample data for all events in this
         * bank, so playback of those events starts without delay.
         * @throws FMODException if the call to Studio::Bank::loadSampleData fails. */
        public void loadSampleData() {
            FMODException.errCheck(nBankLoadSampleData(ptr));
        }

        /** Calls Studio::Bank::unload to unload this bank and all of its contained resources.
         * @throws FMODException if the call to Studio::Bank::unload fails. */
        public void unload() {
            FMODException.errCheck(nBankUnload(ptr));
        }

        /** Calls Studio::Bank::getEventCount/getEventList to list every event description this
         * bank contains -- e.g. for dumping every event path in a bank to figure out real event
         * names/paths by hand (see WeaponFmodEvents' doc in TACZ/SBW for why that's ever needed:
         * this bank's naming isn't consistent enough to guess). Returns an empty list on failure
         * rather than throwing, since this is a diagnostic/tooling method, not part of the normal
         * playback path. */
        public List<EventDescription> getEventList() {
            long[] pointers = nBankGetEventList(ptr);
            List<EventDescription> result = new ArrayList<>(pointers.length);
            for (long pointer : pointers) {
                result.add(new EventDescription(pointer));
            }
            return result;
        }
    }

    /** A description of an FMOD Studio event, as returned by {@link FMODSystem#getEvent}.
     * Use {@link EventDescription#createInstance} to create playable instances of this event. */
    public static final class EventDescription {
        private final long ptr;

        private EventDescription(long ptr) {
            this.ptr = ptr;
        }

        /** Calls Studio::EventDescription::createInstance to create a playable instance of this
         * event.
         * @throws FMODException if the call to Studio::EventDescription::createInstance fails. */
        public EventInstance createInstance() {
            long[] out = new long[1];
            FMODException.errCheck(nEventDescriptionCreateInstance(ptr, out));
            return new EventInstance(out[0]);
        }

        /** Calls Studio::EventDescription::getPath to retrieve this event's full path
         * (e.g. {@code "event:/Weapons/AK47/AK47 1P Shot"}). Requires the project's strings bank
         * to already be loaded, since the path is resolved from it.
         * @throws FMODException if the call to Studio::EventDescription::getPath fails. */
        public String getPath() {
            String[] out = new String[1];
            FMODException.errCheck(nEventDescriptionGetPath(ptr, out));
            return out[0];
        }

        /** Calls Studio::EventDescription::getID to retrieve this event's GUID, formatted as
         * {@code "{xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}"}. This string can be passed to
         * {@link FMODSystem#getEvent} instead of a path, which does not require the project's
         * strings bank to be loaded.
         * @throws FMODException if the call to Studio::EventDescription::getID fails. */
        public String getId() {
            String[] out = new String[1];
            FMODException.errCheck(nEventDescriptionGetId(ptr, out));
            return out[0];
        }

        /** Calls Studio::EventDescription::getParameterDescriptionCount and
         * getParameterDescriptionByIndex to list the names of every local parameter authored on
         * this event (e.g. an "IsOutside" indoor/outdoor parameter, if one exists) -- check this
         * before {@link EventInstance#setParameterByName}, since setting a parameter that
         * doesn't exist on the event throws.
         * @throws FMODException if any of the underlying Studio API calls fail. */
        public List<String> getParameterNames() {
            String[] names = nEventDescriptionGetParameterNames(ptr);
            return names == null ? List.of() : Arrays.asList(names);
        }
    }

    /** A single playable instance of an FMOD Studio event, as returned by
     * {@link EventDescription#createInstance}. */
    public static final class EventInstance {
        private final long ptr;

        private EventInstance(long ptr) {
            this.ptr = ptr;
        }

        /** Calls Studio::EventInstance::start to begin playback of this event instance.
         * @throws FMODException if the call to Studio::EventInstance::start fails. */
        public void start() {
            FMODException.errCheck(nEventInstanceStart(ptr));
        }

        /** Calls Studio::EventInstance::stop, letting the event fade out per its authored
         * envelope before stopping.
         * @throws FMODException if the call to Studio::EventInstance::stop fails. */
        public void stop() {
            stop(false);
        }

        /** Calls Studio::EventInstance::stop.
         * @param immediate If true, stops playback immediately; otherwise allows the event to
         *     fade out per its authored envelope.
         * @throws FMODException if the call to Studio::EventInstance::stop fails. */
        public void stop(boolean immediate) {
            FMODException.errCheck(nEventInstanceStop(ptr, immediate ? 1 : 0));
        }

        /** Calls Studio::EventInstance::getPlaybackState to retrieve the current playback state
         * of this event instance (0 = playing, 1 = sustaining, 2 = stopped, 3 = starting,
         * 4 = stopping).
         * @throws FMODException if the call to Studio::EventInstance::getPlaybackState fails. */
        public int getPlaybackState() {
            int[] out = new int[1];
            FMODException.errCheck(nEventInstanceGetPlaybackState(ptr, out));
            return out[0];
        }

        /** Calls Studio::EventInstance::set3DAttributes to set this event instance's
         * position/velocity/orientation, used for panning and distance attenuation. Has no
         * effect on events that aren't authored as 3D (positional) in FMOD Studio.
         * @throws FMODException if the call to Studio::EventInstance::set3DAttributes fails. */
        public void set3DAttributes(Attributes3D attributes) {
            FMODException.errCheck(nEventInstanceSet3DAttributes(
                    ptr,
                    attributes.position().x(), attributes.position().y(), attributes.position().z(),
                    attributes.velocity().x(), attributes.velocity().y(), attributes.velocity().z(),
                    attributes.forward().x(), attributes.forward().y(), attributes.forward().z(),
                    attributes.up().x(), attributes.up().y(), attributes.up().z()));
        }

        /** Calls Studio::EventInstance::setParameterByName to set a local parameter on this
         * event instance. Has no effect if the parameter doesn't exist on this event.
         * @throws FMODException if the call to Studio::EventInstance::setParameterByName fails. */
        public void setParameterByName(String name, float value) {
            FMODException.errCheck(nEventInstanceSetParameterByName(ptr, name, value, 0));
        }

        /** Calls Studio::EventInstance::setVolume -- a linear multiplier on top of the event's
         * authored volume (1.0 = unchanged; values above 1.0 amplify beyond what was authored,
         * clipping/distorting at the mixer if pushed far enough). Can be called before or after
         * {@link #start}.
         * @throws FMODException if the call to Studio::EventInstance::setVolume fails. */
        public void setVolume(float volume) {
            FMODException.errCheck(nEventInstanceSetVolume(ptr, volume));
        }

        /**
         * Attaches {@code dsp} (see {@link FMODSystem#createLowpassDSP}) to this specific event
         * instance's own Core API channel group -- other concurrently-playing instances of the
         * same event, or any other event, are unaffected. Call before {@link #start} so the
         * effect is active from the first sample.
         * @throws FMODException if the call to Studio::EventInstance::getChannelGroup or ChannelGroup::addDSP fails.
         */
        public void attachDSP(DSP dsp) {
            long[] outGroup = new long[1];
            FMODException.errCheck(nEventInstanceGetChannelGroup(ptr, outGroup));
            // Index 0 = FMOD_CHANNELCONTROL_DSP_HEAD, inserting at the head of the chain (closest
            // to this channel group's output) -- the only sensible position for a single effect.
            FMODException.errCheck(nChannelGroupAddDSP(outGroup[0], 0, dsp.ptr));
        }

        /** Calls Studio::EventInstance::release. Marks the event instance for release once it
         * stops. Should be called once the caller no longer needs to interact with this
         * instance, typically right after {@link EventInstance#start}, for fire-and-forget
         * playback.
         * @throws FMODException if the call to Studio::EventInstance::release fails. */
        public void release() {
            FMODException.errCheck(nEventInstanceRelease(ptr));
        }

        /**
         * Dev-tool only, not used by the shipped mod: registers {@code listener} to be called
         * (from whatever native thread FMOD's callback fires on -- may not be the caller's
         * thread) with the raw sample name every time this instance's compiled multi/random
         * instrument picks and starts a new underlying sound. This is the only way to discover
         * which of a bank's samples an event actually draws from, since Studio's static
         * introspection API doesn't expose the compiled instrument tree at all (see
         * {@code fmod_min.h}'s comment on {@code FMOD_STUDIO_EVENT_CALLBACK}).
         * @throws FMODException if the call to Studio::EventInstance::setCallback fails.
         */
        public void setSoundPlayedListener(Consumer<String> listener) {
            SOUND_PLAYED_LISTENERS.put(ptr, listener);
            FMODException.errCheck(nEventInstanceSetCallback(ptr));
        }
    }

    // Dev-tool only (see EventInstance#setSoundPlayedListener) -- keyed by the same jlong handle
    // JNI already smuggles EventInstance's native pointer through, so the C++ trampoline in
    // jni_fmod.cpp can pass it straight back without needing its own handle table.
    private static final Map<Long, Consumer<String>> SOUND_PLAYED_LISTENERS = new ConcurrentHashMap<>();

    // Called from native code (jni_fmod.cpp's soundPlayedTrampoline) on FMOD's own callback
    // thread, not the JVM thread that called EventInstance#start -- must stay allocation-light
    // and must never throw (the native side clears any exception it sees, but that's a last
    // resort, not something to rely on).
    private static void onSoundPlayed(long instancePtr, String sampleName) {
        Consumer<String> listener = SOUND_PLAYED_LISTENERS.get(instancePtr);
        if (listener != null) {
            listener.accept(sampleName);
        }
    }

    // ---- native declarations (see jni_fmod.cpp) ----

    private static native int nCreate(long[] outSystem);

    private static native int nRelease(long system);

    private static native int nInitialize(long system, int maxChannels, int studioFlags, int flags);

    private static native int nUpdate(long system);

    private static native int nLoadBankFile(long system, String filename, int flags, long[] outBank);

    private static native int nGetEvent(long system, String pathOrId, long[] outEventDescription);

    private static native int nGetCoreSystem(long system, long[] outCoreSystem);

    private static native int nLoadPlugin(long coreSystem, String filename, int priority);

    private static native int nSetListenerAttributes(
            long system,
            int index,
            float px, float py, float pz,
            float vx, float vy, float vz,
            float fx, float fy, float fz,
            float ux, float uy, float uz);

    private static native int nBankLoadSampleData(long bank);

    private static native int nBankUnload(long bank);

    private static native long[] nBankGetEventList(long bank);

    private static native int nEventDescriptionCreateInstance(long eventDescription, long[] outInstance);

    private static native int nEventDescriptionGetPath(long eventDescription, String[] outPath);

    private static native int nEventDescriptionGetId(long eventDescription, String[] outId);

    private static native String[] nEventDescriptionGetParameterNames(long eventDescription);

    private static native int nEventInstanceStart(long instance);

    private static native int nEventInstanceStop(long instance, int mode);

    private static native int nEventInstanceGetPlaybackState(long instance, int[] outState);

    private static native int nEventInstanceSet3DAttributes(
            long instance,
            float px, float py, float pz,
            float vx, float vy, float vz,
            float fx, float fy, float fz,
            float ux, float uy, float uz);

    private static native int nEventInstanceSetParameterByName(long instance, String name, float value, int ignoreSeekSpeed);

    private static native int nEventInstanceSetVolume(long instance, float volume);

    private static native int nEventInstanceGetChannelGroup(long instance, long[] outGroup);

    private static native int nEventInstanceRelease(long instance);

    private static native int nEventInstanceSetCallback(long instance);

    private static native int nCreateDSPByType(long coreSystem, int type, long[] outDsp);

    private static native int nChannelGroupAddDSP(long group, int index, long dsp);

    private static native int nChannelGroupRemoveDSP(long group, long dsp);

    private static native int nDspSetParameterFloat(long dsp, int index, float value);

    private static native int nDspRelease(long dsp);
}
