#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <android/log.h>
#include <vector>
#include "sonic.h"

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

    // --- STATE VARIABLES (အသံမှတ်ဉာဏ်) ---
    // ဒါက အကြံပြုချက်ထဲက "Resampler State" ဆိုတာပါပဲ
    short lastSampleOfPrevChunk; 
    bool isFirstChunk;

    CherrySonicProcessor(int sampleRate, int channels) {
        currentSampleRate = (sampleRate > 0) ? sampleRate : 16000;
        targetRate = 24000;
        currentSpeed = 1.0f;
        currentPitch = 1.0f;

        stream = sonicCreateStream(currentSampleRate, channels);
        sonicSetQuality(stream, 0);
        sonicSetVolume(stream, 1.0f);
        
        // Reset State
        lastSampleOfPrevChunk = 0;
        isFirstChunk = true;
    }

    ~CherrySonicProcessor() {
        if (stream != NULL) {
            sonicDestroyStream(stream);
            stream = NULL;
        }
    }

    // STATEFUL LINEAR RESAMPLER
    // ရှေ့ Chunk နဲ့ နောက် Chunk ကို အပ်ချမပေါ်အောင် ဆက်ပေးမည့် Function
    int resampleLinear(short* input, int inputSamples) {
        if (inputSamples <= 0) return 0;
        
        // Hz တူရင် Copy ကူးရုံပဲ (မှတ်ဉာဏ်မလို)
        if (currentSampleRate == targetRate) {
            resampleBuffer.resize(inputSamples);
            memcpy(resampleBuffer.data(), input, inputSamples * sizeof(short));
            // Update state for consistency
            lastSampleOfPrevChunk = input[inputSamples - 1];
            isFirstChunk = false;
            return inputSamples;
        }

        double ratio = (double)currentSampleRate / targetRate;
        long long newSize = (long long)inputSamples * targetRate / currentSampleRate;
        
        if (newSize <= 0) return 0;
        
        if (resampleBuffer.size() < newSize) {
            resampleBuffer.resize(newSize);
        }
        
        short* outPtr = resampleBuffer.data();
        
        for (int i = 0; i < newSize; i++) {
            double exactPos = i * ratio;
            int index = (int)exactPos;
            double t = exactPos - index; // Fractional part

            short p0, p1;

            if (index == 0) {
                // Chunk အစပိုင်းဆိုရင် အရင် Chunk ရဲ့ နောက်ဆုံးအစက် (History) ကို ယူသုံးမယ်
                // ဒါမှ အသံမပြတ်မှာ ဖြစ်ပါတယ်
                p0 = isFirstChunk ? input[0] : lastSampleOfPrevChunk;
                p1 = (inputSamples > 1) ? input[1] : input[0]; // Avoid OOB if input length is 1
            } 
            else if (index >= inputSamples - 1) {
                // Chunk အဆုံးပိုင်း
                p0 = input[inputSamples - 1];
                p1 = p0; // End extension
            } 
            else {
                // အလယ်ပိုင်း
                p0 = input[index];
                p1 = input[index + 1];
            }

            // Linear Interpolation: y = p0 + (p1 - p0) * t
            outPtr[i] = (short)(p0 + (p1 - p0) * t);
        }

        // လက်ရှိ Chunk ရဲ့ နောက်ဆုံးအစက်ကို မှတ်ထားမယ် (နောက် Chunk အတွက်)
        lastSampleOfPrevChunk = input[inputSamples - 1];
        isFirstChunk = false;

        return newSize;
    }
};

// --- GLOBAL INSTANCE ---
static CherrySonicProcessor* globalProcessor = NULL;

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint sampleRate, jint channels) {
    if (globalProcessor != NULL) {
        delete globalProcessor;
        globalProcessor = NULL;
    }
    globalProcessor = new CherrySonicProcessor(sampleRate, channels);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jfloat speed, jfloat pitch) {
    if (globalProcessor != NULL && globalProcessor->stream != NULL) {
        globalProcessor->currentSpeed = speed;
        globalProcessor->currentPitch = pitch;
        sonicSetRate(globalProcessor->stream, 1.0f); 
        sonicSetSpeed(globalProcessor->stream, speed);
        sonicSetPitch(globalProcessor->stream, pitch);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(JNIEnv* env, jobject, jbyteArray input, jint len) {
    if (globalProcessor == NULL || len <= 0) return env->NewByteArray(0);

    jbyte* bufferPtr = env->GetByteArrayElements(input, NULL);
    if (bufferPtr == NULL) return env->NewByteArray(0);
    
    int safeLen = len & ~1; 
    int inputShortsCount = safeLen / 2;
    short* inputShorts = (short*)bufferPtr;

    // 1. DIRECT RESAMPLE (If Speed/Pitch is normal)
    if (fabs(globalProcessor->currentSpeed - 1.0f) < 0.001f && fabs(globalProcessor->currentPitch - 1.0f) < 0.001f) {
        
        // Stateful Resampler ကို ခေါ်သုံးထားသည်
        int resampledCount = globalProcessor->resampleLinear(inputShorts, inputShortsCount);
        
        env->ReleaseByteArrayElements(input, bufferPtr, 0);

        if (resampledCount > 0) {
            jbyteArray result = env->NewByteArray(resampledCount * 2);
            env->SetByteArrayRegion(result, 0, resampledCount * 2, (jbyte*)globalProcessor->resampleBuffer.data());
            return result;
        }
        return env->NewByteArray(0);
    }

    // 2. SONIC PROCESSING (If Speed/Pitch changed)
    if (globalProcessor->stream == NULL) {
        env->ReleaseByteArrayElements(input, bufferPtr, 0);
        return env->NewByteArray(0);
    }

    sonicWriteShortToStream(globalProcessor->stream, inputShorts, inputShortsCount);
    env->ReleaseByteArrayElements(input, bufferPtr, 0);

    int available = sonicSamplesAvailable(globalProcessor->stream);
    if (available > 0) {
        if (globalProcessor->processingBuffer.size() < available) {
            globalProcessor->processingBuffer.resize(available);
        }
        
        int readSamples = sonicReadShortFromStream(globalProcessor->stream, globalProcessor->processingBuffer.data(), available);

        if (readSamples > 0) {
            // Sonic output ကိုလည်း Stateful Resampler နဲ့ပဲ ဖြတ်မယ်
            int resampledCount = globalProcessor->resampleLinear(globalProcessor->processingBuffer.data(), readSamples);
            if (resampledCount > 0) {
                jbyteArray result = env->NewByteArray(resampledCount * 2);
                env->SetByteArrayRegion(result, 0, resampledCount * 2, (jbyte*)globalProcessor->resampleBuffer.data());
                return result;
            }
        }
    }
    
    return env->NewByteArray(0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_flush(JNIEnv*, jobject) {
    if (globalProcessor != NULL && globalProcessor->stream != NULL) {
        sonicFlushStream(globalProcessor->stream);
        // Reset state for next sentence
        globalProcessor->isFirstChunk = true;
        globalProcessor->lastSampleOfPrevChunk = 0;
    }
}

