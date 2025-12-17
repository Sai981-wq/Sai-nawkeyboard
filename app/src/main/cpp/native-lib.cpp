// native-lib.cpp (Replace this content)

#include <jni.h>
#include <stdlib.h>
#include <vector>
#include <mutex>
#include "sonic.h"

std::mutex processorMutex;
static sonicStream stream = NULL;

// Helper
jbyteArray readFromStream(JNIEnv* env, sonicStream s) {
    int avail = sonicSamplesAvailable(s);
    if (avail <= 0) return env->NewByteArray(0);
    std::vector<short> buf(avail);
    int read = sonicReadShortFromStream(s, buf.data(), avail);
    if (read > 0) {
        jbyteArray res = env->NewByteArray(read * 2);
        env->SetByteArrayRegion(res, 0, read * 2, (jbyte*)buf.data());
        return res;
    }
    return env->NewByteArray(0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint rate, jint ch) {
    std::lock_guard<std::mutex> lock(processorMutex);
    
    if (stream) sonicDestroyStream(stream);
    stream = sonicCreateStream(rate, ch);
    
    // Quality = 1 (Better Speech, no cracking)
    sonicSetQuality(stream, 1); 
    
    // FIX: Don't mess with Rate here. Just Speed & Pitch.
    sonicSetSpeed(stream, 1.0f);
    sonicSetPitch(stream, 1.0f);
    sonicSetRate(stream, 1.0f); // Keep rate 1.0 (Normal)
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jfloat s, jfloat p) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (stream) {
        sonicSetSpeed(stream, s);
        sonicSetPitch(stream, p);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(JNIEnv* env, jobject, jbyteArray in, jint len) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (!stream || len <= 0) return env->NewByteArray(0);

    void* primitive = env->GetPrimitiveArrayCritical(in, 0);
    if (primitive == NULL) return env->NewByteArray(0);

    sonicWriteShortToStream(stream, (short*)primitive, len / 2);
    env->ReleasePrimitiveArrayCritical(in, primitive, 0);

    return readFromStream(env, stream);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_drain(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (!stream) return env->NewByteArray(0);
    sonicFlushStream(stream);
    return readFromStream(env, stream);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_flush(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (stream) {
        sonicFlushStream(stream);
        // Clear buffer
        int avail = sonicSamplesAvailable(stream);
        if (avail > 0) {
            std::vector<short> dummy(avail);
            sonicReadShortFromStream(stream, dummy.data(), avail);
        }
    }
}

