/*
 * JNI bridge for io.github.jwyoon1220.dncity.audio.NativeAudio (see the Kotlin source for the
 * fixed PCM format and the ring-buffer contract). Deliberately dumb: the only thing this file
 * does is move samples between miniaudio's device callbacks and two ma_pcm_rb ring buffers. The
 * audio device thread never touches the JVM (no JNIEnv attach/detach, no callback into Java) --
 * it only reads/writes the ring buffers, which miniaudio documents as safe for exactly one
 * producer and one consumer thread each, matching our capture (device thread writes, Java reads)
 * and playback (Java writes, device thread reads) usage.
 *
 * Uses plain C <string.h> for the ring-buffer memcpy (matching the rest of this file's style).
 * The noise gate/VAD fields below are genuinely shared between the audio callback thread and
 * whichever thread calls into JNI, so they need atomic access -- but via __atomic_load_n/
 * __atomic_store_n compiler builtins on plain fields rather than <atomic>, since this project
 * builds with clang targeting MinGW-w64 (see CMakeLists.txt for why: codec2's C99 `_Complex`
 * usage needs mingw's libc, not MSVC's), and this dev machine's MinGW-w64 sysroot has no
 * libstdc++ headers installed (C runtime only) -- the builtins need no header at all, so this
 * sidesteps that rather than depending on one being added.
 */

#include <string.h>
#include <math.h>

#include <jni.h>

#include "miniaudio.h"

namespace {

constexpr ma_uint32 kSampleRate = 48000;
constexpr ma_uint32 kChannels = 1;
constexpr ma_format kFormat = ma_format_s16;
// 1 second of headroom at the fixed format -- generous relative to the 20ms frames this is
// meant to carry, so a stalled consumer doesn't immediately drop audio.
constexpr ma_uint32 kRingBufferFrames = kSampleRate;
// Plain fixed-size arrays (matching this file's no-STL style) to hold the ma_device_id of each
// enumerated device, indexed the same way as the String[] returned to Java -- selectCaptureDevice/
// selectPlaybackDevice look a chosen index up in these. Comfortably above what any real machine
// exposes.
constexpr int kMaxDevices = 64;

// Noise gate / VAD tuning. The gate closing more slowly than it opens (150ms release vs. 3ms
// attack) avoids chopping the tail off words; the separate, slower envelope follower (as opposed
// to gating sample-by-sample) avoids a buzzy/chattery gate on signals that hover near the
// threshold. kHysteresisDb keeps the open/close decision from flapping right at the threshold.
constexpr float kEnvelopeAttackMs = 5.0f;
constexpr float kEnvelopeReleaseMs = 50.0f;
constexpr float kGateAttackMs = 3.0f;
constexpr float kGateReleaseMs = 150.0f;
constexpr float kHysteresisDb = 3.0f;
constexpr float kMinDb = -100.0f;

float msToOnePoleCoeff(float ms) {
    return expf(-1.0f / (static_cast<float>(kSampleRate) * (ms / 1000.0f)));
}

float linearToDb(float linear) {
    if (linear <= 0.0f) return kMinDb;
    float db = 20.0f * log10f(linear);
    return db < kMinDb ? kMinDb : db;
}

// clang's __atomic_load_n/__atomic_store_n builtins only accept integer/pointer operands, not
// float or bool directly -- these wrap the generic (address-of-source/dest) __atomic_load/
// __atomic_store builtins instead, which accept any trivially-copyable type, to get the same
// relaxed-ordering cross-thread access the header comment above describes.
float atomicLoadRelaxed(const float* addr) {
    float value;
    __atomic_load(addr, &value, __ATOMIC_RELAXED);
    return value;
}

void atomicStoreRelaxed(float* addr, float value) {
    __atomic_store(addr, &value, __ATOMIC_RELAXED);
}

bool atomicLoadRelaxed(const bool* addr) {
    bool value;
    __atomic_load(addr, &value, __ATOMIC_RELAXED);
    return value;
}

void atomicStoreRelaxed(bool* addr, bool value) {
    __atomic_store(addr, &value, __ATOMIC_RELAXED);
}

struct AudioState {
    ma_context context{};
    bool contextInitialized = false;

    ma_device captureDevice{};
    ma_device playbackDevice{};
    ma_pcm_rb captureRb{};
    ma_pcm_rb playbackRb{};
    bool captureRunning = false;
    bool playbackRunning = false;
    bool captureRbInitialized = false;
    bool playbackRbInitialized = false;

    // Populated by listCaptureDevices/listPlaybackDevices; selectCaptureDevice/
    // selectPlaybackDevice index into these. -1 (the default) means "let miniaudio pick the
    // platform default device" (Windows' configured default mic/speaker) -- pDeviceID stays null
    // in that case rather than pointing at one of these.
    ma_device_id captureDeviceIds[kMaxDevices]{};
    int captureDeviceCount = 0;
    ma_device_id playbackDeviceIds[kMaxDevices]{};
    int playbackDeviceCount = 0;
    int selectedCaptureDevice = -1;
    int selectedPlaybackDevice = -1;

    // Noise gate / VAD. noiseGateThresholdDb is written rarely (only from setNoiseGateThresholdDb,
    // called from the game thread) and read every callback from the audio thread; inputLevelDb and
    // voiceActive are the reverse -- genuinely cross-thread, so accessed via __atomic_load_n/
    // __atomic_store_n (relaxed ordering is enough: these are independent status/tuning values,
    // not a handoff of buffer ownership; see the file header comment for why not std::atomic).
    float noiseGateThresholdDb = -50.0f;
    float inputLevelDb = kMinDb;
    bool voiceActive = false;
    float envelopeSq = 0.0f; // audio-thread-only smoothed squared amplitude
    float gateGain = 0.0f;   // audio-thread-only current gate gain, 0..1
};

AudioState g_state;

// Envelope-follows the signal, derives a smoothed open/closed gate gain from it (attack faster
// than release so speech onsets aren't clipped), and attenuates samples below the threshold in
// place. This is the "native noise reduction" -- a gate rather than spectral denoising, chosen
// because it is cheap enough to run unconditionally on the realtime audio thread with no
// allocation. Also updates the VAD state (voiceActive/inputLevelDb) consumers can poll.
void applyNoiseGate(AudioState* state, ma_int16* samples, ma_uint32 frameCount) {
    const float envAttack = msToOnePoleCoeff(kEnvelopeAttackMs);
    const float envRelease = msToOnePoleCoeff(kEnvelopeReleaseMs);
    const float gateAttack = msToOnePoleCoeff(kGateAttackMs);
    const float gateRelease = msToOnePoleCoeff(kGateReleaseMs);
    const float openDb = atomicLoadRelaxed(&state->noiseGateThresholdDb);
    const float closeDb = openDb - kHysteresisDb;

    float envelopeSq = state->envelopeSq;
    float gateGain = state->gateGain;
    float peakLevel = 0.0f;

    for (ma_uint32 i = 0; i < frameCount; ++i) {
        float sample = static_cast<float>(samples[i]) / 32768.0f;
        float sq = sample * sample;
        float envCoeff = sq > envelopeSq ? envAttack : envRelease;
        envelopeSq = envCoeff * envelopeSq + (1.0f - envCoeff) * sq;

        float envelopeDb = linearToDb(sqrtf(envelopeSq));
        float targetGain = envelopeDb >= (gateGain > 0.5f ? closeDb : openDb) ? 1.0f : 0.0f;
        float gateCoeff = targetGain > gateGain ? gateAttack : gateRelease;
        gateGain = gateCoeff * gateGain + (1.0f - gateCoeff) * targetGain;

        samples[i] = static_cast<ma_int16>(sample * gateGain * 32767.0f);
        if (envelopeDb > peakLevel) peakLevel = envelopeDb;
    }

    state->envelopeSq = envelopeSq;
    state->gateGain = gateGain;
    atomicStoreRelaxed(&state->inputLevelDb, peakLevel > kMinDb ? peakLevel : kMinDb);
    atomicStoreRelaxed(&state->voiceActive, gateGain > 0.5f);
}

void captureDataCallback(ma_device* pDevice, void* /*pOutput*/, const void* pInput, ma_uint32 frameCount) {
    auto* state = static_cast<AudioState*>(pDevice->pUserData);
    if (state == nullptr || pInput == nullptr) return;

    // Gate/denoise in place on a local copy is unnecessary here since pInput is miniaudio's own
    // scratch buffer for this callback, not caller-owned memory -- safe to mutate directly before
    // it's copied into the ring buffer below.
    applyNoiseGate(state, const_cast<ma_int16*>(static_cast<const ma_int16*>(pInput)), frameCount);

    const auto* src = static_cast<const ma_uint8*>(pInput);
    ma_uint32 framesRemaining = frameCount;
    while (framesRemaining > 0) {
        ma_uint32 framesToWrite = framesRemaining;
        void* pWriteBuffer = nullptr;
        if (ma_pcm_rb_acquire_write(&state->captureRb, &framesToWrite, &pWriteBuffer) != MA_SUCCESS) {
            break;
        }
        if (framesToWrite == 0) {
            // Ring buffer is full (consumer isn't keeping up) -- drop the remainder of this frame.
            break;
        }
        memcpy(pWriteBuffer, src, static_cast<size_t>(framesToWrite) * ma_get_bytes_per_frame(kFormat, kChannels));
        ma_pcm_rb_commit_write(&state->captureRb, framesToWrite);
        src += static_cast<size_t>(framesToWrite) * ma_get_bytes_per_frame(kFormat, kChannels);
        framesRemaining -= framesToWrite;
    }
}

void playbackDataCallback(ma_device* pDevice, void* pOutput, const void* /*pInput*/, ma_uint32 frameCount) {
    auto* state = static_cast<AudioState*>(pDevice->pUserData);
    auto* dst = static_cast<ma_uint8*>(pOutput);
    const ma_uint32 bytesPerFrame = ma_get_bytes_per_frame(kFormat, kChannels);
    if (state == nullptr) {
        memset(dst, 0, static_cast<size_t>(frameCount) * bytesPerFrame);
        return;
    }

    ma_uint32 framesRemaining = frameCount;
    while (framesRemaining > 0) {
        ma_uint32 framesToRead = framesRemaining;
        void* pReadBuffer = nullptr;
        if (ma_pcm_rb_acquire_read(&state->playbackRb, &framesToRead, &pReadBuffer) != MA_SUCCESS) {
            break;
        }
        if (framesToRead == 0) {
            // Nothing queued -- fill the rest of this callback with silence rather than stalling.
            break;
        }
        memcpy(dst, pReadBuffer, static_cast<size_t>(framesToRead) * bytesPerFrame);
        ma_pcm_rb_commit_read(&state->playbackRb, framesToRead);
        dst += static_cast<size_t>(framesToRead) * bytesPerFrame;
        framesRemaining -= framesToRead;
    }
    if (framesRemaining > 0) {
        memset(dst, 0, static_cast<size_t>(framesRemaining) * bytesPerFrame);
    }
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_io_github_jwyoon1220_dncity_audio_NativeAudio_init(JNIEnv*, jobject) {
    // Devices and ring buffers are still created lazily by startCapture/startPlayback so either
    // half can be used independently -- only the context (needed for device enumeration and to
    // open a non-default device by id) is eagerly created here, idempotently.
    if (!g_state.contextInitialized) {
        if (ma_context_init(nullptr, 0, nullptr, &g_state.context) != MA_SUCCESS) {
            return JNI_FALSE;
        }
        g_state.contextInitialized = true;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_io_github_jwyoon1220_dncity_audio_NativeAudio_shutdown(JNIEnv*, jobject) {
    if (g_state.captureRunning) {
        ma_device_uninit(&g_state.captureDevice);
        g_state.captureRunning = false;
    }
    if (g_state.captureRbInitialized) {
        ma_pcm_rb_uninit(&g_state.captureRb);
        g_state.captureRbInitialized = false;
    }
    if (g_state.playbackRunning) {
        ma_device_uninit(&g_state.playbackDevice);
        g_state.playbackRunning = false;
    }
    if (g_state.playbackRbInitialized) {
        ma_pcm_rb_uninit(&g_state.playbackRb);
        g_state.playbackRbInitialized = false;
    }
    if (g_state.contextInitialized) {
        ma_context_uninit(&g_state.context);
        g_state.contextInitialized = false;
    }
}

JNIEXPORT jboolean JNICALL Java_io_github_jwyoon1220_dncity_audio_NativeAudio_startCapture(JNIEnv*, jobject) {
    if (g_state.captureRunning) return JNI_TRUE;

    if (!g_state.captureRbInitialized) {
        if (ma_pcm_rb_init(kFormat, kChannels, kRingBufferFrames, nullptr, nullptr, &g_state.captureRb) != MA_SUCCESS) {
            return JNI_FALSE;
        }
        g_state.captureRbInitialized = true;
    } else {
        ma_pcm_rb_reset(&g_state.captureRb);
    }
    g_state.envelopeSq = 0.0f;
    g_state.gateGain = 0.0f;
    atomicStoreRelaxed(&g_state.inputLevelDb, kMinDb);
    atomicStoreRelaxed(&g_state.voiceActive, false);

    ma_device_config config = ma_device_config_init(ma_device_type_capture);
    config.capture.format = kFormat;
    config.capture.channels = kChannels;
    if (g_state.selectedCaptureDevice >= 0 && g_state.selectedCaptureDevice < g_state.captureDeviceCount) {
        config.capture.pDeviceID = &g_state.captureDeviceIds[g_state.selectedCaptureDevice];
    }
    config.sampleRate = kSampleRate;
    config.dataCallback = captureDataCallback;
    config.pUserData = &g_state;

    ma_context* pContext = g_state.contextInitialized ? &g_state.context : nullptr;
    if (ma_device_init(pContext, &config, &g_state.captureDevice) != MA_SUCCESS) {
        return JNI_FALSE;
    }
    if (ma_device_start(&g_state.captureDevice) != MA_SUCCESS) {
        ma_device_uninit(&g_state.captureDevice);
        return JNI_FALSE;
    }
    g_state.captureRunning = true;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_io_github_jwyoon1220_dncity_audio_NativeAudio_stopCapture(JNIEnv*, jobject) {
    if (!g_state.captureRunning) return;
    ma_device_uninit(&g_state.captureDevice);
    g_state.captureRunning = false;
}

JNIEXPORT jint JNICALL Java_io_github_jwyoon1220_dncity_audio_NativeAudio_readCapture(JNIEnv* env, jobject, jshortArray buffer) {
    if (!g_state.captureRbInitialized) return 0;

    jsize capacity = env->GetArrayLength(buffer);
    if (capacity <= 0) return 0;

    ma_uint32 framesToRead = static_cast<ma_uint32>(capacity);
    void* pReadBuffer = nullptr;
    if (ma_pcm_rb_acquire_read(&g_state.captureRb, &framesToRead, &pReadBuffer) != MA_SUCCESS) {
        return 0;
    }
    if (framesToRead > 0) {
        env->SetShortArrayRegion(buffer, 0, static_cast<jsize>(framesToRead), static_cast<jshort*>(pReadBuffer));
        ma_pcm_rb_commit_read(&g_state.captureRb, framesToRead);
    }
    return static_cast<jint>(framesToRead);
}

JNIEXPORT jboolean JNICALL Java_io_github_jwyoon1220_dncity_audio_NativeAudio_startPlayback(JNIEnv*, jobject) {
    if (g_state.playbackRunning) return JNI_TRUE;

    if (!g_state.playbackRbInitialized) {
        if (ma_pcm_rb_init(kFormat, kChannels, kRingBufferFrames, nullptr, nullptr, &g_state.playbackRb) != MA_SUCCESS) {
            return JNI_FALSE;
        }
        g_state.playbackRbInitialized = true;
    } else {
        ma_pcm_rb_reset(&g_state.playbackRb);
    }

    ma_device_config config = ma_device_config_init(ma_device_type_playback);
    config.playback.format = kFormat;
    config.playback.channels = kChannels;
    if (g_state.selectedPlaybackDevice >= 0 && g_state.selectedPlaybackDevice < g_state.playbackDeviceCount) {
        config.playback.pDeviceID = &g_state.playbackDeviceIds[g_state.selectedPlaybackDevice];
    }
    config.sampleRate = kSampleRate;
    config.dataCallback = playbackDataCallback;
    config.pUserData = &g_state;

    ma_context* pContext = g_state.contextInitialized ? &g_state.context : nullptr;
    if (ma_device_init(pContext, &config, &g_state.playbackDevice) != MA_SUCCESS) {
        return JNI_FALSE;
    }
    if (ma_device_start(&g_state.playbackDevice) != MA_SUCCESS) {
        ma_device_uninit(&g_state.playbackDevice);
        return JNI_FALSE;
    }
    g_state.playbackRunning = true;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_io_github_jwyoon1220_dncity_audio_NativeAudio_stopPlayback(JNIEnv*, jobject) {
    if (!g_state.playbackRunning) return;
    ma_device_uninit(&g_state.playbackDevice);
    g_state.playbackRunning = false;
}

JNIEXPORT jint JNICALL Java_io_github_jwyoon1220_dncity_audio_NativeAudio_writePlayback(JNIEnv* env, jobject, jshortArray buffer, jint count) {
    if (!g_state.playbackRbInitialized) return 0;

    jsize available = env->GetArrayLength(buffer);
    ma_uint32 framesToWrite = static_cast<ma_uint32>(count < 0 ? 0 : count);
    if (framesToWrite > static_cast<ma_uint32>(available)) {
        framesToWrite = static_cast<ma_uint32>(available);
    }
    if (framesToWrite == 0) return 0;

    void* pWriteBuffer = nullptr;
    if (ma_pcm_rb_acquire_write(&g_state.playbackRb, &framesToWrite, &pWriteBuffer) != MA_SUCCESS) {
        return 0;
    }
    if (framesToWrite > 0) {
        env->GetShortArrayRegion(buffer, 0, static_cast<jsize>(framesToWrite), static_cast<jshort*>(pWriteBuffer));
        ma_pcm_rb_commit_write(&g_state.playbackRb, framesToWrite);
    }
    return static_cast<jint>(framesToWrite);
}

JNIEXPORT void JNICALL Java_io_github_jwyoon1220_dncity_audio_NativeAudio_setNoiseGateThresholdDb(JNIEnv*, jobject, jfloat thresholdDb) {
    atomicStoreRelaxed(&g_state.noiseGateThresholdDb, static_cast<float>(thresholdDb));
}

JNIEXPORT jboolean JNICALL Java_io_github_jwyoon1220_dncity_audio_NativeAudio_isVoiceActive(JNIEnv*, jobject) {
    return atomicLoadRelaxed(&g_state.voiceActive) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL Java_io_github_jwyoon1220_dncity_audio_NativeAudio_getInputLevelDb(JNIEnv*, jobject) {
    return atomicLoadRelaxed(&g_state.inputLevelDb);
}

namespace {

// Shared by listCaptureDevices/listPlaybackDevices: enumerates via ma_context_get_devices (one
// call yields both directions), copies the chosen direction's ma_device_id list into `ids`/`count`
// for later selectXDevice lookups, and returns a Java String[] of device names in the same order.
jobjectArray listDevices(JNIEnv* env, bool capture, ma_device_id* ids, int* count) {
    *count = 0;
    jclass stringClass = env->FindClass("java/lang/String");
    if (!g_state.contextInitialized) {
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    ma_device_info* pPlaybackInfos;
    ma_uint32 playbackCount;
    ma_device_info* pCaptureInfos;
    ma_uint32 captureCount;
    if (ma_context_get_devices(&g_state.context, &pPlaybackInfos, &playbackCount, &pCaptureInfos, &captureCount) != MA_SUCCESS) {
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    ma_device_info* pInfos = capture ? pCaptureInfos : pPlaybackInfos;
    ma_uint32 deviceCount = capture ? captureCount : playbackCount;
    if (deviceCount > static_cast<ma_uint32>(kMaxDevices)) {
        deviceCount = static_cast<ma_uint32>(kMaxDevices);
    }

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(deviceCount), stringClass, nullptr);
    for (ma_uint32 i = 0; i < deviceCount; ++i) {
        ids[i] = pInfos[i].id;
        env->SetObjectArrayElement(result, static_cast<jsize>(i), env->NewStringUTF(pInfos[i].name));
    }
    *count = static_cast<int>(deviceCount);
    return result;
}

} // namespace

JNIEXPORT jobjectArray JNICALL Java_io_github_jwyoon1220_dncity_audio_NativeAudio_listCaptureDevices(JNIEnv* env, jobject) {
    return listDevices(env, true, g_state.captureDeviceIds, &g_state.captureDeviceCount);
}

JNIEXPORT jobjectArray JNICALL Java_io_github_jwyoon1220_dncity_audio_NativeAudio_listPlaybackDevices(JNIEnv* env, jobject) {
    return listDevices(env, false, g_state.playbackDeviceIds, &g_state.playbackDeviceCount);
}

// index of -1 selects the platform default device (e.g. Windows' configured default mic/speaker).
JNIEXPORT void JNICALL Java_io_github_jwyoon1220_dncity_audio_NativeAudio_selectCaptureDevice(JNIEnv*, jobject, jint index) {
    g_state.selectedCaptureDevice = static_cast<int>(index);
}

JNIEXPORT void JNICALL Java_io_github_jwyoon1220_dncity_audio_NativeAudio_selectPlaybackDevice(JNIEnv*, jobject, jint index) {
    g_state.selectedPlaybackDevice = static_cast<int>(index);
}

JNIEXPORT jint JNICALL Java_io_github_jwyoon1220_dncity_audio_NativeAudio_getSelectedCaptureDevice(JNIEnv*, jobject) {
    return static_cast<jint>(g_state.selectedCaptureDevice);
}

JNIEXPORT jint JNICALL Java_io_github_jwyoon1220_dncity_audio_NativeAudio_getSelectedPlaybackDevice(JNIEnv*, jobject) {
    return static_cast<jint>(g_state.selectedPlaybackDevice);
}

} // extern "C"
