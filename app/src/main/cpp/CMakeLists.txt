cmake_minimum_required(VERSION 3.4.1)

project(mettavoice_tts)

set(OGG_DIR ${CMAKE_SOURCE_DIR}/libogg)
set(OPUS_DIR ${CMAKE_SOURCE_DIR}/libopus)
set(OPUSFILE_DIR ${CMAKE_SOURCE_DIR}/libopusfile)

include_directories(
        ${CMAKE_SOURCE_DIR}
        ${OGG_DIR}/include
        ${OPUS_DIR}/include
        ${OPUS_DIR}/celt
        ${OPUS_DIR}/silk
        ${OPUS_DIR}/silk/fixed
        ${OPUSFILE_DIR}/include
)

file(GLOB OGG_SOURCES
        "${OGG_DIR}/src/*.c"
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

file(GLOB OPUSFILE_SOURCES
        "${OPUSFILE_DIR}/src/*.c"
)

file(GLOB OPUSFILE_EXCLUDED
        "${OPUSFILE_DIR}/src/http.c"
        "${OPUSFILE_DIR}/src/wincerts.c"
)
if (OPUSFILE_EXCLUDED)
    list(REMOVE_ITEM OPUSFILE_SOURCES ${OPUSFILE_EXCLUDED})
endif()

add_definitions(
        -DOPUS_BUILD             
        -DFIXED_POINT            
        -DUSE_ALLOCA             
        -DHAVE_LRINT
        -DHAVE_LRINTF
        -DOP_DISABLE_HTTP        
        -DOP_DISABLE_FLOAT_API   
)

add_library(
        native-lib
        SHARED
        native-lib.cpp
        sonic.c
        ${OGG_SOURCES}
        ${OPUS_SOURCES}
        ${OPUSFILE_SOURCES}
)

find_library(
        log-lib
        log
)

target_link_libraries(
        native-lib
        ${log-lib}
)

