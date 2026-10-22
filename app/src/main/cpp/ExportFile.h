#pragma once
#include <cstdint>

namespace mpc {

// Запись экспорта мастера (interleaved stereo 16 бит) в файл. false — не удалось.
bool writeWav16File(const char* path, const int16_t* data, int frames, int sampleRate);
// MP3 CBR через LAME (LGPL, third_party/lame).
bool writeMp3File(const char* path, const int16_t* data, int frames, int sampleRate, int kbps);

}  // namespace mpc
