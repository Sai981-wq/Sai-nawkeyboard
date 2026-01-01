#include <jni.h>
#include <string>
#include <android/log.h>
#include "sonic.h"

#define TAG "CherryTTS_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ★ TARGET OUTPUT HZ (Fixed for Android AudioTrack) ★
#define TARGET_OUTPUT_RATE 24000

extern "C" JNIEXPORT jlong JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(
        JNIEnv* env,
        jobject,
        jint sampleRate,
        jint channels) {

    LOGD("initSonic called: InputRate=%d, Channels=%d", sampleRate, channels);

    sonicStream stream = sonicCreateStream(sampleRate, channels);
    if (stream == NULL) {
        LOGE("Failed to create sonicStream!");
        return 0;
    }

    // ★ RESAMPLING LOGIC ★
    // Calculate ratio to match TARGET_OUTPUT_RATE (24000)
    // If Input=11000, Target=24000 -> Ratio=0.458
    // Sonic will consume input slower, effectively resampling it up.
    float resamplingRatio = (float)sampleRate / (float)TARGET_OUTPUT_RATE;
    
    LOGD("Setting Resampling Rate: %f (Target: %d)", resamplingRatio, TARGET_OUTPUT_RATE);
    sonicSetRate(stream, resamplingRatio);

    sonicSetSpeed(stream, 1.0f);
    sonicSetPitch(stream, 1.0f);
    sonicSetQuality(stream, 1); 

    LOGD("Sonic Init Success. Handle created.");
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
        LOGE("processAudio: Invalid Handle (NULL)");
        return 0;
    }

    char* inputData = (char*) env->GetDirectBufferAddress(inBuffer);
    char* outputData = (char*) env->GetDirectBufferAddress(outBuffer);

    if (inputData == NULL || outputData == NULL) {
        LOGE("processAudio: Buffer access failed");
        return 0;
    }

    // Write input (Engine Hz)
    int samplesWritten = len / 2; // 16-bit = 2 bytes per sample
    if (samplesWritten > 0) {
        int ret = sonicWriteShortToStream(stream, (short*) inputData, samplesWritten);
        if (ret == 0) { 
            LOGE("sonicWriteShortToStream failed/full");
        }
    }

    // Read output (Target 24000Hz)
    int availableShorts = maxOut / 2;
    int samplesRead = sonicReadShortFromStream(stream, (short*) outputData, availableShorts);

    // LOGD("Processed: InBytes=%d -> OutBytes=%d", len, samplesRead * 2);
    return samplesRead * 2; 
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_flush(JNIEnv* env, jobject, jlong handle) {
    sonicStream stream = (sonicStream) handle;
    if (stream != NULL) {
        LOGD("Flushing Sonic Stream...");
        sonicFlushStream(stream);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_stop(JNIEnv* env, jobject, jlong handle) {
    sonicStream stream = (sonicStream) handle;
    if (stream != NULL) {
        LOGD("Destroying Sonic Stream...");
        sonicDestroyStream(stream);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setSonicSpeed(JNIEnv* env, jobject, jlong handle, jfloat speed) {
    sonicStream stream = (sonicStream) handle;
    if (stream != NULL) {
        LOGD("Setting Native Speed: %f", speed);
        sonicSetSpeed(stream, speed);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setSonicPitch(JNIEnv* env, jobject, jlong handle, jfloat pitch) {
    sonicStream stream = (sonicStream) handle;
    if (stream != NULL) {
        LOGD("Setting Native Pitch: %f", pitch);
        sonicSetPitch(stream, pitch);
    }
}

