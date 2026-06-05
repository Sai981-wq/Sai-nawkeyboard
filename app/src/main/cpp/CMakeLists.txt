cmake_minimum_required(VERSION 3.4.1)

project(shan_tts)

# --- 1. Path Setup ---
# သင့် cpp ဖိုင်တွဲထဲတွင် အောက်ပါအမည်များအတိုင်း folder များရှိရမည်
set(OGG_DIR ${CMAKE_SOURCE_DIR}/libogg)
set(OPUS_DIR ${CMAKE_SOURCE_DIR}/libopus)
set(OPUSFILE_DIR ${CMAKE_SOURCE_DIR}/libopusfile)

# --- 2. Include Directories ---
# Header ဖိုင်များကို ရှာဖွေနိုင်ရန် လမ်းကြောင်းပေးခြင်း
include_directories(
        ${CMAKE_SOURCE_DIR}
        ${OGG_DIR}/include
        ${OPUS_DIR}/include
        ${OPUS_DIR}/celt
        ${OPUS_DIR}/silk
        ${OPUS_DIR}/silk/fixed
        ${OPUSFILE_DIR}/include
)

# --- 3. Gather Sources: LibOGG ---
file(GLOB OGG_SOURCES
        "${OGG_DIR}/src/*.c"
)

# --- 4. Gather Sources: LibOPUS ---
file(GLOB OPUS_SOURCES
        "${OPUS_DIR}/src/*.c"
        "${OPUS_DIR}/celt/*.c"
        "${OPUS_DIR}/silk/*.c"
        "${OPUS_DIR}/silk/fixed/*.c"
)

# မလိုတဲ့ Opus ဖိုင်များ (Demo/Tools) ကို ဖယ်ထုတ်ခြင်း
file(GLOB OPUS_EXCLUDED
        "${OPUS_DIR}/src/*_demo.c"
        "${OPUS_DIR}/celt/*_demo.c"
        "${OPUS_DIR}/src/opus_compare.c"
        "${OPUS_DIR}/src/qext_compare.c" # Error တက်တတ်သောကြောင့် ဖယ်သည်
        "${OPUS_DIR}/src/mlp_train.c"
)
if (OPUS_EXCLUDED)
    list(REMOVE_ITEM OPUS_SOURCES ${OPUS_EXCLUDED})
endif()

# --- 5. Gather Sources: LibOpusFile ---
file(GLOB OPUSFILE_SOURCES
        "${OPUSFILE_DIR}/src/*.c"
)

# HTTP stream မသုံးသည့်အတွက် http.c နှင့် wincerts.c ကို ဖယ်ရှားခြင်း
file(GLOB OPUSFILE_EXCLUDED
        "${OPUSFILE_DIR}/src/http.c"
        "${OPUSFILE_DIR}/src/wincerts.c"
)
if (OPUSFILE_EXCLUDED)
    list(REMOVE_ITEM OPUSFILE_SOURCES ${OPUSFILE_EXCLUDED})
endif()

# --- 6. Definitions (Flags) ---
add_definitions(
        -DOPUS_BUILD             # Opus build flag
        -DFIXED_POINT            # Mobile အတွက် Fixed Point သုံးရန်
        -DUSE_ALLOCA             # Memory management အတွက်
        -DHAVE_LRINT
        -DHAVE_LRINTF
        -DOP_DISABLE_HTTP        # Network stream ပိတ်ရန် (SSL error မတက်စေရန်)
        -DOP_DISABLE_FLOAT_API   # Fixed point သီးသန့်သုံးရန်
)

# --- 7. Build Shared Library ---
add_library(
        native-lib
        SHARED
        native-lib.cpp
        sonic.c
        ${OGG_SOURCES}
        ${OPUS_SOURCES}
        ${OPUSFILE_SOURCES}
)

# --- 8. Link Libraries ---
find_library(
        log-lib
        log
)

target_link_libraries(
        native-lib
        ${log-lib}
)

