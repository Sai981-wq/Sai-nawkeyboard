#include <jni.h>
#include "sonic.h"
#include <mutex>

struct SonicSession {
    sonicStream stream;
    std::mutex mutex;
};

extern "C"
JNIEXPORT jlong JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(
        JNIEnv*, jobject, jint sampleRate, jint channels) {

    auto* session = new SonicSession();
    session->stream = sonicCreateStream(sampleRate, channels);

    if (!session->stream) {
        delete session;
        return 0;
    }

    sonicSetSpeed(session->stream, 1.0f);
    sonicSetPitch(session->stream, 1.0f);
    sonicSetRate(session->stream, 1.0f);
    sonicSetVolume(session->stream, 1.0f);
    sonicSetQuality(session->stream, 1);

    return reinterpret_cast<jlong>(session);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(
        JNIEnv* env, jobject,
        jlong handle,
        jobject inBuffer, jint len,
        jobject outBuffer, jint maxOutLen) {

    if (handle == 0) return 0;

    auto* session = reinterpret_cast<SonicSession*>(handle);
    std::lock_guard<std::mutex> lock(session->mutex);

    if (!session->stream) return 0;

    if (inBuffer && len > 0) {
        auto* inAddr = static_cast<short*>(env->GetDirectBufferAddress(inBuffer));
        if (inAddr) {
            sonicWriteShortToStream(session->stream, inAddr, len / 2);
        }
    }

    auto* outAddr = static_cast<short*>(env->GetDirectBufferAddress(outBuffer));
    if (!outAddr) return 0;

    int samples = sonicReadShortFromStream(
            session->stream,
            outAddr,
            maxOutLen / 2
    );

    return samples * 2;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_flush(
        JNIEnv*, jobject, jlong handle) {

    if (handle == 0) return;

    auto* session = reinterpret_cast<SonicSession*>(handle);
    std::lock_guard<std::mutex> lock(session->mutex);

    if (session->stream) {
        sonicFlushStream(session->stream);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_stop(
        JNIEnv*, jobject, jlong handle) {

    if (handle == 0) return;

    auto* session = reinterpret_cast<SonicSession*>(handle);

    if (session->stream) {
        sonicDestroyStream(session->stream);
        session->stream = nullptr;
    }

    delete session;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setSonicSpeed(
        JNIEnv*, jobject, jlong handle, jfloat speed) {

    if (handle == 0) return;
    auto* session = reinterpret_cast<SonicSession*>(handle);
    std::lock_guard<std::mutex> lock(session->mutex);
    if (session->stream) sonicSetSpeed(session->stream, speed);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setSonicPitch(
        JNIEnv*, jobject, jlong handle, jfloat pitch) {

    if (handle == 0) return;
    auto* session = reinterpret_cast<SonicSession*>(handle);
    std::lock_guard<std::mutex> lock(session->mutex);
    if (session->stream) sonicSetPitch(session->stream, pitch);
}
