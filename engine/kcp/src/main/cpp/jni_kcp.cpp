// JNI bridge for DncityKcp (see that header for the algorithm itself). Handle-based, same shape
// as engine/audio's jni_codec2.cpp: create() returns a native pointer reinterpreted as an opaque
// jlong, every other call takes that handle back. Not thread-safe -- see DncityKcp.h's own note;
// one handle used from one thread only (matches NativeKcp.java's doc comment).

#include <jni.h>
#include <vector>
#include "DncityKcp.h"

namespace {
    DncityKcp* handleOf(jlong handle) {
        return reinterpret_cast<DncityKcp*>(handle);
    }

    jbyteArray toJavaByteArray(JNIEnv* env, const std::vector<uint8_t>& bytes) {
        jbyteArray out = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (out != nullptr && !bytes.empty()) {
            env->SetByteArrayRegion(out, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
        }
        return out;
    }
}

extern "C" {

JNIEXPORT jlong JNICALL Java_io_github_jwyoon1220_dncity_kcp_NativeKcp_create(JNIEnv*, jclass, jint conv) {
    return reinterpret_cast<jlong>(new DncityKcp(static_cast<int32_t>(conv)));
}

JNIEXPORT void JNICALL Java_io_github_jwyoon1220_dncity_kcp_NativeKcp_destroy(JNIEnv*, jclass, jlong handle) {
    delete handleOf(handle);
}

JNIEXPORT void JNICALL Java_io_github_jwyoon1220_dncity_kcp_NativeKcp_send(JNIEnv* env, jclass, jlong handle, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    std::vector<uint8_t> buf(static_cast<size_t>(len));
    if (len > 0) env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte*>(buf.data()));
    handleOf(handle)->send(buf.data(), buf.size());
}

JNIEXPORT jbyteArray JNICALL Java_io_github_jwyoon1220_dncity_kcp_NativeKcp_recv(JNIEnv* env, jclass, jlong handle) {
    std::vector<uint8_t> out;
    if (!handleOf(handle)->recv(out)) return nullptr;
    return toJavaByteArray(env, out);
}

JNIEXPORT void JNICALL Java_io_github_jwyoon1220_dncity_kcp_NativeKcp_input(JNIEnv* env, jclass, jlong handle, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    std::vector<uint8_t> buf(static_cast<size_t>(len));
    if (len > 0) env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte*>(buf.data()));
    handleOf(handle)->input(buf.data(), buf.size());
}

JNIEXPORT void JNICALL Java_io_github_jwyoon1220_dncity_kcp_NativeKcp_update(JNIEnv*, jclass, jlong handle, jlong currentMs) {
    handleOf(handle)->update(static_cast<int64_t>(currentMs));
}

JNIEXPORT jbyteArray JNICALL Java_io_github_jwyoon1220_dncity_kcp_NativeKcp_pollOutput(JNIEnv* env, jclass, jlong handle) {
    std::vector<uint8_t> out;
    if (!handleOf(handle)->pollOutput(out)) return nullptr;
    return toJavaByteArray(env, out);
}

} // extern "C"
