#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <android/log.h>
#include "sonic.h"

// Log Tag ကို Debug လုပ်ရန်လွယ်ကူအောင် ပြောင်းထားသည်
#define TAG "CherryTTS_Debug"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define FIXED_TARGET_RATE 24000

class CherrySonicProcessor {
public:
    sonicStream stream;
    int currentSampleRate;
    int targetRate;
    
    short* processingBuffer;
    int processingCap;
    short* resampleBuffer;
    int resampleCap;

    CherrySonicProcessor(int sampleRate, int channels) {
        currentSampleRate = (sampleRate > 0) ? sampleRate : 16000;
        targetRate = FIXED_TARGET_RATE;

        stream = sonicCreateStream(currentSampleRate, channels);
        sonicSetQuality(stream, 1);
        sonicSetVolume(stream, 1.0f);
        
        processingBuffer = NULL;
        processingCap = 0;
        resampleBuffer = NULL;
        resampleCap = 0;

        LOGD("[Native] InitSonic Created: InRate=%d, Target=%d", currentSampleRate, targetRate);
    }

    ~CherrySonicProcessor() {
        if (stream != NULL) sonicDestroyStream(stream);
        if (processingBuffer != NULL) delete[] processingBuffer;
        if (resampleBuffer != NULL) delete[] resampleBuffer;
        LOGD("[Native] Sonic Destroyed");
    }

    void ensureProcessingBuffer(int needed) {
        if (needed > processingCap) {
            if (processingBuffer != NULL) delete[] processingBuffer;
            processingCap = needed + 4096; 
            processingBuffer = new short[processingCap];
            LOGD("[Native] Resized Processing Buffer: %d", processingCap);
        }
    }

    void ensureResampleBuffer(int needed) {
        if (needed > resampleCap) {
            if (resampleBuffer != NULL) delete[] resampleBuffer;
            resampleCap = needed + 4096; 
            resampleBuffer = new short[resampleCap];
            LOGD("[Native] Resized Resample Buffer: %d", resampleCap);
        }
    }

    int resampleLinear(short* input, int inputSamples, int inRate, int outRate) {
        if (inRate <= 0 || outRate <= 0 || inputSamples <= 0) return 0;
        
        if (inRate == outRate) {
            ensureResampleBuffer(inputSamples);
            memcpy(resampleBuffer, input, inputSamples * sizeof(short));
            return inputSamples;
        }

        long long newSize = (long long)inputSamples * outRate / inRate;
        int outSamples = (int)newSize;
        if (outSamples <= 0) return 0;

        ensureResampleBuffer(outSamples);
        double ratio = (double)inRate / outRate;
        
        for (int i = 0; i < outSamples; i++) {
            double exactPos = i * ratio;
            int index = (int)exactPos;
            double frac = exactPos - index;

            short p1 = (index < inputSamples) ? input[index] : 0;
            short p2 = (index + 1 < inputSamples) ? input[index + 1] : p1;

            float mixed = p1 + (float)(p2 - p1) * frac;
            resampleBuffer[i] = (short)mixed;
        }
        return outSamples;
    }
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint sampleRate, jint channels) {
    LOGD("[Native] Request InitSonic: Rate=%d", sampleRate);
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
        sonicSetSpeed(processor->stream, speed);
        sonicSetPitch(processor->stream, pitch);
        // LOGD("[Native] Config Set: Speed=%.2f, Pitch=%.2f", speed, pitch);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(JNIEnv* env, jobject, jlong handle, jbyteArray input, jint len) {
    CherrySonicProcessor* processor = (CherrySonicProcessor*)handle;
    if (processor == NULL || processor->stream == NULL || len <= 0) {
        LOGE("[Native] Process Skipped: Null Handle or Empty Input");
        return env->NewByteArray(0);
    }

    jbyte* bufferPtr = env->GetByteArrayElements(input, NULL);
    if (bufferPtr == NULL) return env->NewByteArray(0);
    
    int safeLen = len & ~1; 
    
    // Write to Sonic
    int ret = sonicWriteShortToStream(processor->stream, (short*)bufferPtr, safeLen / 2);
    env->ReleaseByteArrayElements(input, bufferPtr, 0);

    if (ret == 0) return env->NewByteArray(0);

    int available = sonicSamplesAvailable(processor->stream);
    if (available <= 0) return env->NewByteArray(0);

    processor->ensureProcessingBuffer(available);
    int readSamples = sonicReadShortFromStream(processor->stream, processor->processingBuffer, available);

    if (readSamples > 0) {
        int resampledCount = processor->resampleLinear(processor->processingBuffer, readSamples, processor->currentSampleRate, processor->targetRate);
        
        if (resampledCount > 0) {
            jbyteArray result = env->NewByteArray(resampledCount * 2);
            env->SetByteArrayRegion(result, 0, resampledCount * 2, (jbyte*)processor->resampleBuffer);
            
            // Debug Log: Input vs Output (Uncomment if needed, helps detecting flow)
            // LOGD("[Native] Processed: InSamples=%d -> OutSamples=%d", readSamples, resampledCount);
            
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
        LOGD("[Native] Stream Flushed");
    }
}

