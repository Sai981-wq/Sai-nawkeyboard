#include <jni.h>
#include <string>
#include "sonic.h"

// Output Hz ကို 24000Hz မှာပဲ ငြိမ်ငြိမ်ထားပါမယ်
#define TARGET_OUTPUT_RATE 24000

extern "C" JNIEXPORT jlong JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(
        JNIEnv* env,
        jobject,
        jint sampleRate,
        jint channels) {

    sonicStream stream = sonicCreateStream(sampleRate, channels);

    // Hz မတူရင် Sonic က Resampling (Hz ညှိခြင်း) လုပ်ပေးပါမယ်
    float resamplingRate = (float)sampleRate / (float)TARGET_OUTPUT_RATE;
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
    if (stream == NULL) return 0;

    char* inputData = (char*) env->GetDirectBufferAddress(inBuffer);
    char* outputData = (char*) env->GetDirectBufferAddress(outBuffer);

    if (inputData == NULL || outputData == NULL) return 0;

    int samplesWritten = len / 2;
    int ret = sonicWriteShortToStream(stream, (short*) inputData, samplesWritten);

    if (ret == 0) { }

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

// Speed Function ကို ပြင်ထားသည် (Empty မဟုတ်တော့ပါ)
extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setSonicSpeed(JNIEnv* env, jobject, jlong handle, jfloat speed) {
    sonicStream stream = (sonicStream) handle;
    if (stream != NULL) {
        sonicSetSpeed(stream, speed);
    }
}

// Pitch Function ကို ပြင်ထားသည် (Empty မဟုတ်တော့ပါ)
extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setSonicPitch(JNIEnv* env, jobject, jlong handle, jfloat pitch) {
    sonicStream stream = (sonicStream) handle;
    if (stream != NULL) {
        sonicSetPitch(stream, pitch);
    }
}

