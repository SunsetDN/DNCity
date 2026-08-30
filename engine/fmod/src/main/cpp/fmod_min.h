// Minimal, hand-written declarations for the small subset of the FMOD Studio C API this module
// actually calls (see jni_fmod.cpp) -- NOT a copy of FMOD's own headers (we don't vendor those).
// Struct layouts and function signatures below were verified byte-for-byte against this repo's
// previous jextract-generated bindings (engine/fmod/src/generated/java, derived directly from
// the real FMOD headers by jextract), which is how their correctness was confirmed without
// needing the actual fmod.h/fmod_studio.h on this machine. Links against the vendored
// fmod_vc.lib/fmodstudio_vc.lib import libraries (src/main/resources/windows/<arch>/); the real
// fmod.dll/fmodstudio.dll (also vendored) are what actually get loaded at runtime, and only need
// to export symbols matching these declarations, not literally use this header.
//
// Why hand-written JNI instead of jextract-generated java.lang.foreign bindings (this module's
// previous approach, see git history): the FFM API needed SymbolLookup.findOrThrow() (added in
// JDK 24) and only stabilized (non-preview) in JDK 22 at all, making the whole module -- and by
// extension TACZ, which loads its classes -- require JDK 24+ to even launch. Players run
// Minecraft on whatever JDK their launcher bundles, commonly Java 21; a hand-written JNI bridge
// (matching engine/audio's approach) has no such floor.
#pragma once

extern "C" {

typedef struct FMOD_STUDIO_SYSTEM FMOD_STUDIO_SYSTEM;
typedef struct FMOD_SYSTEM FMOD_SYSTEM;
typedef struct FMOD_STUDIO_BANK FMOD_STUDIO_BANK;
typedef struct FMOD_STUDIO_EVENTDESCRIPTION FMOD_STUDIO_EVENTDESCRIPTION;
typedef struct FMOD_STUDIO_EVENTINSTANCE FMOD_STUDIO_EVENTINSTANCE;
typedef struct FMOD_SOUND FMOD_SOUND;
typedef struct FMOD_CHANNEL FMOD_CHANNEL;
typedef struct FMOD_CHANNELGROUP FMOD_CHANNELGROUP;
typedef struct FMOD_DSP FMOD_DSP;

typedef int FMOD_RESULT;
typedef int FMOD_BOOL;

typedef struct FMOD_VECTOR {
    float x;
    float y;
    float z;
} FMOD_VECTOR;

typedef struct FMOD_3D_ATTRIBUTES {
    FMOD_VECTOR position;
    FMOD_VECTOR velocity;
    FMOD_VECTOR forward;
    FMOD_VECTOR up;
} FMOD_3D_ATTRIBUTES;

typedef struct FMOD_GUID {
    unsigned int Data1;
    unsigned short Data2;
    unsigned short Data3;
    unsigned char Data4[8];
} FMOD_GUID;

typedef struct FMOD_STUDIO_PARAMETER_ID {
    unsigned int data1;
    unsigned int data2;
} FMOD_STUDIO_PARAMETER_ID;

typedef struct FMOD_STUDIO_PARAMETER_DESCRIPTION {
    const char* name;
    FMOD_STUDIO_PARAMETER_ID id;
    float minimum;
    float maximum;
    float defaultvalue;
    int type;
    int flags;
    FMOD_GUID guid;
} FMOD_STUDIO_PARAMETER_DESCRIPTION;

FMOD_RESULT FMOD_Studio_System_Create(FMOD_STUDIO_SYSTEM** system, unsigned int headerversion);
FMOD_RESULT FMOD_Studio_System_Release(FMOD_STUDIO_SYSTEM* system);
FMOD_RESULT FMOD_Studio_System_Initialize(
    FMOD_STUDIO_SYSTEM* system, int maxchannels, int studioflags, int flags, void* extradriverdata);
FMOD_RESULT FMOD_Studio_System_Update(FMOD_STUDIO_SYSTEM* system);
FMOD_RESULT FMOD_Studio_System_LoadBankFile(
    FMOD_STUDIO_SYSTEM* system, const char* filename, int flags, FMOD_STUDIO_BANK** bank);
FMOD_RESULT FMOD_Studio_System_GetEvent(
    FMOD_STUDIO_SYSTEM* system, const char* pathOrID, FMOD_STUDIO_EVENTDESCRIPTION** event);
FMOD_RESULT FMOD_Studio_System_GetCoreSystem(FMOD_STUDIO_SYSTEM* system, FMOD_SYSTEM** coresystem);
FMOD_RESULT FMOD_Studio_System_SetListenerAttributes(
    FMOD_STUDIO_SYSTEM* system,
    int index,
    const FMOD_3D_ATTRIBUTES* attributes,
    const FMOD_VECTOR* attenuationposition);

FMOD_RESULT FMOD_System_LoadPlugin(FMOD_SYSTEM* system, const char* filename, unsigned int* handle, unsigned int priority);

FMOD_RESULT FMOD_Studio_Bank_LoadSampleData(FMOD_STUDIO_BANK* bank);
FMOD_RESULT FMOD_Studio_Bank_Unload(FMOD_STUDIO_BANK* bank);
FMOD_RESULT FMOD_Studio_Bank_GetEventCount(FMOD_STUDIO_BANK* bank, int* count);
FMOD_RESULT FMOD_Studio_Bank_GetEventList(
    FMOD_STUDIO_BANK* bank, FMOD_STUDIO_EVENTDESCRIPTION** array, int capacity, int* count);

FMOD_RESULT FMOD_Studio_EventDescription_CreateInstance(
    FMOD_STUDIO_EVENTDESCRIPTION* eventdescription, FMOD_STUDIO_EVENTINSTANCE** instance);
FMOD_RESULT FMOD_Studio_EventDescription_GetPath(
    FMOD_STUDIO_EVENTDESCRIPTION* eventdescription, char* path, int size, int* retrieved);
FMOD_RESULT FMOD_Studio_EventDescription_GetID(FMOD_STUDIO_EVENTDESCRIPTION* eventdescription, FMOD_GUID* id);
FMOD_RESULT FMOD_Studio_EventDescription_GetParameterDescriptionCount(
    FMOD_STUDIO_EVENTDESCRIPTION* eventdescription, int* count);
FMOD_RESULT FMOD_Studio_EventDescription_GetParameterDescriptionByIndex(
    FMOD_STUDIO_EVENTDESCRIPTION* eventdescription, int index, FMOD_STUDIO_PARAMETER_DESCRIPTION* parameter);

FMOD_RESULT FMOD_Studio_EventInstance_Start(FMOD_STUDIO_EVENTINSTANCE* eventinstance);
FMOD_RESULT FMOD_Studio_EventInstance_Stop(FMOD_STUDIO_EVENTINSTANCE* eventinstance, int mode);
FMOD_RESULT FMOD_Studio_EventInstance_GetPlaybackState(FMOD_STUDIO_EVENTINSTANCE* eventinstance, int* state);
FMOD_RESULT FMOD_Studio_EventInstance_Set3DAttributes(
    FMOD_STUDIO_EVENTINSTANCE* eventinstance, const FMOD_3D_ATTRIBUTES* attributes);
FMOD_RESULT FMOD_Studio_EventInstance_SetParameterByName(
    FMOD_STUDIO_EVENTINSTANCE* eventinstance, const char* name, float value, FMOD_BOOL ignoreseekspeed);
// volume is a linear multiplier on top of the event's authored volume -- 1.0 = unchanged, values
// above 1.0 amplify beyond what was authored (and can clip/distort at the mixer, which is exactly
// the point for a deliberately harsh/overdriven gunshot -- see SbwFmodWeaponSoundManager/
// FmodWeaponSoundManager's use of this).
FMOD_RESULT FMOD_Studio_EventInstance_SetVolume(FMOD_STUDIO_EVENTINSTANCE* eventinstance, float volume);
FMOD_RESULT FMOD_Studio_EventInstance_Release(FMOD_STUDIO_EVENTINSTANCE* eventinstance);
// Retrieves the (Core API) channel group this event instance mixes into -- needed to attach a DSP
// effect (see FMOD_System_CreateDSPByType/FMOD_ChannelGroup_AddDSP below) to one specific event
// instance's output, since Studio events don't expose DSP insertion in the Studio API itself.
FMOD_RESULT FMOD_Studio_EventInstance_GetChannelGroup(
    FMOD_STUDIO_EVENTINSTANCE* eventinstance, FMOD_CHANNELGROUP** group);

// Core API DSP support -- used to fake a suppressed-gunshot timbre (a lowpass filter muffling the
// sharp crack) on events that have no dedicated suppressed take authored in the bank at all. Not
// a general-purpose DSP binding: only what a single inserted lowpass effect per event instance
// needs.
FMOD_RESULT FMOD_System_CreateDSPByType(FMOD_SYSTEM* system, int type, FMOD_DSP** dsp);
FMOD_RESULT FMOD_ChannelGroup_AddDSP(FMOD_CHANNELGROUP* channelgroup, int index, FMOD_DSP* dsp);
FMOD_RESULT FMOD_ChannelGroup_RemoveDSP(FMOD_CHANNELGROUP* channelgroup, FMOD_DSP* dsp);
FMOD_RESULT FMOD_DSP_SetParameterFloat(FMOD_DSP* dsp, int index, float value);
FMOD_RESULT FMOD_DSP_Release(FMOD_DSP* dsp);

// Core System sound playback (FMOD::System, not FMOD::Studio::System) -- used for direct
// file playback (OGG/FLAC/MP3/Opus, all natively decoded by FMOD Core) rather than authored
// Studio events, via a second, independent FMOD_SYSTEM instance owned by FMODCoreSystem.java
// (kept separate from the FMOD_SYSTEM underlying TACZ's own Studio system, which
// FMOD_Studio_System_GetCoreSystem above already exposes for a different purpose -- loading
// codec plugins -- since DNCity shouldn't reach into TACZ's private state to reuse it).
FMOD_RESULT FMOD_System_Create(FMOD_SYSTEM** system, unsigned int headerversion);
FMOD_RESULT FMOD_System_Release(FMOD_SYSTEM* system);
FMOD_RESULT FMOD_System_Init(FMOD_SYSTEM* system, int maxchannels, unsigned int flags, void* extradriverdata);
FMOD_RESULT FMOD_System_Update(FMOD_SYSTEM* system);
FMOD_RESULT FMOD_System_CreateSound(
    FMOD_SYSTEM* system, const char* name_or_data, unsigned int mode, void* exinfo, FMOD_SOUND** sound);
FMOD_RESULT FMOD_System_PlaySound(
    FMOD_SYSTEM* system, FMOD_SOUND* sound, FMOD_CHANNELGROUP* channelgroup, FMOD_BOOL paused, FMOD_CHANNEL** channel);

FMOD_RESULT FMOD_Sound_Release(FMOD_SOUND* sound);
FMOD_RESULT FMOD_Sound_GetName(FMOD_SOUND* sound, char* name, int namelen);

// Event-instance callback support -- used only by a throwaway dev-time sample-name dumper (see
// FMODSystem.EventInstance#setSoundPlayedListener), never by the shipped mod: registering
// FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED is the only way to learn which of a bank's raw samples
// a compiled multi/random instrument actually picked at playback time, since Studio's static
// introspection API (GetParameterDescription* etc., already declared above) doesn't expose that.
// Only the two mask bits and the one callback shape actually used are declared.
typedef unsigned int FMOD_STUDIO_EVENT_CALLBACK_TYPE;
#define FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED ((FMOD_STUDIO_EVENT_CALLBACK_TYPE)0x00002000)
#define FMOD_STUDIO_EVENT_CALLBACK_SOUND_STOPPED ((FMOD_STUDIO_EVENT_CALLBACK_TYPE)0x00004000)

// For SOUND_PLAYED/SOUND_STOPPED specifically, FMOD passes the FMOD_SOUND* directly as
// `parameters` (reinterpreted through the void*) -- not a wrapping struct.
typedef FMOD_RESULT (*FMOD_STUDIO_EVENT_CALLBACK)(
    FMOD_STUDIO_EVENT_CALLBACK_TYPE type, FMOD_STUDIO_EVENTINSTANCE* event, void* parameters);

FMOD_RESULT FMOD_Studio_EventInstance_SetCallback(
    FMOD_STUDIO_EVENTINSTANCE* eventinstance, FMOD_STUDIO_EVENT_CALLBACK callback, FMOD_STUDIO_EVENT_CALLBACK_TYPE callbackmask);

FMOD_RESULT FMOD_Channel_Stop(FMOD_CHANNEL* channel);
FMOD_RESULT FMOD_Channel_SetVolume(FMOD_CHANNEL* channel, float volume);
FMOD_RESULT FMOD_Channel_IsPlaying(FMOD_CHANNEL* channel, FMOD_BOOL* isplaying);

} // extern "C"
