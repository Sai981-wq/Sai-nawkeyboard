#include <jni.h>
#include <stdlib.h>
#include <mutex>
#include "sonic.h"

std::mutex processorMutex;
static sonicStream stream = NULL;
static int currentInputRate = 0;
static float currentSpeed = 1.0f;
static float currentPitch = 1.0f;

void updateSonicConfig() {
    if (!stream || currentInputRate == 0) return;
    float resampleRatio = (float)currentInputRate / 24000.0f;
    sonicSetRate(stream, resampleRatio);
    sonicSetSpeed(stream, currentSpeed);
    sonicSetPitch(stream, currentPitch);
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
            if (len > 44 && inAddr[0] == 'R' && inAddr[1] == 'I' && inAddr[2] == 'F' && inAddr[3] == 'F') {
                for (int i = 12; i < len - 4; i++) {
                    if (inAddr[i] == 'd' && inAddr[i+1] == 'a' && inAddr[i+2] == 't' && inAddr[i+3] == 'a') {
                        dataOffset = i + 8; 
                        break;
                    }
                }
                if (dataOffset == 0) dataOffset = 44; 
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

