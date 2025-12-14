#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "sonic.h"

static sonicStream stream = NULL;
static int currentSampleRate = 16000;
static int TARGET_RATE = 24000;

short* resample(short* input, int inputSamples, int inRate, int outRate, int* outSamples) {
    if (inRate == outRate) {
        *outSamples = inputSamples;
        short* copy = new short[inputSamples];
        memcpy(copy, input, inputSamples * sizeof(short));
        return copy;
    }

    *outSamples = (int)((long long)inputSamples * outRate / inRate);
    short* output = new short[*outSamples];
    double ratio = (double)inRate / outRate;
    
    for (int i = 0; i < *outSamples; i++) {
        double index = i * ratio;
        int leftIndex = (int)index;
        int rightIndex = leftIndex + 1;
        double frac = index - leftIndex;

        if (rightIndex >= inputSamples) {
            output[i] = input[leftIndex];
        } else {
            short val = (short)((1.0 - frac) * input[leftIndex] + frac * input[rightIndex]);
            output[i] = val;
        }
    }
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint sampleRate, jint channels) {
    if (stream != NULL) sonicDestroyStream(stream);
    currentSampleRate = sampleRate;
    stream = sonicCreateStream(sampleRate, channels);
    sonicSetQuality(stream, 1);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jfloat speed, jfloat pitch, jfloat rate) {
    if (stream != NULL) {
        sonicSetSpeed(stream, speed);
        sonicSetPitch(stream, pitch);
        sonicSetRate(stream, rate);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(JNIEnv* env, jobject, jbyteArray input, jint len) {
    if (stream == NULL) return env->NewByteArray(0);

    jbyte* bufferPtr = env->GetByteArrayElements(input, NULL);
    sonicWriteShortToStream(stream, (short*)bufferPtr, len / 2);
    env->ReleaseByteArrayElements(input, bufferPtr, 0);

    int available = sonicSamplesAvailable(stream);
    if (available <= 0) return env->NewByteArray(0);

    short* sonicBuffer = new short[available];
    int readSamples = sonicReadShortFromStream(stream, sonicBuffer, available);

    if (readSamples > 0) {
        int resampledCount = 0;
        short* resampledBuffer = resample(sonicBuffer, readSamples, currentSampleRate, TARGET_RATE, &resampledCount);

        jbyteArray result = env->NewByteArray(resampledCount * 2);
        env->SetByteArrayRegion(result, 0, resampledCount * 2, (jbyte*)resampledBuffer);
        
        delete[] sonicBuffer;
        delete[] resampledBuffer;
        return result;
    }
    
    delete[] sonicBuffer;
    return env->NewByteArray(0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_flush(JNIEnv*, jobject) {
    if (stream != NULL) sonicFlushStream(stream);
}

