#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cmath>
#include <stdint.h>
#include "sonic.h"
#include <opus.h> 

extern "C" {

#define DOWNSAMPLE_RATIO 2
#define OPUS_VOLUME_GAIN 2.0f 
#define SONIC_OUT_GAIN 1.5f 

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
    jshort *data = (jshort *) env->GetPrimitiveArrayCritical(audioData, 0);
    int ret = sonicWriteShortToStream((sonicStream) (intptr_t) streamId, data, len);
    env->ReleasePrimitiveArrayCritical(audioData, data, JNI_ABORT);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_mettavoice_tts_ShanTtsService_sonicReadShortFromStream(JNIEnv *env, jobject thiz, jlong streamId, jshortArray audioData, jint len) {
    jshort *data = (jshort *) env->GetPrimitiveArrayCritical(audioData, 0);
    int ret = sonicReadShortFromStream((sonicStream) (intptr_t) streamId, data, len);
    
    if (ret > 0) {
        for (int i = 0; i < ret; i++) {
            float sample = data[i] * SONIC_OUT_GAIN;
            if (sample > 32767.0f) sample = 32767.0f;
            if (sample < -32768.0f) sample = -32768.0f;
            data[i] = (jshort)sample;
        }
    }

    env->ReleasePrimitiveArrayCritical(audioData, data, 0);
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

    jbyte *opusData = (jbyte *) env->GetPrimitiveArrayCritical(encodedData, 0);
    
    int error = 0;
    OpusDecoder *decoder = opus_decoder_create(48000, 1, &error);
    
    if (error != OPUS_OK || decoder == nullptr) {
        env->ReleasePrimitiveArrayCritical(encodedData, opusData, JNI_ABORT);
        return nullptr;
    }

    std::vector<opus_int16> pcmOutput;
    pcmOutput.reserve(len * 10);

    int offset = 0;

    while (offset + 2 <= len) {
        int packetSize = (opusData[offset] & 0xFF) | ((opusData[offset + 1] & 0xFF) << 8);
        offset += 2;

        if (packetSize <= 0 || offset + packetSize > len) break;

        opus_int16 pcm_buffer[5760];
        int samplesRead = opus_decode(decoder, (const unsigned char*)(opusData + offset), packetSize, pcm_buffer, 5760, 0);

        if (samplesRead > 0) {
            for (int i = 0; i + DOWNSAMPLE_RATIO <= samplesRead; i += DOWNSAMPLE_RATIO) {
                float sum = 0.0f;
                for (int j = 0; j < DOWNSAMPLE_RATIO; j++) {
                    sum += (float)pcm_buffer[i + j];
                }
                
                float avg = (sum / DOWNSAMPLE_RATIO) * OPUS_VOLUME_GAIN;
                
                if (avg > 32767.0f) avg = 32767.0f;
                if (avg < -32768.0f) avg = -32768.0f;
                
                pcmOutput.push_back((opus_int16)avg);
            }
        }
        offset += packetSize;
    }

    opus_decoder_destroy(decoder);
    env->ReleasePrimitiveArrayCritical(encodedData, opusData, JNI_ABORT);

    if (pcmOutput.empty()) return nullptr;

    jshortArray result = env->NewShortArray(pcmOutput.size());
    
    jshort *resData = (jshort *) env->GetPrimitiveArrayCritical(result, 0);
    std::memcpy(resData, pcmOutput.data(), pcmOutput.size() * sizeof(opus_int16));
    env->ReleasePrimitiveArrayCritical(result, resData, 0);

    return result;
}

}

