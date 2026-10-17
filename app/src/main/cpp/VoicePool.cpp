#include "VoicePool.h"

namespace mpc {

void VoicePool::noteOn(const PadParams& p, int pad, float velocity) {
    if (!p.sample) return;

    // Choke: новый звук из группы быстро глушит все звучащие голоса той же группы.
    if (p.choke > 0) {
        const float chokeFrames = 0.005f * static_cast<float>(sampleRate_);
        for (auto& v : voices_)
            if (v.active() && !v.releasing() && v.chokeGroup() == p.choke) v.fastRelease(chokeFrames);
    }

    Voice* slot = nullptr;
    for (auto& v : voices_) {
        if (!v.active()) { slot = &v; break; }
    }
    if (!slot) {
        // Крадём: сначала самый старый из уже затухающих, иначе самый старый вообще.
        Voice* oldestReleasing = nullptr;
        Voice* oldest = &voices_[0];
        for (auto& v : voices_) {
            if (v.age() < oldest->age()) oldest = &v;
            if (v.releasing() && (!oldestReleasing || v.age() < oldestReleasing->age()))
                oldestReleasing = &v;
        }
        slot = oldestReleasing ? oldestReleasing : oldest;
        stealToDying(*slot);
    }

    if (!slot->start(p, pad, velocity, static_cast<double>(sampleRate_), ++counter_)) slot->kill();
}

// Украденный голос продолжает звучать в «dying»-слоте с коротким fade-out (~3 мс) — без щелчка.
void VoicePool::stealToDying(const Voice& v) {
    int idx = -1;
    for (int i = 0; i < kMaxDying; ++i) {
        if (!dying_[i].active()) { idx = i; break; }
    }
    if (idx < 0) { idx = nextDying_; nextDying_ = (nextDying_ + 1) % kMaxDying; }
    dying_[idx] = v;
    dying_[idx].fastRelease(0.003f * static_cast<float>(sampleRate_));
}

void VoicePool::noteOff(int pad) {
    for (auto& v : voices_) {
        if (v.active() && v.pad() == pad && !v.releasing() &&
            (v.mode() == PlayMode::Gate || v.mode() == PlayMode::Loop))
            v.release();
    }
}

void VoicePool::allNotesOff() {
    for (auto& v : voices_) v.release();
}

void VoicePool::killSample(const Sample* s) {
    for (auto& v : voices_) if (v.active() && v.sample() == s) v.kill();
    for (auto& v : dying_)  if (v.active() && v.sample() == s) v.kill();
}

void VoicePool::render(float* out, int numFrames) {
    for (auto& v : voices_) if (v.active()) v.render(out, numFrames);
    for (auto& v : dying_)  if (v.active()) v.render(out, numFrames);
}

int VoicePool::activeCount() const {
    int n = 0;
    for (const auto& v : voices_) if (v.active()) ++n;
    return n;
}

int VoicePool::dyingCount() const {
    int n = 0;
    for (const auto& v : dying_) if (v.active()) ++n;
    return n;
}

uint64_t VoicePool::activePadMask() const {
    uint64_t m = 0;
    for (const auto& v : voices_)
        if (v.active() && v.pad() >= 0 && v.pad() < 64) m |= (1ull << v.pad());
    return m;
}

}  // namespace mpc
