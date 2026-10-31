// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

#pragma once
#include <cstdint>

namespace mpc {

constexpr int kNumPads   = 64;   // 4 банка x 16
constexpr int kMaxVoices = 32;
constexpr int kMaxDying  = 8;    // голоса, которых «украли» и которые досчитывают fade-out

enum class PlayMode : int { OneShot = 0, Gate = 1, Loop = 2 };

// Идентификаторы параметров. Значения ДОЛЖНЫ совпадать с PadParam в Kotlin.
enum ParamId : int {
    kParamVolume = 0,    // 0..2
    kParamPan,           // -1..1
    kParamPitch,         // полутоны, -12..12 (допускается шире)
    kParamStart,         // 0..1 от длины семпла
    kParamEnd,           // 0..1
    kParamMode,          // PlayMode
    kParamReverse,       // 0/1
    kParamChoke,         // 0 = нет, 1..N = группа
    kParamAttackMs,
    kParamReleaseMs,
    kParamDetune,        // центы, -50..50 (точная подстройка к полутонам PITCH)
    kParamPEnvOn,        // ISL-1, огибающая высоты: 0/1
    kParamPEnvX0,        // 5 точек: положение 0..1 в регионе START–END (X0..X4 подряд)
    kParamPEnvY0 = kParamPEnvX0 + 5,   // значения, полутоны -24..24 (Y0..Y4 подряд)
    kParamMute = kParamPEnvY0 + 5,     // ISL-1, MUTE: 0/1 — пад играет беззвучно, фаза петли не теряется
    kParamCount
};

}  // namespace mpc
