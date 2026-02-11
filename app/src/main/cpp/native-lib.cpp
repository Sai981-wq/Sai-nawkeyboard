#include <jni.h>
#include <string>
#include <vector>
#include "sonic.h"
#include "libopus/include/opus.h"

OpusDecoder *decoder = nullptr;
int OPUS_SAMPLE_RATE = 16000;
int OPUS_CHANNELS = 1;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_shan_tts_ShanTtsService_sonicCreateStream(JNIEnv *env, jobject thiz, jint sampleRate, jint numChannels) {
    return (jlong) sonicCreateStream(sampleRate, numChannels);
}

JNIEXPORT void JNICALL
Java_com_shan_tts_ShanTtsService_sonicDestroyStream(JNIEnv *env, jobject thiz, jlong streamId) {
    sonicDestroyStream((sonicStream) streamId);
}

JNIEXPORT void JNICALL
Java_com_shan_tts_ShanTtsService_sonicSetSpeed(JNIEnv *env, jobject thiz, jlong streamId, jfloat speed) {
    sonicSetSpeed((sonicStream) streamId, speed);
}

JNIEXPORT void JNICALL
Java_com_shan_tts_ShanTtsService_sonicSetPitch(JNIEnv *env, jobject thiz, jlong streamId, jfloat pitch) {
    sonicSetPitch((sonicStream) streamId, pitch);
}

JNIEXPORT jint JNICALL
Java_com_shan_tts_ShanTtsService_sonicWriteShortToStream(JNIEnv *env, jobject thiz, jlong streamId, jshortArray audioData, jint len) {
    jshort *data = env->GetShortArrayElements(audioData, NULL);
    int ret = sonicWriteShortToStream((sonicStream) streamId, data, len);
    env->ReleaseShortArrayElements(audioData, data, 0);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_shan_tts_ShanTtsService_sonicReadShortFromStream(JNIEnv *env, jobject thiz, jlong streamId, jshortArray audioData, jint len) {
    jshort *data = env->GetShortArrayElements(audioData, NULL);
    int ret = sonicReadShortFromStream((sonicStream) streamId, data, len);
    env->ReleaseShortArrayElements(audioData, data, 0);
    return ret;
}

JNIEXPORT void JNICALL
Java_com_shan_tts_ShanTtsService_sonicFlushStream(JNIEnv *env, jobject thiz, jlong streamId) {
    sonicFlushStream((sonicStream) streamId);
}

JNIEXPORT jint JNICALL
Java_com_shan_tts_ShanTtsService_sonicSamplesAvailable(JNIEnv *env, jobject thiz, jlong streamId) {
    return sonicSamplesAvailable((sonicStream) streamId);
}

JNIEXPORT void JNICALL
Java_com_shan_tts_ShanTtsService_initOpusDecoder(JNIEnv *env, jobject thiz, jint sampleRate) {
    int error;
    OPUS_SAMPLE_RATE = sampleRate;
    if (decoder != nullptr) {
        opus_decoder_destroy(decoder);
    }
    decoder = opus_decoder_create(OPUS_SAMPLE_RATE, OPUS_CHANNELS, &error);
}

JNIEXPORT jshortArray JNICALL
Java_com_shan_tts_ShanTtsService_decodeOpus(JNIEnv *env, jobject thiz, jbyteArray encodedData, jint len) {
    if (decoder == nullptr) return nullptr;

    jbyte *opusData = env->GetByteArrayElements(encodedData, nullptr);
    std::vector<opus_int16> pcmOutput(5760);

    int samplesDecoded = opus_decode(decoder, (const unsigned char *)opusData, len, pcmOutput.data(), 5760, 0);

    env->ReleaseByteArrayElements(encodedData, opusData, 0);

    if (samplesDecoded < 0) {
        return nullptr;
    }

    jshortArray result = env->NewShortArray(samplesDecoded);
    env->SetShortArrayRegion(result, 0, samplesDecoded, pcmOutput.data());

    return result;
}

JNIEXPORT void JNICALL
Java_com_shan_tts_ShanTtsService_destroyOpusDecoder(JNIEnv *env, jobject thiz) {
    if (decoder != nullptr) {
        opus_decoder_destroy(decoder);
        decoder = nullptr;
    }
}

}

