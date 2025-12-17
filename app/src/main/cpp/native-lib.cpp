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
    
    // အသံမကွဲအောင် Phase ကို မှတ်ထားမည်
    float phase = 0.0f;
    
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

    // Linear Interpolation (အသံချောမွေ့စေရန် တွက်ချက်ခြင်း)
    void processResample(short* in, int inCount) {
        if (inCount <= 0) return;

        // Rate တူရင် တွက်စရာမလို၊ တိုက်ရိုက်ကူးမယ်
        if (inRate == outRate) {
            int oldSize = outputBuffer.size();
            outputBuffer.resize(oldSize + inCount);
            memcpy(outputBuffer.data() + oldSize, in, inCount * sizeof(short));
            return;
        }

        double step = (double)inRate / outRate;
        int needed = (int)ceil((inCount - phase) / step);
        
        int startIdx = outputBuffer.size();
        outputBuffer.resize(startIdx + needed);
        short* outPtr = outputBuffer.data() + startIdx;
        
        int produced = 0;

        while (produced < needed) {
            int idx = (int)phase;
            if (idx >= inCount) break;

            float frac = phase - idx;
            short s0 = in[idx];
            short s1 = (idx + 1 < inCount) ? in[idx + 1] : s0;

            // Linear Formula: s0 + (s1 - s0) * fraction
            float val = s0 + (s1 - s0) * frac;
            outPtr[produced++] = (short)val;
            
            phase += step;
        }

        // နောက်တစ်ခေါက်အတွက် Phase ကို သိမ်းထားမယ် (ဒါမှ အသံမကွဲမှာ)
        phase -= inCount; 
        outputBuffer.resize(startIdx + produced);
    }

    void clear() { 
        outputBuffer.clear(); 
        phase = 0.0f;
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
    
    // 1. Sonic ကို အရင်ပို့ (Speed/Pitch အတွက်)
    sonicWriteShortToStream(proc->stream, (short*)primitive, len / 2);
    env->ReleasePrimitiveArrayCritical(in, primitive, 0);

    // 2. Sonic ကနေ ပြန်ဖတ်
    int avail = sonicSamplesAvailable(proc->stream);
    if (avail > 0) {
        static std::vector<short> tempBuf;
        if (tempBuf.size() < avail) tempBuf.resize(avail);
        int read = sonicReadShortFromStream(proc->stream, tempBuf.data(), avail);

        if (read > 0) {
            // 3. Resample လုပ် (24000Hz ပြောင်း)
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

// --- DRAIN FUNCTION (အသံအဆုံးထိထွက်အောင် လုပ်ပေးမည့်ကောင်) ---
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
            proc->processResample(drainBuf.data(), read); // Drain လုပ်တာကိုလည်း Resample လုပ်ရမယ်
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

