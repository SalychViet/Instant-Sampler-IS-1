#pragma once
#include <cstdint>
#include <vector>

namespace mpc {

// Семпл в памяти: float PCM, ВСЕГДА стерео interleaved (L,R,L,R...).
// Создаётся и заполняется вне аудио-потока, дальше только читается.
struct Sample {
    std::vector<float> data;
    int64_t frames = 0;
    int sampleRate = 44100;
};

}  // namespace mpc
