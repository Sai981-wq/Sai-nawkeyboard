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
// High-quality FIR low-pass filter for 48kHz -> 16kHz downsampling
// Cutoff at ~7.5kHz (slightly below Nyquist of 8kHz for safety margin)
// 31-tap windowed-sinc filter with Blackman window
// ============================================================

#define FIR_TAPS 31
#define FIR_HALF (FIR_TAPS / 2)
#define DOWNSAMPLE_RATIO 3

// Pre-computed FIR filter coefficients
// Designed for: fs=48000, fc=7500Hz, 31 taps, Blackman window
static float fir_coeffs[FIR_TAPS];
static bool fir_initialized = false;

static void init_fir_filter() {
    if (fir_initialized) return;

    // Normalized cutoff frequency: fc / (fs/2) = 7500 / 24000 = 0.3125
    // For sinc filter: wc = 2 * pi * fc / fs = 2 * pi * 7500 / 48000
    double fc = 7500.0 / 48000.0; // Normalized cutoff (0 to 0.5)
    double sum = 0.0;

    for (int i = 0; i < FIR_TAPS; i++) {
        int n = i - FIR_HALF;
        double sinc_val;
        if (n == 0) {
            sinc_val = 2.0 * fc;
        } else {
            sinc_val = sin(2.0 * M_PI * fc * n) / (M_PI * n);
        }

        // Blackman window
        double window = 0.42 - 0.5 * cos(2.0 * M_PI * i / (FIR_TAPS - 1))
                       + 0.08 * cos(4.0 * M_PI * i / (FIR_TAPS - 1));

        fir_coeffs[i] = (float)(sinc_val * window);
        sum += fir_coeffs[i];
    }

    // Normalize to unity gain
    for (int i = 0; i < FIR_TAPS; i++) {
        fir_coeffs[i] /= (float)sum;
    }

    fir_initialized = true;
}

// Apply FIR filter and downsample mono signal from 48kHz to 16kHz
static void fir_downsample(const opus_int16* input, int inputLen, int channels,
                           std::vector<opus_int16>& output) {
    // Extract mono channel first
    std::vector<float> mono(inputLen);
    for (int i = 0; i < inputLen; i++) {
        mono[i] = (float)input[i * channels];
    }

    // Apply FIR filter and decimate by 3
    for (int i = 0; i < inputLen; i += DOWNSAMPLE_RATIO) {
        float acc = 0.0f;
        for (int j = 0; j < FIR_TAPS; j++) {
            int idx = i - FIR_HALF + j;
            if (idx >= 0 && idx < inputLen) {
                acc += mono[idx] * fir_coeffs[j];
            }
            // For out-of-bounds samples, we use zero-padding (implicit)
        }
        // Clamp to int16 range
        if (acc > 32767.0f) acc = 32767.0f;
        if (acc < -32768.0f) acc = -32768.0f;
        output.push_back((opus_int16)acc);
    }
}

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
    init_fir_filter();
}

JNIEXPORT void JNICALL
Java_com_shan_tts_ShanTtsService_destroyOpusDecoder(JNIEnv *env, jobject thiz) {
}

JNIEXPORT jshortArray JNICALL
Java_com_shan_tts_ShanTtsService_decodeOpus(JNIEnv *env, jobject thiz, jbyteArray encodedData, jint len) {
    if (encodedData == nullptr || len <= 0) return nullptr;

    // Ensure FIR filter is initialized
    init_fir_filter();

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
        
        // High-quality downsampling: FIR low-pass filter + decimation
        fir_downsample(buffer, samplesRead, channels, pcmOutput);
    }

    op_free(of);
    env->ReleaseByteArrayElements(encodedData, oggData, 0);

    if (pcmOutput.empty()) return nullptr;

    jshortArray result = env->NewShortArray(pcmOutput.size());
    env->SetShortArrayRegion(result, 0, pcmOutput.size(), pcmOutput.data());

    return result;
}

}
