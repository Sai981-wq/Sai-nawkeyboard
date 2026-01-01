#include <jni.h>
#include <string>
#include <android/log.h>
#include "sonic.h"

// Log Tag for C++
#define TAG "CherryTTS_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Output Hz (Fixed)
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
        LOGE("CRITICAL: Failed to create sonicStream (Out of Memory?)");
        return 0;
    }

    // Calculate Ratio: Input / Target (e.g. 11000 / 24000 = 0.458)
    float resamplingRate = (float)sampleRate / (float)TARGET_OUTPUT_RATE;
    
    LOGD("Resampling Config: Input=%d -> Target=%d, Ratio=%f", sampleRate, TARGET_OUTPUT_RATE, resamplingRate);

    sonicSetRate(stream, resamplingRate);
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
        LOGE("processAudio Error: Invalid Handle (NULL)");
        return 0;
    }

    char* inputData = (char*) env->GetDirectBufferAddress(inBuffer);
    char* outputData = (char*) env->GetDirectBufferAddress(outBuffer);

    if (inputData == NULL || outputData == NULL) {
        LOGE("processAudio Error: Buffer Access Failed (NULL)");
        return 0;
    }

    // Write Input
    int samplesWritten = len / 2;
    if (samplesWritten > 0) {
        int ret = sonicWriteShortToStream(stream, (short*) inputData, samplesWritten);
        if (ret == 0) {
            LOGE("sonicWriteShortToStream Failed (Buffer Full or Error)");
        }
    }

    // Read Output
    int availableShorts = maxOut / 2;
    int samplesRead = sonicReadShortFromStream(stream, (short*) outputData, availableShorts);

    // LOGD("Processed: InBytes=%d -> OutBytes=%d", len, samplesRead * 2); // Uncomment for verbose logs
    return samplesRead * 2;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_flush(JNIEnv* env, jobject, jlong handle) {
    sonicStream stream = (sonicStream) handle;
    if (stream != NULL) {
        LOGD("Flushing Stream...");
        sonicFlushStream(stream);
    } else {
        LOGE("Flush Error: Invalid Handle");
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
    if (stream != NULL) {
        // LOGD("Setting Native Speed: %f", speed);
        sonicSetSpeed(stream, speed);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setSonicPitch(JNIEnv* env, jobject, jlong handle, jfloat pitch) {
    sonicStream stream = (sonicStream) handle;
    if (stream != NULL) {
        sonicSetPitch(stream, pitch);
    }
}

