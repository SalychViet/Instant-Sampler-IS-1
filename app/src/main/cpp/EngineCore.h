// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

#pragma once
#include <atomic>
#include <memory>
#include <mutex>
#include <vector>

#include "KaossFx.h"
#include "Limiter.h"
#include "Sample.h"
#include "Sequencer.h"
#include "SpscQueue.h"
#include "Types.h"
#include "Voice.h"
#include "VoicePool.h"

namespace mpc {

enum class CmdType : uint8_t { NoteOn, NoteOff, SetParam, SetMaster, LoadSample, AllNotesOff, Seq, Export };

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
    // Нота с места внутри региона (0..1) — для петель ISL-1, которые подхватываются «на ходу».
    bool noteOnAt(int pad, float velocity01, double offset01);
    bool noteOff(int pad);
    bool allNotesOff();
    bool setParam(int pad, int paramId, float value);
    // SEQ VOL (ISL-1): громкость всего, кроме петель, запущенных касанием (секвенсор и one-shot).
    bool setSeqVolume(float v);
    // LOOPER (ISL-1): громкость всех петель, запущенных касанием.
    bool setLoopVolume(float v);
    bool setSeqDuck(bool on);
    // iLOOPER: петли идут мимо KAOSS, эффект достаётся только one-shot и секвенсору.
    void setLoopsDry(bool on) { loopsDry_.store(on, std::memory_order_relaxed); }
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
    int seqSongLength() const { return seq_.songLength(); }
    void seqLoadPatternDirect(int pat, const uint64_t* ev, const uint64_t* lk, int n, int bars) {
        seq_.loadPatternDirect(pat, ev, lk, n, bars);
    }

    // ---- ресемплинг: запись выхода (после эффектов и лимитера) ----
    // isolated (iLOOPER) — пишется только то, что не петли: секвенсор и пады с эффектами.
    void resampleStart(bool isolated = false) {
        resLen_.store(0, std::memory_order_relaxed);
        resIsolated_.store(isolated, std::memory_order_relaxed);
        resOn_.store(true, std::memory_order_release);
    }
    int resampleStop() {
        resOn_.store(false, std::memory_order_release);
        return resLen_.load(std::memory_order_acquire);
    }
    bool resampling() const { return resOn_.load(std::memory_order_relaxed); }
    int resampleFrames() const { return resLen_.load(std::memory_order_acquire); }
    // Копия записанного (interleaved stereo). Вызывать после resampleStop.
    int resampleRead(float* dst, int maxFrames) const;
    int seqSongAt(int i) const { return seq_.songAt(i); }

    // ---- LIVE REC: мастер (всё, что слышно) потоком в кольцо, Kotlin забирает и пишет в файл ----
    // Один читатель (поток записи файла). liveRead: сколько кадров скопировано; -1 — читатель отстал и звук потерян.
    // Кольцо выделяется один раз (аудио-поток пишет в него только при liveOn_), не в setSampleRate:
    // поток вывода переоткрывается сам (наушники, ошибки), а читатель в это время может идти.
    void liveStart() {
        if (liveRing_.empty()) {
            liveRing_.assign(static_cast<size_t>(kLiveFrames) * 2, 0.0f);
            liveMask_ = kLiveFrames - 1;
        }
        liveR_ = liveW_.load(std::memory_order_acquire);
        liveOn_.store(true, std::memory_order_release);
    }
    void liveStop() { liveOn_.store(false, std::memory_order_release); }
    bool liveOn() const { return liveOn_.load(std::memory_order_relaxed); }
    int liveRead(float* dst, int maxFrames);

    // ---- EXPORT: мастер (с эффектами) ровно на длину Song/паттерна, с самого начала ----
    // lengthTicks — длина в тиках секвенсора; bpm — текущий темп (по нему выделяется буфер).
    // Секвенсор перезапускается с начала цепочки, запись идёт, пока не наберётся вся длина.
    bool exportStart(int lengthTicks, float bpm);
    void exportCancel();
    // 0 — нет, 1 — ждём старта, 2 — пишем, 3 — готово.
    int exportState() const { return expState_.load(std::memory_order_acquire); }
    int exportFrames() const { return expLen_.load(std::memory_order_acquire); }
    int exportTarget() const { return expTarget_.load(std::memory_order_acquire); }
    // Готовый экспорт (exportState() == 3): interleaved stereo 16 бит.
    const int16_t* exportData() const { return expBuf_.data(); }
    void exportFree();

    // ---- аудио-поток ----
    // out: interleaved stereo float. link — снимок Ableton Link на начало буфера (nullptr = без Link).
    void render(float* out, int numFrames, const LinkFrame* link = nullptr);

    // ---- KAOSS (любой поток: атомики внутри) ----
    KaossFx& fx() { return fx_; }

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
    // control -> audio. При старте, пока поток вывода ещё не запущен, сюда разом попадают параметры
    // всех 64 падов (~700 команд), песня и семплы — с запасом, чтобы ничего не терялось.
    SpscQueue<Command, 4096> commands_;
    SpscQueue<Sample*, 256> garbage_;     // audio -> control (на удаление)

    PadParams pads_[kNumPads];
    VoicePool voices_;
    Sequencer seq_{voices_, pads_};   // объявлен после pads_/voices_: держит ссылки на них
    KaossFx fx_;
    Limiter limiter_;
    LoopDucker ducker_;
    bool ftzDone_ = false;
    int sampleRate_ = 48000;

    std::vector<float> clickBuf_;   // метроном отдельно от микса (мимо KAOSS, лимитера и записи)
    std::vector<float> loopBuf_;    // секция петель до сведения с секвенсором
    std::vector<float> resBuf_;
    std::atomic<bool> resOn_{false};
    std::atomic<bool> resIsolated_{false};
    std::atomic<bool> loopsDry_{false};

    static constexpr uint64_t kLiveFrames = 1u << 20;   // ~21 с при 48 кГц — запас, если запись файла задержится
    std::vector<float> liveRing_;              // kLiveFrames кадров × 2 канала
    uint64_t liveMask_ = 0;                    // кадров в кольце − 1
    std::atomic<bool> liveOn_{false};
    std::atomic<uint64_t> liveW_{0};           // сколько кадров записал аудио-поток (всего)
    uint64_t liveR_ = 0;                       // позиция читателя
    std::atomic<int> resLen_{0};

    std::vector<int16_t> expBuf_;   // выделяется в exportStart, пока аудио-поток в него не пишет
    std::atomic<int> expState_{0};
    std::atomic<int> expLen_{0};
    std::atomic<int> expTarget_{0};

    bool liveSilenced_[kNumPads] = {};   // живой удар заглушён дозаписью (аудио-поток)

    std::atomic<uint64_t> activeMask_{0};
    std::atomic<float> peak_{0.0f};
    std::atomic<int> activeVoices_{0};
};

}  // namespace mpc
