cmake_minimum_required(VERSION 3.4.1)

project(mettavoice_tts)

set(OPUS_DIR ${CMAKE_SOURCE_DIR}/libopus)

include_directories(
        ${CMAKE_SOURCE_DIR}
        ${OPUS_DIR}/include
        ${OPUS_DIR}/celt
        ${OPUS_DIR}/silk
        ${OPUS_DIR}/silk/fixed
)

file(GLOB OPUS_SOURCES
        "${OPUS_DIR}/src/*.c"
        "${OPUS_DIR}/celt/*.c"
        "${OPUS_DIR}/silk/*.c"
        "${OPUS_DIR}/silk/fixed/*.c"
)

file(GLOB OPUS_EXCLUDED
        "${OPUS_DIR}/src/*_demo.c"
        "${OPUS_DIR}/celt/*_demo.c"
        "${OPUS_DIR}/src/opus_compare.c"
        "${OPUS_DIR}/src/qext_compare.c" 
        "${OPUS_DIR}/src/mlp_train.c"
)
if (OPUS_EXCLUDED)
    list(REMOVE_ITEM OPUS_SOURCES ${OPUS_EXCLUDED})
endif()

add_definitions(
        -DOPUS_BUILD             
        -DFIXED_POINT            
        -DUSE_ALLOCA             
        -DHAVE_LRINT
        -DHAVE_LRINTF
)

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

