#include <jni.h>
#include <stdlib.h>
#include <vector>
#include <math.h>
#include "sonic.h"

class CherrySonicProcessor {
public:
    sonicStream stream;
    std::vector<short> sonicOutputBuffer; // Buffer from Sonic (Input Rate)
    std::vector<short> finalOutputBuffer; // Buffer after Resampling (24000Hz)
    
    int inputRate;
    int targetRate;
    
    // --- RESAMPLER STATE ---
    // Chunk တစ်ခုနဲ့ တစ်ခုကြား အသံဆက်စပ်မှုမပြတ်အောင် မှတ်ထားမည့်အရာများ
    short lastSample; 
    double resampleTimePos; 

    CherrySonicProcessor(int sampleRate, int channels) {
        inputRate = sampleRate;
        targetRate = 24000;
        
        stream = sonicCreateStream(inputRate, channels);
        
        // Note: sonicSetOutputSampleRate ကို ဖြုတ်လိုက်ပါပြီ (Sonic အဟောင်းမှာ မရှိလို့ပါ)
        sonicSetQuality(stream, 0);
        sonicSetVolume(stream, 1.0f);
        
        // State Initialization
        lastSample = 0;
        resampleTimePos = 0.0;
    }

    ~CherrySonicProcessor() {
        if (stream != NULL) {
            sonicDestroyStream(stream);
        }
    }

    // Custom Resampler to handle 16k/11k -> 24k
    // This replaces the missing sonic function properly
    void resampleAndAppend(short* input, int inputCount) {
        if (inputCount <= 0) return;

        double step = (double)inputRate / (double)targetRate;
        
        // ခန့်မှန်းခြေ Output Size တွက်ချက်ခြင်း
        // (input - currentPos) / step
        int estimatedOut = (int)((inputCount - resampleTimePos + 1.0) / step) + 10;
        
        if (finalOutputBuffer.size() < estimatedOut) {
            finalOutputBuffer.resize(estimatedOut);
        }
        
        int outGenerated = 0;
        short* outPtr = finalOutputBuffer.data();

        while (true) {
            int index = (int)resampleTimePos;
            
            // Input ကုန်သွားရင် ရပ်မယ်
            if (index >= inputCount) {
                resampleTimePos -= inputCount; // နောက် Chunk အတွက် နေရာပြန်ညှိ
                lastSample = input[inputCount - 1]; // နောက် Chunk အတွက် အစွန်းမှတ်ထား
                break;
            }

            double frac = resampleTimePos - index;
            
            // Linear Interpolation
            // Chunk အစ (index 0) ဖြစ်နေရင် အရင် Chunk ရဲ့ lastSample ကို သုံးမယ် (အသံမထစ်အောင်)
            short s1 = (index == 0) ? lastSample : input[index - 1];
            short s2 = input[index];
            
            // Formula: y = s1 + (s2 - s1) * frac
            int val = s1 + (int)((s2 - s1) * frac);
            
            // Clamping just in case
            if (val > 32767) val = 32767;
            if (val < -32768) val = -32768;

            outPtr[outGenerated++] = (short)val;
            
            resampleTimePos += step;
        }
        
        // Buffer ကို အမှန်တကယ်ထွက်လာတဲ့ အရေအတွက်အတိုင်း ဖြတ်ညှိ (Resize not needed, just return usage)
        // ဒါပေမယ့် JNI ဘက်ကို ပြန်ပို့ဖို့ finalOutputBuffer မှာ Data ရှိနေဖို့လိုတယ်
        // finalOutputBuffer size ကို outGenerated အထိပဲ သုံးမယ်
        finalOutputBuffer.resize(outGenerated);
    }
};

static CherrySonicProcessor* globalProcessor = NULL;

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_initSonic(JNIEnv* env, jobject, jint sampleRate, jint channels) {
    if (globalProcessor != NULL) {
        // Rate တူရင် အသစ်မဆောက်ဘဲ ပြန်သုံး (State မပျက်အောင်)
        if (globalProcessor->inputRate == sampleRate) {
            return;
        }
        delete globalProcessor;
    }
    globalProcessor = new CherrySonicProcessor(sampleRate, channels);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_setConfig(JNIEnv* env, jobject, jfloat speed, jfloat pitch) {
    if (globalProcessor != NULL && globalProcessor->stream != NULL) {
        sonicSetSpeed(globalProcessor->stream, speed);
        sonicSetPitch(globalProcessor->stream, pitch);
        sonicSetRate(globalProcessor->stream, 1.0f);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_processAudio(JNIEnv* env, jobject, jbyteArray input, jint len) {
    if (globalProcessor == NULL || len <= 0) return env->NewByteArray(0);

    jbyte* bufferPtr = env->GetByteArrayElements(input, NULL);
    short* inputShorts = (short*)bufferPtr;
    int inputShortsCount = len / 2;

    // 1. Sonic ထဲ Input ထည့် (Speed/Pitch အတွက်)
    sonicWriteShortToStream(globalProcessor->stream, inputShorts, inputShortsCount);
    env->ReleaseByteArrayElements(input, bufferPtr, 0);

    // 2. Sonic ဆီကနေ Output ပြန်တောင်း (Input Rate အတိုင်းပဲ ထွက်လာမယ်)
    int available = sonicSamplesAvailable(globalProcessor->stream);
    if (available > 0) {
        if (globalProcessor->sonicOutputBuffer.size() < available) {
            globalProcessor->sonicOutputBuffer.resize(available);
        }

        int readSamples = sonicReadShortFromStream(globalProcessor->stream, globalProcessor->sonicOutputBuffer.data(), available);
        
        if (readSamples > 0) {
            // 3. ရလာတဲ့ Sonic Output ကိုမှ 24000Hz ဖြစ်အောင် Resample လုပ်မယ်
            // State ကို ထိန်းသိမ်းထားတဲ့အတွက် အသံထစ်မှာ မဟုတ်ပါ
            globalProcessor->resampleAndAppend(globalProcessor->sonicOutputBuffer.data(), readSamples);
            
            int finalCount = globalProcessor->finalOutputBuffer.size();
            if (finalCount > 0) {
                jbyteArray result = env->NewByteArray(finalCount * 2);
                env->SetByteArrayRegion(result, 0, finalCount * 2, (jbyte*)globalProcessor->finalOutputBuffer.data());
                return result;
            }
        }
    }

    return env->NewByteArray(0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_flush(JNIEnv*, jobject) {
    if (globalProcessor != NULL && globalProcessor->stream != NULL) {
        sonicFlushStream(globalProcessor->stream);
        // Reset State
        globalProcessor->lastSample = 0;
        globalProcessor->resampleTimePos = 0.0;
    }
}

