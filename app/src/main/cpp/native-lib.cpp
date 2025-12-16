#include <jni.h>
#include <stdlib.h>
#include <vector>
#include <android/log.h>
#include "sonic.h"

// Master Output Rate
#define TARGET_RATE 24000

class CherrySonicProcessor {
public:
    sonicStream stream;
    std::vector<short> outputBuffer;
    int inRate;
    int outRate = TARGET_RATE;
    
    // Cubic History
    short p0 = 0, p1 = 0;
    double timePos = 0.0; 

    CherrySonicProcessor(int sampleRate, int channels) {
        inRate = sampleRate;
        stream = sonicCreateStream(inRate, channels);
        sonicSetQuality(stream, 0);
        sonicSetVolume(stream, 1.0f);
        outputBuffer.reserve(8192); // Pre-allocate
    }

    ~CherrySonicProcessor() { 
        if (stream) sonicDestroyStream(stream); 
    }

    // Cubic Interpolation for High Quality Upsampling
    inline short cubic(short y0, short y1, short y2, short y3, double mu) {
        double mu2 = mu * mu;
        double a0 = -0.5*y0 + 1.5*y1 - 1.5*y2 + 0.5*y3;
        double a1 = y0 - 2.5*y1 + 2.0*y2 - 0.5*y3;
        double a2 = -0.5*y0 + 0.5*y2;
        double a3 = y1;
        double res = a0*mu*mu2 + a1*mu2 + a2*mu + a3;
        if (res > 32767) return 32767;
        if (res < -32768) return -32768;
        return (short)res;
    }

    void resample(short* in, int inCount) {
        if (inCount <= 0) return;

        // If rates match (e.g. Google 24k -> 24k), just copy
        if (inRate == outRate) {
            int oldSize = outputBuffer.size();
            outputBuffer.resize(oldSize + inCount);
            memcpy(outputBuffer.data() + oldSize, in, inCount * sizeof(short));
            return;
        }

        double step = (double)inRate / outRate;
        int needed = (int)(inCount / step) + 5;
        int startIdx = outputBuffer.size();
        outputBuffer.resize(startIdx + needed);
        
        short* outPtr = outputBuffer.data() + startIdx;
        int produced = 0;

        for (int i = 0; i < needed; i++) {
            int idx = (int)timePos;
            if (idx >= inCount - 2) {
                // Save state
                if (inCount >= 2) { p0 = in[inCount-2]; p1 = in[inCount-1]; }
                else if (inCount == 1) { p0 = p1; p1 = in[0]; }
                timePos -= idx;
                break;
            }

            double frac = timePos - idx;
            short y0 = (idx == 0) ? p1 : in[idx - 1]; // Use history
            short y1 = in[idx];
            short y2 = in[idx + 1];
            short y3 = (idx + 2 < inCount) ? in[idx + 2] : in[idx + 1];

            outPtr[produced++] = cubic(y0, y1, y2, y3, frac);
            timePos += step;
        }
        outputBuffer.resize(startIdx + produced);
    }
    
    void clear() {
        outputBuffer.clear();
    }
};

static CherrySonicProcessor* proc = NULL;

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint rate, jint ch) {
    if (proc) {
        // Reuse object if rate is same to avoid CPU spikes
        if (proc->inRate == rate) {
            sonicFlushStream(proc->stream);
            proc->p0 = 0; proc->p1 = 0;
            proc->timePos = 0.0;
            proc->clear();
            return;
        }
        delete proc;
    }
    proc = new CherrySonicProcessor(rate, ch);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jfloat s, jfloat p) {
    if (proc) { sonicSetSpeed(proc->stream, s); sonicSetPitch(proc->stream, p); }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(JNIEnv* env, jobject, jbyteArray in, jint len) {
    if (!proc || len <= 0) return env->NewByteArray(0);
    
    void* primitive = env->GetPrimitiveArrayCritical(in, 0);
    sonicWriteShortToStream(proc->stream, (short*)primitive, len / 2);
    env->ReleasePrimitiveArrayCritical(in, primitive, 0);

    int avail = sonicSamplesAvailable(proc->stream);
    if (avail > 0) {
        static std::vector<short> tempBuf;
        if (tempBuf.size() < avail) tempBuf.resize(avail);
        int read = sonicReadShortFromStream(proc->stream, tempBuf.data(), avail);
        
        if (read > 0) {
            proc->clear(); // Clear old buffer data
            proc->resample(tempBuf.data(), read);
            
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
        proc->p0 = 0; proc->p1 = 0; 
        proc->timePos = 0.0; 
        proc->clear();
    }
}

