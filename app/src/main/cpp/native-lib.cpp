#include <jni.h>
#include "sonic.h"

// Global Stream Handle
static sonicStream stream = NULL;

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint sampleRate, jint channels) {
    if (stream != NULL) sonicDestroyStream(stream);
    stream = sonicCreateStream(sampleRate, channels);
    sonicSetQuality(stream, 1); // High Quality Enable
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jfloat speed, jfloat pitch, jfloat rate) {
    if (stream != NULL) {
        sonicSetSpeed(stream, speed);
        sonicSetPitch(stream, pitch);
        sonicSetRate(stream, rate);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(JNIEnv* env, jobject, jbyteArray input, jint len) {
    if (stream == NULL) return env->NewByteArray(0);

    jbyte* bufferPtr = env->GetByteArrayElements(input, NULL);
    // Write
    sonicWriteShortToStream(stream, (short*)bufferPtr, len / 2);
    env->ReleaseByteArrayElements(input, bufferPtr, 0);

    // Read
    int available = sonicSamplesAvailable(stream);
    if (available <= 0) return env->NewByteArray(0);

    int maxBytes = available * 2;
    short* outBuffer = new short[available];
    int readSamples = sonicReadShortFromStream(stream, outBuffer, available);

    if (readSamples > 0) {
        jbyteArray result = env->NewByteArray(readSamples * 2);
        env->SetByteArrayRegion(result, 0, readSamples * 2, (jbyte*)outBuffer);
        delete[] outBuffer;
        return result;
    }
    delete[] outBuffer;
    return env->NewByteArray(0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_flush(JNIEnv*, jobject) {
    if (stream != NULL) sonicFlushStream(stream);
}
