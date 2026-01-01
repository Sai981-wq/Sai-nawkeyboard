#include <jni.h>
#include <string>
#include <android/log.h>
#include "sonic.h"

// Log Tag definition
#define TAG "CherryTTS_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Fixed Output Rate for Android System
#define TARGET_OUTPUT_RATE 24000

extern "C" JNIEXPORT jlong JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(
        JNIEnv* env,
        jobject,
        jint sampleRate,
        jint channels) {

    LOGD("initSonic Called: InputHz=%d, Channels=%d", sampleRate, channels);

    sonicStream stream = sonicCreateStream(sampleRate, channels);
    if (stream == NULL) {
        LOGE("CRITICAL: Failed to create sonicStream");
        return 0;
    }

    // Resampling Logic: InputHz / 24000
    // Example: 11025 / 24000 = 0.459
    float resamplingRatio = (float)sampleRate / (float)TARGET_OUTPUT_RATE;
    
    LOGD("Resampling Config: Input=%d -> Target=%d, Ratio=%f", sampleRate, TARGET_OUTPUT_RATE, resamplingRatio);

    sonicSetRate(stream, resamplingRatio);
    sonicSetSpeed(stream, 1.0f);
    sonicSetPitch(stream, 1.0f);
    sonicSetQuality(stream, 1);

    return (jlong) stream;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(
        JNIEnv* env,
        jobject,
        jlong handle,
        jobject inBuffer,
        jint len,
        jobject outBuffer,
        jint maxOut) {

    sonicStream stream = (sonicStream) handle;
    if (stream == NULL) {
        LOGE("processAudio: Invalid Handle");
        return 0;
    }

    char* inputData = (char*) env->GetDirectBufferAddress(inBuffer);
    char* outputData = (char*) env->GetDirectBufferAddress(outBuffer);

    if (inputData == NULL || outputData == NULL) {
        LOGE("processAudio: Buffer Access Failed");
        return 0;
    }

    // Write Input
    int samplesWritten = len / 2;
    if (samplesWritten > 0) {
        sonicWriteShortToStream(stream, (short*) inputData, samplesWritten);
    }

    // Read Output
    int availableShorts = maxOut / 2;
    int samplesRead = sonicReadShortFromStream(stream, (short*) outputData, availableShorts);

    return samplesRead * 2;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_flush(JNIEnv* env, jobject, jlong handle) {
    sonicStream stream = (sonicStream) handle;
    if (stream != NULL) {
        LOGD("Flushing Stream...");
        sonicFlushStream(stream);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_stop(JNIEnv* env, jobject, jlong handle) {
    sonicStream stream = (sonicStream) handle;
    if (stream != NULL) {
        LOGD("Destroying Stream...");
        sonicDestroyStream(stream);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setSonicSpeed(JNIEnv* env, jobject, jlong handle, jfloat speed) {
    sonicStream stream = (sonicStream) handle;
    if (stream != NULL) sonicSetSpeed(stream, speed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setSonicPitch(JNIEnv* env, jobject, jlong handle, jfloat pitch) {
    sonicStream stream = (sonicStream) handle;
    if (stream != NULL) sonicSetPitch(stream, pitch);
}

