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
