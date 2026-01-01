#include <jni.h>
#include <string>
#include <android/log.h>
#include "sonic.h"

#define TAG "CherryTTS_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define TARGET_OUTPUT_RATE 24000

extern "C" JNIEXPORT jlong JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(
        JNIEnv* env,
        jobject,
        jint sampleRate,
        jint channels) {

    // 1. Initialize Sonic pretending we are at Target Rate (24000Hz)
    // This allows us to output directly to the AudioTrack.
    sonicStream stream = sonicCreateStream(TARGET_OUTPUT_RATE, channels);
    if (stream == NULL) {
        LOGE("Failed to create sonicStream");
        return 0;
    }

    // 2. The "Sonic" way to handle Hz difference:
    // If Input is 11000 and Output is 24000, we set the Rate to (11000/24000).
    // Sonic will naturally slow down the consumption of samples to match the pitch.
    float hzCorrectionRate = (float)sampleRate / (float)TARGET_OUTPUT_RATE;
    
    LOGD("Sonic Init: InputHz=%d, CorrectionRate=%f", sampleRate, hzCorrectionRate);

    sonicSetRate(stream, hzCorrectionRate); // Handles the Hz difference
    sonicSetSpeed(stream, 1.0f);            // User speed starts at 1.0
    sonicSetPitch(stream, 1.0f);            // User pitch starts at 1.0
    sonicSetQuality(stream, 1);             // Enable high quality

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

    // Write raw samples (Sonic thinks it's receiving 24000Hz data)
    int samplesWritten = len / 2;
    if (samplesWritten > 0) {
        sonicWriteShortToStream(stream, (short*) inputData, samplesWritten);
    }

    // Read processed samples
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
    if (stream != NULL) {
        // Just pass the raw speed to Sonic
        // Sonic handles the math combined with Rate
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

