// Host-тесты секвенсора: точность часов, паттерны, квантизация, запись, метроном, очередь паттернов.
#ifdef MPC_MINI_GTEST
#include "mini_gtest.h"
#else
#include <gtest/gtest.h>
#endif

#include <algorithm>
#include <atomic>
#include <cmath>
#include <memory>
#include <thread>
#include <vector>

#include "EngineCore.h"
#include "Sequencer.h"
#include "VoicePool.h"

using namespace mpc;

namespace {

constexpr int kSr = 48000;
constexpr int kTick120 = 250;   // кадров на тик при 120 BPM и 48 кГц: 48000*60/(120*96)

std::unique_ptr<Sample> constSample(int64_t frames, float v) {
    auto s = std::make_unique<Sample>();
    s->frames = frames;
    s->sampleRate = kSr;
    s->data.assign(static_cast<size_t>(frames) * 2, v);
    return s;
}

// Секвенсор + голоса без EngineCore: команды применяем напрямую (тест = «аудио-поток»).
struct Rig {
    VoicePool vp;
    PadParams pads[kNumPads];
    std::unique_ptr<Sample> samples[kNumPads];
    Sequencer seq;

    Rig() : seq(vp, pads) {
        vp.setSampleRate(kSr);
        seq.setSampleRate(kSr);
    }
    void give(int pad, float value, int64_t frames = 200, PlayMode mode = PlayMode::OneShot) {
        samples[pad] = constSample(frames, value);
        pads[pad].sample = samples[pad].get();
        pads[pad].mode = mode;
    }
    void step(int pat, int pad, int tick, int width = 24, float vel = 1.0f) {
        seq.apply(SeqOp::ToggleStep, pat, pad, tick, width, vel);
    }
    // Рендер нужного числа кадров блоками по `block`; возвращает левый канал.
    std::vector<float> render(int frames, int block = 256) {
        std::vector<float> left;
        std::vector<float> buf(static_cast<size_t>(block) * 2);
        int done = 0;
        while (done < frames) {
            const int n = std::min(block, frames - done);
            std::fill(buf.begin(), buf.begin() + 2 * n, 0.0f);
            seq.render(buf.data(), n);
            for (int i = 0; i < n; ++i) left.push_back(buf[2 * i]);
            done += n;
        }
        return left;
    }
    std::vector<SeqEvent> events(int pat) {
        uint64_t w[kMaxPatternEvents];
        const int n = seq.copyPattern(pat, w, kMaxPatternEvents);
        std::vector<SeqEvent> out;
        for (int i = 0; i < n; ++i) out.push_back(unpackEvent(w[i]));
        return out;
    }
};

// Индексы фронтов: кадр, где сигнал впервые выше порога после тишины.
std::vector<int> onsets(const std::vector<float>& x, float thr = 1e-6f) {
    std::vector<int> r;
    for (size_t i = 0; i < x.size(); ++i)
        if (std::fabs(x[i]) > thr && (i == 0 || std::fabs(x[i - 1]) <= thr)) r.push_back(static_cast<int>(i));
    return r;
}

float maxAbs(const std::vector<float>& x, int from, int to) {
    float m = 0;
    to = std::min<int>(to, static_cast<int>(x.size()));
    for (int i = from; i < to; ++i) m = std::max(m, std::fabs(x[i]));
    return m;
}

bool running(uint64_t s) { return (s >> 16) & 1; }
bool recording(uint64_t s) { return (s >> 17) & 1; }
bool countingIn(uint64_t s) { return (s >> 18) & 1; }
int curPattern(uint64_t s) { return static_cast<int>((s >> 24) & 0xFF); }
int queuedPlus1(uint64_t s) { return static_cast<int>((s >> 32) & 0xFF); }

}  // namespace

// ---------------------------------------------------------------- события и точность

TEST(SequencerTest, PackUnpackRoundTrip) {
    SeqEvent e;
    e.tick = 1535; e.pad = 63; e.vel = 127; e.len = 40000;
    const SeqEvent r = unpackEvent(packEvent(e));
    EXPECT_EQ(r.tick, 1535);
    EXPECT_EQ(r.pad, 63);
    EXPECT_EQ(r.vel, 127);
    EXPECT_EQ(r.len, 40000);
}

TEST(SequencerTest, EventStartsSampleAccurately) {
    Rig r;
    r.give(0, 1.0f);
    r.step(0, 0, 0);
    r.step(0, 0, 96);   // вторая доля
    r.seq.apply(SeqOp::SetBpm, 0, 0, 0, 0, 120.0f);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    const auto o = onsets(r.render(30000, 37));
    ASSERT_GE(o.size(), 2u);
    EXPECT_EQ(o[1] - o[0], 96 * kTick120);   // ровно 24000 кадров, независимо от размера блока 37
}

TEST(SequencerTest, TempoScalesTiming) {
    Rig r;
    r.give(0, 1.0f);
    r.step(0, 0, 0);
    r.step(0, 0, 96);
    r.seq.apply(SeqOp::SetBpm, 0, 0, 0, 0, 60.0f);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    const auto o = onsets(r.render(60000, 512));
    ASSERT_GE(o.size(), 2u);
    EXPECT_EQ(o[1] - o[0], 48000);
}

TEST(SequencerTest, FractionalTickLengthDoesNotDrift) {
    // 100 BPM @ 44100 Гц: тик = 275.625 кадра. Через 8 тактов (3072 тиков) ошибка должна быть < 1 кадра.
    Rig r;
    r.vp.setSampleRate(44100);
    r.seq.setSampleRate(44100);
    r.samples[0] = constSample(100, 1.0f);
    r.pads[0].sample = r.samples[0].get();
    r.step(0, 0, 0);
    r.seq.apply(SeqOp::SetBpm, 0, 0, 0, 0, 100.0f);
    r.seq.apply(SeqOp::SetLength, 0, 0, 1, 0, 0);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    const int bar = 105840;   // 384 * 275.625
    const auto o = onsets(r.render(bar * 8 + 1000, 480));
    ASSERT_GE(o.size(), 9u);
    for (int i = 1; i < 9; ++i) EXPECT_NEAR(o[i] - o[0], i * (384 * 275.625), 1.0);
}

TEST(SequencerTest, OutputIdenticalForAnyBlockSize) {
    auto make = [](int block) {
        Rig r;
        r.give(0, 0.8f, 700);
        r.give(1, -0.5f, 300);
        r.step(0, 0, 0);
        r.step(0, 1, 13, 10, 0.5f);
        r.step(0, 0, 100);
        r.step(0, 1, 250);
        r.seq.apply(SeqOp::SetBpm, 0, 0, 0, 0, 137.0f);
        r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
        return r.render(200000, block);
    };
    const auto ref = make(256);
    for (int b : {1, 7, 64, 333, 4096}) {
        const auto x = make(b);
        ASSERT_EQ(x.size(), ref.size());
        EXPECT_TRUE(x == ref);
    }
}

TEST(SequencerTest, PatternLoops) {
    Rig r;
    r.give(0, 1.0f);
    r.step(0, 0, 0);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    const auto o = onsets(r.render(3 * 96000 + 500, 480));
    ASSERT_EQ(o.size(), 4u);
    EXPECT_EQ(o[1] - o[0], 96000);
    EXPECT_EQ(o[2] - o[1], 96000);
    EXPECT_EQ(o[3] - o[2], 96000);
}

TEST(SequencerTest, PatternLengthInBars) {
    Rig r;
    r.give(0, 1.0f);
    r.step(0, 0, 0);
    r.seq.apply(SeqOp::SetLength, 0, 0, 2, 0, 0);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    const auto o = onsets(r.render(2 * 192000 + 500, 480));
    ASSERT_EQ(o.size(), 3u);
    EXPECT_EQ(o[1] - o[0], 192000);
}

TEST(SequencerTest, EventsPastShortenedLengthAreSilent) {
    Rig r;
    r.give(0, 1.0f);
    r.step(0, 0, 400);   // во втором такте
    r.seq.apply(SeqOp::SetLength, 0, 0, 1, 0, 0);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    EXPECT_TRUE(onsets(r.render(3 * 96000, 512)).empty());
}

TEST(SequencerTest, EmptyPatternIsSilent) {
    Rig r;
    r.give(0, 1.0f);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    EXPECT_EQ(maxAbs(r.render(50000), 0, 50000), 0.0f);
}

// ---------------------------------------------------------------- правка паттерна

TEST(SequencerTest, ToggleStepAddsAndRemoves) {
    Rig r;
    r.step(3, 5, 48);
    auto e = r.events(3);
    ASSERT_EQ(e.size(), 1u);
    EXPECT_EQ(e[0].tick, 48);
    EXPECT_EQ(e[0].pad, 5);
    r.step(3, 5, 48);
    EXPECT_TRUE(r.events(3).empty());
}

TEST(SequencerTest, ToggleStepRemovesOffGridEventInsideStep) {
    Rig r;
    r.seq.apply(SeqOp::ToggleStep, 0, 2, 53, 1, 1.0f);   // нота вне сетки (тик 53) попадает в шаг 48..71
    ASSERT_EQ(r.events(0).size(), 1u);
    r.step(0, 2, 48, 24);
    EXPECT_TRUE(r.events(0).empty());
}

TEST(SequencerTest, EventsKeptSortedByTick) {
    Rig r;
    for (int t : {200, 10, 96, 0, 300, 55, 57, 11}) r.step(0, t % 7, t, 1);
    const auto e = r.events(0);
    ASSERT_EQ(e.size(), 8u);
    for (size_t i = 1; i < e.size(); ++i) EXPECT_LE(e[i - 1].tick, e[i].tick);
}

TEST(SequencerTest, ErasePadRemovesOnlyThatPad) {
    Rig r;
    for (int t = 0; t < 8; ++t) { r.step(0, 1, t * 24); r.step(0, 2, t * 24); }
    r.seq.apply(SeqOp::ErasePad, 0, 1, 0, 0, 0);
    const auto e = r.events(0);
    ASSERT_EQ(e.size(), 8u);
    for (const auto& x : e) EXPECT_EQ(x.pad, 2);
}

TEST(SequencerTest, ClearPatternAffectsOnlyThatPattern) {
    Rig r;
    r.step(0, 1, 0);
    r.step(1, 1, 0);
    r.seq.apply(SeqOp::ClearPattern, 0, 0, 0, 0, 0);
    EXPECT_TRUE(r.events(0).empty());
    EXPECT_EQ(r.events(1).size(), 1u);
}

TEST(SequencerTest, PatternCountReflectsEdits) {
    Rig r;
    EXPECT_EQ(r.seq.patternCount(5), 0);
    r.step(5, 1, 0);
    r.step(5, 2, 24);
    EXPECT_EQ(r.seq.patternCount(5), 2);
    EXPECT_EQ(r.seq.patternCount(4), 0);
    r.seq.apply(SeqOp::ClearPattern, 5, 0, 0, 0, 0);
    EXPECT_EQ(r.seq.patternCount(5), 0);
    EXPECT_EQ(r.seq.patternCount(-1), 0);
    EXPECT_EQ(r.seq.patternCount(64), 0);
}

TEST(SequencerTest, EventCapacityIsBounded) {
    Rig r;
    for (int t = 0; t < kMaxPatternEvents + 200; ++t) r.step(0, t % kNumPads, t % 384, 1);
    EXPECT_LE(static_cast<int>(r.events(0).size()), kMaxPatternEvents);
}

TEST(SequencerTest, InvalidArgumentsAreIgnored) {
    Rig r;
    r.seq.apply(SeqOp::ToggleStep, 99, 0, 0, 24, 1.0f);   // нет такого паттерна
    r.seq.apply(SeqOp::ToggleStep, 0, 99, 0, 24, 1.0f);   // нет такого пада
    r.seq.apply(SeqOp::SetBpm, 0, 0, 0, 0, NAN);
    r.seq.apply(SeqOp::SetBpm, 0, 0, 0, 0, 1e9f);          // зажмётся до 300
    EXPECT_TRUE(r.events(0).empty());
    EXPECT_EQ(r.seq.patternBars(99), 1);
}

// ---------------------------------------------------------------- Gate/Loop

TEST(SequencerTest, GateNoteReleasedAfterEventLength) {
    Rig r;
    r.give(0, 1.0f, 400000, PlayMode::Gate);
    r.step(0, 0, 0, 48);   // длина 48 тиков = 12000 кадров
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    const auto x = r.render(20000, 480);
    EXPECT_GT(maxAbs(x, 10000, 11900), 0.1f);       // ещё держится
    EXPECT_LT(maxAbs(x, 12000 + 2000, 20000), 1e-4f);   // отпущено (release 15 мс)
}

TEST(SequencerTest, StopReleasesSequencerNotesAndResets) {
    Rig r;
    r.give(0, 1.0f, 400000, PlayMode::Gate);
    r.step(0, 0, 0, 300);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    r.render(1000);
    EXPECT_TRUE(running(r.seq.state()));
    r.seq.apply(SeqOp::Stop, 0, 0, 0, 0, 0);
    EXPECT_FALSE(running(r.seq.state()));
    const auto x = r.render(4800);
    EXPECT_LT(maxAbs(x, 2400, 4800), 1e-4f);
    EXPECT_EQ(static_cast<int>(r.seq.state() & 0xFFFF), 0);
}

// ---------------------------------------------------------------- запись и квантизация

TEST(SequencerTest, RecordQuantizesToNearestGrid) {
    Rig r;
    r.give(0, 1.0f);
    r.seq.apply(SeqOp::SetQuantize, 0, 0, 24, 0, 0);
    r.seq.apply(SeqOp::SetRecording, 0, 0, 1, 0, 0);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    r.render(30 * kTick120 + 100);   // тик 30.4
    r.seq.onPadDown(0, 1.0f);
    const auto e = r.events(0);
    ASSERT_EQ(e.size(), 1u);
    EXPECT_EQ(e[0].tick, 24);
}

TEST(SequencerTest, RecordRoundsUpToNextGridPoint) {
    Rig r;
    r.give(0, 1.0f);
    r.seq.apply(SeqOp::SetQuantize, 0, 0, 24, 0, 0);
    r.seq.apply(SeqOp::SetRecording, 0, 0, 1, 0, 0);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    r.render(40 * kTick120 + 50);   // тик 40.2 → ближайшая сетка 48
    r.seq.onPadDown(0, 1.0f);
    const auto e = r.events(0);
    ASSERT_EQ(e.size(), 1u);
    EXPECT_EQ(e[0].tick, 48);
}

TEST(SequencerTest, RecordWithoutQuantizeKeepsExactTick) {
    Rig r;
    r.give(0, 1.0f);
    r.seq.apply(SeqOp::SetQuantize, 0, 0, 0, 0, 0);
    r.seq.apply(SeqOp::SetRecording, 0, 0, 1, 0, 0);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    r.render(30 * kTick120 + 100);
    r.seq.onPadDown(0, 0.5f);
    const auto e = r.events(0);
    ASSERT_EQ(e.size(), 1u);
    EXPECT_EQ(e[0].tick, 30);
    EXPECT_NEAR(e[0].vel, 64, 1);
}

TEST(SequencerTest, RecordNearPatternEndWrapsToStart) {
    Rig r;
    r.give(0, 1.0f);
    r.seq.apply(SeqOp::SetQuantize, 0, 0, 24, 0, 0);
    r.seq.apply(SeqOp::SetRecording, 0, 0, 1, 0, 0);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    r.render(380 * kTick120 + 10);   // тик 380.04 → 384 = конец цикла → тик 0
    r.seq.onPadDown(0, 1.0f);
    const auto e = r.events(0);
    ASSERT_EQ(e.size(), 1u);
    EXPECT_EQ(e[0].tick, 0);
}

TEST(SequencerTest, QuantizedForwardNoteIsNotPlayedTwiceInSamePass) {
    Rig r;
    r.give(0, 1.0f, 200);
    r.seq.apply(SeqOp::SetQuantize, 0, 0, 24, 0, 0);
    r.seq.apply(SeqOp::SetRecording, 0, 0, 1, 0, 0);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    auto x = r.render(40 * kTick120 + 50);   // «вживую» (голос не запускаем: тест проверяет только секвенсор)
    r.seq.onPadDown(0, 1.0f);
    const auto y = r.render(70000);
    // Тик 48 этого прохода (кадр 12000) пропущен — иначе был бы «флэм» с живой нотой…
    EXPECT_EQ(maxAbs(y, 48 * kTick120 - (40 * kTick120 + 50), 48 * kTick120 - (40 * kTick120 + 50) + 300), 0.0f);
    // …а на следующем проходе нота звучит.
    const auto z = r.render(60000);
    EXPECT_FALSE(onsets(z).empty());
}

TEST(SequencerTest, RecordedNoteSoundsOnNextLoop) {
    Rig r;
    r.give(0, 1.0f, 200);
    r.seq.apply(SeqOp::SetQuantize, 0, 0, 24, 0, 0);
    r.seq.apply(SeqOp::SetRecording, 0, 0, 1, 0, 0);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    r.render(10 * kTick120);
    r.seq.onPadDown(0, 1.0f);           // тик 10 → сетка 0 (раньше текущего): записано на тик 0
    const auto ev = r.events(0);
    ASSERT_EQ(ev.size(), 1u);
    EXPECT_EQ(ev[0].tick, 0);
    const auto o = onsets(r.render(2 * 96000));
    ASSERT_FALSE(o.empty());
    EXPECT_EQ(o.front() + 10 * kTick120, 96000);   // следующий проход стартует ровно на границе цикла
}

TEST(SequencerTest, NothingRecordedWhenNotRecordingOrStopped) {
    Rig r;
    r.give(0, 1.0f);
    r.seq.onPadDown(0, 1.0f);                           // стоп
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    r.render(1000);
    r.seq.onPadDown(0, 1.0f);                           // играет, но запись не включена
    EXPECT_TRUE(r.events(0).empty());
}

TEST(SequencerTest, HeldPadRecordsNoteLength) {
    Rig r;
    r.give(0, 1.0f, 400000, PlayMode::Gate);
    r.seq.apply(SeqOp::SetQuantize, 0, 0, 24, 0, 0);
    r.seq.apply(SeqOp::SetRecording, 0, 0, 1, 0, 0);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    r.render(24 * kTick120);
    r.seq.onPadDown(0, 1.0f);
    r.render(60 * kTick120);            // удерживаем 60 тиков
    r.seq.onPadUp(0);
    const auto e = r.events(0);
    ASSERT_EQ(e.size(), 1u);
    EXPECT_NEAR(e[0].len, 60, 1);
}

TEST(SequencerTest, RetriggerSameTickUpdatesInsteadOfDuplicating) {
    Rig r;
    r.give(0, 1.0f);
    r.seq.apply(SeqOp::SetQuantize, 0, 0, 24, 0, 0);
    r.seq.apply(SeqOp::SetRecording, 0, 0, 1, 0, 0);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    r.render(24 * kTick120 + 5);
    r.seq.onPadDown(0, 0.3f);
    r.seq.onPadDown(0, 0.9f);
    const auto ev = r.events(0);
    ASSERT_EQ(ev.size(), 1u);
    EXPECT_NEAR(ev[0].vel, 114, 1);
}

// ---------------------------------------------------------------- метроном и отсчёт

TEST(SequencerTest, MetronomeClicksOnEveryBeat) {
    Rig r;
    r.seq.apply(SeqOp::SetMetronome, 0, 0, 1, 0, 1.0f);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    const auto x = r.render(4 * 24000);
    for (int beat = 0; beat < 4; ++beat) {
        EXPECT_GT(maxAbs(x, beat * 24000 + 2, beat * 24000 + 40), 0.05f);        // щелчок точно на доле
        EXPECT_LT(maxAbs(x, beat * 24000 + 6000, beat * 24000 + 23000), 1e-3f);   // и тишина до следующего
    }
}

TEST(SequencerTest, MetronomeOffIsSilent) {
    Rig r;
    r.seq.apply(SeqOp::SetMetronome, 0, 0, 0, 0, 1.0f);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    EXPECT_EQ(maxAbs(r.render(50000), 0, 50000), 0.0f);
}

TEST(SequencerTest, MetronomeRecordOnlyModeClicksOnlyWhileRecording) {
    Rig r;
    r.seq.apply(SeqOp::SetMetronome, 0, 0, 2, 0, 1.0f);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    EXPECT_EQ(maxAbs(r.render(30000), 0, 30000), 0.0f);
    r.seq.apply(SeqOp::SetRecording, 0, 0, 1, 0, 0);
    const auto x = r.render(30000);
    EXPECT_GT(maxAbs(x, 0, 30000), 0.05f);
}

TEST(SequencerTest, CountInDelaysPatternAndBlocksRecording) {
    Rig r;
    r.give(0, 1.0f);
    r.step(0, 0, 0);
    r.seq.apply(SeqOp::SetCountIn, 0, 0, 1, 0, 0);      // 1 такт
    r.seq.apply(SeqOp::SetRecording, 0, 0, 1, 0, 0);
    r.seq.apply(SeqOp::SetMetronome, 0, 0, 0, 0, 1.0f); // метроном выключен — отсчёт всё равно щёлкает
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    EXPECT_TRUE(countingIn(r.seq.state()));

    auto x = r.render(5000);
    r.seq.onPadDown(0, 1.0f);                           // во время отсчёта запись игнорируется
    EXPECT_EQ(r.events(0).size(), 1u);                  // только заранее добавленный шаг
    auto y = r.render(96000 - 5000 + 600);
    x.insert(x.end(), y.begin(), y.end());

    for (int beat = 0; beat < 4; ++beat) EXPECT_GT(maxAbs(x, beat * 24000, beat * 24000 + 600), 0.05f);
    EXPECT_FALSE(countingIn(r.seq.state()));
    // Паттерн (нота на тике 0, амплитуда 1.0 против щелчка ≤ 0.6) стартует ровно после отсчёта.
    EXPECT_GT(maxAbs(x, 96000, 96000 + 600), 0.6f);   // 1/√2: pan по центру
    EXPECT_LT(maxAbs(x, 3 * 24000 + 6000, 96000 - 100), 1e-3f);   // между последним щелчком и паттерном тихо
}

TEST(SequencerTest, NoCountInWhenNotRecording) {
    Rig r;
    r.seq.apply(SeqOp::SetCountIn, 0, 0, 2, 0, 0);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    EXPECT_FALSE(countingIn(r.seq.state()));
}

// ---------------------------------------------------------------- очередь паттернов и состояние

TEST(SequencerTest, QueuedPatternSwitchesAtLoopBoundary) {
    Rig r;
    r.give(0, 1.0f);
    r.give(1, 0.5f);
    r.step(0, 0, 0);
    r.step(1, 1, 0);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    auto x = r.render(10 * kTick120);
    r.seq.apply(SeqOp::Select, 1, 0, 0, 0, 0);          // не сразу
    EXPECT_EQ(curPattern(r.seq.state()), 0);
    EXPECT_EQ(queuedPlus1(r.seq.state()), 2);
    auto y = r.render(2 * 96000 - 10 * kTick120 + 600);
    x.insert(x.end(), y.begin(), y.end());
    const auto o = onsets(x);
    ASSERT_GE(o.size(), 3u);
    EXPECT_EQ(o[1] - o[0], 96000);
    // Первая нота — пад 0 (громче), вторая и третья — пад 1 (вдвое тише).
    const float a0 = x[o[0] + 100], a1 = x[o[1] + 100], a2 = x[o[2] + 100];
    EXPECT_NEAR(a1 / a0, 0.5f, 0.02f);
    EXPECT_NEAR(a2 / a0, 0.5f, 0.02f);
    EXPECT_EQ(curPattern(r.seq.state()), 1);
    EXPECT_EQ(queuedPlus1(r.seq.state()), 0);
}

TEST(SequencerTest, QueuedPatternDoesNotPlayBeforeBoundary) {
    Rig r;
    r.give(0, 1.0f);
    r.give(1, 0.5f);
    r.step(0, 0, 0);
    r.step(1, 1, 20);   // нота нового паттерна лежит в начале цикла, но позже момента выбора (тик 10)
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    auto x = r.render(10 * kTick120);
    r.seq.apply(SeqOp::Select, 1, 0, 0, 0, 0);
    auto y = r.render(96000 - 10 * kTick120);               // дорендерили до конца первого цикла
    x.insert(x.end(), y.begin(), y.end());
    EXPECT_EQ(onsets(x).size(), 1u);                         // сыграла только нота старого паттерна
    auto z = r.render(96000);
    EXPECT_EQ(onsets(z).size(), 1u);                         // и в новом цикле — уже нота паттерна 1
}

TEST(SequencerTest, SelectCurrentPatternCancelsQueue) {
    Rig r;
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    r.seq.apply(SeqOp::Select, 5, 0, 0, 0, 0);
    EXPECT_EQ(queuedPlus1(r.seq.state()), 6);
    r.seq.apply(SeqOp::Select, 0, 0, 0, 0, 0);
    EXPECT_EQ(queuedPlus1(r.seq.state()), 0);
}

TEST(SequencerTest, SelectWhileStoppedIsImmediate) {
    Rig r;
    r.seq.apply(SeqOp::Select, 7, 0, 0, 0, 0);
    EXPECT_EQ(curPattern(r.seq.state()), 7);
    EXPECT_EQ(queuedPlus1(r.seq.state()), 0);
}

TEST(SequencerTest, ImmediateSelectWhilePlaying) {
    Rig r;
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    r.seq.apply(SeqOp::Select, 4, 0, 1, 0, 0);
    EXPECT_EQ(curPattern(r.seq.state()), 4);
}

TEST(SequencerTest, StateReportsTransportAndPosition) {
    Rig r;
    EXPECT_FALSE(running(r.seq.state()));
    r.seq.apply(SeqOp::SetRecording, 0, 0, 1, 0, 0);
    EXPECT_TRUE(recording(r.seq.state()));
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    r.render(10 * kTick120);
    const uint64_t s = r.seq.state();
    EXPECT_TRUE(running(s));
    const int pos = static_cast<int>(s & 0xFFFF);
    EXPECT_GE(pos, 10);
    EXPECT_LE(pos, 11);
}

TEST(SequencerTest, PlayWhileRunningDoesNotRestart) {
    Rig r;
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    r.render(50 * kTick120);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    EXPECT_GE(static_cast<int>(r.seq.state() & 0xFFFF), 50);
}

TEST(SequencerTest, TempoChangeWhilePlayingTakesEffect) {
    Rig r;
    r.give(0, 1.0f);
    r.step(0, 0, 0);
    r.step(0, 0, 192);
    r.seq.apply(SeqOp::SetBpm, 0, 0, 0, 0, 120.0f);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    auto x = r.render(1000);
    r.seq.apply(SeqOp::SetBpm, 0, 0, 0, 0, 240.0f);     // вдвое быстрее
    auto y = r.render(60000);
    x.insert(x.end(), y.begin(), y.end());
    const auto o = onsets(x);
    ASSERT_GE(o.size(), 2u);
    // Тик 4 начался на кадре 1000; дальше 188 тиков по 125 кадров (240 BPM): тик 192 стартует на 24500.
    EXPECT_NEAR(o[1] - o[0], 24500, 2);
}

// ---------------------------------------------------------------- потоки

TEST(SequencerTest, PatternSnapshotIsConsistentWhileEditing) {
    Rig r;
    std::atomic<bool> stop{false};
    bool unsorted = false, badPad = false, tooMany = false;
    std::atomic<int> okSnapshots{0};

    std::thread reader([&] {
        uint64_t w[kMaxPatternEvents];
        while (!stop.load()) {
            const int n = r.seq.copyPattern(0, w, kMaxPatternEvents);
            if (n < 0) continue;   // снимок не получен — это допустимо, главное что он не «рваный»
            ++okSnapshots;
            if (n > kMaxPatternEvents) tooMany = true;
            int last = -1;
            for (int i = 0; i < n; ++i) {
                const SeqEvent e = unpackEvent(w[i]);
                if (e.tick < last) unsorted = true;
                if (e.pad >= kNumPads) badPad = true;
                last = e.tick;
            }
        }
    });
    for (int i = 0; i < 40000; ++i) {
        const int t = (i * 37) % 384;
        r.seq.apply(SeqOp::ToggleStep, 0, i % 16, t, 1, 1.0f);
        if (i % 1000 == 999) r.seq.apply(SeqOp::ClearPattern, 0, 0, 0, 0, 0);
    }
    stop = true;
    reader.join();
    EXPECT_FALSE(unsorted);
    EXPECT_FALSE(badPad);
    EXPECT_FALSE(tooMany);
    EXPECT_GT(okSnapshots.load(), 0);
}

// ---------------------------------------------------------------- копия паттерна и параметры шагов (P-LOCK)

TEST(SequencerTest, CopyPatternCopiesNotesLengthAndLocks) {
    Rig r;
    r.step(0, 1, 0);
    r.step(0, 2, 96);
    r.seq.apply(SeqOp::SetLength, 0, 0, 3, 0, 0);
    r.seq.apply(SeqOp::SetLock, 0, 1, 0, 24 | (kParamVolume << 16), 0.5f);
    r.seq.apply(SeqOp::CopyPattern, 5, 0, 0, 0, 0);
    EXPECT_EQ(r.seq.patternCount(5), 2);
    EXPECT_EQ(r.seq.patternBars(5), 3);
    uint64_t ev[kMaxPatternEvents], lk[kMaxPatternEvents];
    ASSERT_EQ(r.seq.copyPatternWithLocks(5, ev, lk, kMaxPatternEvents), 2);
    const ParamLocks l = unpackLocks(lk[0]);
    EXPECT_TRUE(l.mask & (1u << kLockVol));
    EXPECT_NEAR(l.v[kLockVol], 0.5f, 0.02f);
    // правка копии не трогает оригинал
    r.step(5, 3, 48);
    EXPECT_EQ(r.seq.patternCount(0), 2);
}

TEST(SequencerTest, LocksPackRoundTrip) {
    ParamLocks l;
    l.mask = 0x7F;
    l.v[kLockVol] = 1.3f; l.v[kLockPan] = -0.4f; l.v[kLockPitch] = -7.0f;
    l.v[kLockStart] = 0.25f; l.v[kLockEnd] = 0.75f; l.v[kLockAttack] = 40.0f; l.v[kLockRelease] = 300.0f;
    const ParamLocks r = unpackLocks(packLocks(l));
    EXPECT_EQ(r.mask, 0x7Fu);
    EXPECT_NEAR(r.v[kLockVol], 1.3f, 0.02f);
    EXPECT_NEAR(r.v[kLockPan], -0.4f, 0.02f);
    EXPECT_EQ(r.v[kLockPitch], -7.0f);
    EXPECT_NEAR(r.v[kLockStart], 0.25f, 0.002f);
    EXPECT_NEAR(r.v[kLockEnd], 0.75f, 0.002f);
    EXPECT_NEAR(r.v[kLockAttack], 40.0f, 1.0f);
    EXPECT_NEAR(r.v[kLockRelease], 300.0f, 25.0f);
}

TEST(SequencerTest, LockedVolumeChangesOnlyThatStep) {
    Rig r;
    r.give(0, 1.0f);
    r.step(0, 0, 0);
    r.step(0, 0, 96);
    r.seq.apply(SeqOp::SetLock, 0, 0, 96, 24 | (kParamVolume << 16), 0.5f);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    const auto x = r.render(30000);
    const auto o = onsets(x);
    ASSERT_GE(o.size(), 2u);
    EXPECT_NEAR(x[o[1] + 50] / x[o[0] + 50], 0.5f, 0.03f);
}

TEST(SequencerTest, LocksIgnoredWhenDisabled) {
    Rig r;
    r.give(0, 1.0f);
    r.step(0, 0, 0);
    r.step(0, 0, 96);
    r.seq.apply(SeqOp::SetLock, 0, 0, 96, 24 | (kParamVolume << 16), 0.5f);
    r.seq.apply(SeqOp::SetLocksEnabled, 0, 0, 0, 0, 0);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    const auto x = r.render(30000);
    const auto o = onsets(x);
    ASSERT_GE(o.size(), 2u);
    EXPECT_NEAR(x[o[1] + 50] / x[o[0] + 50], 1.0f, 0.01f);
}

TEST(SequencerTest, ToggleOffRemovesLocksWithNote) {
    Rig r;
    r.step(0, 0, 0);
    r.seq.apply(SeqOp::SetLock, 0, 0, 0, 24 | (kParamPan << 16), 1.0f);
    r.step(0, 0, 0);   // снять ноту
    r.step(0, 0, 0);   // поставить заново — без старых параметров
    uint64_t ev[kMaxPatternEvents], lk[kMaxPatternEvents];
    ASSERT_EQ(r.seq.copyPatternWithLocks(0, ev, lk, kMaxPatternEvents), 1);
    EXPECT_EQ(lk[0], 0u);
}

// ---------------------------------------------------------------- цепочка паттернов и UNDO

TEST(SequencerTest, SongPlaysPatternsInOrderAndLoops) {
    Rig r;
    r.give(0, 1.0f);
    r.give(1, 0.5f);
    r.step(0, 0, 0);
    r.step(1, 1, 0);
    r.seq.apply(SeqOp::SongClear, 0, 0, 0, 0, 0);
    for (int p : {0, 1, 1}) r.seq.apply(SeqOp::SongAppend, p, 0, 0, 0, 0);
    r.seq.apply(SeqOp::SongEnable, 0, 0, 1, 0, 0);
    EXPECT_EQ(r.seq.songLength(), 3);
    r.seq.apply(SeqOp::Play, 0, 0, 0, 0, 0);
    const auto x = r.render(4 * 96000 + 500, 480);
    const auto o = onsets(x);
    ASSERT_GE(o.size(), 4u);
    const float a0 = x[o[0] + 100];
    EXPECT_NEAR(x[o[1] + 100] / a0, 0.5f, 0.02f);   // паттерн 1
    EXPECT_NEAR(x[o[2] + 100] / a0, 0.5f, 0.02f);   // снова 1
    EXPECT_NEAR(x[o[3] + 100] / a0, 1.0f, 0.02f);   // цепочка по кругу: 0
}

TEST(SequencerTest, SnapshotRestoreUndoesEdits) {
    Rig r;
    r.step(2, 1, 0);
    r.step(2, 2, 48);
    r.seq.apply(SeqOp::SnapshotPattern, 2, 0, 3, 0, 0);
    r.seq.apply(SeqOp::ClearPattern, 2, 0, 0, 0, 0);
    r.seq.apply(SeqOp::SetLength, 2, 0, 4, 0, 0);
    EXPECT_EQ(r.seq.patternCount(2), 0);
    r.seq.apply(SeqOp::RestorePattern, 2, 0, 3, 0, 0);
    EXPECT_EQ(r.seq.patternCount(2), 2);
    EXPECT_EQ(r.seq.patternBars(2), 1);
}

// ---------------------------------------------------------------- Ableton Link

namespace {
// Рендер под «Link»: перед каждым блоком отдаём долю Link на первом кадре блока.
std::vector<float> renderLinked(Rig& r, int frames, int block, double beat0, double bpm, bool playing,
                                int64_t* frameCounter) {
    std::vector<float> left;
    std::vector<float> buf(static_cast<size_t>(block) * 2);
    int done = 0;
    while (done < frames) {
        const int n = std::min(block, frames - done);
        LinkFrame lf;
        lf.enabled = true;
        lf.tempo = bpm;
        lf.playing = playing;
        lf.beat = beat0 + static_cast<double>(*frameCounter) * bpm / (60.0 * kSr);
        r.seq.linkSync(lf, n);
        std::fill(buf.begin(), buf.begin() + 2 * n, 0.0f);
        r.seq.render(buf.data(), n);
        for (int i = 0; i < n; ++i) left.push_back(buf[2 * i]);
        done += n;
        *frameCounter += n;
    }
    return left;
}
}  // namespace

TEST(SequencerLinkTest, StartsOnNextBarOfSession) {
    Rig r;
    r.give(0, 1.0f);
    r.step(0, 0, 0);
    int64_t fc = 0;
    // Сессия уже играет, сейчас доля 3.5: до следующего такта полдоли = 12000 кадров при 120 BPM.
    const auto x = renderLinked(r, 30000, 256, 3.5, 120.0, true, &fc);
    const auto o = onsets(x);
    ASSERT_FALSE(o.empty());
    EXPECT_NEAR(o[0], 12000, 1);
}

TEST(SequencerLinkTest, FollowsSessionTempo) {
    Rig r;
    r.give(0, 1.0f);
    r.step(0, 0, 0);
    r.step(0, 0, 96);
    int64_t fc = 0;
    const auto x = renderLinked(r, 60000, 333, 0.0, 60.0, true, &fc);
    const auto o = onsets(x);
    ASSERT_GE(o.size(), 2u);
    EXPECT_NEAR(o[1] - o[0], 48000, 1);
}

TEST(SequencerLinkTest, StaysInPhaseOverManyBars) {
    Rig r;
    r.give(0, 1.0f, 100);
    r.step(0, 0, 0);
    int64_t fc = 0;
    // 133 BPM: тик — дробное число кадров; через 8 тактов фаза не должна уехать.
    const auto x = renderLinked(r, 48000 * 16, 192, 0.0, 133.0, true, &fc);
    const auto o = onsets(x);
    ASSERT_GE(o.size(), 8u);
    const double barFrames = 4.0 * 60.0 * kSr / 133.0;
    for (size_t i = 0; i < o.size(); ++i) EXPECT_NEAR(o[i], i * barFrames, 1.5);
}

TEST(SequencerLinkTest, SessionStopStopsSequencer) {
    Rig r;
    r.give(0, 1.0f);
    int64_t fc = 0;
    renderLinked(r, 5000, 256, 0.0, 120.0, true, &fc);
    EXPECT_TRUE(running(r.seq.state()));
    renderLinked(r, 1000, 256, 0.0, 120.0, false, &fc);
    EXPECT_FALSE(running(r.seq.state()));
}

TEST(SequencerLinkTest, WaitsWhileSessionStopped) {
    Rig r;
    r.give(0, 1.0f);
    r.step(0, 0, 0);
    int64_t fc = 0;
    EXPECT_EQ(maxAbs(renderLinked(r, 50000, 256, 0.0, 120.0, false, &fc), 0, 50000), 0.0f);
    EXPECT_FALSE(running(r.seq.state()));
}

// ---------------------------------------------------------------- через EngineCore (очередь команд)

TEST(SequencerEngineTest, CommandsFlowThroughQueueAndPlay) {
    EngineCore core;
    core.setSampleRate(kSr);
    ASSERT_TRUE(core.loadSample(0, constSample(2000, 0.6f)));
    ASSERT_TRUE(core.seq(SeqOp::ToggleStep, 0, 0, 0, 24, 1.0f));
    ASSERT_TRUE(core.seq(SeqOp::Play));
    std::vector<float> buf(2 * 256, 0.0f);
    core.render(buf.data(), 256);
    float m = 0;
    for (float v : buf) m = std::max(m, std::fabs(v));
    EXPECT_GT(m, 0.01f);
    EXPECT_TRUE(running(core.seqState()));
}

TEST(SequencerEngineTest, PadHitsAreRecordedThroughNoteOn) {
    EngineCore core;
    core.setSampleRate(kSr);
    core.loadSample(0, constSample(2000, 0.6f));
    core.seq(SeqOp::SetQuantize, 0, 0, 24);
    core.seq(SeqOp::SetRecording, 0, 0, 1);
    core.seq(SeqOp::Play);
    std::vector<float> buf(2 * 256, 0.0f);
    int rendered = 0;
    while (rendered < 7500) { core.render(buf.data(), 250); rendered += 250; }   // ~тик 30
    core.noteOn(0, 1.0f);
    core.render(buf.data(), 250);
    uint64_t w[kMaxPatternEvents];
    const int n = core.seqCopyPattern(0, w, kMaxPatternEvents);
    ASSERT_EQ(n, 1);
    EXPECT_EQ(unpackEvent(w[0]).tick, 24);
    EXPECT_EQ(unpackEvent(w[0]).pad, 0);
}

TEST(SequencerEngineTest, RejectsOutOfRangeArguments) {
    EngineCore core;
    EXPECT_FALSE(core.seq(SeqOp::Select, 64, 0));
    EXPECT_FALSE(core.seq(SeqOp::ToggleStep, 0, 64, 0, 24, 1.0f));
    EXPECT_TRUE(core.seq(SeqOp::Select, 63, 63));
}

TEST(SequencerEngineTest, LoadPatternDirectRestoresSavedPattern) {
    Rig r;
    r.step(3, 1, 0);
    r.step(3, 2, 96, 48);
    r.seq.apply(SeqOp::SetLock, 3, 1, 0, 24 | (kParamPan << 16), -1.0f);
    r.seq.apply(SeqOp::SetLength, 3, 0, 2, 0, 0);
    uint64_t ev[kMaxPatternEvents], lk[kMaxPatternEvents];
    const int n = r.seq.copyPatternWithLocks(3, ev, lk, kMaxPatternEvents);
    Rig r2;
    r2.seq.loadPatternDirect(3, ev, lk, n, r.seq.patternBars(3));
    uint64_t ev2[kMaxPatternEvents], lk2[kMaxPatternEvents];
    ASSERT_EQ(r2.seq.copyPatternWithLocks(3, ev2, lk2, kMaxPatternEvents), n);
    for (int i = 0; i < n; ++i) { EXPECT_EQ(ev2[i], ev[i]); EXPECT_EQ(lk2[i], lk[i]); }
    EXPECT_EQ(r2.seq.patternBars(3), 2);
}

TEST(SequencerEngineTest, ResampleCapturesOutput) {
    EngineCore core;
    core.setSampleRate(kSr);
    ASSERT_TRUE(core.loadSample(0, constSample(5000, 0.5f)));
    core.resampleStart();
    core.noteOn(0, 1.0f);
    std::vector<float> buf(2 * 256);
    for (int i = 0; i < 20; ++i) core.render(buf.data(), 256);
    const int n = core.resampleStop();
    EXPECT_EQ(n, 20 * 256);
    std::vector<float> got(static_cast<size_t>(n) * 2);
    EXPECT_EQ(core.resampleRead(got.data(), n), n);
    float m = 0;
    for (float v : got) m = std::max(m, std::fabs(v));
    EXPECT_GT(m, 0.1f);
    core.render(buf.data(), 256);   // после стопа больше не пишет
    EXPECT_EQ(core.resampleFrames(), n);
}
