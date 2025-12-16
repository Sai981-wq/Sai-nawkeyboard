#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <android/log.h>
#include "sonic.h"

#define TAG "CherryNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

static sonicStream stream = NULL;
static int currentSampleRate = 16000;
static int TARGET_RATE = 24000;

inline float cubicInterpolate(float p0, float p1, float p2, float p3, float t) {
    return 0.5f * (
        (2.0f * p1) +
        (-p0 + p2) * t +
        (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * t * t +
        (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * t * t * t
    );
}

short* resampleCubic(short* input, int inputSamples, int inRate, int outRate, int* outSamples) {
    if (inRate <= 0 || outRate <= 0 || inputSamples <= 0) { 
        *outSamples = 0;
        return NULL;
    }
    
    if (inRate == outRate) {
        *outSamples = inputSamples;
        short* copy = new short[inputSamples];
        memcpy(copy, input, inputSamples * sizeof(short));
        return copy;
    }

    long long newSize = (long long)inputSamples * outRate / inRate;
    *outSamples = (int)newSize;
    
    if (*outSamples <= 0) return NULL;

    short* output = new short[*outSamples];
    double ratio = (double)inRate / outRate;
    
    for (int i = 0; i < *outSamples; i++) {
        double exactPos = i * ratio;
        int index = (int)exactPos;
        double t = exactPos - index;

        float p0 = (index > 0) ? input[index - 1] : input[0];
        float p1 = input[index];
        float p2 = (index < inputSamples - 1) ? input[index + 1] : input[inputSamples - 1];
        float p3 = (index < inputSamples - 2) ? input[index + 2] : input[inputSamples - 1];

        float mixed = cubicInterpolate(p0, p1, p2, p3, (float)t);
        
        if (mixed > 32767.0f) mixed = 32767.0f;
        if (mixed < -32768.0f) mixed = -32768.0f;
        
        output[i] = (short)mixed;
    }
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint sampleRate, jint channels) {
    if (stream != NULL) sonicDestroyStream(stream);
    currentSampleRate = (sampleRate > 0) ? sampleRate : 16000;
    stream = sonicCreateStream(currentSampleRate, channels);
    sonicSetQuality(stream, 1);
    sonicSetVolume(stream, 1.0f);
    LOGD("InitSonic: Rate=%d, Target=%d", currentSampleRate, TARGET_RATE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jfloat speed, jfloat pitch) {
    if (stream != NULL) {
        sonicSetRate(stream, 1.0f); 
        sonicSetSpeed(stream, speed);
        sonicSetPitch(stream, pitch);
        LOGD("SetConfig: Speed=%.2f, Pitch=%.2f", speed, pitch);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(JNIEnv* env, jobject, jbyteArray input, jint len) {
    if (stream == NULL || len <= 0) return env->NewByteArray(0);

    jbyte* bufferPtr = env->GetByteArrayElements(input, NULL);
    if (bufferPtr == NULL) return env->NewByteArray(0);
    
    int safeLen = len & ~1; 
    sonicWriteShortToStream(stream, (short*)bufferPtr, safeLen / 2);
    env->ReleaseByteArrayElements(input, bufferPtr, 0);

    int available = sonicSamplesAvailable(stream);
    if (available <= 0) return env->NewByteArray(0);

    short* sonicBuffer = new short[available];
    int readSamples = sonicReadShortFromStream(stream, sonicBuffer, available);

    if (readSamples > 0) {
        int resampledCount = 0;
        short* resampledBuffer = resampleCubic(sonicBuffer, readSamples, currentSampleRate, TARGET_RATE, &resampledCount);

        if (resampledBuffer != NULL && resampledCount > 0) {
            jbyteArray result = env->NewByteArray(resampledCount * 2);
            env->SetByteArrayRegion(result, 0, resampledCount * 2, (jbyte*)resampledBuffer);
            
            LOGD("Process: InSamples=%d -> OutSamples=%d (Cubic)", readSamples, resampledCount);

            delete[] sonicBuffer;
            delete[] resampledBuffer;
            return result;
        }
        if (resampledBuffer != NULL) delete[] resampledBuffer;
    }
    
    delete[] sonicBuffer;
    return env->NewByteArray(0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_flush(JNIEnv*, jobject) {
    if (stream != NULL) {
        sonicFlushStream(stream);
        LOGD("Sonic Stream Flushed");
    }
}

