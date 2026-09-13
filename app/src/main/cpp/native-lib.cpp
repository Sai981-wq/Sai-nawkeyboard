#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cmath>
#include <stdint.h>
#include "sonic.h"
#include <opus.h> 

extern "C" {

#define DOWNSAMPLE_RATIO 3

JNIEXPORT jlong JNICALL
Java_com_mettavoice_tts_ShanTtsService_sonicCreateStream(JNIEnv *env, jobject thiz, jint sampleRate, jint numChannels) {
    return (jlong) (intptr_t) sonicCreateStream(sampleRate, numChannels);
}

JNIEXPORT void JNICALL
Java_com_mettavoice_tts_ShanTtsService_sonicDestroyStream(JNIEnv *env, jobject thiz, jlong streamId) {
    sonicDestroyStream((sonicStream) (intptr_t) streamId);
}

JNIEXPORT void JNICALL
Java_com_mettavoice_tts_ShanTtsService_sonicSetSpeed(JNIEnv *env, jobject thiz, jlong streamId, jfloat speed) {
    sonicSetSpeed((sonicStream) (intptr_t) streamId, speed);
}

JNIEXPORT void JNICALL
Java_com_mettavoice_tts_ShanTtsService_sonicSetPitch(JNIEnv *env, jobject thiz, jlong streamId, jfloat pitch) {
    sonicSetPitch((sonicStream) (intptr_t) streamId, pitch);
}

JNIEXPORT jint JNICALL
Java_com_mettavoice_tts_ShanTtsService_sonicWriteShortToStream(JNIEnv *env, jobject thiz, jlong streamId, jshortArray audioData, jint len) {
    jshort *data = env->GetShortArrayElements(audioData, NULL);
    int ret = sonicWriteShortToStream((sonicStream) (intptr_t) streamId, data, len);
    env->ReleaseShortArrayElements(audioData, data, JNI_ABORT);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_mettavoice_tts_ShanTtsService_sonicReadShortFromStream(JNIEnv *env, jobject thiz, jlong streamId, jshortArray audioData, jint len) {
    jshort *data = env->GetShortArrayElements(audioData, NULL);
    int ret = sonicReadShortFromStream((sonicStream) (intptr_t) streamId, data, len);
    env->ReleaseShortArrayElements(audioData, data, 0);
    return ret;
}

JNIEXPORT void JNICALL
Java_com_mettavoice_tts_ShanTtsService_sonicFlushStream(JNIEnv *env, jobject thiz, jlong streamId) {
    sonicFlushStream((sonicStream) (intptr_t) streamId);
}

JNIEXPORT jint JNICALL
Java_com_mettavoice_tts_ShanTtsService_sonicSamplesAvailable(JNIEnv *env, jobject thiz, jlong streamId) {
    return sonicSamplesAvailable((sonicStream) (intptr_t) streamId);
}

JNIEXPORT void JNICALL
Java_com_mettavoice_tts_ShanTtsService_initOpusDecoder(JNIEnv *env, jobject thiz, jint sampleRate) {
}

JNIEXPORT void JNICALL
Java_com_mettavoice_tts_ShanTtsService_destroyOpusDecoder(JNIEnv *env, jobject thiz) {
}

JNIEXPORT jshortArray JNICALL
Java_com_mettavoice_tts_ShanTtsService_decodeOpus(JNIEnv *env, jobject thiz, jbyteArray encodedData, jint len) {
    if (encodedData == nullptr || len <= 0) return nullptr;

    jbyte *opusData = env->GetByteArrayElements(encodedData, nullptr);
    
    int error = 0;
    OpusDecoder *decoder = opus_decoder_create(48000, 1, &error);
    
    if (error != OPUS_OK || decoder == nullptr) {
        env->ReleaseByteArrayElements(encodedData, opusData, JNI_ABORT);
        return nullptr;
    }

    std::vector<opus_int16> allPcm48k;
    int offset = 0;

    while (offset + 2 <= len) {
        int packetSize = (opusData[offset] & 0xFF) | ((opusData[offset + 1] & 0xFF) << 8);
        offset += 2;

        if (packetSize <= 0 || offset + packetSize > len) break;

        opus_int16 pcm_buffer[5760];
        int samplesRead = opus_decode(decoder, (const unsigned char*)(opusData + offset), packetSize, pcm_buffer, 5760, 0);

        if (samplesRead > 0) {
            for (int i = 0; i < samplesRead; i++) {
                allPcm48k.push_back(pcm_buffer[i]);
            }
        }
        offset += packetSize;
    }

    opus_decoder_destroy(decoder);
    env->ReleaseByteArrayElements(encodedData, opusData, JNI_ABORT);

    if (allPcm48k.empty()) return nullptr;

    int totalSamples = (int)allPcm48k.size();
    std::vector<opus_int16> pcmOutput;
    pcmOutput.reserve(totalSamples / DOWNSAMPLE_RATIO + 1);

    for (int i = 0; i + DOWNSAMPLE_RATIO <= totalSamples; i += DOWNSAMPLE_RATIO) {
        float sum = 0.0f;
        for (int j = 0; j < DOWNSAMPLE_RATIO; j++) {
            sum += (float)allPcm48k[i + j];
        }
        float avg = sum / DOWNSAMPLE_RATIO;
        if (avg > 32767.0f) avg = 32767.0f;
        if (avg < -32768.0f) avg = -32768.0f;
        pcmOutput.push_back((opus_int16)avg);
    }

    if (pcmOutput.empty()) return nullptr;

    jshortArray result = env->NewShortArray(pcmOutput.size());
    env->SetShortArrayRegion(result, 0, pcmOutput.size(), pcmOutput.data());

    return result;
}

}

