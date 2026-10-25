// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

#pragma once
#include <cstdint>

namespace mpc {

// Запись экспорта мастера (interleaved stereo 16 бит) в файл. false — не удалось.
bool writeWav16File(const char* path, const int16_t* data, int frames, int sampleRate);
// MP3 CBR через LAME (LGPL, third_party/lame).
bool writeMp3File(const char* path, const int16_t* data, int frames, int sampleRate, int kbps);

}  // namespace mpc
