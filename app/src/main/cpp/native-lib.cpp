#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <android/log.h>
#include <vector>
#include "sonic.h"

#define TAG "CherryNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

class CherrySonicProcessor {
public:
    sonicStream stream;
    int currentSampleRate;
    int targetRate;
    
    float currentSpeed;
    float currentPitch;

    // Buffers
    std::vector<short> processingBuffer;
    std::vector<short> resampleBuffer;
    
    // --- STATEFUL RESAMPLING VARIABLES ---
    // အသံမထစ်အောင် Data အဟောင်း ၄ လုံး သိမ်းထားမည်
    short history[4]; 
    // Data တစ်သုတ်နဲ့ တစ်သုတ်ကြား နေရာလပ် (Phase) ကို မှတ်ထားမည်
    double resamplePhase; 

    CherrySonicProcessor(int sampleRate, int channels) {
        currentSampleRate = (sampleRate > 0) ? sampleRate : 16000;
        targetRate = 24000;
        
        currentSpeed = 1.0f;
        currentPitch = 1.0f;

        stream = sonicCreateStream(currentSampleRate, channels);
        sonicSetQuality(stream, 1);
        sonicSetVolume(stream, 1.0f);
        
        // Reset State
        memset(history, 0, sizeof(history));
        resamplePhase = 0.0;

        LOGD("InitSonic: Rate=%d, Target=%d", currentSampleRate, targetRate);
    }

    ~CherrySonicProcessor() {
        if (stream != NULL) {
            sonicDestroyStream(stream);
            stream = NULL;
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

    // Stateful Resampler
    int resampleCubic(short* input, int inputSamples) {
        if (inputSamples <= 0) return 0;
        
        // Hz တူလျှင် Copy ကူးရုံသာ (History မလို)
        if (currentSampleRate == targetRate) {
            resampleBuffer.resize(inputSamples);
            memcpy(resampleBuffer.data(), input, inputSamples * sizeof(short));
            return inputSamples;
        }

        double ratio = (double)currentSampleRate / targetRate;
        
        // ခန့်မှန်း Output အရေအတွက်
        int estimatedOut = (int)((inputSamples - resamplePhase) / ratio) + 2;
        resampleBuffer.resize(estimatedOut);
        
        int outCount = 0;
        short* outputData = resampleBuffer.data();

        // Main Resampling Loop
        while (true) {
            int index = (int)resamplePhase; // Integer part
            double t = resamplePhase - index; // Fractional part

            // Data ကုန်သွားရင် ရပ်မယ်
            if (index >= inputSamples) {
                resamplePhase -= inputSamples; // Phase ကို နောက် Chunk အတွက် သိမ်းထား
                break;
            }

            // Cubic Points 4 ခု ရှာခြင်း
            float p0, p1, p2, p3;

            // p1 (Current)
            p1 = input[index];

            // p0 (Previous) - History ကို သုံးမည်
            if (index > 0) p0 = input[index - 1];
            else p0 = history[3]; // Last sample of previous chunk

            // p2 (Next)
            if (index < inputSamples - 1) p2 = input[index + 1];
            else p2 = input[index]; // End extension

            // p3 (Next Next)
            if (index < inputSamples - 2) p3 = input[index + 2];
            else if (index < inputSamples - 1) p3 = input[index + 1];
            else p3 = input[index];

            float mixed = cubicInterpolate(p0, p1, p2, p3, (float)t);
            
            // Clamping
            if (mixed > 32767.0f) mixed = 32767.0f;
            if (mixed < -32768.0f) mixed = -32768.0f;
            
            outputData[outCount++] = (short)mixed;
            
            // Next position
            resamplePhase += ratio;
        }

        // Save last 4 samples for next chunk's history
        if (inputSamples >= 4) {
            memcpy(history, input + inputSamples - 4, 4 * sizeof(short));
        } else {
            // Data နည်းလွန်းရင် ရှိသလောက် ရွှေ့မယ် (ရှားပါးကိစ္စ)
            for (int i = 0; i < inputSamples; i++) {
                history[0] = history[1];
                history[1] = history[2];
                history[2] = history[3];
                history[3] = input[i];
            }
        }

        return outCount;
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
        processor->currentSpeed = speed;
        processor->currentPitch = pitch;
        
        sonicSetRate(processor->stream, 1.0f); 
        sonicSetSpeed(processor->stream, speed);
        sonicSetPitch(processor->stream, pitch);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(JNIEnv* env, jobject, jlong handle, jbyteArray input, jint len) {
    CherrySonicProcessor* processor = (CherrySonicProcessor*)handle;
    if (processor == NULL || len <= 0) return env->NewByteArray(0);

    jbyte* bufferPtr = env->GetByteArrayElements(input, NULL);
    if (bufferPtr == NULL) return env->NewByteArray(0);
    
    int safeLen = len & ~1; 
    int inputShortsCount = safeLen / 2;
    short* inputShorts = (short*)bufferPtr;

    // --- BYPASS LOGIC with Stateful Resampler ---
    if (fabs(processor->currentSpeed - 1.0f) < 0.001f && fabs(processor->currentPitch - 1.0f) < 0.001f) {
        
        int resampledCount = processor->resampleCubic(inputShorts, inputShortsCount);
        
        env->ReleaseByteArrayElements(input, bufferPtr, 0);

        if (resampledCount > 0) {
            jbyteArray result = env->NewByteArray(resampledCount * 2);
            env->SetByteArrayRegion(result, 0, resampledCount * 2, (jbyte*)processor->resampleBuffer.data());
            return result;
        }
        return env->NewByteArray(0);
    }

    // --- SONIC PROCESSING ---
    if (processor->stream == NULL) {
        env->ReleaseByteArrayElements(input, bufferPtr, 0);
        return env->NewByteArray(0);
    }

    sonicWriteShortToStream(processor->stream, inputShorts, inputShortsCount);
    env->ReleaseByteArrayElements(input, bufferPtr, 0);

    int available = sonicSamplesAvailable(processor->stream);
    if (available <= 0) return env->NewByteArray(0);

    processor->processingBuffer.resize(available);
    int readSamples = sonicReadShortFromStream(processor->stream, processor->processingBuffer.data(), available);

    if (readSamples > 0) {
        int resampledCount = processor->resampleCubic(processor->processingBuffer.data(), readSamples);

        if (resampledCount > 0) {
            jbyteArray result = env->NewByteArray(resampledCount * 2);
            env->SetByteArrayRegion(result, 0, resampledCount * 2, (jbyte*)processor->resampleBuffer.data());
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

