# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22.1)
project(selfband_audio LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)

add_library(selfband_audio SHARED
        jni_bridge.cpp
        probe/AudioProbe.cpp
)

target_include_directories(selfband_audio PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_options(selfband_audio PRIVATE
        -Wall -Wextra -Werror=return-type
        -fno-exceptions -fno-rtti
        $<$<CONFIG:Release>:-O3>
)

target_link_libraries(selfband_audio PRIVATE oboe::oboe log)
