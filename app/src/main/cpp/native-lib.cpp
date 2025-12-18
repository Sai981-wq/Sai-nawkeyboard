#include <jni.h>
#include <stdlib.h>
#include <vector>
#include <android/log.h>
#include <mutex>
#include "sonic.h"

#define TARGET_RATE 24000  // System Output Fixed at 24kHz
#define MAX_OUTPUT_SAMPLES 4096

std::mutex processorMutex;
static sonicStream stream = NULL;

// Variables to store User Preferences
static float userSpeed = 1.0f;
static float userPitch = 1.0f;
static int currentInputRate = 16000;

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

// Update Sonic settings based on InputRate vs TargetRate
void updateSonicConfig() {
    if (!stream) return;

    // Calculate the compensation factor
    // Example: Input 16k, Target 24k. Factor = 0.666
    float rateFactor = (float)currentInputRate / (float)TARGET_RATE;

    // We apply this factor to both Speed and Pitch to counteract the
    // "Chipmunk effect" of playing low-rate audio on high-rate stream.
    
    float finalSpeed = userSpeed * rateFactor;
    float finalPitch = userPitch * rateFactor;

    // Safety checks
    if (finalSpeed < 0.1f) finalSpeed = 0.1f;
    if (finalPitch < 0.1f) finalPitch = 0.1f; // Pitch 0.4 check handles in Java or here if needed

    sonicSetSpeed(stream, finalSpeed);
    sonicSetPitch(stream, finalPitch);
    sonicSetRate(stream, 1.0f); // Always 1.0 playback rate
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint inputRate, jint ch) {
    std::lock_guard<std::mutex> lock(processorMutex);
    
    currentInputRate = inputRate;

    // Always create stream at TARGET_RATE (24000)
    if (!stream) {
        stream = sonicCreateStream(TARGET_RATE, ch);
        sonicSetQuality(stream, 1); // Enable High Quality SINC Filter
        sonicSetVolume(stream, 1.0f);
    }
    
    // Apply the correction math
    updateSonicConfig();
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jfloat s, jfloat p) {
    std::lock_guard<std::mutex> lock(processorMutex);
    
    userSpeed = s;
    
    // Protect against Low Pitch Crash (User requested 0.4 limit previously)
    userPitch = (p < 0.4f) ? 0.4f : p;
    
    updateSonicConfig();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(JNIEnv* env, jobject, jobject buffer, jint len) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (!stream || len <= 0) return env->NewByteArray(0);

    void* bufferAddr = env->GetDirectBufferAddress(buffer);
    if (bufferAddr == NULL) return env->NewByteArray(0);

    // Write RAW input samples directly. 
    // Since stream is 24k and data is (e.g.) 16k, Sonic treats it as 24k (fast).
    // But 'updateSonicConfig' has already set Speed/Pitch to slow it down correctly.
    sonicWriteShortToStream(stream, (short*)bufferAddr, len / 2);
    
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
    }
}

