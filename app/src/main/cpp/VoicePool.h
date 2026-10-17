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

    void noteOn(const PadParams& p, int pad, float velocity);
    void noteOff(int pad);
    void allNotesOff();                 // мягкий release всех
    void killSample(const Sample* s);   // жёстко остановить всё, что играет этот семпл

    void render(float* out, int numFrames);  // ДОБАВЛЯЕТ в out

    int activeCount() const;
    int dyingCount() const;
    uint64_t activePadMask() const;     // бит i = пад i звучит

private:
    void stealToDying(const Voice& v);

    Voice voices_[kMaxVoices];
    Voice dying_[kMaxDying];
    int nextDying_ = 0;
    uint64_t counter_ = 0;
    int sampleRate_ = 48000;
};

}  // namespace mpc
