// Host-тесты KAOSS: все программы устойчивы, без касания звук не меняется, ключевые эффекты делают своё.
#ifdef MPC_MINI_GTEST
#include "mini_gtest.h"
#else
#include <gtest/gtest.h>
#endif

#include <cmath>
#include <vector>

#include "KaossFx.h"

using namespace mpc;

namespace {
constexpr int kSr = 48000;

std::vector<float> sine(int frames, float hz, float amp = 0.5f) {
    std::vector<float> v(static_cast<size_t>(frames) * 2);
    for (int i = 0; i < frames; ++i) v[2 * i] = v[2 * i + 1] = amp * std::sin(6.2831853f * hz * i / kSr);
    return v;
}

void run(KaossFx& fx, std::vector<float>& buf, int block = 256, float bpm = 120.0f) {
    const int frames = static_cast<int>(buf.size() / 2);
    for (int i = 0; i < frames; i += block) fx.process(buf.data() + 2 * i, std::min(block, frames - i), bpm);
}
}  // namespace

TEST(KaossTest, EveryProgramIsStableAndBounded) {
    for (int p = 0; p < kKpCount; ++p) {
        for (float xy : {0.0f, 0.5f, 1.0f}) {
            KaossFx fx;
            fx.setSampleRate(kSr);
            fx.setProgram(p);
            fx.setXY(xy, 1.0f - xy);
            fx.setTouch(true);
            auto buf = sine(kSr, 220.0f);
            run(fx, buf);
            fx.setTouch(false);
            auto tail = sine(kSr / 2, 330.0f);
            run(fx, tail);
            float m = 0;
            bool finite = true;
            for (float v : buf) { m = std::max(m, std::fabs(v)); finite = finite && std::isfinite(v); }
            for (float v : tail) { m = std::max(m, std::fabs(v)); finite = finite && std::isfinite(v); }
            EXPECT_TRUE(finite);
            EXPECT_LT(m, 4.0f);
        }
    }
}

TEST(KaossTest, NoTouchIsBitExactPassthrough) {
    for (int p = 0; p < kKpCount; ++p) {
        if (KaossFx::hasTails(p)) continue;   // хвосты дилеев/ревербов
        KaossFx fx;
        fx.setSampleRate(kSr);
        fx.setProgram(p);
        auto ref = sine(10000, 440.0f);
        auto buf = ref;
        run(fx, buf);
        EXPECT_TRUE(buf == ref);
    }
}

TEST(KaossTest, DelayEchoArrivesOnBeat) {
    KaossFx fx;
    fx.setSampleRate(kSr);
    fx.setProgram(kKpDelay);
    fx.setXY(0.7f, 0.0f);   // 0.7*8 -> индекс 5 -> 1 доля = 24000 кадров при 120 BPM
    fx.setTouch(true);
    std::vector<float> buf(2 * 40000, 0.0f);
    buf[2 * 1000] = buf[2 * 1000 + 1] = 1.0f;
    run(fx, buf, 128);
    int peakAt = 0;
    float peak = 0;
    for (int i = 5000; i < 40000; ++i)
        if (std::fabs(buf[2 * i]) > peak) { peak = std::fabs(buf[2 * i]); peakAt = i; }
    EXPECT_GT(peak, 0.15f);
    EXPECT_NEAR(peakAt, 1000 + 24000, 3);
}

TEST(KaossTest, LooperLoopsCapturedBeat) {
    KaossFx fx;
    fx.setSampleRate(kSr);
    fx.setProgram(kKpFreezeLoop);
    fx.setXY(0.6f, 1.0f);   // Looper Beat индекс 4 -> 1/4 доли = 6000 кадров при 120 BPM; Y=1 — только петля
    std::vector<float> warm(2 * 1000, 0.1f);
    run(fx, warm);
    fx.setTouch(true);
    // Вход — нарастающая «пила»: после захвата выход должен повторять первые 4000 кадров.
    const int frames = 20000;
    std::vector<float> buf(2 * frames);
    for (int i = 0; i < frames; ++i) buf[2 * i] = buf[2 * i + 1] = static_cast<float>(i % 9000) / 9000.0f;
    const auto in = buf;
    run(fx, buf, 200);
    // Первый круг записывается, дальше кадр 12500 = копия кадра 12500 % 6000 = 500 входа.
    EXPECT_NEAR(buf[2 * 12500], in[2 * 500], 0.01f);
    EXPECT_NEAR(buf[2 * 16900], in[2 * 4900], 0.01f);
}

TEST(KaossTest, LowpassCutsHighs) {
    KaossFx fx;
    fx.setSampleRate(kSr);
    fx.setProgram(kKpLpf);
    fx.setXY(0.25f, 0.0f);   // ~150 Гц
    fx.setTouch(true);
    auto buf = sine(kSr, 5000.0f);
    run(fx, buf);
    float m = 0;
    for (int i = kSr / 2; i < kSr; ++i) m = std::max(m, std::fabs(buf[2 * i]));
    EXPECT_LT(m, 0.01f);
}

TEST(KaossTest, EchoBreakCutsDrySignal) {
    KaossFx fx;
    fx.setSampleRate(kSr);
    fx.setProgram(kKpEchoBreak);
    fx.setXY(0.7f, 0.5f);   // эхо через 1 долю = 24000 кадров
    fx.setTouch(true);
    auto buf = sine(kSr, 440.0f);
    run(fx, buf);
    float m = 0;
    for (int i = 2000; i < 23000; ++i) m = std::max(m, std::fabs(buf[2 * i]));
    EXPECT_LT(m, 0.01f);   // живой звук оборван, эхо ещё не пришло
    float e = 0;
    for (int i = 25000; i < 30000; ++i) e = std::max(e, std::fabs(buf[2 * i]));
    EXPECT_GT(e, 0.2f);    // а потом слышно эхо
}

TEST(KaossTest, VinylLooperStopsAtCenter) {
    KaossFx fx;
    fx.setSampleRate(kSr);
    fx.setProgram(kKpVinylLoop);
    fx.setXY(0.6f, 0.5f);   // Y в центре — «пластинка» стоит
    fx.setTouch(true);
    auto buf = sine(kSr, 440.0f);
    run(fx, buf);
    float m = 0;
    for (int i = 20000; i < kSr; ++i) m = std::max(m, std::fabs(buf[2 * i]));
    EXPECT_LT(m, 0.01f);
}

TEST(KaossTest, MixZeroIsDry) {
    KaossFx fx;
    fx.setSampleRate(kSr);
    fx.setProgram(kKpDigiTalk);
    fx.setXY(1.0f, 1.0f);
    fx.setMix(0.0f);
    fx.setTouch(true);
    auto ref = sine(kSr, 440.0f);
    auto buf = ref;
    run(fx, buf);
    float d = 0;
    for (int i = kSr / 2; i < kSr; ++i) d = std::max(d, std::fabs(buf[2 * i] - ref[2 * i]));
    EXPECT_LT(d, 1e-3f);
}
