// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

#pragma once
#include <algorithm>
#include <cmath>

namespace mpc {

// Простой мастер-лимитер: мгновенная атака, плавный release, жёсткий клип как страховка.
// Без lookahead — на очень резких пиках возможна лёгкая дисторсия (приемлемо для этапа 2).
class Limiter {
public:
    void setSampleRate(float sr) { releaseCoef_ = 1.0f - std::exp(-1.0f / (0.12f * sr)); }

    // Применяет preGain, лимитирует, возвращает пик блока после лимитера.
    float process(float* buf, int frames, float preGain) {
        float blockPeak = 0.0f;
        for (int i = 0; i < frames; ++i) {
            float l = buf[2 * i] * preGain;
            float r = buf[2 * i + 1] * preGain;
            float p = std::max(std::fabs(l), std::fabs(r));
            if (!(p < 1.0e6f)) { l = r = 0.0f; p = 0.0f; }   // ловит и NaN, и Inf

            const float target = p > kCeiling ? kCeiling / p : 1.0f;
            if (target < gain_) gain_ = target;
            else gain_ += (target - gain_) * releaseCoef_;

            l = std::clamp(l * gain_, -1.0f, 1.0f);
            r = std::clamp(r * gain_, -1.0f, 1.0f);
            buf[2 * i] = l;
            buf[2 * i + 1] = r;
            blockPeak = std::max(blockPeak, std::max(std::fabs(l), std::fabs(r)));
        }
        return blockPeak;
    }

private:
    static constexpr float kCeiling = 0.98f;
    float gain_ = 1.0f;
    float releaseCoef_ = 0.001f;
};

// Петли уступают секвенсору: если сумма не влезает в потолок, тише становятся только петли,
// а барабаны секвенсора остаются на своём уровне.
class LoopDucker {
public:
    void setSampleRate(float sr) { releaseCoef_ = 1.0f - std::exp(-1.0f / (0.12f * sr)); }

    // seq += loops × усиление (стерео, interleaved).
    void mix(float* seq, const float* loops, int frames) {
        for (int i = 0; i < frames; ++i) {
            float target = 1.0f;
            for (int c = 0; c < 2; ++c) {
                const float s = seq[2 * i + c];
                const float l = loops[2 * i + c];
                if (std::fabs(s + l) > kCeiling) {
                    target = std::min(target, std::max(0.0f, kCeiling - std::fabs(s)) / std::max(std::fabs(l), 1e-6f));
                }
            }
            if (target < gain_) gain_ = target;
            else gain_ += (target - gain_) * releaseCoef_;
            seq[2 * i] += loops[2 * i] * gain_;
            seq[2 * i + 1] += loops[2 * i + 1] * gain_;
        }
    }

private:
    static constexpr float kCeiling = 0.98f;
    float gain_ = 1.0f;
    float releaseCoef_ = 0.001f;
};

}  // namespace mpc
