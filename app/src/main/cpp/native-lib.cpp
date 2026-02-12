#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
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

    std::vector<opus_int16> pcmOutput;
    
    // Buffer size to read from Opus (48kHz)
    // 120ms @ 48kHz = 5760 samples
    opus_int16 buffer[5760 * 2]; // *2 for stereo safety

    while (true) {
        int samplesRead = op_read(of, buffer, 5760, NULL);
        if (samplesRead <= 0) break;

        int channels = op_channel_count(of, -1);
        
        // Manual Resample: 48000Hz -> 16000Hz
        // We take every 3rd sample (Skip 2)
        for (int i = 0; i < samplesRead; i += 3) {
            if (i * channels < samplesRead * channels) {
                // Always take the first channel (Mono)
                pcmOutput.push_back(buffer[i * channels]);
            }
        }
    }

    op_free(of);
    env->ReleaseByteArrayElements(encodedData, oggData, 0);

    if (pcmOutput.empty()) return nullptr;

    jshortArray result = env->NewShortArray(pcmOutput.size());
    env->SetShortArrayRegion(result, 0, pcmOutput.size(), pcmOutput.data());

    return result;
}

}

