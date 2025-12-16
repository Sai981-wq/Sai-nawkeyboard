package com.shan.tts.manager

object AudioProcessor {
    // အစ်ကို့ရဲ့ CMakeLists.txt ထဲမှာ ပေးထားတဲ့ library နာမည်ကို ဒီမှာ ထည့်ရပါမယ်။
    // ပုံမှန်အားဖြင့် "native-lib" သို့မဟုတ် "sai-nawkeyboard" ဖြစ်တတ်ပါတယ်။
    init {
        try {
            System.loadLibrary("native-lib") 
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    // JNI Methods
    external fun createProcessor(inRate: Int, outRate: Int, ch: Int)
    external fun process(inData: ByteArray, len: Int): ByteArray
    external fun flush()
}

