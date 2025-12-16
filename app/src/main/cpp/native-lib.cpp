#include <jni.h>
#include <stdlib.h>
#include <vector>
#include "sonic.h"

class CherrySonicProcessor {
public:
    sonicStream stream;
    std::vector<short> outputBuffer;

    CherrySonicProcessor(int inputSampleRate, int channels) {
        stream = sonicCreateStream(inputSampleRate, channels);
        sonicSetOutputSampleRate(stream, 24000);
        sonicSetQuality(stream, 0);
        sonicSetVolume(stream, 1.0f);
    }

    ~CherrySonicProcessor() {
        if (stream != NULL) {
            sonicDestroyStream(stream);
        }
    }
};

static CherrySonicProcessor* globalProcessor = NULL;

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint sampleRate, jint channels) {
    if (globalProcessor != NULL) {
        if (sonicGetSampleRate(globalProcessor->stream) == sampleRate) {
            return;
        }
        delete globalProcessor;
    }
    globalProcessor = new CherrySonicProcessor(sampleRate, channels);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jfloat speed, jfloat pitch) {
    if (globalProcessor != NULL && globalProcessor->stream != NULL) {
        sonicSetSpeed(globalProcessor->stream, speed);
        sonicSetPitch(globalProcessor->stream, pitch);
        sonicSetRate(globalProcessor->stream, 1.0f);
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
        if (globalProcessor->outputBuffer.size() < available) {
            globalProcessor->outputBuffer.resize(available + 1024);
        }

        int readSamples = sonicReadShortFromStream(globalProcessor->stream, globalProcessor->outputBuffer.data(), available);
        
        if (readSamples > 0) {
            jbyteArray result = env->NewByteArray(readSamples * 2);
            env->SetByteArrayRegion(result, 0, readSamples * 2, (jbyte*)globalProcessor->outputBuffer.data());
            return result;
        }
    }

    return env->NewByteArray(0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_flush(JNIEnv*, jobject) {
    if (globalProcessor != NULL && globalProcessor->stream != NULL) {
        sonicFlushStream(globalProcessor->stream);
    }
}

