#include <jni.h>
#include <vector>
#include "sonic.h"

// Simple Processor Class
class Processor {
public:
    sonicStream stream;
    std::vector<short> buffer;
    int inputRate;

    Processor(int inRate, int outRate, int ch) {
        inputRate = inRate;
        stream = sonicCreateStream(inRate, ch);
        sonicSetQuality(stream, 0);
        // Use sonicSetRate to resample (Change playback rate)
        sonicSetRate(stream, (float)outRate / inRate); 
    }
    
    ~Processor() { 
        if(stream) sonicDestroyStream(stream); 
    }
};

// Global pointer - simpler than a map and safer for this use case
static Processor* currentProc = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_createProcessor(JNIEnv*, jobject, jint inRate, jint outRate, jint ch) {
    // Only recreate if the rate has changed or it doesn't exist
    if (currentProc) {
        if (currentProc->inputRate == inRate) {
            // Reuse existing processor, just flush it
            sonicFlushStream(currentProc->stream);
            return;
        }
        delete currentProc;
    }
    currentProc = new Processor(inRate, outRate, ch);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shan_tts_manager_AudioProcessor_process(JNIEnv* env, jobject, jbyteArray in, jint len) {
    if (!currentProc || len <= 0) return env->NewByteArray(0);

    // Get input data
    jbyte* data = env->GetByteArrayElements(in, nullptr);
    
    // Feed to Sonic
    sonicWriteShortToStream(currentProc->stream, (short*)data, len / 2);
    env->ReleaseByteArrayElements(in, data, 0);

    // Check available data
    int avail = sonicSamplesAvailable(currentProc->stream);
    if (avail <= 0) return env->NewByteArray(0);

    // Read from Sonic
    if (currentProc->buffer.size() < avail) {
        currentProc->buffer.resize(avail);
    }
    
    int read = sonicReadShortFromStream(currentProc->stream, currentProc->buffer.data(), avail);
    if (read <= 0) return env->NewByteArray(0);

    // Return result
    jbyteArray out = env->NewByteArray(read * 2);
    env->SetByteArrayRegion(out, 0, read * 2, (jbyte*)currentProc->buffer.data());
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shan_tts_manager_AudioProcessor_flush(JNIEnv*, jobject) {
    if (currentProc) {
        sonicFlushStream(currentProc->stream);
    }
}

