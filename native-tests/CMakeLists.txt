# Host-тесты C++ движка (без Android): cmake -S native-tests -B build-tests && cmake --build build-tests
cmake_minimum_required(VERSION 3.22)
project(mpc_tests LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(MPC_MINI_GTEST "Собрать без GoogleTest (встроенная мини-замена)" OFF)
set(ENGINE ${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp)

add_executable(mpc_tests
    tests_sequencer.cpp
    tests_aiff.cpp
    tests_kaoss.cpp
    ${ENGINE}/Voice.cpp
    ${ENGINE}/VoicePool.cpp
    ${ENGINE}/EngineCore.cpp
    ${ENGINE}/Sequencer.cpp
    ${ENGINE}/KaossFx.cpp
    ${ENGINE}/WavCodec.cpp
    ${ENGINE}/SampleOps.cpp)
target_include_directories(mpc_tests PRIVATE ${ENGINE} ${CMAKE_CURRENT_SOURCE_DIR})

if(MPC_MINI_GTEST)
    target_compile_definitions(mpc_tests PRIVATE MPC_MINI_GTEST)
    target_sources(mpc_tests PRIVATE mini_main.cpp)
else()
    include(FetchContent)
    FetchContent_Declare(googletest URL https://github.com/google/googletest/archive/refs/tags/v1.15.2.zip)
    set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(googletest)
    target_link_libraries(mpc_tests PRIVATE GTest::gtest_main)
endif()
