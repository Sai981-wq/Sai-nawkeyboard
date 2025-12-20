#include <jni.h>
#include <stdlib.h>
#include "sonic.h"

sonicStream getStream(jlong handle) {
    return (sonicStream) handle;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_shan_tts_manager_AudioProcessor_nativeCreate(JNIEnv* env, jobject, jint sampleRate, jint channels) {
    sonicStream stream = sonicCreateStream(sampleRate, channels);
    sonicSetQuality(stream, 1);
    sonicSetVolume(stream, 1.0f);
    return (jlong) stream;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_nativeSetConfig(JNIEnv* env, jobject, jlong handle, jfloat speed, jfloat pitch, jfloat rate) {
    sonicStream stream = getStream(handle);
    if (stream) {
        sonicSetSpeed(stream, speed);
        sonicSetPitch(stream, pitch);
        sonicSetRate(stream, rate);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_shan_tts_manager_AudioProcessor_nativeProcess(
        JNIEnv* env, jobject,
        jlong handle,
        jobject inBuffer, jint len,
        jbyteArray outArray
) {
    sonicStream stream = getStream(handle);
    if (!stream) return 0;

    if (len > 0 && inBuffer != NULL) {
        void* inAddr = env->GetDirectBufferAddress(inBuffer);
        if (inAddr != NULL) {
            sonicWriteShortToStream(stream, (short*)inAddr, len / 2);
        }
    }

    int avail = sonicSamplesAvailable(stream);
    if (avail <= 0) return 0;

    jbyte* outPtr = env->GetByteArrayElements(outArray, NULL);
    if (outPtr == NULL) return 0;

    jsize outLen = env->GetArrayLength(outArray);
    int maxShorts = outLen / 2;

    int samplesRead = sonicReadShortFromStream(stream, (short*)outPtr, maxShorts);

    env->ReleaseByteArrayElements(outArray, outPtr, 0);

    return samplesRead * 2;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_nativeFlush(JNIEnv* env, jobject, jlong handle) {
    sonicStream stream = getStream(handle);
    if (stream) {
        sonicFlushStream(stream);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_nativeDestroy(JNIEnv* env, jobject, jlong handle) {
    sonicStream stream = getStream(handle);
    if (stream) {
        sonicDestroyStream(stream);
    }
}

