/*
 * JNI bridge for com.iwei20.fmod.studio.FMODSystem -- see fmod_min.h for why this exists (hand-
 * written JNI over FMOD's C API, replacing a jextract/java.lang.foreign approach that needed
 * JDK 24+). Every native method here is handle-based: a "system"/"bank"/"event description"/
 * "event instance" handle is just the real FMOD pointer smuggled through Java as a jlong, never
 * dereferenced by anything but FMOD itself. Handle-producing calls write their result through a
 * caller-supplied one-element jlongArray (mirroring how the FMOD C API itself writes through an
 * out-pointer) rather than returning it directly, so the FMOD_RESULT error code can still be the
 * return value -- FMODSystem.java's FMODException.errCheck() is what actually surfaces failures.
 */

#include <cstdio>
#include <cstring>
#include <vector>

#include <jni.h>

#include "fmod_min.h"

namespace {

// Stashed once (first EventInstance#setCallback call) so the native FMOD callback thread -- which
// is not a JVM thread and has no JNIEnv of its own -- can attach itself and call back into Java.
// This is the first JNI upcall (native -> Java) in this codebase; engine/audio's miniaudio
// callbacks (jni_audio.cpp) stay purely native and never need this.
JavaVM* g_javaVm = nullptr;
jclass g_fmodSystemClass = nullptr;
jmethodID g_onSoundPlayedMethod = nullptr;

// FMOD_STUDIO_EVENT_CALLBACK shape (see fmod_min.h) -- only SOUND_PLAYED is unmasked by
// nEventInstanceSetCallback below, so `type` is always that here in practice.
FMOD_RESULT soundPlayedTrampoline(FMOD_STUDIO_EVENT_CALLBACK_TYPE type, FMOD_STUDIO_EVENTINSTANCE* event, void* parameters) {
    if (type != FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED || g_javaVm == nullptr || g_onSoundPlayedMethod == nullptr) {
        return 0;
    }

    auto* sound = reinterpret_cast<FMOD_SOUND*>(parameters);
    char name[256] = {0};
    FMOD_Sound_GetName(sound, name, sizeof(name));

    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_javaVm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) {
            return 0;
        }
        attached = true;
    }

    jstring nameStr = env->NewStringUTF(name);
    env->CallStaticVoidMethod(
        g_fmodSystemClass, g_onSoundPlayedMethod, reinterpret_cast<jlong>(event), nameStr);
    env->DeleteLocalRef(nameStr);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }

    if (attached) {
        g_javaVm->DetachCurrentThread();
    }
    return 0;
}

// FMOD_VERSION as encoded by the FMOD SDK this project's vendored binaries are from -- taken
// directly (as a decimal literal, to avoid a hex-transcription mistake) from the
// jextract-generated fmod_studio_h_1.java's `FMOD_VERSION = 131860` constant, cross-checked
// before that file was removed as part of this module's move away from jextract.
constexpr unsigned int kFmodVersion = 131860u;

jlong getLong(JNIEnv* env, jlongArray arr, jsize index = 0) {
    jlong value = 0;
    env->GetLongArrayRegion(arr, index, 1, &value);
    return value;
}

void putLong(JNIEnv* env, jlongArray arr, jlong value) {
    env->SetLongArrayRegion(arr, 0, 1, &value);
}

void putInt(JNIEnv* env, jintArray arr, jint value) {
    env->SetIntArrayRegion(arr, 0, 1, &value);
}

// Copies a Java 3D-attributes' worth of loose floats (position/velocity/forward/up, each xyz)
// into a native FMOD_3D_ATTRIBUTES -- called from both Set3DAttributes overloads below, which
// take the same 12 floats.
FMOD_3D_ATTRIBUTES buildAttributes(
    float px, float py, float pz,
    float vx, float vy, float vz,
    float fx, float fy, float fz,
    float ux, float uy, float uz) {
    FMOD_3D_ATTRIBUTES attrs{};
    attrs.position = {px, py, pz};
    attrs.velocity = {vx, vy, vz};
    attrs.forward = {fx, fy, fz};
    attrs.up = {ux, uy, uz};
    return attrs;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nCreate(JNIEnv* env, jclass, jlongArray outSystem) {
    FMOD_STUDIO_SYSTEM* system = nullptr;
    FMOD_RESULT result = FMOD_Studio_System_Create(&system, kFmodVersion);
    putLong(env, outSystem, reinterpret_cast<jlong>(system));
    return result;
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nRelease(JNIEnv*, jclass, jlong system) {
    return FMOD_Studio_System_Release(reinterpret_cast<FMOD_STUDIO_SYSTEM*>(system));
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nInitialize(
    JNIEnv*, jclass, jlong system, jint maxChannels, jint studioFlags, jint flags) {
    return FMOD_Studio_System_Initialize(
        reinterpret_cast<FMOD_STUDIO_SYSTEM*>(system), maxChannels, studioFlags, flags, nullptr);
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nUpdate(JNIEnv*, jclass, jlong system) {
    return FMOD_Studio_System_Update(reinterpret_cast<FMOD_STUDIO_SYSTEM*>(system));
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nLoadBankFile(
    JNIEnv* env, jclass, jlong system, jstring filename, jint flags, jlongArray outBank) {
    const char* filenameChars = env->GetStringUTFChars(filename, nullptr);
    FMOD_STUDIO_BANK* bank = nullptr;
    FMOD_RESULT result =
        FMOD_Studio_System_LoadBankFile(reinterpret_cast<FMOD_STUDIO_SYSTEM*>(system), filenameChars, flags, &bank);
    env->ReleaseStringUTFChars(filename, filenameChars);
    putLong(env, outBank, reinterpret_cast<jlong>(bank));
    return result;
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nGetEvent(
    JNIEnv* env, jclass, jlong system, jstring pathOrId, jlongArray outEventDescription) {
    const char* pathChars = env->GetStringUTFChars(pathOrId, nullptr);
    FMOD_STUDIO_EVENTDESCRIPTION* event = nullptr;
    FMOD_RESULT result = FMOD_Studio_System_GetEvent(reinterpret_cast<FMOD_STUDIO_SYSTEM*>(system), pathChars, &event);
    env->ReleaseStringUTFChars(pathOrId, pathChars);
    putLong(env, outEventDescription, reinterpret_cast<jlong>(event));
    return result;
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nGetCoreSystem(
    JNIEnv* env, jclass, jlong system, jlongArray outCoreSystem) {
    FMOD_SYSTEM* core = nullptr;
    FMOD_RESULT result = FMOD_Studio_System_GetCoreSystem(reinterpret_cast<FMOD_STUDIO_SYSTEM*>(system), &core);
    putLong(env, outCoreSystem, reinterpret_cast<jlong>(core));
    return result;
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nLoadPlugin(
    JNIEnv* env, jclass, jlong coreSystem, jstring filename, jint priority) {
    const char* filenameChars = env->GetStringUTFChars(filename, nullptr);
    unsigned int handle = 0;
    FMOD_RESULT result = FMOD_System_LoadPlugin(
        reinterpret_cast<FMOD_SYSTEM*>(coreSystem), filenameChars, &handle, static_cast<unsigned int>(priority));
    env->ReleaseStringUTFChars(filename, filenameChars);
    return result;
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nSetListenerAttributes(
    JNIEnv*,
    jclass,
    jlong system,
    jint index,
    jfloat px, jfloat py, jfloat pz,
    jfloat vx, jfloat vy, jfloat vz,
    jfloat fx, jfloat fy, jfloat fz,
    jfloat ux, jfloat uy, jfloat uz) {
    FMOD_3D_ATTRIBUTES attrs = buildAttributes(px, py, pz, vx, vy, vz, fx, fy, fz, ux, uy, uz);
    return FMOD_Studio_System_SetListenerAttributes(
        reinterpret_cast<FMOD_STUDIO_SYSTEM*>(system), index, &attrs, nullptr);
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nBankLoadSampleData(JNIEnv*, jclass, jlong bank) {
    return FMOD_Studio_Bank_LoadSampleData(reinterpret_cast<FMOD_STUDIO_BANK*>(bank));
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nBankUnload(JNIEnv*, jclass, jlong bank) {
    return FMOD_Studio_Bank_Unload(reinterpret_cast<FMOD_STUDIO_BANK*>(bank));
}

// Returns every event description pointer in the bank as a long[], empty on failure -- same
// "return the array directly" shape as nEventDescriptionGetParameterNames below, rather than an
// out-param + FMOD_RESULT, since callers (FMODSystem.Bank#getEventList) only ever want the list.
JNIEXPORT jlongArray JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nBankGetEventList(JNIEnv* env, jclass, jlong bank) {
    auto* bankPtr = reinterpret_cast<FMOD_STUDIO_BANK*>(bank);

    int count = 0;
    if (FMOD_Studio_Bank_GetEventCount(bankPtr, &count) != 0 || count <= 0) {
        return env->NewLongArray(0);
    }

    std::vector<FMOD_STUDIO_EVENTDESCRIPTION*> descriptions(static_cast<size_t>(count));
    int retrieved = 0;
    if (FMOD_Studio_Bank_GetEventList(bankPtr, descriptions.data(), count, &retrieved) != 0) {
        return env->NewLongArray(0);
    }

    jlongArray result = env->NewLongArray(retrieved);
    std::vector<jlong> boxed(static_cast<size_t>(retrieved));
    for (int i = 0; i < retrieved; i++) {
        boxed[static_cast<size_t>(i)] = reinterpret_cast<jlong>(descriptions[static_cast<size_t>(i)]);
    }
    env->SetLongArrayRegion(result, 0, retrieved, boxed.data());
    return result;
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nEventDescriptionCreateInstance(
    JNIEnv* env, jclass, jlong eventDescription, jlongArray outInstance) {
    FMOD_STUDIO_EVENTINSTANCE* instance = nullptr;
    FMOD_RESULT result = FMOD_Studio_EventDescription_CreateInstance(
        reinterpret_cast<FMOD_STUDIO_EVENTDESCRIPTION*>(eventDescription), &instance);
    putLong(env, outInstance, reinterpret_cast<jlong>(instance));
    return result;
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nEventDescriptionGetPath(
    JNIEnv* env, jclass, jlong eventDescription, jobjectArray outPath) {
    // Matches the previous jextract-based implementation's fixed 512-byte buffer.
    char buffer[512];
    int retrieved = 0;
    FMOD_RESULT result = FMOD_Studio_EventDescription_GetPath(
        reinterpret_cast<FMOD_STUDIO_EVENTDESCRIPTION*>(eventDescription), buffer, sizeof(buffer), &retrieved);
    env->SetObjectArrayElement(outPath, 0, env->NewStringUTF(buffer));
    return result;
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nEventDescriptionGetId(
    JNIEnv* env, jclass, jlong eventDescription, jobjectArray outId) {
    FMOD_GUID guid{};
    FMOD_RESULT result =
        FMOD_Studio_EventDescription_GetID(reinterpret_cast<FMOD_STUDIO_EVENTDESCRIPTION*>(eventDescription), &guid);
    char formatted[40];
    std::snprintf(
        formatted,
        sizeof(formatted),
        "{%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x}",
        guid.Data1,
        guid.Data2,
        guid.Data3,
        guid.Data4[0], guid.Data4[1],
        guid.Data4[2], guid.Data4[3], guid.Data4[4], guid.Data4[5], guid.Data4[6], guid.Data4[7]);
    env->SetObjectArrayElement(outId, 0, env->NewStringUTF(formatted));
    return result;
}

JNIEXPORT jobjectArray JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nEventDescriptionGetParameterNames(
    JNIEnv* env, jclass, jlong eventDescription) {
    auto* description = reinterpret_cast<FMOD_STUDIO_EVENTDESCRIPTION*>(eventDescription);

    int count = 0;
    if (FMOD_Studio_EventDescription_GetParameterDescriptionCount(description, &count) != 0 || count <= 0) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(count, stringClass, nullptr);
    for (int i = 0; i < count; i++) {
        FMOD_STUDIO_PARAMETER_DESCRIPTION parameter{};
        if (FMOD_Studio_EventDescription_GetParameterDescriptionByIndex(description, i, &parameter) == 0) {
            env->SetObjectArrayElement(result, i, env->NewStringUTF(parameter.name));
        }
    }
    return result;
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nEventInstanceStart(JNIEnv*, jclass, jlong instance) {
    return FMOD_Studio_EventInstance_Start(reinterpret_cast<FMOD_STUDIO_EVENTINSTANCE*>(instance));
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nEventInstanceStop(JNIEnv*, jclass, jlong instance, jint mode) {
    return FMOD_Studio_EventInstance_Stop(reinterpret_cast<FMOD_STUDIO_EVENTINSTANCE*>(instance), mode);
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nEventInstanceGetPlaybackState(
    JNIEnv* env, jclass, jlong instance, jintArray outState) {
    int state = 0;
    FMOD_RESULT result =
        FMOD_Studio_EventInstance_GetPlaybackState(reinterpret_cast<FMOD_STUDIO_EVENTINSTANCE*>(instance), &state);
    putInt(env, outState, state);
    return result;
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nEventInstanceSet3DAttributes(
    JNIEnv*,
    jclass,
    jlong instance,
    jfloat px, jfloat py, jfloat pz,
    jfloat vx, jfloat vy, jfloat vz,
    jfloat fx, jfloat fy, jfloat fz,
    jfloat ux, jfloat uy, jfloat uz) {
    FMOD_3D_ATTRIBUTES attrs = buildAttributes(px, py, pz, vx, vy, vz, fx, fy, fz, ux, uy, uz);
    return FMOD_Studio_EventInstance_Set3DAttributes(reinterpret_cast<FMOD_STUDIO_EVENTINSTANCE*>(instance), &attrs);
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nEventInstanceSetParameterByName(
    JNIEnv* env, jclass, jlong instance, jstring name, jfloat value, jint ignoreSeekSpeed) {
    const char* nameChars = env->GetStringUTFChars(name, nullptr);
    FMOD_RESULT result = FMOD_Studio_EventInstance_SetParameterByName(
        reinterpret_cast<FMOD_STUDIO_EVENTINSTANCE*>(instance), nameChars, value, ignoreSeekSpeed);
    env->ReleaseStringUTFChars(name, nameChars);
    return result;
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nEventInstanceSetVolume(
    JNIEnv*, jclass, jlong instance, jfloat volume) {
    return FMOD_Studio_EventInstance_SetVolume(reinterpret_cast<FMOD_STUDIO_EVENTINSTANCE*>(instance), volume);
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nEventInstanceRelease(JNIEnv*, jclass, jlong instance) {
    return FMOD_Studio_EventInstance_Release(reinterpret_cast<FMOD_STUDIO_EVENTINSTANCE*>(instance));
}

// Dev-tool only (see fmod_min.h's comment on FMOD_STUDIO_EVENT_CALLBACK) -- lazily caches the JVM
// and the target static Java method (FMODSystem.onSoundPlayed(long, String)) on first call, then
// registers soundPlayedTrampoline for SOUND_PLAYED only.
JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nEventInstanceSetCallback(JNIEnv* env, jclass clazz, jlong instance) {
    if (g_javaVm == nullptr) {
        env->GetJavaVM(&g_javaVm);
        g_fmodSystemClass = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));
        g_onSoundPlayedMethod = env->GetStaticMethodID(clazz, "onSoundPlayed", "(JLjava/lang/String;)V");
    }
    return FMOD_Studio_EventInstance_SetCallback(
        reinterpret_cast<FMOD_STUDIO_EVENTINSTANCE*>(instance), soundPlayedTrampoline, FMOD_STUDIO_EVENT_CALLBACK_SOUND_PLAYED);
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nEventInstanceGetChannelGroup(
    JNIEnv* env, jclass, jlong instance, jlongArray outGroup) {
    FMOD_CHANNELGROUP* group = nullptr;
    FMOD_RESULT result =
        FMOD_Studio_EventInstance_GetChannelGroup(reinterpret_cast<FMOD_STUDIO_EVENTINSTANCE*>(instance), &group);
    putLong(env, outGroup, reinterpret_cast<jlong>(group));
    return result;
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nCreateDSPByType(
    JNIEnv* env, jclass, jlong coreSystem, jint type, jlongArray outDsp) {
    FMOD_DSP* dsp = nullptr;
    FMOD_RESULT result = FMOD_System_CreateDSPByType(reinterpret_cast<FMOD_SYSTEM*>(coreSystem), type, &dsp);
    putLong(env, outDsp, reinterpret_cast<jlong>(dsp));
    return result;
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nChannelGroupAddDSP(
    JNIEnv*, jclass, jlong group, jint index, jlong dsp) {
    return FMOD_ChannelGroup_AddDSP(
        reinterpret_cast<FMOD_CHANNELGROUP*>(group), index, reinterpret_cast<FMOD_DSP*>(dsp));
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nChannelGroupRemoveDSP(
    JNIEnv*, jclass, jlong group, jlong dsp) {
    return FMOD_ChannelGroup_RemoveDSP(
        reinterpret_cast<FMOD_CHANNELGROUP*>(group), reinterpret_cast<FMOD_DSP*>(dsp));
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nDspSetParameterFloat(
    JNIEnv*, jclass, jlong dsp, jint index, jfloat value) {
    return FMOD_DSP_SetParameterFloat(reinterpret_cast<FMOD_DSP*>(dsp), index, value);
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODSystem_nDspRelease(JNIEnv*, jclass, jlong dsp) {
    return FMOD_DSP_Release(reinterpret_cast<FMOD_DSP*>(dsp));
}

// ---- com.iwei20.fmod.studio.FMODCoreSystem (Core FMOD::System, direct sound-file playback) ----

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODCoreSystem_nCreate(JNIEnv* env, jclass, jlongArray outSystem) {
    FMOD_SYSTEM* system = nullptr;
    FMOD_RESULT result = FMOD_System_Create(&system, kFmodVersion);
    putLong(env, outSystem, reinterpret_cast<jlong>(system));
    return result;
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODCoreSystem_nRelease(JNIEnv*, jclass, jlong system) {
    return FMOD_System_Release(reinterpret_cast<FMOD_SYSTEM*>(system));
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODCoreSystem_nInit(
    JNIEnv*, jclass, jlong system, jint maxChannels, jint flags) {
    return FMOD_System_Init(reinterpret_cast<FMOD_SYSTEM*>(system), maxChannels, static_cast<unsigned int>(flags), nullptr);
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODCoreSystem_nUpdate(JNIEnv*, jclass, jlong system) {
    return FMOD_System_Update(reinterpret_cast<FMOD_SYSTEM*>(system));
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODCoreSystem_nCreateSound(
    JNIEnv* env, jclass, jlong system, jstring path, jint mode, jlongArray outSound) {
    const char* pathChars = env->GetStringUTFChars(path, nullptr);
    FMOD_SOUND* sound = nullptr;
    FMOD_RESULT result = FMOD_System_CreateSound(
        reinterpret_cast<FMOD_SYSTEM*>(system), pathChars, static_cast<unsigned int>(mode), nullptr, &sound);
    env->ReleaseStringUTFChars(path, pathChars);
    putLong(env, outSound, reinterpret_cast<jlong>(sound));
    return result;
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODCoreSystem_nPlaySound(
    JNIEnv* env, jclass, jlong system, jlong sound, jlongArray outChannel) {
    FMOD_CHANNEL* channel = nullptr;
    FMOD_RESULT result = FMOD_System_PlaySound(
        reinterpret_cast<FMOD_SYSTEM*>(system), reinterpret_cast<FMOD_SOUND*>(sound), nullptr, 0, &channel);
    putLong(env, outChannel, reinterpret_cast<jlong>(channel));
    return result;
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODCoreSystem_nSoundRelease(JNIEnv*, jclass, jlong sound) {
    return FMOD_Sound_Release(reinterpret_cast<FMOD_SOUND*>(sound));
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODCoreSystem_nChannelStop(JNIEnv*, jclass, jlong channel) {
    return FMOD_Channel_Stop(reinterpret_cast<FMOD_CHANNEL*>(channel));
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODCoreSystem_nChannelSetVolume(
    JNIEnv*, jclass, jlong channel, jfloat volume) {
    return FMOD_Channel_SetVolume(reinterpret_cast<FMOD_CHANNEL*>(channel), volume);
}

JNIEXPORT jint JNICALL Java_com_iwei20_fmod_studio_FMODCoreSystem_nChannelIsPlaying(
    JNIEnv* env, jclass, jlong channel, jintArray outPlaying) {
    FMOD_BOOL playing = 0;
    FMOD_RESULT result = FMOD_Channel_IsPlaying(reinterpret_cast<FMOD_CHANNEL*>(channel), &playing);
    putInt(env, outPlaying, playing);
    return result;
}

} // extern "C"
