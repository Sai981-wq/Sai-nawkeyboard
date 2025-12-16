#include <jni.h>
#include <stdlib.h>
#include <vector>
#include <string.h> 
#include <android/log.h>
#include "sonic.h"

#define LOG_TAG "AutoTTS_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

class CherrySonicProcessor {
public:
    sonicStream stream;
    std::vector<short> sonicOutputBuffer;
    std::vector<short> finalOutputBuffer;
    int inputRate;
    int targetRate = 24000;
    short lastSample = 0; 
    double resampleTimePos = 0.0; 

    CherrySonicProcessor(int sampleRate, int channels) {
        inputRate = sampleRate;
        stream = sonicCreateStream(inputRate, channels);
        sonicSetQuality(stream, 0);
        sonicSetVolume(stream, 1.0f);
        LOGI("INIT: In=%d, Out=%d", inputRate, targetRate);
    }

    ~CherrySonicProcessor() {
        if (stream != NULL) sonicDestroyStream(stream);
    }

    void resampleData(short* input, int inputCount) {
        finalOutputBuffer.clear();
        if (inputCount <= 0) return;
        if (inputRate == targetRate) {
            finalOutputBuffer.assign(input, input + inputCount);
            return;
        }
        double step = (double)inputRate / (double)targetRate;
        while (true) {
            int index = (int)resampleTimePos;
            if (index >= inputCount) {
                resampleTimePos -= inputCount; 
                lastSample = input[inputCount - 1];
                break;
            }
            double frac = resampleTimePos - index;
            short s1 = (index == 0) ? lastSample : input[index - 1];
            short s2 = input[index];
            int val = s1 + (int)((s2 - s1) * frac);
            if (val > 32767) val = 32767;
            if (val < -32768) val = -32768;
            finalOutputBuffer.push_back((short)val);
            resampleTimePos += step;
        }
    }
};

static CherrySonicProcessor* globalProcessor = NULL;

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint sampleRate, jint channels) {
    if (globalProcessor != NULL) {
        if (globalProcessor->inputRate == sampleRate) return;
        delete globalProcessor;
    }
    globalProcessor = new CherrySonicProcessor(sampleRate, channels);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jfloat speed, jfloat pitch) {
    if (globalProcessor != NULL) {
        sonicSetSpeed(globalProcessor->stream, speed);
        sonicSetPitch(globalProcessor->stream, pitch);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(JNIEnv* env, jobject, jbyteArray input, jint len) {
    if (globalProcessor == NULL || len <= 0) return env->NewByteArray(0);
    jbyte* bufferPtr = env->GetByteArrayElements(input, NULL);
    short* inputShorts = (short*)bufferPtr;
    int inputShortsCount = len / 2;
    sonicWriteShortToStream(globalProcessor->stream, inputShorts, inputShortsCount);
    env->ReleaseByteArrayElements(input, bufferPtr, 0);
    int available = sonicSamplesAvailable(globalProcessor->stream);
    if (available > 0) {
        globalProcessor->sonicOutputBuffer.resize(available);
        int readSamples = sonicReadShortFromStream(globalProcessor->stream, globalProcessor->sonicOutputBuffer.data(), available);
        if (readSamples > 0) {
            globalProcessor->resampleData(globalProcessor->sonicOutputBuffer.data(), readSamples);
            int finalCount = globalProcessor->finalOutputBuffer.size();
            LOGI("TRACE: In=%d, SonicOut=%d, Resampled=%d", inputShortsCount, readSamples, finalCount);
            if (finalCount > 0) {
                jbyteArray result = env->NewByteArray(finalCount * 2);
                env->SetByteArrayRegion(result, 0, finalCount * 2, (jbyte*)globalProcessor->finalOutputBuffer.data());
                return result;
            }
        }
    }
    return env->NewByteArray(0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_flush(JNIEnv*, jobject) {
    if (globalProcessor != NULL) {
        sonicFlushStream(globalProcessor->stream);
        globalProcessor->lastSample = 0;
        globalProcessor->resampleTimePos = 0.0;
        LOGI("FLUSHED");
    }
}

