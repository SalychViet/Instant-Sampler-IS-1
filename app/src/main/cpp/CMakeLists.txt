cmake_minimum_required(VERSION 3.22.1)
project(mpcengine LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(mpcengine SHARED
    Voice.cpp
    VoicePool.cpp
    EngineCore.cpp
    Sequencer.cpp
    KaossFx.cpp
    LinkSync.cpp
    AudioOutput.cpp
    WavCodec.cpp
    ExportFile.cpp
    SampleOps.cpp
    SampleJni.cpp
    EngineJni.cpp)

# НЕ использовать -ffast-math: защита от NaN/Inf в Limiter на нём держится.
target_compile_options(mpcengine PRIVATE -Wall -Wextra -O3)
# Ableton Link (header-only, GPLv2): ../../../../third_party/link
include(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/link/AbletonLinkConfig.cmake)
target_compile_definitions(mpcengine PRIVATE LINK_PLATFORM_LINUX=1)

# LAME 3.100 (LGPL): MP3 для EXPORT. ../../../../third_party/lame
set(LAME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/lame)
file(GLOB LAME_SOURCES ${LAME_DIR}/libmp3lame/*.c)
add_library(mp3lame STATIC ${LAME_SOURCES})
target_include_directories(mp3lame PRIVATE ${LAME_DIR}/libmp3lame PUBLIC ${LAME_DIR}/include)
target_compile_definitions(mp3lame PRIVATE STDC_HEADERS HAVE_STDINT_H ieee754_float32_t=float)
target_compile_options(mp3lame PRIVATE -O2 -w)

target_link_libraries(mpcengine oboe::oboe Ableton::Link mp3lame log android)
# Страницы памяти 16 КБ (Android 15+, требование Google Play). В NDK r28+ это по умолчанию, флаг — на всякий случай.
target_link_options(mpcengine PRIVATE "-Wl,-z,max-page-size=16384")
