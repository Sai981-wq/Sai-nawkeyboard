package com.shan.tts.manager

/**
 * စာသားနှင့် ဘာသာစကားကို တွဲဖက်သိမ်းဆည်းမည့် Data Class
 * Service နှင့် Utils ဖိုင်များအကြား Shared သုံးရန်။
 */
data class LangChunk(
    val text: String, 
    val lang: String
)