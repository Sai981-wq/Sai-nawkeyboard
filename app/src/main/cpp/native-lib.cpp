#include <jni.h>
#include <stdlib.h>
#include <vector>
#include <android/log.h>
#include <mutex>
#include "sonic.h"

#define TARGET_RATE 24000

std::mutex processorMutex;
static sonicStream stream = NULL;
static int currentInRate = 0;

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
    
    if (stream && currentInRate == rate) {
        return;
    }
    
    if (stream) sonicDestroyStream(stream);
    
    stream = sonicCreateStream(rate, ch);
    currentInRate = rate;
    
    sonicSetQuality(stream, 1); 
    sonicSetSpeed(stream, 1.0f);
    sonicSetPitch(stream, 1.0f);
    
    float playbackRate = (float)rate / (float)TARGET_RATE;
    sonicSetRate(stream, playbackRate); 
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jfloat s, jfloat p) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (stream) {
        float safeSpeed = (s < 0.1f) ? 0.1f : s;
        float safePitch = (p < 0.1f) ? 0.1f : p;
        
        sonicSetSpeed(stream, safeSpeed);
        sonicSetPitch(stream, safePitch);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(JNIEnv* env, jobject, jbyteArray in, jint len) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (!stream || len <= 0) return env->NewByteArray(0);

    void* primitive = env->GetPrimitiveArrayCritical(in, 0);
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
        int avail = sonicSamplesAvailable(stream);
        if (avail > 0) {
            std::vector<short> dummy(avail);
            sonicReadShortFromStream(stream, dummy.data(), avail);
        }
    }
}

