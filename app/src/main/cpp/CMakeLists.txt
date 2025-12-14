cmake_minimum_required(VERSION 3.18.0)
project("cherrytts")

add_library( cherry-audio SHARED native-lib.cpp sonic.c )
find_library( log-lib log )
target_link_libraries( cherry-audio ${log-lib} )
