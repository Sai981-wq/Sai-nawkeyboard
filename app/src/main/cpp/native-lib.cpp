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

// Helper to convert Sonic output to Java ByteArray
jbyteArray readFromStream(JNIEnv* env, sonicStream s) {
    int avail = sonicSamplesAvailable(s);
    if (avail <= 0) return env->NewByteArray(0);

    // Sonic uses short (16-bit), so byte size is count * 2
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
    
    if (stream) {
        if (currentInRate == rate) {
            sonicFlushStream(stream);
            return;
        }
        sonicDestroyStream(stream);
    }
    
    stream = sonicCreateStream(rate, ch);
    currentInRate = rate;
    
    // CHANGE: Use Quality 1 for better speech clarity (reduces cracking)
    sonicSetQuality(stream, 1); 
    
    sonicSetSpeed(stream, 1.0f);
    sonicSetPitch(stream, 1.0f);
    
    // Resampling Logic: Adjust internal rate to match fixed System Output (24kHz)
    float playbackRate = (float)rate / (float)TARGET_RATE;
    sonicSetRate(stream, playbackRate);
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
    if (primitive == NULL) return env->NewByteArray(0); // Safety check

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

