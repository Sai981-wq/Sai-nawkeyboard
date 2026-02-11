cmake_minimum_required(VERSION 3.4.1)

project(shan_tts)

# --- 1. Libopus Setup (Opus ဖိုင်များ လမ်းကြောင်း) ---
set(OPUS_DIR ${CMAKE_SOURCE_DIR}/libopus)

# Include ဖိုဒါများကို ညွှန်ပြခြင်း
include_directories(
        ${OPUS_DIR}/include
        ${OPUS_DIR}/celt
        ${OPUS_DIR}/silk
        ${OPUS_DIR}/silk/fixed  # Fixed Point အတွက်
        ${CMAKE_SOURCE_DIR}
)

# Opus Source Files များကို စုစည်းခြင်း
# (မှတ်ချက်: silk/float မပါဘဲ silk/fixed ကိုပဲ ယူပါမယ်)
file(GLOB OPUS_SOURCES
        "${OPUS_DIR}/src/*.c"
        "${OPUS_DIR}/celt/*.c"
        "${OPUS_DIR}/silk/*.c"
        "${OPUS_DIR}/silk/fixed/*.c"
)

# --- 2. Build Definitions (အရေးကြီးဆုံးအပိုင်း) ---
# FIXED_POINT ကို ထည့်ပေးလိုက်မှ main_FLP.h ကို မရှာတော့ဘဲ main_FIX.h ကို သုံးပါလိမ့်မယ်
add_definitions(
        -DOPUS_BUILD 
        -DUSE_ALLOCA 
        -DHAVE_LRINT 
        -DHAVE_LRINTF 
        -DFIXED_POINT
)

# --- 3. Build Library (Sonic + Opus ပေါင်းစပ်ခြင်း) ---
add_library(
        native-lib
        SHARED
        native-lib.cpp   # JNI Bridge
        sonic.c          # Sonic Source
        ${OPUS_SOURCES}  # Opus Source
)

find_library(
        log-lib
        log
)

target_link_libraries(
        native-lib
        ${log-lib}
)

