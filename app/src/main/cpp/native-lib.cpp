#include <jni.h>
#include <string>
#include <android/log.h>
#include "sonic.h"

#define TAG "CherryTTS_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// System Output Fixed Rate
#define TARGET_OUTPUT_RATE 24000

extern "C" JNIEXPORT jlong JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(
        JNIEnv* env,
        jobject,
        jint sampleRate,
        jint channels) {

    // 1. Initialize Sonic with TARGET RATE (24000)
    // We lie to Sonic that we are working in 24000Hz environment
    sonicStream stream = sonicCreateStream(TARGET_OUTPUT_RATE, channels);
    if (stream == NULL) {
        LOGE("Failed to create sonicStream");
        return 0;
    }

    // 2. Calculate Resampling Ratio
    // If Input=11000, Target=24000
    // Rate = 11000 / 24000 = 0.4583
    // This tells Sonic to consume samples slower, effectively upsampling.
    float resamplingRate = (float)sampleRate / (float)TARGET_OUTPUT_RATE;
    
    LOGD("Sonic Init: Input=%d, Target=%d, RateRatio=%f", sampleRate, TARGET_OUTPUT_RATE, resamplingRate);

    // 3. Apply Settings
    sonicSetRate(stream, resamplingRate); // Handles Resampling (Hz)
    sonicSetSpeed(stream, 1.0f);          // Handles Speed (Duration)
    sonicSetPitch(stream, 1.0f);          // Handles Pitch (Tone)
    sonicSetQuality(stream, 1);           // High Quality

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
    if (stream == NULL) return 0;

    char* inputData = (char*) env->GetDirectBufferAddress(inBuffer);
    char* outputData = (char*) env->GetDirectBufferAddress(outBuffer);

    if (inputData == NULL || outputData == NULL) return 0;

    // Write raw bytes (Sonic thinks it's 24000Hz but Rate slows it down)
    int samplesWritten = len / 2;
    if (samplesWritten > 0) {
        sonicWriteShortToStream(stream, (short*) inputData, samplesWritten);
    }

    // Read processed bytes
    int availableShorts = maxOut / 2;
    int samplesRead = sonicReadShortFromStream(stream, (short*) outputData, availableShorts);

    return samplesRead * 2;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_flush(JNIEnv* env, jobject, jlong handle) {
    sonicStream stream = (sonicStream) handle;
    if (stream != NULL) sonicFlushStream(stream);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_stop(JNIEnv* env, jobject, jlong handle) {
    sonicStream stream = (sonicStream) handle;
    if (stream != NULL) sonicDestroyStream(stream);
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

