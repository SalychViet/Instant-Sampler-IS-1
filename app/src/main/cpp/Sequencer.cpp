#include "Sequencer.h"

#include <algorithm>
#include <cmath>

namespace mpc {
namespace {

constexpr float kMinBpm = 20.0f;
constexpr float kMaxBpm = 300.0f;
constexpr int kDefaultLen = 24;          // длина записанной ноты, пока пад не отпущен
constexpr double kClickSeconds = 0.03;   // щелчок метронома
constexpr double kClickTau = 0.008;
constexpr float kClickDownbeatHz = 1760.0f;
constexpr float kClickBeatHz = 1320.0f;

int velFrom01(float v) {
    if (!(v > 0.0f)) return 100;
    return std::clamp(static_cast<int>(std::lround(v * 127.0f)), 1, 127);
}

// Линейное квантование x из [lo, hi] в bits бит.
uint64_t q(float x, float lo, float hi, int bits) {
    const float maxv = static_cast<float>((1u << bits) - 1);
    const float n = std::clamp((x - lo) / (hi - lo), 0.0f, 1.0f);
    return static_cast<uint64_t>(std::lround(n * maxv));
}
float dq(uint64_t v, float lo, float hi, int bits) {
    const float maxv = static_cast<float>((1u << bits) - 1);
    return lo + (hi - lo) * static_cast<float>(v) / maxv;
}

constexpr int kLockBits[kLockCount] = {7, 7, 6, 10, 10, 8, 8};

}  // namespace

uint64_t packLocks(const ParamLocks& l) {
    uint64_t w = l.mask & 0x7Fu;
    int shift = 7;
    for (int i = 0; i < kLockCount; ++i) {
        const int bits = kLockBits[i];
        uint64_t f = 0;
        switch (i) {
            case kLockVol: f = q(l.v[i], 0.0f, 2.0f, bits); break;
            case kLockPan: f = q(l.v[i], -1.0f, 1.0f, bits); break;
            case kLockPitch: f = q(std::round(l.v[i]), -24.0f, 39.0f, bits); break;   // 64 значения: шаг 1 полутон
            case kLockStart:
            case kLockEnd: f = q(l.v[i], 0.0f, 1.0f, bits); break;
            case kLockAttack: f = q(l.v[i], 0.0f, 255.0f, bits); break;                 // 1 мс
            case kLockRelease: f = q(std::sqrt(std::max(0.0f, (l.v[i] - 5.0f) / 1995.0f)), 0.0f, 1.0f, bits); break;
        }
        w |= f << shift;
        shift += bits;
    }
    return w;
}

ParamLocks unpackLocks(uint64_t w) {
    ParamLocks l;
    l.mask = static_cast<uint32_t>(w & 0x7Fu);
    int shift = 7;
    for (int i = 0; i < kLockCount; ++i) {
        const int bits = kLockBits[i];
        const uint64_t f = (w >> shift) & ((1ull << bits) - 1);
        switch (i) {
            case kLockVol: l.v[i] = dq(f, 0.0f, 2.0f, bits); break;
            case kLockPan: l.v[i] = dq(f, -1.0f, 1.0f, bits); break;
            case kLockPitch: l.v[i] = std::round(dq(f, -24.0f, 39.0f, bits)); break;
            case kLockStart:
            case kLockEnd: l.v[i] = dq(f, 0.0f, 1.0f, bits); break;
            case kLockAttack: l.v[i] = dq(f, 0.0f, 255.0f, bits); break;
            case kLockRelease: {
                const float n = dq(f, 0.0f, 1.0f, bits);
                l.v[i] = 5.0f + n * n * 1995.0f;
                break;
            }
        }
        shift += bits;
    }
    return l;
}

void applyLocks(const ParamLocks& l, PadParams& p) {
    if (l.mask & (1u << kLockVol)) p.volume = l.v[kLockVol];
    if (l.mask & (1u << kLockPan)) p.pan = l.v[kLockPan];
    if (l.mask & (1u << kLockPitch)) p.pitch = l.v[kLockPitch];
    if (l.mask & (1u << kLockStart)) p.start = l.v[kLockStart];
    if (l.mask & (1u << kLockEnd)) p.end = l.v[kLockEnd];
    if (l.mask & (1u << kLockAttack)) p.attackMs = l.v[kLockAttack];
    if (l.mask & (1u << kLockRelease)) p.releaseMs = l.v[kLockRelease];
    if (p.end <= p.start) p.end = std::min(1.0f, p.start + 0.01f);
}

Sequencer::Sequencer(VoicePool& voices, PadParams* pads) : voices_(voices), pads_(pads) {
    for (int i = 0; i < kNumPads; ++i) {
        noteOffAt_[i] = -1;
        skipTick_[i] = -1;
        skipPass_[i] = -1;
        heldPat_[i] = -1;
        heldTick_[i] = 0;
        heldPressAbs_[i] = 0.0;
    }
    setSampleRate(sampleRate_);
    publishState();
}

void Sequencer::setSampleRate(int sr) {
    sampleRate_ = sr > 0 ? sr : 48000;
    setBpm(bpm_);
}

// ------------------------------------------------------------ паттерны

void Sequencer::beginWrite(Pattern& p) {
    p.ver.store(p.ver.load(std::memory_order_relaxed) + 1, std::memory_order_relaxed);
    std::atomic_thread_fence(std::memory_order_release);
}

void Sequencer::endWrite(Pattern& p) {
    p.ver.store(p.ver.load(std::memory_order_relaxed) + 1, std::memory_order_release);
}

int Sequencer::lowerBound(const Pattern& p, int tick) const {
    int lo = 0, hi = p.count.load(std::memory_order_relaxed);
    while (lo < hi) {
        const int mid = (lo + hi) / 2;
        if (eventAt(p, mid).tick < tick) lo = mid + 1; else hi = mid;
    }
    return lo;
}

int Sequencer::findEvent(const Pattern& p, int tick, int pad) const {
    const int n = p.count.load(std::memory_order_relaxed);
    for (int i = lowerBound(p, tick); i < n; ++i) {
        const SeqEvent e = eventAt(p, i);
        if (e.tick != tick) break;
        if (e.pad == pad) return i;
    }
    return -1;
}

// Вызывать между beginWrite/endWrite.
int Sequencer::insertEvent(Pattern& p, const SeqEvent& e, uint64_t locks) {
    const int n = p.count.load(std::memory_order_relaxed);
    if (n >= kMaxPatternEvents) return -1;
    const int at = lowerBound(p, e.tick + 1);   // после событий с тем же тиком
    for (int i = n; i > at; --i) {
        p.ev[i].store(p.ev[i - 1].load(std::memory_order_relaxed), std::memory_order_relaxed);
        p.lk[i].store(p.lk[i - 1].load(std::memory_order_relaxed), std::memory_order_relaxed);
    }
    p.ev[at].store(packEvent(e), std::memory_order_relaxed);
    p.lk[at].store(locks, std::memory_order_relaxed);
    p.count.store(n + 1, std::memory_order_relaxed);
    return at;
}

void Sequencer::removeAt(Pattern& p, int idx) {
    const int n = p.count.load(std::memory_order_relaxed);
    for (int i = idx; i + 1 < n; ++i) {
        p.ev[i].store(p.ev[i + 1].load(std::memory_order_relaxed), std::memory_order_relaxed);
        p.lk[i].store(p.lk[i + 1].load(std::memory_order_relaxed), std::memory_order_relaxed);
    }
    p.count.store(n - 1, std::memory_order_relaxed);
}

// Шаг [tick, tick+width): если у пада там есть ноты — убрать их все, иначе поставить ноту на tick.
void Sequencer::toggleStep(int pat, int pad, int tick, int width, float vel) {
    if (tick < 0 || tick >= kMaxBars * kBarTicks) return;
    width = std::max(1, width);
    Pattern& p = patterns_[pat];
    beginWrite(p);
    bool removed = false;
    for (int i = lowerBound(p, tick); i < p.count.load(std::memory_order_relaxed);) {
        const SeqEvent e = eventAt(p, i);
        if (e.tick >= tick + width) break;
        if (e.pad == pad) { removeAt(p, i); removed = true; } else { ++i; }
    }
    if (!removed) {
        SeqEvent e;
        e.tick = tick;
        e.pad = pad;
        e.vel = velFrom01(vel);
        e.len = std::min(width, 0xFFFF);
        insertEvent(p, e);
    }
    endWrite(p);
}

void Sequencer::erasePad(int pat, int pad) {
    Pattern& p = patterns_[pat];
    beginWrite(p);
    for (int i = 0; i < p.count.load(std::memory_order_relaxed);) {
        if (eventAt(p, i).pad == pad) removeAt(p, i); else ++i;
    }
    endWrite(p);
}

void Sequencer::clearPattern(int pat) {
    Pattern& p = patterns_[pat];
    beginWrite(p);
    p.count.store(0, std::memory_order_relaxed);
    endWrite(p);
}

void Sequencer::copyPatternTo(int dst, int src) {
    if (dst == src) return;
    const Pattern& s = patterns_[src];
    Pattern& d = patterns_[dst];
    beginWrite(d);
    const int n = s.count.load(std::memory_order_relaxed);
    for (int i = 0; i < n; ++i) {
        d.ev[i].store(s.ev[i].load(std::memory_order_relaxed), std::memory_order_relaxed);
        d.lk[i].store(s.lk[i].load(std::memory_order_relaxed), std::memory_order_relaxed);
    }
    d.count.store(n, std::memory_order_relaxed);
    d.bars.store(s.bars.load(std::memory_order_relaxed), std::memory_order_relaxed);
    endWrite(d);
}

// Закрепить значение параметра за нотами пада в шаге [tick, tick+width).
void Sequencer::setLock(int pat, int pad, int tick, int width, int paramId, float value) {
    const int slot = lockSlotOf(paramId);
    if (slot < 0 || !std::isfinite(value)) return;
    Pattern& p = patterns_[pat];
    beginWrite(p);
    const int n = p.count.load(std::memory_order_relaxed);
    for (int i = lowerBound(p, tick); i < n; ++i) {
        const SeqEvent e = eventAt(p, i);
        if (e.tick >= tick + std::max(1, width)) break;
        if (e.pad != pad) continue;
        ParamLocks l = unpackLocks(p.lk[i].load(std::memory_order_relaxed));
        l.mask |= 1u << slot;
        l.v[slot] = value;
        p.lk[i].store(packLocks(l), std::memory_order_relaxed);
    }
    endWrite(p);
}

void Sequencer::clearLocks(int pat, int pad, int tick, int width) {
    Pattern& p = patterns_[pat];
    beginWrite(p);
    const int n = p.count.load(std::memory_order_relaxed);
    for (int i = lowerBound(p, tick); i < n; ++i) {
        const SeqEvent e = eventAt(p, i);
        if (e.tick >= tick + std::max(1, width)) break;
        if (e.pad == pad) p.lk[i].store(0, std::memory_order_relaxed);
    }
    endWrite(p);
}

int Sequencer::copyPattern(int pat, uint64_t* dst, int cap) const {
    return copyPatternWithLocks(pat, dst, nullptr, cap);
}

int Sequencer::copyPatternWithLocks(int pat, uint64_t* dst, uint64_t* locks, int cap) const {
    if (pat < 0 || pat >= kNumPatterns || !dst || cap < 0) return -1;
    const Pattern& p = patterns_[pat];
    for (int attempt = 0; attempt < 8; ++attempt) {
        const uint32_t v1 = p.ver.load(std::memory_order_acquire);
        if (v1 & 1u) continue;
        const int n = std::min(p.count.load(std::memory_order_relaxed), cap);
        for (int i = 0; i < n; ++i) dst[i] = p.ev[i].load(std::memory_order_relaxed);
        if (locks)
            for (int i = 0; i < n; ++i) locks[i] = p.lk[i].load(std::memory_order_relaxed);
        std::atomic_thread_fence(std::memory_order_acquire);
        if (p.ver.load(std::memory_order_relaxed) == v1) return n;
    }
    return -1;
}

int Sequencer::patternBars(int pat) const {
    if (pat < 0 || pat >= kNumPatterns) return 1;
    return patterns_[pat].bars.load(std::memory_order_relaxed);
}

int Sequencer::patternCount(int pat) const {
    if (pat < 0 || pat >= kNumPatterns) return 0;
    return patterns_[pat].count.load(std::memory_order_relaxed);
}

// ------------------------------------------------------------ команды

void Sequencer::apply(SeqOp op, int pat, int pad, int a, int b, float f) {
    const bool patOk = pat >= 0 && pat < kNumPatterns;
    const bool padOk = pad >= 0 && pad < kNumPads;
    switch (op) {
        case SeqOp::Play: play(); break;
        case SeqOp::Stop: stop(); break;
        case SeqOp::SetRecording:
            recording_ = a != 0;
            if (!recording_)
                for (auto& h : heldPat_) h = -1;
            break;
        case SeqOp::SetBpm:
            if (std::isfinite(f)) setBpm(f);
            break;
        case SeqOp::SetQuantize: quantize_ = std::clamp(a, 0, kBarTicks); break;
        case SeqOp::SetMetronome:
            metroMode_ = std::clamp(a, 0, 2);
            if (std::isfinite(f)) metroLevel_ = std::clamp(f, 0.0f, 1.0f);
            break;
        case SeqOp::SetCountIn: countInBars_ = std::clamp(a, 0, 4); break;
        case SeqOp::Select:
            if (patOk) select(pat, a != 0);
            break;
        case SeqOp::ToggleStep:
            if (patOk && padOk) toggleStep(pat, pad, a, b, f);
            break;
        case SeqOp::ClearPattern:
            if (patOk) clearPattern(pat);
            break;
        case SeqOp::ErasePad:
            if (patOk && padOk) erasePad(pat, pad);
            break;
        case SeqOp::SetLength:
            if (patOk) patterns_[pat].bars.store(std::clamp(a, 1, kMaxBars), std::memory_order_relaxed);
            break;
        case SeqOp::CopyPattern:
            if (patOk && a >= 0 && a < kNumPatterns) copyPatternTo(pat, a);
            break;
        case SeqOp::SetLock:
            if (patOk && padOk) setLock(pat, pad, a, b & 0xFFFF, (b >> 16) & 0xFF, f);
            break;
        case SeqOp::ClearLocks:
            if (patOk && padOk) clearLocks(pat, pad, a, b);
            break;
        case SeqOp::SetLocksEnabled:
            locksEnabled_ = a != 0;
            break;
    }
    publishState();
}

void Sequencer::setBpm(float bpm) {
    bpm_ = std::clamp(bpm, kMinBpm, kMaxBpm);
    // Новый темп действует со следующего тика: он остаётся на своём кадре, дальше — новый шаг.
    anchorTick_ = absTick_;
    anchorFrame_ = nextTickFrame_;
    framesPerTick_ = static_cast<double>(sampleRate_) * 60.0 / (static_cast<double>(bpm_) * kPpq);
}

int64_t Sequencer::frameOfTick(int64_t absTick) const {
    const double off = static_cast<double>(absTick - anchorTick_) * framesPerTick_;
    return anchorFrame_ + static_cast<int64_t>(std::ceil(off - 1e-7));
}

double Sequencer::exactAbsTick() const {
    return static_cast<double>(absTick_) - static_cast<double>(nextTickFrame_ - absFrame_) / framesPerTick_;
}

void Sequencer::play() {
    if (running_) return;
    running_ = true;
    pos_ = 0;
    pass_ = 0;
    absTick_ = 0;
    anchorTick_ = 0;
    anchorFrame_ = absFrame_;
    nextTickFrame_ = absFrame_;
    for (int i = 0; i < kNumPads; ++i) {
        skipTick_[i] = -1;
        heldPat_[i] = -1;
    }
    countingIn_ = recording_ && countInBars_ > 0;
    countInLeft_ = countingIn_ ? countInBars_ * kBarTicks : 0;
    countInDone_ = 0;
}

void Sequencer::stop() {
    running_ = false;
    linkWaiting_ = false;
    countingIn_ = false;
    countInLeft_ = 0;
    pos_ = 0;
    queued_ = -1;
    releaseSeqNotes();
    for (auto& h : heldPat_) h = -1;
}

void Sequencer::releaseSeqNotes() {
    for (int i = 0; i < kNumPads; ++i) {
        if (noteOffAt_[i] >= 0) {
            voices_.noteOff(i);
            noteOffAt_[i] = -1;
        }
    }
}

void Sequencer::select(int pat, bool immediate) {
    if (!running_ || immediate) {
        current_ = pat;
        queued_ = -1;
        const int len = patternLenTicks(pat);
        if (pos_ >= len) pos_ %= len;
        return;
    }
    queued_ = pat == current_ ? -1 : pat;
}

// ------------------------------------------------------------ запись

void Sequencer::onPadDown(int pad, float velocity01) {
    if (pad < 0 || pad >= kNumPads) return;
    if (!running_ || !recording_ || countingIn_) return;
    const int len = patternLenTicks(current_);
    double exact = static_cast<double>(pos_) - static_cast<double>(nextTickFrame_ - absFrame_) / framesPerTick_;
    if (exact < 0.0) exact += len;   // ещё не наступил тик 0: это конец предыдущего прохода

    int tick = quantize_ > 0 ? static_cast<int>(std::lround(exact / quantize_)) * quantize_
                             : static_cast<int>(std::floor(exact));
    bool wrapped = false;
    if (tick >= len) {
        tick -= len;
        wrapped = true;
    }
    tick = std::clamp(tick, 0, len - 1);

    Pattern& p = patterns_[current_];
    beginWrite(p);
    const int idx = findEvent(p, tick, pad);
    if (idx >= 0) {
        SeqEvent e = eventAt(p, idx);
        e.vel = velFrom01(velocity01);
        p.ev[idx].store(packEvent(e), std::memory_order_relaxed);
    } else {
        SeqEvent e;
        e.tick = tick;
        e.pad = pad;
        e.vel = velFrom01(velocity01);
        e.len = kDefaultLen;
        insertEvent(p, e);
    }
    endWrite(p);

    // Живая нота уже прозвучала: тот же тик «впереди» в этом проходе пропускаем, чтобы не было флэма.
    if (wrapped) {
        skipTick_[pad] = tick;
        skipPass_[pad] = pass_ + 1;
    } else if (tick >= pos_) {
        skipTick_[pad] = tick;
        skipPass_[pad] = pass_;
    }

    heldPat_[pad] = current_;
    heldTick_[pad] = tick;
    heldPressAbs_[pad] = exactAbsTick();
}

void Sequencer::onPadUp(int pad) {
    if (pad < 0 || pad >= kNumPads || heldPat_[pad] < 0) return;
    const int pat = heldPat_[pad];
    heldPat_[pad] = -1;
    if (!running_) return;
    const int len = std::clamp(static_cast<int>(std::lround(exactAbsTick() - heldPressAbs_[pad])), 1, 0xFFFF);
    Pattern& p = patterns_[pat];
    const int idx = findEvent(p, heldTick_[pad], pad);
    if (idx < 0) return;
    beginWrite(p);
    SeqEvent e = eventAt(p, idx);
    e.len = len;
    p.ev[idx].store(packEvent(e), std::memory_order_relaxed);
    endWrite(p);
}

// ------------------------------------------------------------ воспроизведение

void Sequencer::processTick() {
    const int64_t now = absTick_;
    for (int i = 0; i < kNumPads; ++i) {
        if (noteOffAt_[i] >= 0 && noteOffAt_[i] <= now) {
            voices_.noteOff(i);
            noteOffAt_[i] = -1;
        }
    }

    if (countingIn_) {
        if (countInDone_ % kPpq == 0) startClick(countInDone_ % kBarTicks == 0);
        ++countInDone_;
        if (--countInLeft_ <= 0) countingIn_ = false;   // следующий тик — тик 0 паттерна
        ++absTick_;
        return;
    }

    int len = patternLenTicks(current_);
    if (pos_ >= len) {   // паттерн укоротили на ходу
        pos_ = 0;
        ++pass_;
    }

    const bool metro = metroMode_ == 1 || (metroMode_ == 2 && recording_);
    if (metro && pos_ % kPpq == 0) startClick(pos_ % kBarTicks == 0);

    const Pattern& p = patterns_[current_];
    const int n = p.count.load(std::memory_order_relaxed);
    for (int i = lowerBound(p, pos_); i < n; ++i) {
        const SeqEvent e = eventAt(p, i);
        if (e.tick != pos_) break;
        if (skipTick_[e.pad] == e.tick && skipPass_[e.pad] == pass_) {
            skipTick_[e.pad] = -1;
            continue;
        }
        PadParams pp = pads_[e.pad];
        if (locksEnabled_) {
            const uint64_t lw = p.lk[i].load(std::memory_order_relaxed);
            if (lw) applyLocks(unpackLocks(lw), pp);
        }
        voices_.noteOn(pp, e.pad, static_cast<float>(e.vel) / 127.0f);
        if (pp.mode == PlayMode::Gate || pp.mode == PlayMode::Loop)
            noteOffAt_[e.pad] = now + std::max(1, e.len);
    }

    ++pos_;
    ++absTick_;
    if (pos_ >= len) {
        pos_ = 0;
        ++pass_;
        if (queued_ >= 0) {
            current_ = queued_;
            queued_ = -1;
        }
    }
}

void Sequencer::startClick(bool downbeat) {
    if (metroLevel_ <= 0.0f) return;
    clickAmp_ = (downbeat ? 0.6f : 0.45f) * metroLevel_;
    clickInc_ = static_cast<float>(2.0 * 3.14159265358979323846 * (downbeat ? kClickDownbeatHz : kClickBeatHz) / sampleRate_);
    clickDecay_ = static_cast<float>(std::exp(-1.0 / (kClickTau * sampleRate_)));
    clickEnv_ = 1.0f;
    clickPos_ = 0;
    clickLeft_ = static_cast<int>(kClickSeconds * sampleRate_);
}

void Sequencer::renderClick(float* out, int numFrames) {
    for (int i = 0; i < numFrames && clickLeft_ > 0; ++i, --clickLeft_) {
        const float v = clickAmp_ * clickEnv_ * std::sin(clickInc_ * static_cast<float>(clickPos_));
        out[2 * i] += v;
        out[2 * i + 1] += v;
        clickEnv_ *= clickDecay_;
        ++clickPos_;
    }
}

void Sequencer::render(float* out, int numFrames) {
    int pos = 0;
    while (pos < numFrames) {
        while (running_ && absFrame_ >= nextTickFrame_) {
            processTick();
            nextTickFrame_ = frameOfTick(absTick_);
        }
        int seg = numFrames - pos;
        if (running_) seg = static_cast<int>(std::min<int64_t>(seg, nextTickFrame_ - absFrame_));
        voices_.render(out + 2 * pos, seg);
        renderClick(out + 2 * pos, seg);
        pos += seg;
        absFrame_ += seg;
    }
    publishState();
}

// ------------------------------------------------------------ Ableton Link

void Sequencer::linkSync(const LinkFrame& lf, int numFrames) {
    if (!lf.enabled) {
        if (linkActive_) {
            // Без Link продолжаем идти от собственных часов с текущего места.
            linkActive_ = false;
            linkWaiting_ = false;
            publishState();
        }
        return;
    }
    if (!linkActive_) {
        linkActive_ = true;
        linkWasPlaying_ = running_;   // сессия уже играет — подключимся к ней с ближайшего такта
        if (running_) linkTickOffset_ = lf.beat * kPpq - static_cast<double>(absTick_);
    }

    if (std::isfinite(lf.tempo)) {
        bpm_ = std::clamp(static_cast<float>(lf.tempo), kMinBpm, kMaxBpm);
        framesPerTick_ = static_cast<double>(sampleRate_) * 60.0 / (lf.tempo * kPpq);
    }

    // Общий старт/стоп: играет сессия — играем и мы (с ближайшей границы такта).
    if (lf.playing && !linkWasPlaying_ && !running_) linkWaiting_ = true;
    if (!lf.playing && linkWasPlaying_) {
        if (running_) stop();
        linkWaiting_ = false;
    }
    linkWasPlaying_ = lf.playing;

    const double g = lf.beat * kPpq;   // тик Link на первом кадре буфера
    if (linkWaiting_) {
        const double bar = std::max(1.0, lf.quantum) * kPpq;
        const double nextBar = std::ceil(g / bar - 1e-9) * bar;
        const double framesUntil = (nextBar - g) * framesPerTick_;
        if (framesUntil < numFrames) {
            linkWaiting_ = false;
            running_ = true;
            countingIn_ = false;   // с Link отсчёт не нужен: старт и так по такту
            countInLeft_ = 0;
            pos_ = 0;
            pass_ = 0;
            absTick_ = 0;
            for (int i = 0; i < kNumPads; ++i) {
                skipTick_[i] = -1;
                heldPat_[i] = -1;
            }
            linkTickOffset_ = nextBar;
            nextTickFrame_ = absFrame_ + static_cast<int64_t>(std::llround(std::max(0.0, framesUntil)));
            anchorTick_ = absTick_;
            anchorFrame_ = nextTickFrame_;
        }
        publishState();
        return;
    }

    if (!running_ || countingIn_) return;
    double dt = linkTickOffset_ + static_cast<double>(absTick_) - g;   // наш следующий тик — через dt тиков
    if (dt < -2.0 || dt > 4.0) {
        // Сильно разошлись (смена темпа в Ableton, «перемотка» сессии): прыгаем на позицию Link.
        const int64_t target = static_cast<int64_t>(std::ceil(g - linkTickOffset_));
        const int len = patternLenTicks(current_);
        const int64_t delta = target - absTick_;
        pos_ = static_cast<int>(((static_cast<int64_t>(pos_) + delta) % len + len) % len);
        absTick_ = target;
        dt = linkTickOffset_ + static_cast<double>(absTick_) - g;
    }
    nextTickFrame_ = absFrame_ + static_cast<int64_t>(std::llround(std::max(0.0, dt) * framesPerTick_));
    anchorTick_ = absTick_;
    anchorFrame_ = nextTickFrame_;
}

void Sequencer::publishState() {
    uint64_t s = static_cast<uint64_t>(countingIn_ ? 0 : pos_) & 0xFFFF;
    if (running_) s |= 1ull << 16;
    if (recording_) s |= 1ull << 17;
    if (countingIn_) s |= 1ull << 18;
    if (linkWaiting_) s |= 1ull << 19;
    s |= (static_cast<uint64_t>(current_) & 0xFF) << 24;
    s |= (static_cast<uint64_t>(queued_ + 1) & 0xFF) << 32;
    s |= (static_cast<uint64_t>(countingIn_ ? countInLeft_ : 0) & 0xFFFF) << 40;
    state_.store(s, std::memory_order_release);
}

}  // namespace mpc
