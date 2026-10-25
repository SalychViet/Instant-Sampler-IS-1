// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

namespace mpc {

struct WavInfo {
    int channels = 0;
    int sampleRate = 0;
    int bitsPerSample = 0;
    int64_t frames = 0;
    bool floatData = false;
};

// Разбирает WAV: PCM 8/16/24/32 бит и IEEE float 32/64 (в т.ч. WAVE_FORMAT_EXTENSIBLE).
// Результат — interleaved float [-1..1]. Терпим к «недописанному» заголовку (запись оборвалась
// из-за краша): если размер data равен 0, 0xFFFFFFFF или выходит за пределы файла,
// берётся весь остаток файла. false — формат не поддерживается или данных нет.
bool parseWav(const uint8_t* bytes, size_t size, std::vector<float>& out, WavInfo& info);

// Разбирает AIFF и AIFC: PCM 8/16/24/32 бит (big-endian), AIFC 'sowt' (little-endian PCM) и float 'fl32'/'fl64'.
// Сжатые варианты (ulaw/alaw/ima4 и т.п.) не поддерживаются — вернётся false. Частота — 80-битный extended float.
// Как и parseWav, терпим к обрезанному файлу: если SSND заявляет больше данных, чем есть, берём сколько есть.
bool parseAiff(const uint8_t* bytes, size_t size, std::vector<float>& out, WavInfo& info);

// Определяет контейнер по сигнатуре («RIFF…WAVE» или «FORM…AIFF/AIFC») и вызывает нужный разборщик.
bool parseAudioFile(const uint8_t* bytes, size_t size, std::vector<float>& out, WavInfo& info);

// 16-bit PCM WAV с корректным заголовком.
std::vector<uint8_t> encodeWav16(const float* data, int channels, int64_t frames, int sampleRate);

}  // namespace mpc
