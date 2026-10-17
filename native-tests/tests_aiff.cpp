// Host-тесты разбора AIFF/AIFC и определения контейнера.
#ifdef MPC_MINI_GTEST
#include "mini_gtest.h"
#else
#include <gtest/gtest.h>
#endif

#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#include "WavCodec.h"

using namespace mpc;

namespace {

using Bytes = std::vector<uint8_t>;

void be32(Bytes& v, uint32_t x) { for (int i = 3; i >= 0; --i) v.push_back(static_cast<uint8_t>((x >> (8 * i)) & 0xFF)); }
void be16(Bytes& v, uint16_t x) { v.push_back(static_cast<uint8_t>(x >> 8)); v.push_back(static_cast<uint8_t>(x & 0xFF)); }
void tag(Bytes& v, const char* t) { v.insert(v.end(), t, t + 4); }

// 80-битное extended для целых положительных частот.
void ext80(Bytes& v, double rate) {
    int e = 0;
    const double m = std::frexp(rate, &e);                          // rate = m * 2^e, m ∈ [0.5, 1)
    const uint64_t mant = static_cast<uint64_t>(std::ldexp(m, 64));  // старший бит мантиссы = 1
    const uint16_t exp = static_cast<uint16_t>(e - 1 + 16383);
    be16(v, exp);
    for (int i = 7; i >= 0; --i) v.push_back(static_cast<uint8_t>((mant >> (8 * i)) & 0xFF));
}

struct AiffSpec {
    bool aifc = false;
    const char* compression = "NONE";   // только для AIFC
    int channels = 1;
    int bits = 16;
    int rate = 44100;
    bool littleEndian = false;          // для 'sowt': отсчёты в little-endian
    bool floatData = false;
    uint32_t ssndOffset = 0;
    bool extraOddChunk = false;         // посторонний чанк нечётной длины перед SSND (проверка выравнивания)
    int declaredFramesOverride = -1;
};

// samples: interleaved float; кодируются согласно spec.
Bytes makeAiff(const AiffSpec& s, const std::vector<float>& samples) {
    const int frames = s.channels > 0 ? static_cast<int>(samples.size()) / s.channels : 0;
    Bytes data;
    for (float f : samples) {
        if (s.floatData) {
            if (s.bits == 32) {
                uint32_t u; std::memcpy(&u, &f, 4); be32(data, u);
            } else {
                double d = f; uint64_t u; std::memcpy(&u, &d, 8);
                be32(data, static_cast<uint32_t>(u >> 32)); be32(data, static_cast<uint32_t>(u & 0xFFFFFFFFu));
            }
            continue;
        }
        const int bytes = (s.bits + 7) / 8;
        const double scale = std::ldexp(1.0, 8 * bytes - 1);
        long long x = std::llround(f * (scale - 1));
        for (int b = 0; b < bytes; ++b) {
            const int idx = s.littleEndian ? b : bytes - 1 - b;
            data.push_back(static_cast<uint8_t>((x >> (8 * idx)) & 0xFF));
        }
    }

    Bytes comm;
    be16(comm, static_cast<uint16_t>(s.channels));
    be32(comm, static_cast<uint32_t>(s.declaredFramesOverride >= 0 ? s.declaredFramesOverride : frames));
    be16(comm, static_cast<uint16_t>(s.bits));
    ext80(comm, s.rate);
    if (s.aifc) {
        tag(comm, s.compression);
        comm.push_back(0);   // pstring: пустое имя (длина 0 + добивка до чётного)
        comm.push_back(0);
    }

    Bytes body;
    tag(body, s.aifc ? "AIFC" : "AIFF");
    tag(body, "COMM"); be32(body, static_cast<uint32_t>(comm.size())); body.insert(body.end(), comm.begin(), comm.end());
    if (s.extraOddChunk) {
        tag(body, "NAME"); be32(body, 5);
        body.insert(body.end(), {'h', 'e', 'l', 'l', 'o', 0});   // 5 байт + байт выравнивания
    }
    tag(body, "SSND");
    be32(body, static_cast<uint32_t>(8 + s.ssndOffset + data.size()));
    be32(body, s.ssndOffset);
    be32(body, 0);
    for (uint32_t i = 0; i < s.ssndOffset; ++i) body.push_back(0xEE);
    body.insert(body.end(), data.begin(), data.end());

    Bytes file;
    tag(file, "FORM");
    be32(file, static_cast<uint32_t>(body.size()));
    file.insert(file.end(), body.begin(), body.end());
    return file;
}

std::vector<float> ramp(int n, int channels = 1) {
    std::vector<float> v;
    for (int i = 0; i < n; ++i)
        for (int c = 0; c < channels; ++c)
            v.push_back(std::sin(0.05f * static_cast<float>(i)) * (c == 0 ? 0.8f : -0.4f));
    return v;
}

void expectNear(const std::vector<float>& a, const std::vector<float>& b, float eps) {
    ASSERT_EQ(a.size(), b.size());
    float worst = 0;
    for (size_t i = 0; i < a.size(); ++i) worst = std::max(worst, std::fabs(a[i] - b[i]));
    EXPECT_LE(worst, eps);
}

}  // namespace

TEST(AiffTest, Pcm16BigEndianStereo) {
    AiffSpec s; s.channels = 2; s.bits = 16; s.rate = 44100;
    const auto src = ramp(500, 2);
    std::vector<float> out; WavInfo info;
    const Bytes f = makeAiff(s, src);
    ASSERT_TRUE(parseAiff(f.data(), f.size(), out, info));
    EXPECT_EQ(info.channels, 2);
    EXPECT_EQ(info.sampleRate, 44100);
    EXPECT_EQ(info.frames, 500);
    expectNear(out, src, 1.0f / 16384.0f);
}

TEST(AiffTest, Pcm24And32AndMono) {
    for (int bits : {24, 32}) {
        AiffSpec s; s.channels = 1; s.bits = bits; s.rate = 48000;
        const auto src = ramp(300);
        const Bytes f = makeAiff(s, src);
        std::vector<float> out; WavInfo info;
        ASSERT_TRUE(parseAiff(f.data(), f.size(), out, info));
        EXPECT_EQ(info.sampleRate, 48000);
        expectNear(out, src, 1e-4f);
    }
}

TEST(AiffTest, Pcm8BitIsSigned) {
    AiffSpec s; s.bits = 8; s.rate = 22050;
    const std::vector<float> src = {0.0f, 0.5f, -0.5f, 0.99f, -0.99f};
    const Bytes f = makeAiff(s, src);
    std::vector<float> out; WavInfo info;
    ASSERT_TRUE(parseAiff(f.data(), f.size(), out, info));
    EXPECT_EQ(info.sampleRate, 22050);
    EXPECT_NEAR(out[0], 0.0f, 0.01f);
    EXPECT_NEAR(out[1], 0.5f, 0.02f);
    EXPECT_NEAR(out[2], -0.5f, 0.02f);
    EXPECT_GT(out[3], 0.95f);
    EXPECT_LT(out[4], -0.95f);
}

TEST(AiffTest, TwelveBitLeftJustifiedInTwoBytes) {
    AiffSpec s; s.bits = 12;
    // 12 бит лежат в 16-битном контейнере, выровнены по старшим битам
    Bytes f = makeAiff(s, {0.5f, -0.5f});
    std::vector<float> out; WavInfo info;
    ASSERT_TRUE(parseAiff(f.data(), f.size(), out, info));
    EXPECT_EQ(out.size(), 2u);
}

TEST(AiffTest, AifcNoneIsBigEndianPcm) {
    AiffSpec s; s.aifc = true; s.compression = "NONE"; s.channels = 2; s.bits = 16;
    const auto src = ramp(200, 2);
    const Bytes f = makeAiff(s, src);
    std::vector<float> out; WavInfo info;
    ASSERT_TRUE(parseAiff(f.data(), f.size(), out, info));
    expectNear(out, src, 1.0f / 16384.0f);
}

TEST(AiffTest, AifcSowtIsLittleEndianPcm) {
    AiffSpec s; s.aifc = true; s.compression = "sowt"; s.littleEndian = true; s.channels = 2; s.bits = 16;
    const auto src = ramp(200, 2);
    const Bytes f = makeAiff(s, src);
    std::vector<float> out; WavInfo info;
    ASSERT_TRUE(parseAiff(f.data(), f.size(), out, info));
    expectNear(out, src, 1.0f / 16384.0f);
}

TEST(AiffTest, AifcFloat32And64) {
    for (int bits : {32, 64}) {
        AiffSpec s; s.aifc = true; s.compression = bits == 32 ? "fl32" : "fl64"; s.floatData = true; s.bits = bits;
        const auto src = ramp(100);
        const Bytes f = makeAiff(s, src);
        std::vector<float> out; WavInfo info;
        ASSERT_TRUE(parseAiff(f.data(), f.size(), out, info));
        EXPECT_TRUE(info.floatData);
        expectNear(out, src, 1e-6f);
    }
}

TEST(AiffTest, CompressedAifcIsRejected) {
    for (const char* c : {"ulaw", "alaw", "ima4", "MAC3"}) {
        AiffSpec s; s.aifc = true; s.compression = c; s.bits = 16;
        const Bytes f = makeAiff(s, ramp(50));
        std::vector<float> out; WavInfo info;
        EXPECT_FALSE(parseAiff(f.data(), f.size(), out, info));
    }
}

TEST(AiffTest, SampleRatesFromExtendedFloat) {
    for (int rate : {8000, 11025, 22050, 32000, 44100, 48000, 88200, 96000, 192000}) {
        AiffSpec s; s.rate = rate;
        const Bytes f = makeAiff(s, ramp(40));
        std::vector<float> out; WavInfo info;
        ASSERT_TRUE(parseAiff(f.data(), f.size(), out, info));
        EXPECT_EQ(info.sampleRate, rate);
    }
}

TEST(AiffTest, SkipsForeignChunksWithOddLengthPadding) {
    AiffSpec s; s.extraOddChunk = true;
    const auto src = ramp(120);
    const Bytes f = makeAiff(s, src);
    std::vector<float> out; WavInfo info;
    ASSERT_TRUE(parseAiff(f.data(), f.size(), out, info));
    expectNear(out, src, 1.0f / 16384.0f);
}

TEST(AiffTest, HonorsSsndOffset) {
    AiffSpec s; s.ssndOffset = 6;
    const auto src = ramp(120);
    const Bytes f = makeAiff(s, src);
    std::vector<float> out; WavInfo info;
    ASSERT_TRUE(parseAiff(f.data(), f.size(), out, info));
    expectNear(out, src, 1.0f / 16384.0f);
}

TEST(AiffTest, TruncatedFileKeepsAvailableFrames) {
    AiffSpec s; s.channels = 2;
    Bytes f = makeAiff(s, ramp(1000, 2));
    f.resize(f.size() - 1000);   // оборванный файл
    std::vector<float> out; WavInfo info;
    ASSERT_TRUE(parseAiff(f.data(), f.size(), out, info));
    EXPECT_GT(info.frames, 0);
    EXPECT_LT(info.frames, 1000);
    EXPECT_EQ(out.size(), static_cast<size_t>(info.frames) * 2);
}

TEST(AiffTest, DeclaredFrameCountLargerThanDataIsClamped) {
    AiffSpec s; s.declaredFramesOverride = 1000000;
    const Bytes f = makeAiff(s, ramp(100));
    std::vector<float> out; WavInfo info;
    ASSERT_TRUE(parseAiff(f.data(), f.size(), out, info));
    EXPECT_EQ(info.frames, 100);
}

TEST(AiffTest, DeclaredFrameCountSmallerThanDataWins) {
    AiffSpec s; s.declaredFramesOverride = 50;
    const Bytes f = makeAiff(s, ramp(100));
    std::vector<float> out; WavInfo info;
    ASSERT_TRUE(parseAiff(f.data(), f.size(), out, info));
    EXPECT_EQ(info.frames, 50);
}

TEST(AiffTest, GarbageAndTruncatedHeadersAreRejectedSafely) {
    std::vector<float> out; WavInfo info;
    EXPECT_FALSE(parseAiff(nullptr, 0, out, info));
    const Bytes tiny = {'F', 'O', 'R', 'M'};
    EXPECT_FALSE(parseAiff(tiny.data(), tiny.size(), out, info));
    Bytes junk(200, 0x55);
    EXPECT_FALSE(parseAiff(junk.data(), junk.size(), out, info));

    AiffSpec s;
    const Bytes good = makeAiff(s, ramp(50));
    for (size_t cut = 0; cut < 40; ++cut) {   // любые обрезки заголовка — без падения
        std::vector<float> o; WavInfo i;
        parseAiff(good.data(), cut, o, i);
    }
    EXPECT_TRUE(true);
}

TEST(AiffTest, InvalidFieldsAreRejected) {
    std::vector<float> out; WavInfo info;
    { AiffSpec s; s.channels = 0; const Bytes f = makeAiff(s, {}); EXPECT_FALSE(parseAiff(f.data(), f.size(), out, info)); }
    { AiffSpec s; s.rate = 100;  const Bytes f = makeAiff(s, ramp(10)); EXPECT_FALSE(parseAiff(f.data(), f.size(), out, info)); }
    { AiffSpec s; s.bits = 0;    const Bytes f = makeAiff(s, ramp(10)); EXPECT_FALSE(parseAiff(f.data(), f.size(), out, info)); }
    { AiffSpec s; s.bits = 40;   const Bytes f = makeAiff(s, ramp(10)); EXPECT_FALSE(parseAiff(f.data(), f.size(), out, info)); }
}

TEST(AiffTest, WavIsNotAiffAndViceVersa) {
    const Bytes aiff = makeAiff(AiffSpec{}, ramp(50));
    std::vector<float> out; WavInfo info;
    EXPECT_FALSE(parseWav(aiff.data(), aiff.size(), out, info));
    const std::vector<uint8_t> wav = encodeWav16(ramp(50).data(), 1, 50, 44100);
    EXPECT_FALSE(parseAiff(wav.data(), wav.size(), out, info));
}

TEST(AudioFileTest, DispatchesByContainerSignature) {
    const auto src = ramp(64);
    std::vector<float> out; WavInfo info;
    const std::vector<uint8_t> wav = encodeWav16(src.data(), 1, 64, 32000);
    ASSERT_TRUE(parseAudioFile(wav.data(), wav.size(), out, info));
    EXPECT_EQ(info.sampleRate, 32000);
    const Bytes aiff = makeAiff(AiffSpec{}, src);
    ASSERT_TRUE(parseAudioFile(aiff.data(), aiff.size(), out, info));
    EXPECT_EQ(info.sampleRate, 44100);
    const Bytes mp3 = {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    EXPECT_FALSE(parseAudioFile(mp3.data(), mp3.size(), out, info));
    EXPECT_TRUE(out.empty());
}
