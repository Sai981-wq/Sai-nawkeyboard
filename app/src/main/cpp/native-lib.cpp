#include <jni.h>
#include <stdlib.h>
#include <vector>
#include <android/log.h>
#include <mutex>
#include "sonic.h"

std::mutex processorMutex;
static sonicStream stream = NULL;
static int currentInputRate = 0;

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
    if (!stream) return 0;

    if (len > 0 && inBuffer != NULL) {
        void* inAddr = env->GetDirectBufferAddress(inBuffer);
        if (inAddr != NULL) {
            sonicWriteShortToStream(stream, (short*)inAddr, len / 2);
        }
    }

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

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_flush(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (stream) {
        sonicFlushStream(stream);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_stop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (stream) {
        sonicDestroyStream(stream);
        stream = NULL;
        currentInputRate = 0;
    }
}

