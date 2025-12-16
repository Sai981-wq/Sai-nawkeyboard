#include <jni.h>
#include <stdlib.h>
#include <vector>
#include <android/log.h>
#include "sonic.h"

class CherrySonicProcessor {
public:
    sonicStream stream;
    std::vector<short> outputBuffer;
    int inRate;
    int outRate = 16000; // Stable 16kHz Target
    short lastS = 0; 
    double timePos = 0.0; 

    CherrySonicProcessor(int sampleRate, int channels) {
        inRate = sampleRate;
        stream = sonicCreateStream(inRate, channels);
        sonicSetQuality(stream, 0);
        sonicSetVolume(stream, 1.0f);
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
        int approxOut = (int)(inCount / step) + 32;
        outputBuffer.reserve(approxOut);
        
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
            
            outputBuffer.push_back((short)val);
            timePos += step;
        }
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
        std::vector<short> temp(avail);
        int read = sonicReadShortFromStream(proc->stream, temp.data(), avail);
        if (read > 0) {
            proc->resample(temp.data(), read);
            int sz = proc->outputBuffer.size();
            if (sz > 0) {
                jbyteArray res = env->NewByteArray(sz * 2);
                env->SetByteArrayRegion(res, 0, sz * 2, (jbyte*)proc->outputBuffer.data());
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
    }
}

