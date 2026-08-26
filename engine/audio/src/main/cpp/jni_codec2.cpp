/*
 * JNI bridge for io.github.jwyoon1220.dncity.audio.Codec2 -- thin wrapper over vendor/codec2
 * (vendored as a git submodule, see CMakeLists.txt) for the radio-voice tiers' actual codec
 * (CODEC2_MODE_2400 for VHF/UHF, CODEC2_MODE_1200 for HF/MF -- see the radio voice-chat design
 * plan's tier table). Handle-based: each Codec2.create() allocates one native `struct CODEC2*`,
 * returned to Java as an opaque jlong; the caller owns it and must pair every create() with
 * exactly one destroy().
 *
 * codec2 itself is 8kHz mono, samples-per-frame varies by mode (160 for 2400, 320 for 1200) --
 * resampling to/from the rest of the mod's 48kHz pipeline happens on the Kotlin side
 * (voice/Resampler.kt), not here; this file only ever moves already-8kHz PCM in and packed bits
 * out (or vice versa).
 */

#include <jni.h>

#include <codec2.h>

namespace {
// codec2_bytes_per_frame's largest value across all modes this build enables is 8 (3200 mode,
// 64 bits); 2400/1200 modes both produce 6. codec2_samples_per_frame's largest value is 320
// (1200/1600/1400/1300/700C modes). Fixed-size stack buffers sized generously above both, so
// encode/decode never need to allocate.
constexpr int kMaxBytesPerFrame = 16;
constexpr int kMaxSamplesPerFrame = 512;
} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_io_github_jwyoon1220_dncity_audio_Codec2_create(JNIEnv*, jclass, jint mode) {
    struct CODEC2* state = codec2_create(mode);
    return reinterpret_cast<jlong>(state);
}

JNIEXPORT void JNICALL Java_io_github_jwyoon1220_dncity_audio_Codec2_destroy(JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return;
    codec2_destroy(reinterpret_cast<struct CODEC2*>(handle));
}

JNIEXPORT jint JNICALL Java_io_github_jwyoon1220_dncity_audio_Codec2_samplesPerFrame(JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return 0;
    return codec2_samples_per_frame(reinterpret_cast<struct CODEC2*>(handle));
}

JNIEXPORT jint JNICALL Java_io_github_jwyoon1220_dncity_audio_Codec2_bytesPerFrame(JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return 0;
    return codec2_bytes_per_frame(reinterpret_cast<struct CODEC2*>(handle));
}

JNIEXPORT jbyteArray JNICALL Java_io_github_jwyoon1220_dncity_audio_Codec2_encode(JNIEnv* env, jclass, jlong handle, jshortArray samples) {
    auto* state = reinterpret_cast<struct CODEC2*>(handle);
    if (state == nullptr) return env->NewByteArray(0);

    const int samplesPerFrame = codec2_samples_per_frame(state);
    const int bytesPerFrame = codec2_bytes_per_frame(state);
    if (env->GetArrayLength(samples) < samplesPerFrame || bytesPerFrame > kMaxBytesPerFrame) {
        return env->NewByteArray(0);
    }

    jshort* samplesPtr = env->GetShortArrayElements(samples, nullptr);
    unsigned char outBuf[kMaxBytesPerFrame];
    codec2_encode(state, outBuf, reinterpret_cast<short*>(samplesPtr));
    env->ReleaseShortArrayElements(samples, samplesPtr, JNI_ABORT);

    jbyteArray result = env->NewByteArray(bytesPerFrame);
    env->SetByteArrayRegion(result, 0, bytesPerFrame, reinterpret_cast<jbyte*>(outBuf));
    return result;
}

JNIEXPORT jshortArray JNICALL Java_io_github_jwyoon1220_dncity_audio_Codec2_decode(JNIEnv* env, jclass, jlong handle, jbyteArray bytes) {
    auto* state = reinterpret_cast<struct CODEC2*>(handle);
    if (state == nullptr) return env->NewShortArray(0);

    const int samplesPerFrame = codec2_samples_per_frame(state);
    const int bytesPerFrame = codec2_bytes_per_frame(state);
    if (env->GetArrayLength(bytes) < bytesPerFrame || samplesPerFrame > kMaxSamplesPerFrame) {
        return env->NewShortArray(0);
    }

    jbyte* bytesPtr = env->GetByteArrayElements(bytes, nullptr);
    short outBuf[kMaxSamplesPerFrame];
    codec2_decode(state, outBuf, reinterpret_cast<unsigned char*>(bytesPtr));
    env->ReleaseByteArrayElements(bytes, bytesPtr, JNI_ABORT);

    jshortArray result = env->NewShortArray(samplesPerFrame);
    env->SetShortArrayRegion(result, 0, samplesPerFrame, outBuf);
    return result;
}

} // extern "C"
