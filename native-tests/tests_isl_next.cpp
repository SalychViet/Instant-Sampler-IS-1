// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

// ISL-1: EQ пада, дозапись без фазинга, iLOOPER, приоритет секвенсора над петлями.
#include <algorithm>
#include <cmath>
#include <memory>
#include <vector>

#include "EngineCore.h"
#ifdef MPC_MINI_GTEST
#include "mini_gtest.h"
#else
#include <gtest/gtest.h>
#endif

using namespace mpc;

namespace {

constexpr int kSr = 48000;
constexpr float kCenter = 0.70710678f;   // pan по центру

std::unique_ptr<Sample> constant(int frames, float v) {
    auto s = std::make_unique<Sample>();
    s->frames = frames;
    s->sampleRate = kSr;
    s->data.assign(static_cast<size_t>(frames) * 2, v);
    return s;
}

std::unique_ptr<Sample> sine(float hz, float amp) {
    auto s = constant(kSr, 0.0f);
    for (int i = 0; i < kSr; ++i) {
        const float v = amp * std::sin(2.0f * 3.14159265f * hz * static_cast<float>(i) / kSr);
        s->data[2 * i] = s->data[2 * i + 1] = v;
    }
    return s;
}

std::vector<float> run(EngineCore& core, int frames) {
    std::vector<float> buf(2 * 256), left;
    for (int done = 0; done < frames; done += 256) {
        core.render(buf.data(), 256);
        for (int i = 0; i < 256; ++i) left.push_back(buf[2 * i]);
    }
    return left;
}

float peakOf(const std::vector<float>& v, int from) {
    float p = 0.0f;
    for (size_t i = static_cast<size_t>(from); i < v.size(); ++i) p = std::max(p, std::fabs(v[i]));
    return p;
}

float sinePeakThrough(float hz, void (*setup)(EngineCore&)) {
    EngineCore core;
    core.setSampleRate(kSr);
    core.loadSample(0, sine(hz, 0.2f));
    setup(core);
    core.noteOn(0, 1.0f);
    return peakOf(run(core, 24000), 12000);
}

}  // namespace

TEST(PadEqTest, SlopesAndBell) {
    const float flat = sinePeakThrough(1000.0f, [](EngineCore&) {});
    EXPECT_NEAR(flat, 0.2f * kCenter, 0.01f);
    const float hp = sinePeakThrough(100.0f, [](EngineCore& c) {
        c.setParam(0, kParamEqHpHz, 1000.0f);
        c.setParam(0, kParamEqHpOn, 1.0f);
    });
    EXPECT_LT(hp, flat * 0.03f);   // на три с лишним октавы ниже среза: 12 дБ/окт
    const float lp = sinePeakThrough(8000.0f, [](EngineCore& c) {
        c.setParam(0, kParamEqLpHz, 500.0f);
        c.setParam(0, kParamEqLpOn, 1.0f);
    });
    EXPECT_LT(lp, flat * 0.02f);
    const float bell = sinePeakThrough(1000.0f, [](EngineCore& c) {
        c.setParam(0, kParamEqBellHz, 1000.0f);
        c.setParam(0, kParamEqBellDb, 12.0f);
    });
    EXPECT_NEAR(bell / flat, 3.98f, 0.1f);
}

TEST(PadEqTest, ChangesReachPlayingLoop) {
    EngineCore core;
    core.setSampleRate(kSr);
    core.loadSample(0, sine(100.0f, 0.2f));
    core.setParam(0, kParamMode, 2.0f);
    core.noteOn(0, 1.0f);
    run(core, 4800);
    core.setParam(0, kParamEqHpHz, 2000.0f);
    core.setParam(0, kParamEqHpOn, 1.0f);
    EXPECT_LT(peakOf(run(core, 12000), 6000), 0.2f * kCenter * 0.02f);
}

TEST(OverdubTest, LateHitIsSilencedAndNoteStaysInPattern) {
    EngineCore core;
    core.setSampleRate(kSr);
    core.loadSample(0, constant(kSr, 0.1f));
    core.loadSample(1, constant(kSr, 0.1f));
    core.seq(SeqOp::SetBpm, 0, 0, 0, 0, 120.0f);
    core.seq(SeqOp::ToggleStep, 0, 0, 0, 24, 1.0f);
    core.seq(SeqOp::Play);
    core.seq(SeqOp::SetRecording, 0, 0, 1);
    run(core, 256 * 2);                       // нота тика 0 уже звучит
    EXPECT_EQ(core.activeVoiceCount(), 1);
    core.noteOn(0, 0.5f);                     // тот же звук на 10 мс позже
    run(core, 256);
    EXPECT_EQ(core.activeVoiceCount(), 1);    // живой удар не добавился
    core.noteOff(0);
    core.noteOn(1, 0.5f);                     // нового звука в паттерне нет — слышно сразу
    run(core, 256);
    EXPECT_EQ(core.activeVoiceCount(), 2);
    EXPECT_EQ(core.seqPatternCount(0), 2);
}

TEST(OverdubTest, EarlyHitPlaysFromPatternOnce) {
    EngineCore core;
    core.setSampleRate(kSr);
    core.loadSample(0, constant(kSr, 0.1f));
    core.seq(SeqOp::SetBpm, 0, 0, 0, 0, 120.0f);
    core.seq(SeqOp::ToggleStep, 0, 0, 96, 24, 1.0f);   // вторая доля: кадр 24000
    core.seq(SeqOp::Play);
    core.seq(SeqOp::SetRecording, 0, 0, 1);
    run(core, 24000 - 512);
    core.noteOn(0, 1.0f);                              // за 10 мс до ноты
    run(core, 256);
    EXPECT_EQ(core.activeVoiceCount(), 0);
    run(core, 512);
    EXPECT_EQ(core.activeVoiceCount(), 1);             // прозвучала записанная, а не две
}

TEST(LoopSectionTest, IsolatedTakeHasNoLoops) {
    EngineCore core;
    core.setSampleRate(kSr);
    core.loadSample(0, constant(kSr, 0.4f));
    core.setParam(0, kParamMode, 2.0f);
    core.loadSample(1, constant(kSr, 0.2f));
    core.noteOn(0, 1.0f);
    core.noteOn(1, 1.0f);
    run(core, 2048);
    core.resampleStart(true);
    const auto out = run(core, 2048);
    const int n = core.resampleStop();
    std::vector<float> take(2 * static_cast<size_t>(n));
    core.resampleRead(take.data(), n);
    EXPECT_NEAR(take[static_cast<size_t>(n)], 0.2f * kCenter, 0.005f);       // только one-shot
    EXPECT_NEAR(out.back(), 0.6f * kCenter, 0.005f);                         // а слышно всё
}

TEST(LoopSectionTest, LoopsDrySkipKaossButOneShotsDoNot) {
    const auto render = [](bool dry, bool loop) {
        EngineCore core;
        core.setSampleRate(kSr);
        core.loadSample(0, sine(997.0f, 0.3f));
        if (loop) core.setParam(0, kParamMode, 2.0f);
        core.setLoopsDry(dry);
        core.fx().setProgram(0);
        core.fx().setXY(0.1f, 0.9f);
        core.fx().setTouch(true);
        core.noteOn(0, 1.0f);
        return run(core, 24000);
    };
    const auto clean = [] {
        EngineCore core;
        core.setSampleRate(kSr);
        core.loadSample(0, sine(997.0f, 0.3f));
        core.setParam(0, kParamMode, 2.0f);
        core.noteOn(0, 1.0f);
        return run(core, 24000);
    }();
    const auto diff = [&clean](const std::vector<float>& v) {
        float d = 0.0f;
        for (size_t i = 12000; i < v.size(); ++i) d = std::max(d, std::fabs(v[i] - clean[i]));
        return d;
    };
    EXPECT_LT(diff(render(true, true)), 0.001f);     // петля осталась сухой
    EXPECT_GT(diff(render(false, true)), 0.02f);     // без iLOOPER KAOSS на петле слышен
    EXPECT_GT(diff(render(true, false)), 0.02f);     // one-shot эффект получает
}

TEST(LoopSectionTest, LoopsDryOneShotKeepsKaossNextToPlayingLoop) {
    const auto render = [](bool fx) {
        EngineCore core;
        core.setSampleRate(kSr);
        core.loadSample(0, sine(220.0f, 0.3f));
        core.setParam(0, kParamMode, 2.0f);
        core.loadSample(1, sine(997.0f, 0.3f));
        core.setLoopsDry(true);
        if (fx) {
            core.fx().setProgram(0);
            core.fx().setXY(0.1f, 0.9f);
            core.fx().setTouch(true);
        }
        core.noteOn(0, 1.0f);
        core.noteOn(1, 1.0f);
        return run(core, 24000);
    };
    const auto clean = render(false), wet = render(true);
    float d = 0.0f;
    for (size_t i = 12000; i < clean.size(); ++i) d = std::max(d, std::fabs(clean[i] - wet[i]));
    EXPECT_GT(d, 0.02f);
}

TEST(LiveRecTest, StreamsMasterIncludingLoopsAndDetectsOverrun) {
    EngineCore core;
    core.setSampleRate(kSr);
    core.loadSample(0, constant(kSr, 0.4f));
    core.setParam(0, kParamMode, 2.0f);   // петля с касания
    core.loadSample(1, constant(kSr, 0.2f));
    core.noteOn(0, 1.0f);
    core.noteOn(1, 1.0f);
    run(core, 1024);
    std::vector<float> buf(2 * 4096);
    EXPECT_EQ(core.liveRead(buf.data(), 4096), 0);   // до старта ничего
    core.liveStart();
    const auto out = run(core, 2048);
    EXPECT_EQ(core.liveRead(buf.data(), 4096), 2048);
    EXPECT_NEAR(buf[2 * 2047], out.back(), 1e-6f);   // ровно то, что слышно
    EXPECT_NEAR(buf[2 * 2047], 0.6f * kCenter, 0.005f);
    run(core, kSr * 25);                              // никто не читал 25 с — кольцо переполнилось
    EXPECT_EQ(core.liveRead(buf.data(), 4096), -1);
    core.liveStop();
    run(core, 1024);
    EXPECT_EQ(core.liveRead(buf.data(), 4096), 0);
}

TEST(LoopSectionTest, MixTakeKeepsLevelWhenPlayedBack) {
    EngineCore core;
    core.setSampleRate(kSr);
    core.loadSample(0, constant(kSr, 0.4f));
    core.noteOn(0, 1.0f);
    run(core, 2048);
    core.resampleStart();
    const auto out = run(core, 2048);
    const int n = core.resampleStop();
    std::vector<float> take(2 * static_cast<size_t>(n));
    core.resampleRead(take.data(), n);
    EXPECT_NEAR(take[static_cast<size_t>(n)], 0.4f * kCenter, 0.002f);       // без лишнего множителя громкости
}

TEST(LoopSectionTest, LoopsYieldToSequencer) {
    LoopDucker d;
    d.setSampleRate(kSr);
    std::vector<float> seq(2 * 1000, 0.8f), loops(2 * 1000, 0.8f);
    d.mix(seq.data(), loops.data(), 1000);
    EXPECT_NEAR(seq[1998], 0.98f, 0.001f);   // 0.8 секвенсора целы, петля заняла остаток
}

TEST(LoopSectionTest, DuckSilencesSequencerButNotLivePads) {
    EngineCore core;
    core.setSampleRate(kSr);
    core.loadSample(0, constant(kSr, 0.4f));
    core.loadSample(1, constant(kSr, 0.2f));
    core.seq(SeqOp::ToggleStep, 0, 0, 0, 24, 1.0f);
    core.seq(SeqOp::Play);
    run(core, 1024);
    core.setSeqDuck(true);
    core.noteOn(1, 1.0f);
    const auto out = run(core, 8192);
    EXPECT_NEAR(out.back(), 0.2f * kCenter, 0.005f);
    core.setSeqDuck(false);
    EXPECT_NEAR(run(core, 8192).back(), 0.6f * kCenter, 0.005f);
}
