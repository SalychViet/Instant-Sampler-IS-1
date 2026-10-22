#include "ExportFile.h"

#include <algorithm>
#include <cstdio>
#include <vector>
#include <lame.h>

namespace mpc {

namespace {

void put32(uint8_t* p, uint32_t v) {
    p[0] = v & 0xFF; p[1] = (v >> 8) & 0xFF; p[2] = (v >> 16) & 0xFF; p[3] = (v >> 24) & 0xFF;
}
void put16(uint8_t* p, uint16_t v) {
    p[0] = v & 0xFF; p[1] = (v >> 8) & 0xFF;
}

}  // namespace

bool writeWav16File(const char* path, const int16_t* data, int frames, int sampleRate) {
    if (!path || !data || frames <= 0) return false;
    FILE* f = std::fopen(path, "wb");
    if (!f) return false;
    const uint32_t bytes = static_cast<uint32_t>(frames) * 4;
    uint8_t h[44] = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E', 'f', 'm', 't', ' '};
    put32(h + 4, 36 + bytes);
    put32(h + 16, 16);
    put16(h + 20, 1);                                       // PCM
    put16(h + 22, 2);                                       // стерео
    put32(h + 24, static_cast<uint32_t>(sampleRate));
    put32(h + 28, static_cast<uint32_t>(sampleRate) * 4);   // байт в секунду
    put16(h + 32, 4);
    put16(h + 34, 16);
    h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
    put32(h + 40, bytes);
    bool ok = std::fwrite(h, 1, sizeof(h), f) == sizeof(h);
    // Данные уже little-endian (ARM/x86).
    ok = ok && std::fwrite(data, 4, static_cast<size_t>(frames), f) == static_cast<size_t>(frames);
    ok = (std::fclose(f) == 0) && ok;
    return ok;
}

bool writeMp3File(const char* path, const int16_t* data, int frames, int sampleRate, int kbps) {
    if (!path || !data || frames <= 0) return false;
    lame_t lame = lame_init();
    if (!lame) return false;
    lame_set_in_samplerate(lame, sampleRate);
    lame_set_out_samplerate(lame, sampleRate);
    lame_set_num_channels(lame, 2);
    lame_set_mode(lame, JOINT_STEREO);
    lame_set_brate(lame, kbps);
    lame_set_VBR(lame, vbr_off);
    lame_set_quality(lame, 2);   // 0 — лучшее и медленное, 2 — почти лучшее
    if (lame_init_params(lame) < 0) {
        lame_close(lame);
        return false;
    }
    FILE* f = std::fopen(path, "wb");
    if (!f) {
        lame_close(lame);
        return false;
    }
    const int chunk = 8192;
    std::vector<unsigned char> buf(static_cast<size_t>(1.25 * chunk + 7200));
    bool ok = true;
    for (int pos = 0; ok && pos < frames; pos += chunk) {
        const int n = std::min(chunk, frames - pos);
        // const_cast: LAME не меняет входные данные, просто старый API без const.
        const int w = lame_encode_buffer_interleaved(lame, const_cast<short*>(data + 2 * static_cast<size_t>(pos)), n,
                                                     buf.data(), static_cast<int>(buf.size()));
        ok = w >= 0 && std::fwrite(buf.data(), 1, static_cast<size_t>(w), f) == static_cast<size_t>(w);
    }
    if (ok) {
        const int w = lame_encode_flush(lame, buf.data(), static_cast<int>(buf.size()));
        ok = w >= 0 && std::fwrite(buf.data(), 1, static_cast<size_t>(w), f) == static_cast<size_t>(w);
    }
    lame_close(lame);
    ok = (std::fclose(f) == 0) && ok;
    return ok;
}

}  // namespace mpc
