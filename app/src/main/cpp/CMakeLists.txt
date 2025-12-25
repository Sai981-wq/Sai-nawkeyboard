cmake_minimum_required(VERSION 3.18.0)

project("cherrytts")

add_library( native-lib SHARED native-lib.cpp sonic.c )

find_library( log-lib log )

target_link_libraries( native-lib ${log-lib} )

