#pragma once
#include <atomic>
#include <memory>
#include <mutex>

#include "Limiter.h"
#include "Sample.h"
#include "Sequencer.h"
#include "SpscQueue.h"
#include "Types.h"
#include "Voice.h"
#include "VoicePool.h"

namespace mpc {

enum class CmdType : uint8_t { NoteOn, NoteOff, SetParam, SetMaster, LoadSample, AllNotesOff, Seq };

struct Command {
    CmdType type = CmdType::NoteOn;
    uint8_t pad = 0;
    uint8_t pat = 0;   // Seq: паттерн
    uint8_t op = 0;    // Seq: SeqOp
    int32_t param = 0;
    int32_t b = 0;     // Seq: второй целый аргумент
    float value = 0.0f;
    Sample* sample = nullptr;  // LoadSample: владение переходит аудио-потоку
};

// Ядро движка без привязки к Oboe: его же используют host-тесты и (на этапе 5) offline-экспорт.
//
// Потоки:
//  * «control» методы (noteOn, setParam, loadSample...) можно звать из любых не-аудио потоков —
//    писатели сериализуются внутренним мьютексом, аудио-поток этот мьютекс никогда не берёт.
//  * render() — только из аудио-потока.
class EngineCore {
public:
    EngineCore();
    ~EngineCore();
    EngineCore(const EngineCore&) = delete;
    EngineCore& operator=(const EngineCore&) = delete;

    void setSampleRate(int sr);  // только пока аудио-поток не работает
    int sampleRate() const { return sampleRate_; }

    // ---- control-потоки ----
    bool noteOn(int pad, float velocity01);
    bool noteOff(int pad);
    bool allNotesOff();
    bool setParam(int pad, int paramId, float value);
    bool setMasterVolume(float v01);
    // nullptr = очистить пад. Старый семпл освободится позже, вне аудио-потока.
    bool loadSample(int pad, std::unique_ptr<Sample> sample);
    // Команда секвенсору (см. SeqOp: смысл a/b/f зависит от операции).
    bool seq(SeqOp op, int pat = 0, int pad = 0, int a = 0, int b = 0, float f = 0.0f);

    // ---- состояние секвенсора (любой поток) ----
    uint64_t seqState() const { return seq_.state(); }
    int seqCopyPattern(int pat, uint64_t* dst, int cap) const { return seq_.copyPattern(pat, dst, cap); }
    int seqCopyPatternWithLocks(int pat, uint64_t* dst, uint64_t* locks, int cap) const {
        return seq_.copyPatternWithLocks(pat, dst, locks, cap);
    }
    int seqPatternBars(int pat) const { return seq_.patternBars(pat); }
    int seqPatternCount(int pat) const { return seq_.patternCount(pat); }

    // ---- аудио-поток ----
    // out: interleaved stereo float. link — снимок Ableton Link на начало буфера (nullptr = без Link).
    void render(float* out, int numFrames, const LinkFrame* link = nullptr);

    // ---- метры (чтение откуда угодно) ----
    uint64_t activePadMask() const { return activeMask_.load(std::memory_order_relaxed); }
    float masterPeak() const { return peak_.load(std::memory_order_relaxed); }
    int activeVoiceCount() const { return activeVoices_.load(std::memory_order_relaxed); }

private:
    bool post(const Command& c);
    void drainCommands();
    void apply(const Command& c);
    void collectGarbageLocked();

    std::mutex producerMutex_;
    SpscQueue<Command, 1024> commands_;   // control -> audio
    SpscQueue<Sample*, 256> garbage_;     // audio -> control (на удаление)

    PadParams pads_[kNumPads];
    VoicePool voices_;
    Sequencer seq_{voices_, pads_};   // объявлен после pads_/voices_: держит ссылки на них
    Limiter limiter_;
    float master_ = 0.8f;
    bool ftzDone_ = false;
    int sampleRate_ = 48000;

    std::atomic<uint64_t> activeMask_{0};
    std::atomic<float> peak_{0.0f};
    std::atomic<int> activeVoices_{0};
};

}  // namespace mpc
