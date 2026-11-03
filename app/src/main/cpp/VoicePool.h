// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

#pragma once
#include <cstdint>

#include "Types.h"
#include "Voice.h"

namespace mpc {

// Пул голосов: полифония, choke-группы, voice stealing с fade-out.
// Все методы вызываются только из аудио-потока. Аллокаций нет.
class VoicePool {
public:
    void setSampleRate(int sr) { sampleRate_ = sr; }

    void noteOn(const PadParams& p, int pad, float velocity, double offset01 = 0.0, bool fromSeq = false);
    // SEQ VOL: громкость всего, кроме секции петель (секвенсор и one-shot с падов), 0..1.5.
    void setSeqVolume(float v) { seqTarget_ = v; }
    // LOOPER: громкость секции петель (запущенных касанием), 0..1.5.
    void setLoopVolume(float v) { loopTarget_ = v; }
    // Ноты секвенсора беззвучны (ISL-1: они уже есть в петле с SRC SELF); живые пады играют.
    void setSeqDuck(bool on) { duckTarget_ = on ? 0.0f : 1.0f; }
    bool seqDucked() const { return duckTarget_ == 0.0f; }
    // VOL/PAN пада поменялись — звучащие голоса этого пада плавно подстраиваются.
    void updateLevel(int pad, float volume, float pan);
    void updateEq(int pad, const PadEq& eq);
    void noteOff(int pad);
    void allNotesOff();                 // мягкий release всех
    void killSample(const Sample* s);   // жёстко остановить всё, что играет этот семпл

    // ДОБАВЛЯЕТ в out; loops — буфер секции петель (nullptr — всё в out).
    void render(float* out, int numFrames, float* loops = nullptr);

    int activeCount() const;
    int dyingCount() const;
    uint64_t activePadMask() const;     // бит i = пад i звучит

private:
    void stealToDying(const Voice& v);

    float seqGain_ = 1.0f, seqTarget_ = 1.0f;   // шина SEQ VOL (сглаживается поблочно)
    float loopGain_ = 1.0f, loopTarget_ = 1.0f; // шина LOOPER
    float duckGain_ = 1.0f, duckTarget_ = 1.0f; // ноты секвенсора
    Voice voices_[kMaxVoices];
    Voice dying_[kMaxDying];
    int nextDying_ = 0;
    uint64_t counter_ = 0;
    int sampleRate_ = 48000;
};

}  // namespace mpc
