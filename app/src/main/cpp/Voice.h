#pragma once
#include <cstdint>

#include "Sample.h"
#include "Types.h"

namespace mpc {

// Параметры пада. Читаются и пишутся ТОЛЬКО из аудио-потока (через команды).
struct PadParams {
    float volume = 1.0f;
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
    const Sample* sample = nullptr;  // владелец — EngineCore
};

// Один голос: ресэмплер (кубическая интерполяция Эрмита) + огибающая + pan.
class Voice {
public:
    // false, если голос запустить нельзя (нет семпла, пустой регион).
    bool start(const PadParams& p, int padIndex, float velocity, double engineRate, uint64_t age);

    // ДОБАВЛЯЕТ numFrames стерео-кадров в out. false — голос закончился.
    bool render(float* out, int numFrames);

    void release();                  // обычный release из параметров пада
    void fastRelease(float frames);  // быстрый fade (choke, voice stealing)
    void kill() { active_ = false; }

    bool active() const { return active_; }
    bool releasing() const { return state_ == State::Release; }
    int pad() const { return pad_; }
    int chokeGroup() const { return chokeGroup_; }
    PlayMode mode() const { return mode_; }
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
    float env_ = 0.0f, attackInc_ = 1.0f, releaseInc_ = 0.01f;
    float releaseRate_ = 0.01f;  // release из параметров пада (для обычного release())
};

}  // namespace mpc
