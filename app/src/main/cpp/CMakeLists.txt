cmake_minimum_required(VERSION 3.22.1)
project(pty_bridge C)

add_library(pty_bridge SHARED pty_bridge.c)

target_link_libraries(pty_bridge log)

target_compile_definitions(pty_bridge PRIVATE
    _FILE_OFFSET_BITS=64
    _GNU_SOURCE
)
