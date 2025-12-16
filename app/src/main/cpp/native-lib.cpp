#include <jni.h>
#include <stdlib.h>
#include <vector>
#include <android/log.h>
#include "sonic.h"

// Master Fixed Rate (Standard for Android TTS)
#define MASTER_RATE 16000

class CherrySonicProcessor {
public:
    sonicStream stream;
    std::vector<short> outputBuffer;
    int inRate;
    int outRate = MASTER_RATE;
    
    // Resampling State
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

    // Linear Resampler (Integer-safe logic handled by float for precision here)
    void resample(short* in, int inCount) {
        outputBuffer.clear();
        if (inCount <= 0) return;
        
        // If rates match, direct copy
        if (inRate == outRate) {
            outputBuffer.assign(in, in + inCount);
            return;
        }
        
        double step = (double)inRate / outRate;
        int approxOut = (int)(inCount / step) + 64; // Add extra padding
        outputBuffer.reserve(approxOut);
        
        while (true) {
            int idx = (int)timePos;
            if (idx >= inCount) {
                // Keep the state for next chunk
                timePos -= inCount;
                lastS = in[inCount - 1];
                break;
            }
            
            double frac = timePos - idx;
            short s1 = (idx == 0) ? lastS : in[idx - 1];
            short s2 = in[idx];
            
            // Linear Interpolation
            int val = s1 + (int)((s2 - s1) * frac);
            
            // Hard Clamp
            if (val > 32767) val = 32767;
            else if (val < -32768) val = -32768;
            
            outputBuffer.push_back((short)val);
            timePos += step;
        }
    }

    void resetState() {
        sonicFlushStream(stream);
        lastS = 0;
        timePos = 0.0;
        outputBuffer.clear();
    }
};

static CherrySonicProcessor* proc = NULL;

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint rate, jint ch) {
    // FORCE RESET: Even if rate is same, we might want to ensure clean state on new utterance
    // But for performance, we only re-create if rate changes.
    // However, onStop will call flush, which handles the reset.
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
    
    // Feed to Sonic
    sonicWriteShortToStream(proc->stream, (short*)ptr, len / 2);
    env->ReleaseByteArrayElements(in, ptr, JNI_ABORT);

    // Read from Sonic
    int avail = sonicSamplesAvailable(proc->stream);
    if (avail > 0) {
        std::vector<short> temp(avail);
        int read = sonicReadShortFromStream(proc->stream, temp.data(), avail);
        
        if (read > 0) {
            // Resample to Master Fixed Rate
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
        proc->resetState();
    }
}

