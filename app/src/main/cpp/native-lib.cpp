#include <jni.h>
#include <stdlib.h>
#include <mutex>
#include "sonic.h"

std::mutex processorMutex;
static sonicStream stream = NULL;

static int currentInputRate = 22050; 
const int FIXED_OUTPUT_RATE = 24000;

static float currentSpeed = 1.0f;
static float currentPitch = 1.0f;

static int skippedHeaderBytes = 0;
const int HEADER_SIZE = 44;

void updateSonicConfig() {
    if (!stream) return;
    float resampleRatio = (float)currentInputRate / (float)FIXED_OUTPUT_RATE;
    sonicSetRate(stream, resampleRatio);
    sonicSetSpeed(stream, currentSpeed);
    sonicSetPitch(stream, currentPitch);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint inputRate, jint ch) {
    std::lock_guard<std::mutex> lock(processorMutex);
    
    currentInputRate = inputRate; 
    skippedHeaderBytes = 0;     

    if (stream) {
        sonicDestroyStream(stream);
        stream = NULL;
    }

    stream = sonicCreateStream(FIXED_OUTPUT_RATE, ch);
    sonicSetQuality(stream, 0); 
    sonicSetVolume(stream, 1.0f);
    
    updateSonicConfig();
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jfloat s, jfloat p) {
    std::lock_guard<std::mutex> lock(processorMutex);
    currentSpeed = s;
    currentPitch = p;
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
        unsigned char* inAddr = (unsigned char*)env->GetDirectBufferAddress(inBuffer);
        if (inAddr != NULL) {
            
            int dataOffset = 0;
            
            if (skippedHeaderBytes < HEADER_SIZE) {
                int remainingToSkip = HEADER_SIZE - skippedHeaderBytes;
                if (len <= remainingToSkip) {
                    skippedHeaderBytes += len;
                    return 0; 
                } else {
                    dataOffset = remainingToSkip;
                    skippedHeaderBytes += remainingToSkip;
                }
            }

            if ((uintptr_t)(inAddr + dataOffset) % 2 != 0) {
                dataOffset++;
            }

            int audioLen = len - dataOffset;
            if (audioLen > 0) {
                sonicWriteShortToStream(stream, (short*)(inAddr + dataOffset), audioLen / 2);
            }
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

