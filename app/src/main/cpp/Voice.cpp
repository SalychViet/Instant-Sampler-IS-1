// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

#include "Voice.h"

#include <algorithm>
#include <cmath>

namespace mpc {
namespace {

constexpr float kQuarterPi = 0.78539816339f;
constexpr double kTailFrames = 64.0;  // микро-fade в конце не зацикленного региона

inline float hermite(float ym1, float y0, float y1, float y2, float f) {
    const float c1 = 0.5f * (y1 - ym1);
    const float c2 = ym1 - 2.5f * y0 + 2.0f * y1 - 0.5f * y2;
    const float c3 = 0.5f * (y2 - ym1) + 1.5f * (y0 - y1);
    return ((c3 * f + c2) * f + c1) * f + y0;
}

// Коэффициенты по Audio EQ Cookbook (Р. Бристоу-Джонсон). Q 0.707 — без подъёма у частоты среза.
Biquad cookbook(double b0, double b1, double b2, double a0, double a1, double a2) {
    Biquad c;
    c.b0 = static_cast<float>(b0 / a0);
    c.b1 = static_cast<float>(b1 / a0);
    c.b2 = static_cast<float>(b2 / a0);
    c.a1 = static_cast<float>(a1 / a0);
    c.a2 = static_cast<float>(a2 / a0);
    return c;
}

constexpr double kPi = 3.14159265358979323846;
constexpr double kPassQ = 0.70710678118;
constexpr double kBellQ = 1.0;

double omega(double hz, double sampleRate) { return 2.0 * kPi * std::clamp(hz, 10.0, 0.45 * sampleRate) / sampleRate; }

// Transposed Direct Form II: устойчива к изменению коэффициентов на ходу.
inline float tick(const Biquad& c, float* z, float x) {
    const float y = c.b0 * x + z[0];
    z[0] = c.b1 * x - c.a1 * y + z[1];
    z[1] = c.b2 * x - c.a2 * y;
    return y;
}

}  // namespace

void PadEq::update(double sampleRate) {
    {
        const double w = omega(hpHz, sampleRate), cw = std::cos(w), al = std::sin(w) / (2.0 * kPassQ);
        hp = cookbook((1.0 + cw) / 2.0, -(1.0 + cw), (1.0 + cw) / 2.0, 1.0 + al, -2.0 * cw, 1.0 - al);
    }
    {
        const double w = omega(lpHz, sampleRate), cw = std::cos(w), al = std::sin(w) / (2.0 * kPassQ);
        lp = cookbook((1.0 - cw) / 2.0, 1.0 - cw, (1.0 - cw) / 2.0, 1.0 + al, -2.0 * cw, 1.0 - al);
    }
    {
        const double w = omega(bellHz, sampleRate), cw = std::cos(w), al = std::sin(w) / (2.0 * kBellQ);
        const double A = std::pow(10.0, static_cast<double>(bellDb) / 40.0);
        bell = cookbook(1.0 + al * A, -2.0 * cw, 1.0 - al * A, 1.0 + al / A, -2.0 * cw, 1.0 - al / A);
    }
}

bool Voice::start(const PadParams& p, int padIndex, float velocity, double engineRate,
                  uint64_t age, double offset01, bool fromSeq) {
    active_ = false;
    const Sample* s = p.sample;
    if (!s || s->frames < 2 || engineRate <= 0.0) return false;

    const double n = static_cast<double>(s->frames);
    const double st = std::clamp(static_cast<double>(p.start), 0.0, 1.0) * n;
    const double en = std::clamp(static_cast<double>(p.end), 0.0, 1.0) * n;
    if (en - st < 2.0) return false;

    sample_ = s;
    pad_ = padIndex;
    fromSeq_ = fromSeq;
    chokeGroup_ = p.choke;
    mode_ = p.mode;
    age_ = age;
    startF_ = st;
    endF_ = en;

    const double ratio = (static_cast<double>(s->sampleRate) / engineRate) *
                         std::exp2((static_cast<double>(p.pitch) + static_cast<double>(p.detune) * 0.01) / 12.0);
    inc_ = p.reverse ? -ratio : ratio;
    baseInc_ = inc_;
    envOn_ = p.pEnvOn;
    envLeft_ = 0;
    if (envOn_) {
        for (int k = 0; k < 5; ++k) {
            envX_[k] = p.pEnvX[k];
            envY_[k] = p.pEnvY[k];
        }
    }
    eq_ = p.eq;
    std::fill(&eqZ_[0][0][0], &eqZ_[0][0][0] + 12, 0.0f);
    const double off = std::clamp(offset01, 0.0, 0.999999) * (en - st);
    pos_ = p.reverse ? en - 1e-6 - off : st + off;

    const float v = p.muted ? 0.0f : std::clamp(velocity, 0.0f, 1.0f) * std::max(p.volume, 0.0f);
    const float ang = (std::clamp(p.pan, -1.0f, 1.0f) + 1.0f) * kQuarterPi;  // constant power
    gainL_ = v * std::cos(ang);
    gainR_ = v * std::sin(ang);
    targetL_ = gainL_;
    targetR_ = gainR_;
    velocity_ = std::clamp(velocity, 0.0f, 1.0f);
    glide_ = 1.0f - std::exp(-1.0f / (0.01f * static_cast<float>(engineRate)));

    const float framesPerMs = static_cast<float>(engineRate) / 1000.0f;
    attackInc_ = 1.0f / (std::max(p.attackMs, 1.0f) * framesPerMs);      // минимум 1 мс против щелчка
    releaseRate_ = 1.0f / (std::max(p.releaseMs, 2.0f) * framesPerMs);   // минимум 2 мс
    releaseInc_ = releaseRate_;
    env_ = 0.0f;
    state_ = State::Attack;
    active_ = true;
    return true;
}

void Voice::setLevel(float volume, float pan) {
    const float v = velocity_ * std::max(volume, 0.0f);
    const float ang = (std::clamp(pan, -1.0f, 1.0f) + 1.0f) * kQuarterPi;
    targetL_ = v * std::cos(ang);
    targetR_ = v * std::sin(ang);
}

void Voice::release() {
    if (!active_ || state_ == State::Release) return;
    state_ = State::Release;
    releaseInc_ = releaseRate_;   // затухание от текущего уровня env_
}

void Voice::fastRelease(float frames) {
    if (!active_) return;
    const float inc = 1.0f / std::max(frames, 1.0f);
    if (state_ == State::Release) {
        releaseInc_ = std::max(releaseInc_, inc);
    } else {
        state_ = State::Release;
        releaseInc_ = inc;
    }
}

bool Voice::render(float* out, int numFrames, float bus) {
    if (!active_) return false;

    const float* d = sample_->data.data();
    const int64_t last = sample_->frames - 1;
    const bool loop = (mode_ == PlayMode::Loop);
    const double len = endF_ - startF_;
    const bool bell = eq_.bellOn();
    const bool eqOn = bell || eq_.hpOn || eq_.lpOn;

    for (int i = 0; i < numFrames; ++i) {
        // --- огибающая высоты: X — место в регионе (для реверса — от конца) ---
        if (envOn_ && --envLeft_ <= 0) {
            envLeft_ = 32;
            const double ph = std::clamp((pos_ - startF_) / len, 0.0, 1.0);
            const float x = static_cast<float>(inc_ > 0.0 ? ph : 1.0 - ph);
            int k = 0;
            while (k < 3 && x > envX_[k + 1]) ++k;
            const float span = std::max(envX_[k + 1] - envX_[k], 1e-5f);
            const float t = std::clamp((x - envX_[k]) / span, 0.0f, 1.0f);
            const float semis = envY_[k] + (envY_[k + 1] - envY_[k]) * t;
            inc_ = baseInc_ * std::exp2(static_cast<double>(semis) / 12.0);
        }

        // --- границы региона ---
        if (inc_ > 0.0) {
            if (pos_ >= endF_) {
                if (loop) pos_ = startF_ + std::fmod(pos_ - startF_, len);
                else { active_ = false; return false; }
            }
        } else if (pos_ < startF_) {
            if (loop) pos_ = endF_ - std::fmod(endF_ - pos_, len);
            else { active_ = false; return false; }
        }

        // --- огибающая ---
        if (state_ == State::Attack) {
            env_ += attackInc_;
            if (env_ >= 1.0f) { env_ = 1.0f; state_ = State::Sustain; }
        } else if (state_ == State::Release) {
            env_ -= releaseInc_;
            if (env_ <= 0.0f) { active_ = false; return false; }
        }

        float tail = 1.0f;
        if (!loop) {
            const double dist = inc_ > 0.0 ? endF_ - pos_ : pos_ - startF_;
            if (dist < kTailFrames) tail = static_cast<float>(std::max(dist, 0.0) / kTailFrames);
        }

        // --- интерполяция ---
        const int64_t i0 = static_cast<int64_t>(pos_);
        const float f = static_cast<float>(pos_ - static_cast<double>(i0));
        auto tap = [&](int64_t k, int c) {
            k = k < 0 ? 0 : (k > last ? last : k);
            return d[2 * k + c];
        };
        float l = hermite(tap(i0 - 1, 0), tap(i0, 0), tap(i0 + 1, 0), tap(i0 + 2, 0), f);
        float r = hermite(tap(i0 - 1, 1), tap(i0, 1), tap(i0 + 1, 1), tap(i0 + 2, 1), f);
        if (eqOn) {
            if (eq_.hpOn) { l = tick(eq_.hp, eqZ_[0][0], l); r = tick(eq_.hp, eqZ_[0][1], r); }
            if (bell) { l = tick(eq_.bell, eqZ_[1][0], l); r = tick(eq_.bell, eqZ_[1][1], r); }
            if (eq_.lpOn) { l = tick(eq_.lp, eqZ_[2][0], l); r = tick(eq_.lp, eqZ_[2][1], r); }
        }

        if (gainL_ != targetL_ || gainR_ != targetR_) {
            gainL_ += (targetL_ - gainL_) * glide_;
            gainR_ += (targetR_ - gainR_) * glide_;
            if (std::fabs(targetL_ - gainL_) < 1e-6f && std::fabs(targetR_ - gainR_) < 1e-6f) {
                gainL_ = targetL_;
                gainR_ = targetR_;
            }
        }
        const float g = env_ * tail * bus;
        out[2 * i] += l * g * gainL_;
        out[2 * i + 1] += r * g * gainR_;
        pos_ += inc_;
    }
    return true;
}

}  // namespace mpc
