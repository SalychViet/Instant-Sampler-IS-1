// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

// EXPORT: запись мастера ровно на длину паттерна/Song, с начала, без метронома.
#include <vector>

#include "EngineCore.h"
#ifdef MPC_MINI_GTEST
#include "mini_gtest.h"
#else
#include <gtest/gtest.h>
#endif

using namespace mpc;

TEST(ExportTest, OneBarIsExactlyOneBarLong) {
    EngineCore core;
    core.setSampleRate(48000);
    core.seq(SeqOp::SetBpm, 0, 0, 0, 0, 120.0f);
    core.seq(SeqOp::SetMetronome, 0, 0, 1, 0, 1.0f);   // метроном включён, но в экспорт попасть не должен
    std::vector<float> buf(2 * 256);
    core.render(buf.data(), 256);
    ASSERT_TRUE(core.exportStart(kBarTicks, 120.0f));
    for (int i = 0; i < 2000 && core.exportState() != 3; ++i) core.render(buf.data(), 256);
    ASSERT_EQ(core.exportState(), 3);
    EXPECT_EQ(core.exportFrames(), 96000);   // 1 такт 4/4 при 120 BPM = 2 с
    const int16_t* d = core.exportData();
    int peak = 0;
    for (int i = 0; i < 2 * core.exportFrames(); ++i) peak = std::max(peak, d[i] < 0 ? -d[i] : static_cast<int>(d[i]));
    EXPECT_EQ(peak, 0);   // пустой паттерн, метроном не слышен
    EXPECT_EQ(core.seqState() & (1ull << 16), 0u);   // после экспорта секвенсор остановлен
    core.exportFree();
    EXPECT_EQ(core.exportState(), 0);
}

TEST(ExportTest, CancelStopsRecording) {
    EngineCore core;
    core.setSampleRate(48000);
    std::vector<float> buf(2 * 256);
    ASSERT_TRUE(core.exportStart(kBarTicks * 4, 120.0f));
    core.render(buf.data(), 256);
    EXPECT_EQ(core.exportState(), 2);
    core.exportCancel();
    core.render(buf.data(), 256);
    EXPECT_EQ(core.exportState(), 0);
}

// ISL-1: петля, запущенная «на ходу» (noteOnAt), играет с нужного места региона.
TEST(LooperTest, NoteOnAtStartsInsideRegion) {
    EngineCore core;
    core.setSampleRate(48000);
    auto s = std::make_unique<Sample>();
    s->frames = 48000;
    s->sampleRate = 48000;
    s->data.resize(static_cast<size_t>(s->frames) * 2);
    for (int64_t i = 0; i < s->frames; ++i) s->data[2 * i] = s->data[2 * i + 1] = static_cast<float>(i) / s->frames;   // пила 0 → 1
    ASSERT_TRUE(core.loadSample(0, std::move(s)));
    core.setParam(0, kParamMode, 2.0f);         // LOOP
    core.setParam(0, kParamAttackMs, 0.0f);
    ASSERT_TRUE(core.noteOnAt(0, 1.0f, 0.5));
    std::vector<float> buf(2 * 512);
    for (int k = 0; k < 4; ++k) core.render(buf.data(), 512);   // атака (≥1 мс) прошла
    // Через ~2048 кадров от середины пилы: значение ≈ 0.5 + 2048/48000 (до лимитера и громкости).
    EXPECT_GT(buf[2 * 511], 0.2f);
    std::vector<float> ref(2 * 512);
    EngineCore core2;
    core2.setSampleRate(48000);
    auto s2 = std::make_unique<Sample>();
    s2->frames = 48000;
    s2->sampleRate = 48000;
    s2->data.resize(static_cast<size_t>(s2->frames) * 2);
    for (int64_t i = 0; i < s2->frames; ++i) s2->data[2 * i] = s2->data[2 * i + 1] = static_cast<float>(i) / s2->frames;
    ASSERT_TRUE(core2.loadSample(0, std::move(s2)));
    core2.setParam(0, kParamMode, 2.0f);
    core2.setParam(0, kParamAttackMs, 0.0f);
    ASSERT_TRUE(core2.noteOn(0, 1.0f));
    for (int k = 0; k < 4; ++k) core2.render(ref.data(), 512);
    EXPECT_GT(buf[2 * 511], ref[2 * 511] * 5.0f);   // с середины пилы — заметно громче, чем с начала
}

// Голосов не хватает — крадётся не играющая петля, а one-shot.
TEST(LooperTest, VoiceStealingSparesLoops) {
    EngineCore core;
    core.setSampleRate(48000);
    auto loop = std::make_unique<Sample>();
    loop->frames = 48000;
    loop->sampleRate = 48000;
    loop->data.assign(static_cast<size_t>(loop->frames) * 2, 0.3f);
    ASSERT_TRUE(core.loadSample(0, std::move(loop)));
    core.setParam(0, kParamMode, 2.0f);          // LOOP
    for (int pad = 1; pad <= 2; ++pad) {
        auto s = std::make_unique<Sample>();
        s->frames = 48000 * 4;
        s->sampleRate = 48000;
        s->data.assign(static_cast<size_t>(s->frames) * 2, 0.01f);
        ASSERT_TRUE(core.loadSample(pad, std::move(s)));
    }
    std::vector<float> buf(2 * 64);
    ASSERT_TRUE(core.noteOn(0, 1.0f));           // петля — самый старый голос
    core.render(buf.data(), 64);
    for (int i = 0; i < kMaxVoices + 8; ++i) {   // переполняем пул длинными one-shot
        core.noteOn(1 + (i % 2), 1.0f);
        core.render(buf.data(), 64);
    }
    EXPECT_TRUE((core.activePadMask() & 1ull) != 0);   // петля на паде 0 всё ещё звучит
}

// Метроном звучит в выходе, но в ресемплинг (запись выхода) не попадает.
TEST(MetronomeTest, ClickIsHeardButNotResampled) {
    EngineCore core;
    core.setSampleRate(48000);
    core.seq(SeqOp::SetBpm, 0, 0, 0, 0, 120.0f);
    core.seq(SeqOp::SetMetronome, 0, 0, 1, 0, 1.0f);
    core.resampleStart();
    core.seq(SeqOp::Play);
    std::vector<float> buf(2 * 256);
    float heard = 0;
    for (int k = 0; k < 40; ++k) {
        core.render(buf.data(), 256);
        for (float v : buf) heard = std::max(heard, std::fabs(v));
    }
    const int n = core.resampleStop();
    std::vector<float> rec(2 * static_cast<size_t>(n));
    core.resampleRead(rec.data(), n);
    float recorded = 0;
    for (float v : rec) recorded = std::max(recorded, std::fabs(v));
    EXPECT_GT(heard, 0.05f);       // щелчок слышен
    EXPECT_LT(recorded, 1e-6f);    // а в записи его нет
}

// VOL пада меняется — звучащий голос (петля) становится тише сразу, без перезапуска.
TEST(LooperTest, VolumeChangesWhilePlaying) {
    EngineCore core;
    core.setSampleRate(48000);
    auto s = std::make_unique<Sample>();
    s->frames = 48000;
    s->sampleRate = 48000;
    s->data.assign(static_cast<size_t>(s->frames) * 2, 0.4f);
    ASSERT_TRUE(core.loadSample(0, std::move(s)));
    core.setParam(0, kParamMode, 2.0f);   // LOOP
    ASSERT_TRUE(core.noteOn(0, 1.0f));
    std::vector<float> buf(2 * 512);
    for (int k = 0; k < 10; ++k) core.render(buf.data(), 512);
    const float before = std::fabs(buf[2 * 511]);
    core.setParam(0, kParamVolume, 0.25f);
    for (int k = 0; k < 10; ++k) core.render(buf.data(), 512);   // ~100 мс — сглаживание прошло
    const float after = std::fabs(buf[2 * 511]);
    EXPECT_GT(before, 0.1f);
    EXPECT_LT(after, before * 0.4f);
}

// SEQ VOL глушит одноразовые звуки и секвенсор, а петлю лупера (запущенную касанием) — нет.
TEST(LooperTest, SeqVolumeSparesLoopSection) {
    for (int mode : {0, 2}) {   // 0 — one-shot, 2 — LOOP
        EngineCore core;
        core.setSampleRate(48000);
        auto s = std::make_unique<Sample>();
        s->frames = 48000;
        s->sampleRate = 48000;
        s->data.assign(static_cast<size_t>(s->frames) * 2, 0.4f);
        ASSERT_TRUE(core.loadSample(0, std::move(s)));
        core.setParam(0, kParamMode, static_cast<float>(mode));
        core.setSeqVolume(0.0f);
        ASSERT_TRUE(core.noteOn(0, 1.0f));
        std::vector<float> buf(2 * 512);
        for (int k = 0; k < 20; ++k) core.render(buf.data(), 512);
        const float level = std::fabs(buf[2 * 511]);
        if (mode == 2) EXPECT_GT(level, 0.1f);   // петля звучит
        else EXPECT_LT(level, 1e-3f);            // one-shot заглушен SEQ VOL
    }
}

// LOOPER-фейдер глушит петли лупера, а one-shot — нет.
TEST(LooperTest, LoopVolumeOnlyLoops) {
    for (int mode : {0, 2}) {
        EngineCore core;
        core.setSampleRate(48000);
        auto s = std::make_unique<Sample>();
        s->frames = 48000;
        s->sampleRate = 48000;
        s->data.assign(static_cast<size_t>(s->frames) * 2, 0.4f);
        ASSERT_TRUE(core.loadSample(0, std::move(s)));
        core.setParam(0, kParamMode, static_cast<float>(mode));
        core.setLoopVolume(0.0f);
        ASSERT_TRUE(core.noteOn(0, 1.0f));
        std::vector<float> buf(2 * 512);
        for (int k = 0; k < 20; ++k) core.render(buf.data(), 512);
        const float level = std::fabs(buf[2 * 511]);
        if (mode == 2) EXPECT_LT(level, 1e-3f);   // петля заглушена
        else EXPECT_GT(level, 0.1f);              // one-shot звучит
    }
}

// Огибающая высоты: ровные +12 полутонов проигрывают one-shot вдвое быстрее.
TEST(PitchEnvTest, FlatOctaveUpPlaysTwiceAsFast) {
    int lastFrames[2] = {0, 0};
    for (int on = 0; on < 2; ++on) {
        EngineCore core;
        core.setSampleRate(48000);
        auto s = std::make_unique<Sample>();
        s->frames = 24000;
        s->sampleRate = 48000;
        s->data.assign(static_cast<size_t>(s->frames) * 2, 0.4f);
        ASSERT_TRUE(core.loadSample(0, std::move(s)));
        if (on) {
            core.setParam(0, kParamPEnvOn, 1.0f);
            for (int k = 0; k < 5; ++k) core.setParam(0, kParamPEnvY0 + k, 12.0f);
        }
        ASSERT_TRUE(core.noteOn(0, 1.0f));
        std::vector<float> buf(2 * 256);
        int frames = 0;
        for (int k = 0; k < 200; ++k) {
            core.render(buf.data(), 256);
            if (core.activePadMask() == 0) break;
            frames += 256;
        }
        lastFrames[on] = frames;
    }
    EXPECT_NEAR(lastFrames[1], lastFrames[0] / 2, 600);
}

// Огибающая по точкам: начало на +24, конец на 0 — быстрее обычного, но медленнее, чем ровные +24.
TEST(PitchEnvTest, RampIsBetween) {
    EngineCore core;
    core.setSampleRate(48000);
    auto s = std::make_unique<Sample>();
    s->frames = 48000;
    s->sampleRate = 48000;
    s->data.assign(static_cast<size_t>(s->frames) * 2, 0.4f);
    ASSERT_TRUE(core.loadSample(0, std::move(s)));
    core.setParam(0, kParamPEnvOn, 1.0f);
    core.setParam(0, kParamPEnvY0, 24.0f);   // остальные точки 0: спад к началу второй четверти
    ASSERT_TRUE(core.noteOn(0, 1.0f));
    std::vector<float> buf(2 * 256);
    int frames = 0;
    for (int k = 0; k < 400; ++k) {
        core.render(buf.data(), 256);
        if (core.activePadMask() == 0) break;
        frames += 256;
    }
    EXPECT_LT(frames, 47000);
    EXPECT_GT(frames, 12000);
}
