#include <jni.h>
#include <stdlib.h>
#include <vector>
#include <android/log.h>
#include "sonic.h"

#define LOG_TAG "CherryTTS_Monitor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

class CherrySonicProcessor {
public:
    sonicStream stream;
    std::vector<short> sonicBuffer;
    std::vector<short> outputBuffer;
    int inRate;
    int outRate = 24000;
    short lastS = 0; 
    double timePos = 0.0; 

    CherrySonicProcessor(int sampleRate, int channels) {
        inRate = sampleRate;
        stream = sonicCreateStream(inRate, channels);
        sonicSetQuality(stream, 0);
        sonicSetVolume(stream, 1.0f);
        LOGI("Native Init: %dHz -> %dHz", inRate, outRate);
    }

    ~CherrySonicProcessor() { 
        if (stream) sonicDestroyStream(stream); 
    }

    void resample(short* in, int inCount) {
        outputBuffer.clear();
        if (inCount <= 0) return;
        
        if (inRate == outRate) {
            outputBuffer.assign(in, in + inCount);
            return;
        }
        
        double step = (double)inRate / outRate;
        int estimatedOut = (int)((inCount - timePos) / step) + 2;
        if (outputBuffer.capacity() < estimatedOut) outputBuffer.reserve(estimatedOut);
        outputBuffer.resize(estimatedOut);
        
        short* outPtr = outputBuffer.data();
        int genCount = 0;
        
        while (true) {
            int idx = (int)timePos;
            if (idx >= inCount) {
                timePos -= inCount;
                lastS = in[inCount - 1];
                break;
            }
            
            double frac = timePos - idx;
            short s1 = (idx == 0) ? lastS : in[idx - 1];
            short s2 = in[idx];
            
            int val = s1 + (int)((s2 - s1) * frac);
            if (val > 32767) val = 32767;
            else if (val < -32768) val = -32768;
            
            outPtr[genCount++] = (short)val;
            timePos += step;
        }
        outputBuffer.resize(genCount);
    }
};

static CherrySonicProcessor* proc = NULL;

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint rate, jint ch) {
    if (proc && proc->inRate == rate) return;
    if (proc) delete proc;
    proc = new CherrySonicProcessor(rate, ch);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jfloat s, jfloat p) {
    if (proc) { 
        sonicSetSpeed(proc->stream, s); 
        sonicSetPitch(proc->stream, p); 
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(JNIEnv* env, jobject, jbyteArray in, jint len) {
    if (!proc || len <= 0) return env->NewByteArray(0);
    
    jbyte* ptr = env->GetByteArrayElements(in, NULL);
    sonicWriteShortToStream(proc->stream, (short*)ptr, len / 2);
    env->ReleaseByteArrayElements(in, ptr, JNI_ABORT);

    int avail = sonicSamplesAvailable(proc->stream);
    if (avail > 0) {
        if (proc->sonicBuffer.size() < avail) proc->sonicBuffer.resize(avail);
        int read = sonicReadShortFromStream(proc->stream, proc->sonicBuffer.data(), avail);
        
        if (read > 0) {
            proc->resample(proc->sonicBuffer.data(), read);
            int outSz = proc->outputBuffer.size();
            
            // Log every chunk will spam, uncomment only if needed for debugging stutter
            // LOGI("Native Trace: InBytes=%d, OutShorts=%d", len, outSz);

            if (outSz > 0) {
                jbyteArray res = env->NewByteArray(outSz * 2);
                env->SetByteArrayRegion(res, 0, outSz * 2, (jbyte*)proc->outputBuffer.data());
                return res;
            }
        }
    }
    return env->NewByteArray(0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_flush(JNIEnv*, jobject) {
    if (proc) { 
        sonicFlushStream(proc->stream); 
        proc->lastS = 0; 
        proc->timePos = 0.0; 
        LOGI("Native Flush: State Reset");
    }
}

