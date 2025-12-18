#include <jni.h>
#include <stdlib.h>
#include <vector>
#include <android/log.h>
#include <mutex>
#include <cmath>
#include "sonic.h"

#define TARGET_RATE 24000  // Master Output Rate
#define MAX_OUTPUT_SAMPLES 4096

std::mutex processorMutex;
static sonicStream stream = NULL;
static int currentInputRate = 16000; // Default

// --- Helper: Linear Resampler ---
// Converts input samples to TARGET_RATE (24000) smoothly
std::vector<short> SimpleLinearResample(const short* input, int inputLen, int inRate, int outRate) {
    if (inRate == outRate || inputLen <= 0) {
        return std::vector<short>(input, input + inputLen);
    }

    float ratio = (float)inRate / (float)outRate;
    int outputLen = (int)((float)inputLen / ratio);
    std::vector<short> output(outputLen);

    for (int i = 0; i < outputLen; i++) {
        float index = i * ratio;
        int leftIndex = (int)index;
        int rightIndex = leftIndex + 1;
        float frac = index - leftIndex;

        if (rightIndex >= inputLen) {
            output[i] = input[leftIndex]; // End case
        } else {
            // Linear Interpolation formula
            float val = input[leftIndex] * (1.0f - frac) + input[rightIndex] * frac;
            output[i] = (short)val;
        }
    }
    return output;
}

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

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint inputRate, jint ch) {
    std::lock_guard<std::mutex> lock(processorMutex);
    
    currentInputRate = inputRate; // Store the Engine's Rate (e.g., 22050)

    // Ensure Stream is always 24000Hz (TARGET_RATE)
    if (!stream) {
        stream = sonicCreateStream(TARGET_RATE, ch);
        sonicSetQuality(stream, 1);
        sonicSetRate(stream, 1.0f);
        sonicSetVolume(stream, 1.0f);
    } 
    // No need to destroy/recreate if just input rate changed. 
    // The stream stays at 24000Hz. We handle conversion before feeding.
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jfloat s, jfloat p) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (stream) {
        float safeSpeed = (s < 0.1f) ? 0.1f : s;
        float safePitch = (p < 0.4f) ? 0.4f : p; // Safety floor
        
        sonicSetSpeed(stream, safeSpeed);
        sonicSetPitch(stream, safePitch);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(JNIEnv* env, jobject, jobject buffer, jint len) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (!stream || len <= 0) return env->NewByteArray(0);

    void* bufferAddr = env->GetDirectBufferAddress(buffer);
    if (bufferAddr == NULL) return env->NewByteArray(0);

    short* rawInput = (short*)bufferAddr;
    int sampleCount = len / 2;

    // 1. Resample Input -> 24000Hz
    std::vector<short> resampled = SimpleLinearResample(rawInput, sampleCount, currentInputRate, TARGET_RATE);

    // 2. Feed 24000Hz data to Sonic
    sonicWriteShortToStream(stream, resampled.data(), resampled.size());
    
    // 3. Read back
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

