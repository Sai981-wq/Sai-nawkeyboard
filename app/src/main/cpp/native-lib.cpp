#include <jni.h>
#include <stdlib.h>
#include <vector>
#include <android/log.h>
#include <mutex>
#include "sonic.h"

#define TARGET_RATE 24000  
#define MAX_OUTPUT_SAMPLES 4096

std::mutex processorMutex;
static sonicStream stream = NULL;

static float userSpeed = 1.0f;
static float userPitch = 1.0f;
static int currentInputRate = 16000;

static int garbageBytesRemaining = 0; 

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
    if (!stream) return;

    float resampleRatio = (float)currentInputRate / (float)TARGET_RATE;
    sonicSetRate(stream, resampleRatio);

    sonicSetSpeed(stream, userSpeed);
    
    float safePitch = (userPitch < 0.4f) ? 0.4f : userPitch;
    sonicSetPitch(stream, safePitch);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint inputRate, jint ch) {
    std::lock_guard<std::mutex> lock(processorMutex);
    
    currentInputRate = inputRate;

    if (!stream) {
        stream = sonicCreateStream(TARGET_RATE, ch);
        sonicSetQuality(stream, 1); 
        sonicSetVolume(stream, 1.0f);
    }
    updateSonicConfig();
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_resetHeaderSkip(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(processorMutex);
    garbageBytesRemaining = 44; 
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jfloat s, jfloat p) {
    std::lock_guard<std::mutex> lock(processorMutex);
    userSpeed = s;
    userPitch = p;
    updateSonicConfig();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(JNIEnv* env, jobject, jobject buffer, jint len) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (!stream || len <= 0) return env->NewByteArray(0);

    void* bufferAddr = env->GetDirectBufferAddress(buffer);
    if (bufferAddr == NULL) return env->NewByteArray(0);

    jbyte* rawBytes = (jbyte*)bufferAddr;
    int processLen = len;
    int offset = 0;

    if (garbageBytesRemaining > 0) {
        if (processLen <= garbageBytesRemaining) {
            garbageBytesRemaining -= processLen;
            return env->NewByteArray(0); 
        } else {
            offset = garbageBytesRemaining;
            processLen -= garbageBytesRemaining;
            garbageBytesRemaining = 0;
        }
    }

    sonicWriteShortToStream(stream, (short*)(rawBytes + offset), processLen / 2);
    
    return readFromStream(env, stream);
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
        garbageBytesRemaining = 0;
    }
}

