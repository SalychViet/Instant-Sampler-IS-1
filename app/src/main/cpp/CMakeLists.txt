cmake_minimum_required(VERSION 3.22.1)
project(mpcengine LANGUAGES CXX)

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
    SampleOps.cpp
    SampleJni.cpp
    EngineJni.cpp)

# НЕ использовать -ffast-math: защита от NaN/Inf в Limiter на нём держится.
target_compile_options(mpcengine PRIVATE -Wall -Wextra -O3)
# Ableton Link (header-only, GPLv2): ../../../../third_party/link
include(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/link/AbletonLinkConfig.cmake)
target_compile_definitions(mpcengine PRIVATE LINK_PLATFORM_LINUX=1)

target_link_libraries(mpcengine oboe::oboe Ableton::Link log android)
