#pragma once
#include <atomic>
#include <cstdint>

#include "Types.h"
#include "Voice.h"
#include "VoicePool.h"

namespace mpc {

constexpr int kPpq = 96;                    // тиков на четвертную (PPQ в Kotlin)
constexpr int kBarTicks = kPpq * 4;         // такт 4/4
constexpr int kNumPatterns = 64;            // 4 банка x 16
constexpr int kMaxPatternEvents = 768;
constexpr int kMaxBars = 16;
constexpr int kUndoSlots = 8;               // скрытые копии паттернов для UNDO
constexpr int kMaxSong = 128;               // длина цепочки паттернов

// Значения ДОЛЖНЫ совпадать с SeqOp в Kotlin. Смысл аргументов (pat, pad, a, b, f):
enum class SeqOp : int {
    Play = 0,       // —
    Stop,           // —
    SetRecording,   // a: 0/1
    SetBpm,         // f: BPM (20..300)
    SetQuantize,    // a: сетка в тиках, 0 = без квантизации
    SetMetronome,   // a: 0 выкл, 1 всегда, 2 только при записи; f: громкость 0..1
    SetCountIn,     // a: тактов отсчёта перед записью
    Select,         // pat; a: 1 = сразу, иначе в конце цикла (если играет)
    ToggleStep,     // pat, pad; a: начальный тик, b: ширина шага в тиках, f: velocity 0..1
    ClearPattern,   // pat
    ErasePad,       // pat, pad
    SetLength,      // pat; a: тактов
    CopyPattern,    // pat: куда; a: откуда (ноты, параметры шагов и длина)
    SetLock,        // pat, pad; a: начальный тик, b: ширина | (параметр << 16), f: значение
    ClearLocks,     // pat, pad; a: начальный тик, b: ширина — снять параметры шагов
    SetLocksEnabled,// a: 0/1 — играть ли параметры шагов (P-LOCK)
    SnapshotPattern,// pat -> скрытый слот a (UNDO)
    RestorePattern, // скрытый слот a -> pat
    SongClear,      // очистить цепочку паттернов
    SongAppend,     // pat — добавить в конец цепочки
    SongEnable,     // a: 0/1 — играть цепочку вместо одного паттерна
};

// Параметры, которые можно «закрепить» за шагом (P-LOCK). Индексы бит в маске.
enum LockSlot : int { kLockVol = 0, kLockPan, kLockPitch, kLockStart, kLockEnd, kLockAttack, kLockRelease, kLockCount };

// paramId (ParamId из Types.h) -> слот P-LOCK, -1 — параметр не закрепляется.
inline int lockSlotOf(int paramId) {
    switch (paramId) {
        case kParamVolume: return kLockVol;
        case kParamPan: return kLockPan;
        case kParamPitch: return kLockPitch;
        case kParamStart: return kLockStart;
        case kParamEnd: return kLockEnd;
        case kParamAttackMs: return kLockAttack;
        case kParamReleaseMs: return kLockRelease;
        default: return -1;
    }
}

// Упаковка параметров шага в 63 бита: [0..6] маска, vol 7, pan 7, pitch 6, start 10, end 10, attack 8, release 8.
// Значения квантуются — точности хватает для ручек на экране.
struct ParamLocks {
    uint32_t mask = 0;
    float v[kLockCount] = {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 15.0f};
};
uint64_t packLocks(const ParamLocks& l);
ParamLocks unpackLocks(uint64_t w);
// Подставляет закреплённые значения в параметры пада.
void applyLocks(const ParamLocks& l, PadParams& p);

// Снимок Ableton Link на начало аудио-буфера (с учётом латентности вывода и ручного сдвига).
struct LinkFrame {
    bool enabled = false;
    double beat = 0.0;      // доля Link, которая прозвучит на первом кадре буфера
    double tempo = 120.0;
    bool playing = false;   // общий старт/стоп сессии
    double quantum = 4.0;   // доли в такте: старт по границе такта
};

struct SeqEvent {
    int tick = 0;   // 0..65535
    int pad = 0;    // 0..63
    int vel = 0;    // 1..127
    int len = 0;    // длина в тиках (для Gate/Loop)
};

// Упаковка: [0..15] tick, [16..23] pad, [24..31] vel, [32..47] len.
inline uint64_t packEvent(const SeqEvent& e) {
    return (static_cast<uint64_t>(e.tick) & 0xFFFF) |
           ((static_cast<uint64_t>(e.pad) & 0xFF) << 16) |
           ((static_cast<uint64_t>(e.vel) & 0xFF) << 24) |
           ((static_cast<uint64_t>(e.len) & 0xFFFF) << 32);
}

inline SeqEvent unpackEvent(uint64_t w) {
    SeqEvent e;
    e.tick = static_cast<int>(w & 0xFFFF);
    e.pad = static_cast<int>((w >> 16) & 0xFF);
    e.vel = static_cast<int>((w >> 24) & 0xFF);
    e.len = static_cast<int>((w >> 32) & 0xFFFF);
    return e;
}

// Пошаговый секвенсор с сэмплово-точными часами.
//
// Потоки: apply/onPadDown/onPadUp/render — только аудио-поток (единственный писатель).
// state/copyPattern/patternBars/patternCount — из любого потока: паттерны защищены seqlock-ом,
// состояние транспорта публикуется одним атомарным словом.
class Sequencer {
public:
    Sequencer(VoicePool& voices, PadParams* pads);

    void setSampleRate(int sr);

    void apply(SeqOp op, int pat, int pad, int a, int b, float f);
    void onPadDown(int pad, float velocity01);   // запись живой игры
    void onPadUp(int pad);

    // Голоса + метроном. ДОБАВЛЯЕТ в out (interleaved stereo); блок режется по границам тиков.
    void render(float* out, int numFrames);

    // Перед render (аудио-поток): подстроить темп и фазу под Link, стартовать/остановиться вместе с сессией.
    void linkSync(const LinkFrame& lf, int numFrames);

    // [0..15] позиция (следующий тик), 16 играет, 17 запись, 18 отсчёт, 19 ждём такт Link,
    // [24..31] паттерн, [32..39] очередь+1, [40..55] осталось тиков отсчёта, [56..63] номер в цепочке+1.
    uint64_t state() const { return state_.load(std::memory_order_acquire); }
    // Согласованный снимок событий (отсортированы по тику). -1 — снимок не получен (идёт правка).
    int copyPattern(int pat, uint64_t* dst, int cap) const;
    // То же + параметры шагов (locks[i] — для события dst[i], 0 = нет).
    int copyPatternWithLocks(int pat, uint64_t* dst, uint64_t* locks, int cap) const;
    // Восстановление проекта: записать паттерн целиком. Только пока аудио-поток не работает
    // (иначе писателей будет два).
    void loadPatternDirect(int pat, const uint64_t* ev, const uint64_t* locks, int n, int bars);
    int patternBars(int pat) const;
    int patternCount(int pat) const;
    // Цепочка паттернов (любой поток): длина и элементы.
    int songLength() const { return songLenPub_.load(std::memory_order_relaxed); }
    int songAt(int i) const { return i >= 0 && i < kMaxSong ? songPub_[i].load(std::memory_order_relaxed) : -1; }
    float bpm() const { return bpm_; }   // аудио-поток

private:
    struct Pattern {
        std::atomic<uint32_t> ver{0};   // seqlock: нечётное — идёт запись
        std::atomic<int> count{0};
        std::atomic<int> bars{1};
        std::atomic<uint64_t> ev[kMaxPatternEvents];
        std::atomic<uint64_t> lk[kMaxPatternEvents];   // параметры шагов, выровнены с ev
    };

    // ---- правка паттернов (аудио-поток) ----
    void beginWrite(Pattern& p);
    void endWrite(Pattern& p);
    SeqEvent eventAt(const Pattern& p, int i) const { return unpackEvent(p.ev[i].load(std::memory_order_relaxed)); }
    int lowerBound(const Pattern& p, int tick) const;
    int findEvent(const Pattern& p, int tick, int pad) const;
    int insertEvent(Pattern& p, const SeqEvent& e, uint64_t locks = 0);
    void removeAt(Pattern& p, int i);
    void toggleStep(int pat, int pad, int tick, int width, float vel);
    void erasePad(int pat, int pad);
    void clearPattern(int pat);
    void copyPatternTo(int dst, int src);
    void setLock(int pat, int pad, int tick, int width, int paramId, float value);
    void clearLocks(int pat, int pad, int tick, int width);

    // ---- транспорт ----
    void play();
    void stop();
    void select(int pat, bool immediate);
    void setBpm(float bpm);
    int patternLenTicks(int pat) const { return patterns_[pat].bars.load(std::memory_order_relaxed) * kBarTicks; }
    int64_t frameOfTick(int64_t absTick) const;
    double exactAbsTick() const;   // дробная позиция «сейчас» в абсолютных тиках
    void processTick();
    void releaseSeqNotes();
    void startClick(bool downbeat);
    void renderClick(float* out, int numFrames);
    void publishState();

    VoicePool& voices_;
    PadParams* pads_;
    Pattern patterns_[kNumPatterns + kUndoSlots];

    // Цепочка паттернов (song): пишет аудио-поток, копия для чтения — songPub_.
    int song_[kMaxSong] = {};
    int songLen_ = 0;
    bool songOn_ = false;
    int songIdx_ = 0;
    std::atomic<int> songPub_[kMaxSong];
    std::atomic<int> songLenPub_{0};
    void songStart();

    int sampleRate_ = 48000;
    float bpm_ = 120.0f;
    double framesPerTick_ = 250.0;
    int quantize_ = 24;
    int metroMode_ = 0;
    float metroLevel_ = 0.7f;
    int countInBars_ = 0;
    bool locksEnabled_ = true;

    bool running_ = false;
    bool recording_ = false;
    bool countingIn_ = false;
    int countInLeft_ = 0;
    int countInDone_ = 0;
    int current_ = 0;
    int queued_ = -1;
    int pos_ = 0;           // следующий тик паттерна
    int64_t pass_ = 0;      // номер прохода по паттерну

    // Часы: тик absTick стартует на кадре anchorFrame_ + ceil((absTick - anchorTick_) * framesPerTick_).
    int64_t absFrame_ = 0;
    int64_t absTick_ = 0;   // следующий абсолютный тик
    int64_t anchorTick_ = 0;
    int64_t anchorFrame_ = 0;
    int64_t nextTickFrame_ = 0;

    // Нота Gate/Loop от секвенсора: отпустить на абсолютном тике offAt (-1 — нет).
    int64_t noteOffAt_[kNumPads];
    // Нота, записанная квантизацией «вперёд», не должна сыграть второй раз в том же проходе.
    int skipTick_[kNumPads];
    int64_t skipPass_[kNumPads];
    // Удерживаемый при записи пад — чтобы записать длину ноты.
    int heldPat_[kNumPads];
    int heldTick_[kNumPads];
    double heldPressAbs_[kNumPads];

    // Метроном: синус с экспоненциальным затуханием.
    int clickLeft_ = 0;
    int clickPos_ = 0;
    float clickAmp_ = 0.0f;
    float clickInc_ = 0.0f;
    float clickDecay_ = 1.0f;
    float clickEnv_ = 0.0f;

    // Link: тик Link (доля * kPpq), на который пришёлся наш абсолютный тик 0.
    bool linkActive_ = false;
    bool linkWaiting_ = false;
    bool linkWasPlaying_ = false;
    double linkTickOffset_ = 0.0;

    std::atomic<uint64_t> state_{0};
};

}  // namespace mpc
