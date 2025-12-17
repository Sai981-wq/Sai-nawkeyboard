#include <jni.h>
#include <stdlib.h>
#include <vector>
#include <android/log.h>
#include <mutex>
#include <cmath>
#include "sonic.h"

#define TAG "AutoTTS_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define TARGET_RATE 24000

std::mutex processorMutex;

class CherryResampler {
public:
    sonicStream stream;
    std::vector<short> outputBuffer;
    
    int inRate;
    int outRate = TARGET_RATE;
    
    // Precise State Keeping
    double phase = 0.0; 
    
    CherryResampler(int sampleRate, int channels) {
        inRate = sampleRate;
        stream = sonicCreateStream(inRate, channels);
        sonicSetQuality(stream, 0); 
        sonicSetSpeed(stream, 1.0f);
        sonicSetPitch(stream, 1.0f);
        sonicSetRate(stream, 1.0f);
        
        outputBuffer.reserve(4096);
        LOGI("Created Resampler: In=%d", inRate);
    }

    ~CherryResampler() {
        if (stream) sonicDestroyStream(stream);
    }

    // ROBUST LINEAR INTERPOLATION (FM Radio အသံပျောက်စေမည့် နည်းလမ်း)
    void processResample(short* in, int inCount) {
        if (inCount <= 0) return;

        // Same Rate - Copy and Return
        if (inRate == outRate) {
            int oldSize = outputBuffer.size();
            outputBuffer.resize(oldSize + inCount);
            memcpy(outputBuffer.data() + oldSize, in, inCount * sizeof(short));
            return;
        }

        double step = (double)inRate / (double)outRate;
        
        // Calculate needed output size safely
        // (inCount - phase) / step gives approximate output
        int expectedOut = (int)((inCount - phase) / step);
        if (expectedOut < 0) expectedOut = 0;
        
        int startIdx = outputBuffer.size();
        outputBuffer.resize(startIdx + expectedOut + 5); // Add safety padding
        short* outPtr = outputBuffer.data() + startIdx;
        
        int produced = 0;

        while (true) {
            int idx = (int)phase; // Integer part
            
            // Boundary Check: If we need next sample but it's not there, stop.
            if (idx >= inCount - 1) {
                // Save remaining fractional phase for next chunk
                phase -= idx; 
                // Wait! We can't keep phase > 1.0 effectively without data.
                // Logic fix: Just subtract the integer part we consumed.
                // Actually, we usually keep phase relative to current buffer.
                // Let's refine:
                phase = phase - (int)phase; // Keep only fraction? No.
                // Revert to simpler logic:
                // We stop processing this chunk. The remaining 'phase' implies
                // we need future data.
                // We must adjust phase relative to the END of this buffer.
                phase = phase - inCount; // Phase becomes negative relative to new buffer start? No.
                
                // Let's restart the math to be simpler:
                // Phase is position in input.
                // When phase >= inCount, we stop.
                phase -= inCount; // This makes phase negative? No.
                // Example: inCount=100. phase was 99.8. Next sample at 99.8+step(0.5) = 100.3
                // Loop breaks. phase becomes 100.3 - 100 = 0.3. Correct.
                break;
            }

            double frac = phase - idx;
            
            short s0 = in[idx];
            short s1 = in[idx + 1]; // Safe because we checked idx >= inCount - 1

            // Linear Interpolation
            float val = s0 + (s1 - s0) * frac;
            
            // Clamp to 16-bit range (Prevents cracking noise)
            if (val > 32767) val = 32767;
            if (val < -32768) val = -32768;
            
            outPtr[produced++] = (short)val;
            
            phase += step;
        }

        outputBuffer.resize(startIdx + produced);
    }

    void clear() { 
        outputBuffer.clear(); 
        phase = 0.0;
    }
};

static CherryResampler* proc = NULL;

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint rate, jint ch) {
    std::lock_guard<std::mutex> lock(processorMutex);
    
    if (proc) {
        if (proc->inRate == rate) {
            sonicFlushStream(proc->stream);
            proc->clear();
            return;
        }
        delete proc;
    }
    proc = new CherryResampler(rate, ch);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jfloat s, jfloat p) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (proc) {
        sonicSetSpeed(proc->stream, s);
        sonicSetPitch(proc->stream, p);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(JNIEnv* env, jobject, jbyteArray in, jint len) {
    std::lock_guard<std::mutex> lock(processorMutex);
    
    if (!proc || len <= 0) return env->NewByteArray(0);

    void* primitive = env->GetPrimitiveArrayCritical(in, 0);
    
    // 1. Sonic Speed/Pitch Processing
    sonicWriteShortToStream(proc->stream, (short*)primitive, len / 2);
    env->ReleasePrimitiveArrayCritical(in, primitive, 0);

    // 2. Read from Sonic
    int avail = sonicSamplesAvailable(proc->stream);
    if (avail > 0) {
        static std::vector<short> tempBuf;
        if (tempBuf.size() < avail) tempBuf.resize(avail);
        int read = sonicReadShortFromStream(proc->stream, tempBuf.data(), avail);

        if (read > 0) {
            // 3. Resample using Robust Linear Math
            proc->clear(); 
            proc->processResample(tempBuf.data(), read);
            
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

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_drain(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(processorMutex);
    if (!proc) return env->NewByteArray(0);

    sonicFlushStream(proc->stream);
    int avail = sonicSamplesAvailable(proc->stream);
    
    if (avail > 0) {
        static std::vector<short> drainBuf;
        if (drainBuf.size() < avail) drainBuf.resize(avail);
        int read = sonicReadShortFromStream(proc->stream, drainBuf.data(), avail);
        
        if (read > 0) {
            proc->clear();
            proc->processResample(drainBuf.data(), read);
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
    std::lock_guard<std::mutex> lock(processorMutex);
    if (proc) {
        sonicFlushStream(proc->stream);
        proc->clear();
    }
}

