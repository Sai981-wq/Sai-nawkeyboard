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

    std::vector<short> processingBuffer;
    std::vector<short> resampleBuffer;

    CherrySonicProcessor(int sampleRate, int channels) {
        currentSampleRate = (sampleRate > 0) ? sampleRate : 16000;
        targetRate = 24000;
        currentSpeed = 1.0f;
        currentPitch = 1.0f;

        stream = sonicCreateStream(currentSampleRate, channels);
        sonicSetQuality(stream, 0);
        sonicSetVolume(stream, 1.0f);
    }

    ~CherrySonicProcessor() {
        if (stream != NULL) {
            sonicDestroyStream(stream);
            stream = NULL;
        }
    }

    int resampleLinear(short* input, int inputSamples) {
        if (inputSamples <= 0) return 0;
        
        if (currentSampleRate == targetRate) {
            resampleBuffer.resize(inputSamples);
            memcpy(resampleBuffer.data(), input, inputSamples * sizeof(short));
            return inputSamples;
        }

        double ratio = (double)currentSampleRate / targetRate;
        long long newSize = (long long)inputSamples * targetRate / currentSampleRate;
        
        if (newSize <= 0) return 0;
        resampleBuffer.resize(newSize);
        
        short* outPtr = resampleBuffer.data();
        
        for (int i = 0; i < newSize; i++) {
            double exactPos = i * ratio;
            int index = (int)exactPos;
            double t = exactPos - index;

            if (index >= inputSamples - 1) {
                outPtr[i] = input[inputSamples - 1];
            } else {
                short p0 = input[index];
                short p1 = input[index + 1];
                outPtr[i] = (short)(p0 + (p1 - p0) * t);
            }
        }
        return newSize;
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

    if (fabs(processor->currentSpeed - 1.0f) < 0.001f && fabs(processor->currentPitch - 1.0f) < 0.001f) {
        int resampledCount = processor->resampleLinear(inputShorts, inputShortsCount);
        env->ReleaseByteArrayElements(input, bufferPtr, 0);

        if (resampledCount > 0) {
            jbyteArray result = env->NewByteArray(resampledCount * 2);
            env->SetByteArrayRegion(result, 0, resampledCount * 2, (jbyte*)processor->resampleBuffer.data());
            return result;
        }
        return env->NewByteArray(0);
    }

    if (processor->stream == NULL) {
        env->ReleaseByteArrayElements(input, bufferPtr, 0);
        return env->NewByteArray(0);
    }

    sonicWriteShortToStream(processor->stream, inputShorts, inputShortsCount);
    env->ReleaseByteArrayElements(input, bufferPtr, 0);

    int available = sonicSamplesAvailable(processor->stream);
    if (available > 0) {
        processor->processingBuffer.resize(available);
        int readSamples = sonicReadShortFromStream(processor->stream, processor->processingBuffer.data(), available);

        if (readSamples > 0) {
            int resampledCount = processor->resampleLinear(processor->processingBuffer.data(), readSamples);
            if (resampledCount > 0) {
                jbyteArray result = env->NewByteArray(resampledCount * 2);
                env->SetByteArrayRegion(result, 0, resampledCount * 2, (jbyte*)processor->resampleBuffer.data());
                return result;
            }
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

