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
