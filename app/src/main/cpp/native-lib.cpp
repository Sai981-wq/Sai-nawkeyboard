#include <jni.h>
#include <stdlib.h>
#include <vector>
#include <android/log.h>
#include "sonic.h"

#define TARGET_RATE 24000

class CherrySonicProcessor {
public:
    sonicStream stream;
    std::vector<short> outputBuffer;
    int inRate;
    int outRate = TARGET_RATE;
    
    // Cubic Interpolation needs history of 4 points
    // p0(prev), p1(current), p2(next), p3(next-next)
    short p0 = 0, p1 = 0, p2 = 0, p3 = 0;
    double timePos = 0.0; 

    CherrySonicProcessor(int sampleRate, int channels) {
        inRate = sampleRate;
        stream = sonicCreateStream(inRate, channels);
        sonicSetQuality(stream, 0);
        sonicSetVolume(stream, 1.0f);
        outputBuffer.reserve(16384); 
    }

    ~CherrySonicProcessor() { 
        if (stream) sonicDestroyStream(stream); 
    }

    // Cubic Hermite Interpolation (Smooth Curve)
    // မျဉ်းဖြောင့်မဆွဲဘဲ အကွေးဆွဲပေးမည့် Function
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

        // Direct Copy if rates match
        if (inRate == outRate) {
            if (outputBuffer.size() < inCount) outputBuffer.resize(inCount);
            memcpy(outputBuffer.data(), in, inCount * sizeof(short));
            return;
        }

        double step = (double)inRate / outRate;
        int neededSize = (int)(inCount / step) + 32;
        
        if (outputBuffer.size() < neededSize) {
            outputBuffer.resize(neededSize);
        }

        short* outPtr = outputBuffer.data();
        int outIdx = 0;

        // Main Resampling Loop
        for (int i = 0; i < neededSize; i++) {
            int idx = (int)timePos;
            
            // Loop break condition
            if (idx >= inCount - 2) {
                // Save history for next chunk to ensure continuity
                if (inCount >= 2) {
                    p0 = in[inCount - 2];
                    p1 = in[inCount - 1];
                } else if (inCount == 1) {
                    p0 = p1; 
                    p1 = in[0];
                }
                timePos -= idx; // Keep fraction
                break;
            }

            double frac = timePos - idx;

            // Fetch 4 points for Cubic Calculation
            // Handle edge cases carefully
            short y0, y1, y2, y3;

            if (idx == 0) {
                y0 = p1; // Use history
                y1 = in[0];
                y2 = in[1];
                y3 = (inCount > 2) ? in[2] : in[1];
            } else {
                y0 = in[idx - 1];
                y1 = in[idx];
                y2 = in[idx + 1];
                y3 = (idx + 2 < inCount) ? in[idx + 2] : in[idx + 1];
            }

            // Calculate Smooth Value
            outPtr[outIdx++] = cubic(y0, y1, y2, y3, frac);

            timePos += step;
        }
        
        // Update history
        p0 = outPtr[outIdx-2]; // Approximate history from output or last input
        // Actually, strictly we should update from input, but inside the loop we handled it.
    }
};

static CherrySonicProcessor* proc = NULL;

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint rate, jint ch) {
    if (proc) {
        if (proc->inRate == rate) {
            sonicFlushStream(proc->stream);
            proc->p0 = 0; proc->p1 = 0; // Reset history
            proc->timePos = 0.0;
            return;
        }
        delete proc;
    }
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
    
    // FAST ACCESS: Using PrimitiveArrayCritical for speed
    void* primitive = env->GetPrimitiveArrayCritical(in, 0);
    sonicWriteShortToStream(proc->stream, (short*)primitive, len / 2);
    env->ReleasePrimitiveArrayCritical(in, primitive, 0);

    int avail = sonicSamplesAvailable(proc->stream);
    if (avail > 0) {
        static std::vector<short> tempBuf;
        if (tempBuf.size() < avail) tempBuf.resize(avail);
        
        int read = sonicReadShortFromStream(proc->stream, tempBuf.data(), avail);
        
        if (read > 0) {
            if (proc->inRate == proc->outRate) {
                jbyteArray res = env->NewByteArray(read * 2);
                env->SetByteArrayRegion(res, 0, read * 2, (jbyte*)tempBuf.data());
                return res;
            } else {
                // Apply Cubic Resampling
                proc->resample(tempBuf.data(), read);
                
                // Calculate output size safely
                double step = (double)proc->inRate / proc->outRate;
                int outCount = (int)(read / step); 
                if (outCount > proc->outputBuffer.size()) outCount = proc->outputBuffer.size();

                if (outCount > 0) {
                    jbyteArray res = env->NewByteArray(outCount * 2);
                    env->SetByteArrayRegion(res, 0, outCount * 2, (jbyte*)proc->outputBuffer.data());
                    return res;
                }
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
    }
}

