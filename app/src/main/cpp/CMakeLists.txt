cmake_minimum_required(VERSION 3.4.1)

project(shan_tts)

# --- 1. Libopus Setup ---
set(OPUS_DIR ${CMAKE_SOURCE_DIR}/libopus)

# Include Directories
include_directories(
        ${OPUS_DIR}/include
        ${OPUS_DIR}/celt
        ${OPUS_DIR}/silk
        ${OPUS_DIR}/silk/fixed
        ${CMAKE_SOURCE_DIR}
)

# --- 2. Gather Sources (အားလုံးကို အရင်ယူမယ်) ---
file(GLOB OPUS_SOURCES
        "${OPUS_DIR}/src/*.c"
        "${OPUS_DIR}/celt/*.c"
        "${OPUS_DIR}/silk/*.c"
        "${OPUS_DIR}/silk/fixed/*.c"
)

# --- 3. Exclude Unwanted Files (မလိုချင်တာတွေ ပြန်ထုတ်မယ်) ---
# Demo ဖိုင်တွေနဲ့ Tool ဖိုင်တွေမှာ main() ပါလို့ Error တက်စေပါတယ်
file(GLOB OPUS_EXCLUDED
        "${OPUS_DIR}/src/*_demo.c"        # demo ဖိုင်များ (ဥပမာ repacketizer_demo.c)
        "${OPUS_DIR}/celt/*_demo.c"       # celt demo ဖိုင်များ (ဥပမာ opus_custom_demo.c)
        "${OPUS_DIR}/src/opus_compare.c"  # compare tool
        "${OPUS_DIR}/src/qext_compare.c"  # qext tool (Error တက်နေသောကောင်)
        "${OPUS_DIR}/src/mlp_train.c"     # training tool
)

# စာရင်းထဲကနေ မလိုတဲ့ဖိုင်တွေကို ဖယ်ရှားခြင်း
if (OPUS_EXCLUDED)
    list(REMOVE_ITEM OPUS_SOURCES ${OPUS_EXCLUDED})
endif()

# --- 4. Definitions ---
add_definitions(
        -DOPUS_BUILD
        -DUSE_ALLOCA
        -DHAVE_LRINT
        -DHAVE_LRINTF
        -DFIXED_POINT  # Fixed Point Mode (မဖြစ်မနေပါရမည်)
)

# --- 5. Build Library ---
add_library(
        native-lib
        SHARED
        native-lib.cpp
        sonic.c
        ${OPUS_SOURCES}
)

find_library(
        log-lib
        log
)

target_link_libraries(
        native-lib
        ${log-lib}
)

