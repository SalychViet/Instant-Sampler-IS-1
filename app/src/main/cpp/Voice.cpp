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

}  // namespace

bool Voice::start(const PadParams& p, int padIndex, float velocity, double engineRate,
                  uint64_t age) {
    active_ = false;
    const Sample* s = p.sample;
    if (!s || s->frames < 2 || engineRate <= 0.0) return false;

    const double n = static_cast<double>(s->frames);
    const double st = std::clamp(static_cast<double>(p.start), 0.0, 1.0) * n;
    const double en = std::clamp(static_cast<double>(p.end), 0.0, 1.0) * n;
    if (en - st < 2.0) return false;

    sample_ = s;
    pad_ = padIndex;
    chokeGroup_ = p.choke;
    mode_ = p.mode;
    age_ = age;
    startF_ = st;
    endF_ = en;

    const double ratio = (static_cast<double>(s->sampleRate) / engineRate) *
                         std::exp2((static_cast<double>(p.pitch) + static_cast<double>(p.detune) * 0.01) / 12.0);
    inc_ = p.reverse ? -ratio : ratio;
    pos_ = p.reverse ? en - 1e-6 : st;

    const float v = std::clamp(velocity, 0.0f, 1.0f) * std::max(p.volume, 0.0f);
    const float ang = (std::clamp(p.pan, -1.0f, 1.0f) + 1.0f) * kQuarterPi;  // constant power
    gainL_ = v * std::cos(ang);
    gainR_ = v * std::sin(ang);

    const float framesPerMs = static_cast<float>(engineRate) / 1000.0f;
    attackInc_ = 1.0f / (std::max(p.attackMs, 1.0f) * framesPerMs);      // минимум 1 мс против щелчка
    releaseRate_ = 1.0f / (std::max(p.releaseMs, 2.0f) * framesPerMs);   // минимум 2 мс
    releaseInc_ = releaseRate_;
    env_ = 0.0f;
    state_ = State::Attack;
    active_ = true;
    return true;
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

bool Voice::render(float* out, int numFrames) {
    if (!active_) return false;

    const float* d = sample_->data.data();
    const int64_t last = sample_->frames - 1;
    const bool loop = (mode_ == PlayMode::Loop);
    const double len = endF_ - startF_;

    for (int i = 0; i < numFrames; ++i) {
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
        const float l = hermite(tap(i0 - 1, 0), tap(i0, 0), tap(i0 + 1, 0), tap(i0 + 2, 0), f);
        const float r = hermite(tap(i0 - 1, 1), tap(i0, 1), tap(i0 + 1, 1), tap(i0 + 2, 1), f);

        const float g = env_ * tail;
        out[2 * i] += l * g * gainL_;
        out[2 * i + 1] += r * g * gainR_;
        pos_ += inc_;
    }
    return true;
}

}  // namespace mpc
