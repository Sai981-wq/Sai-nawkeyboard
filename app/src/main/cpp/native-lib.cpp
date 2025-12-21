#include <jni.h>
#include "sonic.h"
#include <mutex>

std::mutex processorMutex;
static sonicStream stream = NULL;

static int currentInputRate = 22050; 
const int FIXED_OUTPUT_RATE = 24000;

void updateSonicConfig() {
    if (!stream) return;
    float resampleRatio = (float)currentInputRate / (float)FIXED_OUTPUT_RATE;
    sonicSetRate(stream, resampleRatio);
    sonicSetSpeed(stream, 1.0f);
    sonicSetPitch(stream, 1.0f);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint inputRate, jint ch) {
    std::lock_guard<std::mutex> lock(processorMutex);
    
    currentInputRate = inputRate; 

    if (stream) {
        sonicDestroyStream(stream);
        stream = NULL;
    }

    stream = sonicCreateStream(FIXED_OUTPUT_RATE, ch);
    sonicSetQuality(stream, 0); 
    sonicSetVolume(stream, 1.0f);
    
    updateSonicConfig();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(
        JNIEnv* env, jobject, 
        jobject inBuffer, jint len, 
        jobject outBuffer, jint maxOutLen
) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (!stream) return 0;

    if (len > 0 && inBuffer != NULL) {
        short* inAddr = (short*)env->GetDirectBufferAddress(inBuffer);
        if (inAddr != NULL) {
            sonicWriteShortToStream(stream, inAddr, len / 2);
        }
    }

    void* outAddr = env->GetDirectBufferAddress(outBuffer);
    if (outAddr == NULL) return 0;

    int maxShorts = maxOutLen / 2;
    int samplesRead = sonicReadShortFromStream(stream, (short*)outAddr, maxShorts);

    return samplesRead * 2;
}

extern "C" JNIEXPORT void JNICALL Java_com_shan_tts_manager_AudioProcessor_flush(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (stream) sonicFlushStream(stream);
}

extern "C" JNIEXPORT void JNICALL Java_com_shan_tts_manager_AudioProcessor_stop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (stream) { 
        sonicDestroyStream(stream); 
        stream = NULL; 
    }
}

