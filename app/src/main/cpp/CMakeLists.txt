cmake_minimum_required(VERSION 3.4.1)

set(OPUS_DIR ${CMAKE_SOURCE_DIR}/libopus)

include_directories(
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

add_definitions(-DOPUS_BUILD -DUSE_ALLOCA -DHAVE_LRINT -DHAVE_LRINTF)

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

