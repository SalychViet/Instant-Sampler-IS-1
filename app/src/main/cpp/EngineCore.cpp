#include "EngineCore.h"

#include <algorithm>
#include <cstring>

#include "Denormals.h"

namespace mpc {

EngineCore::EngineCore() { setSampleRate(sampleRate_); }

EngineCore::~EngineCore() {
    // Аудио-поток к этому моменту остановлен.
    Command c;
    while (commands_.pop(c)) {
        if (c.type == CmdType::LoadSample) delete c.sample;
    }
    Sample* s;
    while (garbage_.pop(s)) delete s;
    for (auto& p : pads_) delete p.sample;
}

void EngineCore::setSampleRate(int sr) {
    sampleRate_ = sr > 0 ? sr : 48000;
    voices_.setSampleRate(sampleRate_);
    seq_.setSampleRate(sampleRate_);
    limiter_.setSampleRate(static_cast<float>(sampleRate_));
}

// ------------------------- control side -------------------------

bool EngineCore::post(const Command& c) { return commands_.push(c); }

bool EngineCore::noteOn(int pad, float velocity01) {
    if (pad < 0 || pad >= kNumPads) return false;
    Command c;
    c.type = CmdType::NoteOn;
    c.pad = static_cast<uint8_t>(pad);
    c.value = std::clamp(velocity01, 0.0f, 1.0f);
    std::lock_guard<std::mutex> lk(producerMutex_);
    return post(c);
}

bool EngineCore::noteOff(int pad) {
    if (pad < 0 || pad >= kNumPads) return false;
    Command c;
    c.type = CmdType::NoteOff;
    c.pad = static_cast<uint8_t>(pad);
    std::lock_guard<std::mutex> lk(producerMutex_);
    return post(c);
}

bool EngineCore::allNotesOff() {
    Command c;
    c.type = CmdType::AllNotesOff;
    std::lock_guard<std::mutex> lk(producerMutex_);
    return post(c);
}

bool EngineCore::setParam(int pad, int paramId, float value) {
    if (pad < 0 || pad >= kNumPads || paramId < 0 || paramId >= kParamCount) return false;
    Command c;
    c.type = CmdType::SetParam;
    c.pad = static_cast<uint8_t>(pad);
    c.param = paramId;
    c.value = value;
    std::lock_guard<std::mutex> lk(producerMutex_);
    return post(c);
}

bool EngineCore::setMasterVolume(float v) {
    Command c;
    c.type = CmdType::SetMaster;
    c.value = std::clamp(v, 0.0f, 1.5f);
    std::lock_guard<std::mutex> lk(producerMutex_);
    return post(c);
}

void EngineCore::collectGarbageLocked() {
    Sample* s;
    while (garbage_.pop(s)) delete s;
}

bool EngineCore::loadSample(int pad, std::unique_ptr<Sample> sample) {
    if (pad < 0 || pad >= kNumPads) return false;
    std::lock_guard<std::mutex> lk(producerMutex_);
    collectGarbageLocked();  // освобождаем семплы, отданные аудио-потоком ранее
    Command c;
    c.type = CmdType::LoadSample;
    c.pad = static_cast<uint8_t>(pad);
    c.sample = sample.release();
    if (!post(c)) {
        delete c.sample;
        return false;
    }
    return true;
}

bool EngineCore::seq(SeqOp op, int pat, int pad, int a, int b, float f) {
    if (pat < 0 || pat >= kNumPatterns || pad < 0 || pad >= kNumPads) return false;
    Command c;
    c.type = CmdType::Seq;
    c.op = static_cast<uint8_t>(op);
    c.pat = static_cast<uint8_t>(pat);
    c.pad = static_cast<uint8_t>(pad);
    c.param = a;
    c.b = b;
    c.value = f;
    std::lock_guard<std::mutex> lk(producerMutex_);
    return post(c);
}

// ------------------------- audio side -------------------------

void EngineCore::apply(const Command& c) {
    switch (c.type) {
        case CmdType::NoteOn:
            voices_.noteOn(pads_[c.pad], c.pad, c.value);
            seq_.onPadDown(c.pad, c.value);   // если идёт запись — нота попадёт в паттерн
            break;
        case CmdType::NoteOff:
            voices_.noteOff(c.pad);
            seq_.onPadUp(c.pad);
            break;
        case CmdType::Seq:
            seq_.apply(static_cast<SeqOp>(c.op), c.pat, c.pad, c.param, c.b, c.value);
            break;
        case CmdType::AllNotesOff:
            voices_.allNotesOff();
            break;
        case CmdType::SetMaster:
            master_ = c.value;
            break;
        case CmdType::LoadSample: {
            Sample* old = const_cast<Sample*>(pads_[c.pad].sample);
            if (old) voices_.killSample(old);  // чтобы голоса не читали освобождаемую память
            pads_[c.pad].sample = c.sample;
            if (old && !garbage_.push(old)) {
                // Очередь мусора переполнена (практически невозможно: она чистится при каждой загрузке).
                // Освобождать здесь нельзя (free в callback), поэтому сознательно допускаем утечку.
            }
            break;
        }
        case CmdType::SetParam: {
            PadParams& p = pads_[c.pad];
            const float v = c.value;
            switch (c.param) {
                case kParamVolume:    p.volume = std::clamp(v, 0.0f, 2.0f); break;
                case kParamPan:       p.pan = std::clamp(v, -1.0f, 1.0f); break;
                case kParamPitch:     p.pitch = std::clamp(v, -48.0f, 48.0f); break;
                case kParamStart:     p.start = std::clamp(v, 0.0f, 1.0f); break;
                case kParamEnd:       p.end = std::clamp(v, 0.0f, 1.0f); break;
                case kParamMode:
                    p.mode = static_cast<PlayMode>(std::clamp(static_cast<int>(v), 0, 2));
                    break;
                case kParamReverse:   p.reverse = v > 0.5f; break;
                case kParamChoke:     p.choke = std::max(0, static_cast<int>(v)); break;
                case kParamAttackMs:  p.attackMs = std::clamp(v, 0.0f, 2000.0f); break;
                case kParamReleaseMs: p.releaseMs = std::clamp(v, 0.0f, 5000.0f); break;
                default: break;
            }
            break;
        }
    }
}

void EngineCore::drainCommands() {
    Command c;
    for (int guard = 0; guard < 512 && commands_.pop(c); ++guard) apply(c);
}

void EngineCore::render(float* out, int numFrames, const LinkFrame* link) {
    if (!ftzDone_) { enableFlushToZero(); ftzDone_ = true; }

    drainCommands();
    if (link) seq_.linkSync(*link, numFrames);
    std::memset(out, 0, sizeof(float) * 2 * static_cast<size_t>(numFrames));
    seq_.render(out, numFrames);   // голоса + метроном, с разбивкой по тикам секвенсора
    const float peak = limiter_.process(out, numFrames, master_);

    peak_.store(peak, std::memory_order_relaxed);
    activeMask_.store(voices_.activePadMask(), std::memory_order_relaxed);
    activeVoices_.store(voices_.activeCount(), std::memory_order_relaxed);
}

}  // namespace mpc
