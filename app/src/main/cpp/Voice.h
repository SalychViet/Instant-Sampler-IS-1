// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

#pragma once
#include <cstdint>

#include "Sample.h"
#include "Types.h"

namespace mpc {

// Коэффициенты биквада (a0 = 1).
struct Biquad {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
};

// EQ пада (ISL-1): срез низа, срез верха и колокол. Выключенная полоса не считается.
struct PadEq {
    bool hpOn = false, lpOn = false;
    float hpHz = 120.0f, lpHz = 8000.0f;
    float bellHz = 1000.0f, bellDb = 0.0f;
    Biquad hp, lp, bell;
    bool bellOn() const { return bellDb != 0.0f; }
    bool active() const { return hpOn || lpOn || bellOn(); }
    void update(double sampleRate);   // пересчитать коэффициенты из частот
};

// Параметры пада. Читаются и пишутся ТОЛЬКО из аудио-потока (через команды).
struct PadParams {
    float volume = 1.0f;
    bool muted = false;
    float pan = 0.0f;
    float pitch = 0.0f;
    float start = 0.0f;
    float end = 1.0f;
    PlayMode mode = PlayMode::OneShot;
    bool reverse = false;
    int choke = 0;
    float attackMs = 0.0f;
    float releaseMs = 15.0f;
    float detune = 0.0f;   // центы
    // Огибающая высоты (ISL-1): 5 точек, X — доля региона по возрастанию, Y — полутоны.
    bool pEnvOn = false;
    float pEnvX[5] = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};
    float pEnvY[5] = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
    PadEq eq;
    const Sample* sample = nullptr;  // владелец — EngineCore
};

// Один голос: ресэмплер (кубическая интерполяция Эрмита) + огибающая + pan.
class Voice {
public:
    // false, если голос запустить нельзя (нет семпла, пустой регион).
    // offset01 — откуда начать внутри региона START–END (0 — с начала): петля ISL-1 подхватывается с середины.
    // fromSeq — ноту дал секвенсор (иначе — касание пада / петля ISL-1).
    bool start(const PadParams& p, int padIndex, float velocity, double engineRate, uint64_t age, double offset01 = 0.0,
               bool fromSeq = false);

    // ДОБАВЛЯЕТ numFrames стерео-кадров в out (× bus — громкость шины). false — голос закончился.
    bool render(float* out, int numFrames, float bus = 1.0f);

    void release();                  // обычный release из параметров пада
    void fastRelease(float frames);  // быстрый fade (choke, voice stealing)
    void kill() { active_ = false; }
    // Громкость/панорама пада поменялись, пока голос звучит: плавно (~10 мс) к новому уровню.
    void setLevel(float volume, float pan);
    // EQ пада поменялся, пока голос звучит (состояние фильтров сохраняется — без щелчка).
    void setEq(const PadEq& eq) { eq_ = eq; }

    bool active() const { return active_; }
    bool releasing() const { return state_ == State::Release; }
    int pad() const { return pad_; }
    int chokeGroup() const { return chokeGroup_; }
    PlayMode mode() const { return mode_; }
    // Секция петель (ISL-1): петля, запущенная касанием, а не секвенсором. Остальное — шина SEQ VOL.
    bool loopSection() const { return mode_ == PlayMode::Loop && !fromSeq_; }
    bool fromSeq() const { return fromSeq_; }
    uint64_t age() const { return age_; }
    const Sample* sample() const { return sample_; }

private:
    enum class State : uint8_t { Attack, Sustain, Release };

    const Sample* sample_ = nullptr;
    bool active_ = false;
    State state_ = State::Attack;
    PlayMode mode_ = PlayMode::OneShot;
    int pad_ = 0;
    int chokeGroup_ = 0;
    uint64_t age_ = 0;

    double pos_ = 0.0, inc_ = 1.0;
    double startF_ = 0.0, endF_ = 0.0;
    float gainL_ = 0.0f, gainR_ = 0.0f;
    float targetL_ = 0.0f, targetR_ = 0.0f;   // куда плавно идёт gain (VOL/PAN на ходу)
    float glide_ = 0.0f;                      // коэффициент сглаживания на кадр
    float velocity_ = 1.0f;
    bool fromSeq_ = false;
    // Огибающая высоты: копия точек пада на момент ноты; шаг пересчитывается каждые 32 кадра.
    bool envOn_ = false;
    float envX_[5] = {};
    float envY_[5] = {};
    double baseInc_ = 0.0;
    int envLeft_ = 0;
    PadEq eq_;
    float eqZ_[3][2][2] = {};   // состояние фильтров: полоса, канал, z1/z2
    float env_ = 0.0f, attackInc_ = 1.0f, releaseInc_ = 0.01f;
    float releaseRate_ = 0.01f;  // release из параметров пада (для обычного release())
};

}  // namespace mpc
