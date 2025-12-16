#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <android/log.h>
#include "sonic.h"

#define TAG "CherryNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

class CherrySonicProcessor {
public:
    sonicStream stream;
    int currentSampleRate;
    int targetRate;

    // Reusable Buffers (Memory Optimization)
    short* processingBuffer;
    int processingCap;
    
    short* resampleBuffer;
    int resampleCap;

    CherrySonicProcessor(int sampleRate, int channels) {
        currentSampleRate = (sampleRate > 0) ? sampleRate : 16000;
        targetRate = 24000;
        stream = sonicCreateStream(currentSampleRate, channels);
        sonicSetQuality(stream, 1);
        sonicSetVolume(stream, 1.0f);
        
        // Init buffers to NULL
        processingBuffer = NULL;
        processingCap = 0;
        resampleBuffer = NULL;
        resampleCap = 0;

        LOGD("InitSonic: Rate=%d, Target=%d", currentSampleRate, targetRate);
    }

    ~CherrySonicProcessor() {
        if (stream != NULL) {
            sonicDestroyStream(stream);
            stream = NULL;
        }
        // Clean up buffers
        if (processingBuffer != NULL) {
            delete[] processingBuffer;
            processingBuffer = NULL;
        }
        if (resampleBuffer != NULL) {
            delete[] resampleBuffer;
            resampleBuffer = NULL;
        }
    }

    // Ensure processing buffer has enough size
    void ensureProcessingBuffer(int needed) {
        if (needed > processingCap) {
            if (processingBuffer != NULL) delete[] processingBuffer;
            processingCap = needed + 1024; // Add padding to reduce frequent resizing
            processingBuffer = new short[processingCap];
        }
    }

    // Ensure resample buffer has enough size
    void ensureResampleBuffer(int needed) {
        if (needed > resampleCap) {
            if (resampleBuffer != NULL) delete[] resampleBuffer;
            resampleCap = needed + 1024; // Add padding
            resampleBuffer = new short[resampleCap];
        }
    }

    inline float cubicInterpolate(float p0, float p1, float p2, float p3, float t) {
        return 0.5f * (
            (2.0f * p1) +
            (-p0 + p2) * t +
            (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * t * t +
            (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * t * t * t
        );
    }

    // Modified to write directly to member variable 'resampleBuffer'
    // Returns number of samples produced
    int resampleCubic(short* input, int inputSamples, int inRate, int outRate) {
        if (inRate <= 0 || outRate <= 0 || inputSamples <= 0) return 0;
        
        // If rates match, just copy to resampleBuffer
        if (inRate == outRate) {
            ensureResampleBuffer(inputSamples);
            memcpy(resampleBuffer, input, inputSamples * sizeof(short));
            return inputSamples;
        }

        long long newSize = (long long)inputSamples * outRate / inRate;
        int outSamples = (int)newSize;
        
        if (outSamples <= 0) return 0;

        // Resize buffer if needed
        ensureResampleBuffer(outSamples);

        double ratio = (double)inRate / outRate;
        
        for (int i = 0; i < outSamples; i++) {
            double exactPos = i * ratio;
            int index = (int)exactPos;
            double t = exactPos - index;

            // Boundary checks
            float p0 = (index > 0) ? input[index - 1] : input[0];
            float p1 = input[index];
            float p2 = (index < inputSamples - 1) ? input[index + 1] : input[inputSamples - 1];
            float p3 = (index < inputSamples - 2) ? input[index + 2] : input[inputSamples - 1];

            float mixed = cubicInterpolate(p0, p1, p2, p3, (float)t);
            
            if (mixed > 32767.0f) mixed = 32767.0f;
            if (mixed < -32768.0f) mixed = -32768.0f;
            
            resampleBuffer[i] = (short)mixed;
        }
        return outSamples;
    }
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint sampleRate, jint channels) {
    CherrySonicProcessor* processor = new CherrySonicProcessor(sampleRate, channels);
    return (jlong)processor;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_release(JNIEnv* env, jobject, jlong handle) {
    CherrySonicProcessor* processor = (CherrySonicProcessor*)handle;
    if (processor != NULL) {
        delete processor;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jlong handle, jfloat speed, jfloat pitch) {
    CherrySonicProcessor* processor = (CherrySonicProcessor*)handle;
    if (processor != NULL && processor->stream != NULL) {
        sonicSetRate(processor->stream, 1.0f); 
        sonicSetSpeed(processor->stream, speed);
        sonicSetPitch(processor->stream, pitch);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(JNIEnv* env, jobject, jlong handle, jbyteArray input, jint len) {
    CherrySonicProcessor* processor = (CherrySonicProcessor*)handle;
    if (processor == NULL || processor->stream == NULL || len <= 0) return env->NewByteArray(0);

    jbyte* bufferPtr = env->GetByteArrayElements(input, NULL);
    if (bufferPtr == NULL) return env->NewByteArray(0);
    
    int safeLen = len & ~1; 
    sonicWriteShortToStream(processor->stream, (short*)bufferPtr, safeLen / 2);
    env->ReleaseByteArrayElements(input, bufferPtr, 0);

    int available = sonicSamplesAvailable(processor->stream);
    if (available <= 0) return env->NewByteArray(0);

    // 1. Ensure we have memory to read from Sonic
    processor->ensureProcessingBuffer(available);

    // 2. Read into the reused buffer
    int readSamples = sonicReadShortFromStream(processor->stream, processor->processingBuffer, available);

    if (readSamples > 0) {
        // 3. Resample directly into processor->resampleBuffer
        int resampledCount = processor->resampleCubic(processor->processingBuffer, readSamples, processor->currentSampleRate, processor->targetRate);

        if (resampledCount > 0) {
            jbyteArray result = env->NewByteArray(resampledCount * 2);
            // Copy from the reused member buffer to Java array
            env->SetByteArrayRegion(result, 0, resampledCount * 2, (jbyte*)processor->resampleBuffer);
            
            // LOGD("Process: InSamples=%d -> OutSamples=%d", readSamples, resampledCount);
            return result;
        }
    }
    
    return env->NewByteArray(0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_flush(JNIEnv*, jobject, jlong handle) {
    CherrySonicProcessor* processor = (CherrySonicProcessor*)handle;
    if (processor != NULL && processor->stream != NULL) {
        sonicFlushStream(processor->stream);
    }
}

