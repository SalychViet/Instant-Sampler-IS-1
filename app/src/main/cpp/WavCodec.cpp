// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

#include "WavCodec.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace mpc {
namespace {

constexpr int64_t kMaxSamples = 1ll << 28;  // ~1 ГБ float — разумный потолок против мусора в заголовке

inline uint32_t rd32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}
inline uint16_t rd16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}
inline void wr32(std::vector<uint8_t>& v, uint32_t x) {
    for (int i = 0; i < 4; ++i) v.push_back(static_cast<uint8_t>((x >> (8 * i)) & 0xFF));
}
inline void wr16(std::vector<uint8_t>& v, uint16_t x) {
    v.push_back(static_cast<uint8_t>(x & 0xFF));
    v.push_back(static_cast<uint8_t>(x >> 8));
}
inline float sane(float x) { return std::isfinite(x) ? x : 0.0f; }

inline uint32_t be32(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
}
inline uint16_t be16(const uint8_t* p) { return static_cast<uint16_t>((p[0] << 8) | p[1]); }

// 80-битное extended (IEEE 754, big-endian) → double. NaN/Inf → 0 (отвергается проверкой частоты).
double extended80(const uint8_t* p) {
    const int exp = ((p[0] & 0x7F) << 8) | p[1];
    uint64_t mant = 0;
    for (int i = 0; i < 8; ++i) mant = (mant << 8) | p[2 + i];
    if (exp == 0x7FFF || (exp == 0 && mant == 0)) return 0.0;
    const double v = std::ldexp(static_cast<double>(mant), exp - 16383 - 63);
    return (p[0] & 0x80) ? -v : v;
}

}  // namespace

bool parseWav(const uint8_t* bytes, size_t size, std::vector<float>& out, WavInfo& info) {
    out.clear();
    info = WavInfo{};
    if (!bytes || size < 12) return false;
    if (std::memcmp(bytes, "RIFF", 4) != 0 || std::memcmp(bytes + 8, "WAVE", 4) != 0) return false;

    size_t pos = 12;
    bool haveFmt = false;
    int format = 0, channels = 0, rate = 0, bits = 0;

    while (pos + 8 <= size) {
        const uint8_t* ck = bytes + pos;
        const uint32_t csz = rd32(ck + 4);
        const size_t body = pos + 8;

        if (std::memcmp(ck, "fmt ", 4) == 0) {
            if (csz < 16 || body + 16 > size) return false;
            format = rd16(bytes + body);
            channels = rd16(bytes + body + 2);
            rate = static_cast<int>(rd32(bytes + body + 4));
            bits = rd16(bytes + body + 14);
            if (format == 0xFFFE && csz >= 40 && body + 26 <= size) format = rd16(bytes + body + 24);
            haveFmt = true;
        } else if (std::memcmp(ck, "data", 4) == 0) {
            if (!haveFmt) return false;
            const bool isFloat = (format == 3);
            const bool isPcm = (format == 1);
            if (channels < 1 || channels > 8 || rate < 1000) return false;
            if (isPcm && !(bits == 8 || bits == 16 || bits == 24 || bits == 32)) return false;
            if (isFloat && !(bits == 32 || bits == 64)) return false;
            if (!isPcm && !isFloat) return false;

            const size_t avail = size - body;
            size_t dsz = (csz == 0 || csz == 0xFFFFFFFFu || csz > avail) ? avail : csz;
            const size_t bytesPerSample = static_cast<size_t>(bits / 8);
            const size_t frameBytes = bytesPerSample * static_cast<size_t>(channels);
            const int64_t frames = static_cast<int64_t>(dsz / frameBytes);
            if (frames <= 0 || frames * channels > kMaxSamples) return false;

            const uint8_t* d = bytes + body;
            const int64_t total = frames * channels;
            out.resize(static_cast<size_t>(total));
            for (int64_t i = 0; i < total; ++i) {
                const uint8_t* s = d + static_cast<size_t>(i) * bytesPerSample;
                float v = 0.0f;
                if (isFloat && bits == 32) {
                    float f;
                    std::memcpy(&f, s, 4);
                    v = sane(f);
                } else if (isFloat) {
                    double f;
                    std::memcpy(&f, s, 8);
                    v = sane(static_cast<float>(f));
                } else if (bits == 8) {
                    v = (static_cast<int>(s[0]) - 128) / 128.0f;
                } else if (bits == 16) {
                    v = static_cast<int16_t>(rd16(s)) / 32768.0f;
                } else if (bits == 24) {
                    int32_t x = s[0] | (s[1] << 8) | (s[2] << 16);
                    if (x & 0x800000) x |= ~0xFFFFFF;           // знаковое расширение
                    v = static_cast<float>(x) / 8388608.0f;
                } else {
                    v = static_cast<float>(static_cast<int32_t>(rd32(s)) / 2147483648.0);
                }
                out[static_cast<size_t>(i)] = v;
            }
            info.channels = channels;
            info.sampleRate = rate;
            info.bitsPerSample = bits;
            info.frames = frames;
            info.floatData = isFloat;
            return true;
        }

        const uint64_t next = static_cast<uint64_t>(body) + csz + (csz & 1u);  // чанки выровнены на 2 байта
        if (next > size) break;
        pos = static_cast<size_t>(next);
    }
    return false;
}

bool parseAiff(const uint8_t* bytes, size_t size, std::vector<float>& out, WavInfo& info) {
    out.clear();
    info = WavInfo{};
    if (!bytes || size < 12) return false;
    if (std::memcmp(bytes, "FORM", 4) != 0) return false;
    const bool aifc = std::memcmp(bytes + 8, "AIFC", 4) == 0;
    if (!aifc && std::memcmp(bytes + 8, "AIFF", 4) != 0) return false;

    size_t pos = 12;
    bool haveComm = false;
    int channels = 0, bits = 0;
    uint32_t declaredFrames = 0;
    double rate = 0.0;
    bool littleEndian = false, isFloat = false;

    while (pos + 8 <= size) {
        const uint8_t* ck = bytes + pos;
        const uint32_t csz = be32(ck + 4);
        const size_t body = pos + 8;

        if (std::memcmp(ck, "COMM", 4) == 0) {
            if (csz < 18 || body + 18 > size) return false;
            channels = be16(bytes + body);
            declaredFrames = be32(bytes + body + 2);
            bits = be16(bytes + body + 6);
            rate = extended80(bytes + body + 8);
            if (aifc) {
                if (csz < 22 || body + 22 > size) return false;
                const uint8_t* t = bytes + body + 18;
                if (std::memcmp(t, "NONE", 4) == 0 || std::memcmp(t, "twos", 4) == 0) {
                } else if (std::memcmp(t, "sowt", 4) == 0) {
                    littleEndian = true;
                } else if (std::memcmp(t, "fl32", 4) == 0 || std::memcmp(t, "FL32", 4) == 0) {
                    isFloat = true;
                    bits = 32;
                } else if (std::memcmp(t, "fl64", 4) == 0 || std::memcmp(t, "FL64", 4) == 0) {
                    isFloat = true;
                    bits = 64;
                } else {
                    return false;  // ulaw / alaw / ima4 / ...
                }
            }
            haveComm = true;
        } else if (std::memcmp(ck, "SSND", 4) == 0) {
            if (!haveComm || csz < 8 || body + 8 > size) return false;
            if (channels < 1 || channels > 8 || !(rate >= 1000.0 && rate <= 768000.0)) return false;
            if (bits < 1 || bits > 32) {
                if (!(isFloat && bits == 64)) return false;
            }
            const uint32_t offset = be32(bytes + body);
            const size_t dataStart = body + 8 + static_cast<size_t>(offset);
            if (dataStart > size) return false;
            const size_t avail = size - dataStart;
            const size_t declared = csz - 8 > offset ? static_cast<size_t>(csz - 8 - offset) : 0;
            size_t dsz = (declared == 0 || declared > avail) ? avail : declared;

            const size_t bytesPerSample = isFloat ? static_cast<size_t>(bits / 8)
                                                  : static_cast<size_t>((bits + 7) / 8);
            const size_t frameBytes = bytesPerSample * static_cast<size_t>(channels);
            int64_t frames = static_cast<int64_t>(dsz / frameBytes);
            if (declaredFrames > 0 && static_cast<int64_t>(declaredFrames) < frames) frames = declaredFrames;
            if (frames <= 0 || frames * channels > kMaxSamples) return false;

            const uint8_t* d = bytes + dataStart;
            const int64_t total = frames * channels;
            out.resize(static_cast<size_t>(total));
            for (int64_t i = 0; i < total; ++i) {
                const uint8_t* s = d + static_cast<size_t>(i) * bytesPerSample;
                float v = 0.0f;
                if (isFloat) {
                    if (bits == 32) {
                        const uint32_t u = be32(s);
                        float f;
                        std::memcpy(&f, &u, 4);
                        v = sane(f);
                    } else {
                        uint64_t u = (static_cast<uint64_t>(be32(s)) << 32) | be32(s + 4);
                        double f;
                        std::memcpy(&f, &u, 8);
                        v = sane(static_cast<float>(f));
                    }
                } else {
                    // Знаковое целое, выровненное по старшим битам контейнера (AIFF-соглашение).
                    uint32_t u = 0;
                    for (size_t b = 0; b < bytesPerSample; ++b) {
                        const uint8_t byte = littleEndian ? s[bytesPerSample - 1 - b] : s[b];
                        u = (u << 8) | byte;
                    }
                    const int shift = static_cast<int>(32 - 8 * bytesPerSample);
                    const int32_t x = static_cast<int32_t>(u << shift);   // знак в старшем бите
                    v = static_cast<float>(x / 2147483648.0);
                }
                out[static_cast<size_t>(i)] = v;
            }
            info.channels = channels;
            info.sampleRate = static_cast<int>(std::lround(rate));
            info.bitsPerSample = bits;
            info.frames = frames;
            info.floatData = isFloat;
            return true;
        }

        const uint64_t next = static_cast<uint64_t>(body) + csz + (csz & 1u);
        if (next > size) break;
        pos = static_cast<size_t>(next);
    }
    return false;
}

bool parseAudioFile(const uint8_t* bytes, size_t size, std::vector<float>& out, WavInfo& info) {
    if (!bytes || size < 12) return false;
    if (std::memcmp(bytes, "RIFF", 4) == 0) return parseWav(bytes, size, out, info);
    if (std::memcmp(bytes, "FORM", 4) == 0) return parseAiff(bytes, size, out, info);
    out.clear();
    info = WavInfo{};
    return false;
}

std::vector<uint8_t> encodeWav16(const float* data, int channels, int64_t frames, int sampleRate) {
    std::vector<uint8_t> v;
    if (!data || channels < 1 || frames < 0 || sampleRate < 1) return v;
    const uint32_t dataBytes = static_cast<uint32_t>(frames * channels * 2);
    v.reserve(44 + dataBytes);
    v.insert(v.end(), {'R', 'I', 'F', 'F'});
    wr32(v, 36 + dataBytes);
    v.insert(v.end(), {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
    wr32(v, 16);
    wr16(v, 1);  // PCM
    wr16(v, static_cast<uint16_t>(channels));
    wr32(v, static_cast<uint32_t>(sampleRate));
    wr32(v, static_cast<uint32_t>(sampleRate * channels * 2));
    wr16(v, static_cast<uint16_t>(channels * 2));
    wr16(v, 16);
    v.insert(v.end(), {'d', 'a', 't', 'a'});
    wr32(v, dataBytes);
    const int64_t total = frames * channels;
    for (int64_t i = 0; i < total; ++i) {
        const float x = std::clamp(sane(data[i]), -1.0f, 1.0f);
        const int s = std::clamp(static_cast<int>(std::lround(x * 32768.0f)), -32768, 32767);
        wr16(v, static_cast<uint16_t>(static_cast<int16_t>(s)));
    }
    return v;
}

}  // namespace mpc
