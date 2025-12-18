#include <jni.h>
#include <stdlib.h>
#include <vector>
#include <android/log.h>
#include <mutex>
#include <cmath>
#include "sonic.h"

#define TARGET_RATE 24000  // Master Output
#define MAX_OUTPUT_SAMPLES 4096

std::mutex processorMutex;
static sonicStream stream = NULL;
static int currentInputRate = 16000;
static bool isFirstBuffer = true; // To track start of playback for Fade-In

// --- Helper: Cubic Hermite Interpolation (High Quality Audio) ---
// This calculates smooth curves between samples, preventing "Phone Sound" and "Wavering"
float cubic_hermite(float p0, float p1, float p2, float p3, float x) {
    float a = -0.5f * p0 + 1.5f * p1 - 1.5f * p2 + 0.5f * p3;
    float b = p0 - 2.5f * p1 + 2.0f * p2 - 0.5f * p3;
    float c = -0.5f * p0 + 0.5f * p2;
    float d = p1;
    return a*x*x*x + b*x*x + c*x + d;
}

// Resamples input to TARGET_RATE using Cubic Interpolation
std::vector<short> CubicResample(const short* input, int inputLen, int inRate, int outRate) {
    if (inRate == outRate || inputLen <= 0) {
        return std::vector<short>(input, input + inputLen);
    }

    float ratio = (float)inRate / (float)outRate;
    int outputLen = (int)((float)inputLen / ratio);
    std::vector<short> output(outputLen);

    for (int i = 0; i < outputLen; i++) {
        float pos = i * ratio;
        int idx = (int)pos;
        float frac = pos - idx;

        // Get 4 neighboring samples (Clamp to edges)
        float p0 = (idx > 0) ? input[idx - 1] : input[0];
        float p1 = input[idx];
        float p2 = (idx + 1 < inputLen) ? input[idx + 1] : input[inputLen - 1];
        float p3 = (idx + 2 < inputLen) ? input[idx + 2] : input[inputLen - 1];

        float val = cubic_hermite(p0, p1, p2, p3, frac);
        
        // Clip to 16-bit range
        if (val > 32767.0f) val = 32767.0f;
        if (val < -32768.0f) val = -32768.0f;
        
        output[i] = (short)val;
    }
    return output;
}

// Apply a tiny fade-in to the first few milliseconds to remove "Duk/Click" sound
void applyFadeIn(short* buffer, int len) {
    int fadeLen = 240; // Approx 10ms at 24kHz
    if (len < fadeLen) fadeLen = len;
    
    for (int i = 0; i < fadeLen; i++) {
        float gain = (float)i / (float)fadeLen;
        buffer[i] = (short)(buffer[i] * gain);
    }
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
    
    currentInputRate = inputRate;
    isFirstBuffer = true; // Reset fade-in flag for new stream

    if (!stream) {
        stream = sonicCreateStream(TARGET_RATE, ch); // Stream is always 24k
        sonicSetQuality(stream, 1);
        sonicSetRate(stream, 1.0f);
        sonicSetVolume(stream, 1.0f);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jfloat s, jfloat p) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (stream) {
        float safeSpeed = (s < 0.1f) ? 0.1f : s;
        float safePitch = (p < 0.4f) ? 0.4f : p;
        
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

    // 1. Resample (Smooth Cubic)
    std::vector<short> resampled = CubicResample(rawInput, sampleCount, currentInputRate, TARGET_RATE);

    // 2. Apply Fade-In only for the very first chunk to kill "Duk" sound
    if (isFirstBuffer && resampled.size() > 0) {
        applyFadeIn(resampled.data(), resampled.size());
        isFirstBuffer = false;
    }

    // 3. Feed to Sonic
    sonicWriteShortToStream(stream, resampled.data(), resampled.size());
    
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
        isFirstBuffer = true; // Reset for next utterance
    }
}

