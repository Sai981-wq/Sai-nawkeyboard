#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cmath>
#include "sonic.h"
#include <opus.h>
#include <opusfile.h>

extern "C" {

typedef struct {
    const unsigned char* data;
    opus_int64 size;
    opus_int64 pos;
} MemoryStream;

int read_mem(void* _stream, unsigned char* _ptr, int _nbytes) {
    MemoryStream* stream = (MemoryStream*)_stream;
    opus_int64 remaining = stream->size - stream->pos;
    int check = _nbytes > remaining ? remaining : _nbytes;
    if (check > 0) {
        memcpy(_ptr, stream->data + stream->pos, check);
        stream->pos += check;
    }
    return check;
}

int seek_mem(void* _stream, opus_int64 _offset, int _whence) {
    MemoryStream* stream = (MemoryStream*)_stream;
    opus_int64 new_pos;
    switch (_whence) {
        case SEEK_SET: new_pos = _offset; break;
        case SEEK_CUR: new_pos = stream->pos + _offset; break;
        case SEEK_END: new_pos = stream->size + _offset; break;
        default: return -1;
    }
    if (new_pos < 0 || new_pos > stream->size) return -1;
    stream->pos = new_pos;
    return 0;
}

opus_int64 tell_mem(void* _stream) {
    MemoryStream* stream = (MemoryStream*)_stream;
    return stream->pos;
}

static const OpusFileCallbacks mem_callbacks = {
    read_mem, seek_mem, tell_mem, NULL
};

// ============================================================
// Audio was pre-processed: normalized + resampled to 16kHz + Opus encoded
// Opus always decodes to 48kHz, so we still need 48kHz -> 16kHz downsample
// But since the source audio is already band-limited to 8kHz (16kHz Nyquist),
// a simple averaging decimation is sufficient (no aliasing possible)
// ============================================================

#define DOWNSAMPLE_RATIO 3

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
    // No initialization needed - audio is pre-processed
}

JNIEXPORT void JNICALL
Java_com_shan_tts_ShanTtsService_destroyOpusDecoder(JNIEnv *env, jobject thiz) {
}

JNIEXPORT jshortArray JNICALL
Java_com_shan_tts_ShanTtsService_decodeOpus(JNIEnv *env, jobject thiz, jbyteArray encodedData, jint len) {
    if (encodedData == nullptr || len <= 0) return nullptr;

    jbyte *oggData = env->GetByteArrayElements(encodedData, nullptr);
    
    MemoryStream memStream;
    memStream.data = (unsigned char*)oggData;
    memStream.size = len;
    memStream.pos = 0;

    int error = 0;
    OggOpusFile *of = op_open_callbacks(&memStream, &mem_callbacks, NULL, 0, &error);
    
    if (error != 0 || of == nullptr) {
        env->ReleaseByteArrayElements(encodedData, oggData, 0);
        return nullptr;
    }

    // Collect all decoded PCM (Opus always decodes to 48kHz)
    std::vector<opus_int16> allPcm48k;
    int channels = 1;

    opus_int16 buffer[5760 * 2]; // 120ms @ 48kHz, stereo safety

    while (true) {
        int samplesRead = op_read(of, buffer, 5760, NULL);
        if (samplesRead <= 0) break;

        channels = op_channel_count(of, -1);

        for (int i = 0; i < samplesRead; i++) {
            allPcm48k.push_back(buffer[i * channels]);
        }
    }

    op_free(of);
    env->ReleaseByteArrayElements(encodedData, oggData, 0);

    if (allPcm48k.empty()) return nullptr;

    // Simple averaging downsample 48kHz -> 16kHz
    // Safe because source audio was already band-limited to 8kHz before Opus encoding
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
