#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "sonic.h"

static sonicStream stream = NULL;
static int currentSampleRate = 16000;
static int TARGET_RATE = 24000;

// High-Precision Linear Resampler to fix cracking audio
short* resample(short* input, int inputSamples, int inRate, int outRate, int* outSamples) {
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
    
    // Using double for better precision to avoid "cracking" artifacts
    double ratio = (double)(inRate) / outRate;
    
    for (int i = 0; i < *outSamples; i++) {
        double exactPos = i * ratio;
        int index1 = (int)exactPos;
        int index2 = index1 + 1;
        double frac = exactPos - index1;

        if (index1 >= inputSamples) {
            output[i] = 0; // Silence if out of bounds
        } else if (index2 >= inputSamples) {
            output[i] = input[index1]; // Edge case
        } else {
            // Linear Interpolation
            double val = (1.0 - frac) * input[index1] + frac * input[index2];
            // Clamp value to short range to prevent overflow distortion
            if (val > 32767) val = 32767;
            if (val < -32768) val = -32768;
            output[i] = (short)val;
        }
    }
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint sampleRate, jint channels) {
    if (stream != NULL) sonicDestroyStream(stream);
    currentSampleRate = (sampleRate > 0) ? sampleRate : 16000;
    stream = sonicCreateStream(currentSampleRate, channels);
    sonicSetQuality(stream, 1); // Enable High Quality
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jfloat speed, jfloat pitch) {
    if (stream != NULL) {
        // Explicitly set Rate to 1.0 to prevent Pitch/Speed linking
        sonicSetRate(stream, 1.0f);
        sonicSetSpeed(stream, speed);
        sonicSetPitch(stream, pitch);
        sonicSetVolume(stream, 1.0f);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(JNIEnv* env, jobject, jbyteArray input, jint len) {
    if (stream == NULL || len <= 0) return env->NewByteArray(0);

    jbyte* bufferPtr = env->GetByteArrayElements(input, NULL);
    if (bufferPtr == NULL) return env->NewByteArray(0);
    
    // Write to Sonic
    sonicWriteShortToStream(stream, (short*)bufferPtr, len / 2);
    env->ReleaseByteArrayElements(input, bufferPtr, 0);

    // Read from Sonic
    int available = sonicSamplesAvailable(stream);
    if (available <= 0) return env->NewByteArray(0);

    short* sonicBuffer = new short[available];
    int readSamples = sonicReadShortFromStream(stream, sonicBuffer, available);

    if (readSamples > 0) {
        // Resample
        int resampledCount = 0;
        short* resampledBuffer = resample(sonicBuffer, readSamples, currentSampleRate, TARGET_RATE, &resampledCount);

        if (resampledBuffer != NULL && resampledCount > 0) {
            jbyteArray result = env->NewByteArray(resampledCount * 2);
            env->SetByteArrayRegion(result, 0, resampledCount * 2, (jbyte*)resampledBuffer);
            
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
    if (stream != NULL) sonicFlushStream(stream);
}

