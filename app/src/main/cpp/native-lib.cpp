#include <jni.h>
#include <stdlib.h>
#include <vector>
#include <android/log.h>
#include <mutex>
#include "sonic.h"

#define MAX_OUTPUT_SAMPLES 16384 

std::mutex processorMutex;
static sonicStream stream = NULL;
static int currentInputRate = 0;

jbyteArray readFromStream(JNIEnv* env, sonicStream s) {
    int avail = sonicSamplesAvailable(s);
    if (avail <= 0) return env->NewByteArray(0);

    int samplesToRead = (avail > MAX_OUTPUT_SAMPLES) ? MAX_OUTPUT_SAMPLES : avail;
    std::vector<short> buf(samplesToRead);
    int read = sonicReadShortFromStream(s, buf.data(), samplesToRead);
    
    if (read > 0) {
        jbyteArray res = env->NewByteArray(read * 2);
        if (res == NULL) return env->NewByteArray(0);
        env->SetByteArrayRegion(res, 0, read * 2, (jbyte*)buf.data());
        return res;
    }
    return env->NewByteArray(0);
}

void updateSonicConfig() {
    if (!stream || currentInputRate == 0) return;
    float resampleRatio = (float)currentInputRate / 24000.0f;
    sonicSetRate(stream, resampleRatio);
    sonicSetSpeed(stream, 1.0f);
    sonicSetPitch(stream, 1.0f);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint inputRate, jint ch) {
    std::lock_guard<std::mutex> lock(processorMutex);
    
    if (stream && currentInputRate != inputRate) {
        sonicDestroyStream(stream);
        stream = NULL;
    }
    
    currentInputRate = inputRate;

    if (!stream) {
        stream = sonicCreateStream(24000, ch);
        sonicSetQuality(stream, 1); 
        sonicSetVolume(stream, 1.0f);
    } else {
        sonicFlushStream(stream);
    }
    
    updateSonicConfig();
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jfloat s, jfloat p) {
    std::lock_guard<std::mutex> lock(processorMutex);
    updateSonicConfig();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(
        JNIEnv* env, jobject, 
        jobject inBuffer, jint len, 
        jbyteArray outArray
) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (!stream || len <= 0) return 0;

    void* inAddr = env->GetDirectBufferAddress(inBuffer);
    if (inAddr == NULL) return 0;
    
    sonicWriteShortToStream(stream, (short*)inAddr, len / 2);

    int avail = sonicSamplesAvailable(stream);
    if (avail <= 0) return 0;

    jbyte* outPtr = env->GetByteArrayElements(outArray, NULL);
    if (outPtr == NULL) return 0;

    jsize outLen = env->GetArrayLength(outArray);
    int maxShorts = outLen / 2;
    
    int samplesRead = sonicReadShortFromStream(stream, (short*)outPtr, maxShorts);
    
    env->ReleaseByteArrayElements(outArray, outPtr, 0);

    return samplesRead * 2;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_drain(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (!stream) return env->NewByteArray(0);
    return readFromStream(env, stream);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_flush(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (stream) {
        sonicFlushStream(stream);
    }
}

