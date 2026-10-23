#include "KaossFx.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace mpc {
namespace {

constexpr float kPi = 3.14159265358979f;
constexpr double kTwoPi = 6.283185307179586;

// Деления для эффектов с синхронизацией (в долях-четвертях), X выбирает по порядку.
// Delay Time (X): слева коротко, справа длинно.
constexpr float kDelayBeats[] = {0.125f, 0.25f, 1.0f / 3.0f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f};
// Looper Beat (X): слева длинная петля, справа короткая.
constexpr float kLoopBeats[] = {4.0f, 2.0f, 1.0f, 0.5f, 0.25f, 0.125f, 1.0f / 16.0f};
// LFO Speed (X): период в долях, слева медленно.
constexpr float kLfoBeats[] = {8.0f, 4.0f, 2.0f, 1.0f, 0.5f, 0.25f, 0.125f, 1.0f / 16.0f};
// Ритмы Audio Gate (LF.26): 16 шагов по 1/16, X выбирает ритм (слева реже).
constexpr uint16_t kGatePatterns[8] = {0b1000100010001000, 0b1010101010101010, 0b1001001010010010, 0b1100110011001100,
                                       0b1011011010110110, 0b1110111011101110, 0b1101101101101101, 0b1111000011110000};
// Grain Cycle Speed (X).
constexpr float kGrainBeats[] = {0.5f, 0.25f, 0.125f, 1.0f / 16.0f, 1.0f / 32.0f};

template <size_t N>
float pick(const float (&a)[N], float x) {
    const int i = std::clamp(static_cast<int>(x * N), 0, static_cast<int>(N) - 1);
    return a[i];
}

// Freeverb: длины гребёнок/allpass при 44.1 кГц.
constexpr int kCombLen[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
constexpr int kApLen[4] = {556, 441, 341, 225};
constexpr int kStereoSpread = 23;

// Огибающая на стыках повторов: убирает щелчки.
float seam(int64_t d, int64_t len, int fade) {
    if (len <= 2 * fade) return 1.0f;
    const float a = std::min<float>(1.0f, static_cast<float>(d) / fade);
    const float b = std::min<float>(1.0f, static_cast<float>(len - d) / fade);
    return std::min(a, b);
}

float mix(float dry, float wet, float amount) { return dry + (wet - dry) * amount; }

// «Cutoff & Resonance» по Y у KP3: ФНЧ закрывается и резонирует, ФВЧ — открывается вверх.
float lpfCut(float y) { return 18000.0f * std::pow(2.0f, -y * 6.8f); }
float hpfCut(float y) { return 20.0f * std::pow(2.0f, y * 9.0f); }
float bpfCut(float y) { return 150.0f * std::pow(2.0f, y * 6.0f); }
float yReso(float y) { return 0.7f + y * 6.0f; }
// Мягкое ограничение резонансных пиков: тихий сигнал не трогает, всплески держит в пределах ~1.25.
float soft(float v) { return std::tanh(v * 0.8f) * 1.25f; }

}  // namespace

bool KaossFx::hasTails(int p) {
    switch (p) {
        case kKpLpfDelay: case kKpHpfDelay: case kKpBpfDelay: case kKpLpfReverb: case kKpHpfReverb:
        case kKpDelay: case kKpEchoBreak: case kKpTapeEcho: case kKpDubEcho: case kKpPingPong:
        case kKpPumpReverb: case kKpFreezeReverb: case kKpGateReverb: case kKpBreakVerb: case kKpLfoHpfDelay:
        case kKpSweepNoise: case kKpPitchDelay:
            return true;
        default:
            return false;
    }
}

KaossFx::KaossFx() { setSampleRate(sr_); }

void KaossFx::setSampleRate(int sr) {
    sr_ = sr > 0 ? sr : 48000;
    histSize_ = sr_ * 6;
    dlSize_ = sr_ * 3;
    for (int c = 0; c < 2; ++c) {
        hist_[c].assign(static_cast<size_t>(histSize_), 0.0f);
        dl_[c].assign(static_cast<size_t>(dlSize_), 0.0f);
        const float scale = static_cast<float>(sr_) / 44100.0f;
        for (int i = 0; i < kCombs; ++i)
            comb_[c][i].assign(static_cast<size_t>((kCombLen[i] + c * kStereoSpread) * scale) + 1, 0.0f);
        for (int i = 0; i < kAllpasses; ++i)
            ap_[c][i].assign(static_cast<size_t>((kApLen[i] + c * kStereoSpread) * scale) + 1, 0.0f);
    }
    histW_ = 0;
    dlW_ = 0;
    resetState();
}

void KaossFx::resetState() {
    for (int c = 0; c < 2; ++c) {
        std::fill(dl_[c].begin(), dl_[c].end(), 0.0f);
        for (auto& v : comb_[c]) std::fill(v.begin(), v.end(), 0.0f);
        for (auto& v : ap_[c]) std::fill(v.begin(), v.end(), 0.0f);
        for (int i = 0; i < kCombs; ++i) { combIdx_[c][i] = 0; combStore_[c][i] = 0; }
        for (int i = 0; i < kAllpasses; ++i) apIdx_[c][i] = 0;
        f_[c].reset();
        fbLp_[c].reset();
        for (auto& s : f2_[c]) s.reset();
        for (auto& s : f6_[c]) s.reset();
        for (auto& s : apState_[c]) s = 0;
        phaserFb_[c] = 0;
        crushHold_[c] = 0;
    }
    dlTime_ = 0;
    coefCountdown_ = 0;
    env_ = 0;
    gateEnv_ = 0;
    gateHold_ = 0;
    compGain_ = 1;
    compEnv_ = 0;
    lfoCycle_ = -1;
    grainSlot_ = -1;
}

void KaossFx::SvfCoef::set(float fc, float q, float sr) {
    fc = std::clamp(fc, 10.0f, sr * 0.45f);
    const float g = std::tan(kPi * fc / sr);
    k = 1.0f / std::max(0.3f, q);
    a1 = 1.0f / (1.0f + g * (g + k));
    a2 = g * a1;
    a3 = g * a2;
}

float KaossFx::svf(Svf& s, const SvfCoef& c, float v0, SvfOut out) {
    const float v3 = v0 - s.ic2;
    const float v1 = c.a1 * s.ic1 + c.a2 * v3;
    const float v2 = s.ic2 + c.a2 * s.ic1 + c.a3 * v3;
    s.ic1 = 2.0f * v1 - s.ic1;
    s.ic2 = 2.0f * v2 - s.ic2;
    switch (out) {
        case SvfOut::Low: return v2;
        case SvfOut::Band: return v1;
        case SvfOut::Notch: return v0 - c.k * v1;
        default: return v0 - c.k * v1 - v2;
    }
}

void KaossFx::svf3(Svf& s, const SvfCoef& c, float v0, float& lo, float& bp, float& hp) {
    const float v3 = v0 - s.ic2;
    const float v1 = c.a1 * s.ic1 + c.a2 * v3;
    const float v2 = s.ic2 + c.a2 * s.ic1 + c.a3 * v3;
    s.ic1 = 2.0f * v1 - s.ic1;
    s.ic2 = 2.0f * v2 - s.ic2;
    lo = v2;
    bp = v1;
    hp = v0 - c.k * v1 - v2;
}

float KaossFx::loopLen(float x, float bpm) const {
    return std::clamp(beatsToFrames(pick(kLoopBeats, x), bpm), 64.0f, static_cast<float>(histSize_ / 2));
}

// Компрессор: огибающая уровня -> плавное усиление compGain_ (ratio 6:1).
void KaossFx::compress(float level, float thresh, float attackMs, float releaseMs) {
    const float sr = static_cast<float>(sr_);
    compEnv_ += (level - compEnv_) * (level > compEnv_ ? 1.0f - std::exp(-1.0f / (0.0005f * sr)) : 1.0f - std::exp(-1.0f / (0.05f * sr)));
    float target = 1.0f;
    if (compEnv_ > thresh) target = std::pow(thresh / compEnv_, 1.0f - 1.0f / 6.0f);
    const float ms = target < compGain_ ? attackMs : releaseMs;
    compGain_ += (target - compGain_) * (1.0f - std::exp(-1.0f / (ms * 0.001f * sr)));
}

// Гранулярный сдвиг высоты по истории: две головки со сдвигом в полокна, окна-синусы.
float KaossFx::pitchTap(int ch, double& phase, double ratio, double win, bool advance) {
    if (advance) {
        phase += (1.0 - ratio) / win;
        phase -= std::floor(phase);
    }
    const double ph2 = std::fmod(phase + 0.5, 1.0);
    const double base = static_cast<double>(histW_ - 1) - 2.0;
    const float g1 = std::sin(kPi * static_cast<float>(phase));
    const float g2 = std::sin(kPi * static_cast<float>(ph2));
    return hist(ch, base - phase * win) * g1 + hist(ch, base - ph2 * win) * g2;
}

float KaossFx::histAt(int ch, int64_t pos) const {
    int64_t i = pos % histSize_;
    if (i < 0) i += histSize_;
    return hist_[ch][static_cast<size_t>(i)];
}

float KaossFx::hist(int ch, double pos) const {
    const int64_t i = static_cast<int64_t>(std::floor(pos));
    const float t = static_cast<float>(pos - static_cast<double>(i));
    return histAt(ch, i) * (1.0f - t) + histAt(ch, i + 1) * t;
}

float KaossFx::delayRead(int ch, float d) const {
    double pos = static_cast<double>(dlW_) - d;
    while (pos < 0) pos += dlSize_;
    const int i = static_cast<int>(pos);
    const float t = static_cast<float>(pos - i);
    const int j = (i + 1) % dlSize_;
    return dl_[ch][static_cast<size_t>(i % dlSize_)] * (1.0f - t) + dl_[ch][static_cast<size_t>(j)] * t;
}

void KaossFx::delayWrite(float l, float r) {
    dl_[0][static_cast<size_t>(dlW_)] = l;
    dl_[1][static_cast<size_t>(dlW_)] = r;
    dlW_ = (dlW_ + 1) % dlSize_;
}

float KaossFx::beatsToFrames(float beats, float bpm) const {
    return beats * 60.0f * static_cast<float>(sr_) / std::max(20.0f, bpm);
}

float KaossFx::noise() {
    noise_ = noise_ * 1664525u + 1013904223u;
    return static_cast<float>(noise_ >> 9) / 4194304.0f - 1.0f;
}

void KaossFx::reverb(float in, float room, float damp, float& outL, float& outR) {
    float out[2];
    for (int c = 0; c < 2; ++c) {
        float acc = 0;
        for (int k = 0; k < kCombs; ++k) {
            auto& buf = comb_[c][k];
            int& idx = combIdx_[c][k];
            const float o = buf[static_cast<size_t>(idx)];
            combStore_[c][k] = o * (1.0f - damp) + combStore_[c][k] * damp;
            buf[static_cast<size_t>(idx)] = in + combStore_[c][k] * room;
            idx = (idx + 1) % static_cast<int>(buf.size());
            acc += o;
        }
        for (int k = 0; k < kAllpasses; ++k) {
            auto& buf = ap_[c][k];
            int& idx = apIdx_[c][k];
            const float b = buf[static_cast<size_t>(idx)];
            buf[static_cast<size_t>(idx)] = acc + b * 0.5f;
            acc = b - acc;
            idx = (idx + 1) % static_cast<int>(buf.size());
        }
        out[c] = acc;
    }
    outL = out[0];
    outR = out[1];
}

void KaossFx::process(float* io, int n, float bpm) {
    const int prog = std::clamp(program_.load(std::memory_order_relaxed), 0, kKpCount - 1);
    const bool touch = touch_.load(std::memory_order_relaxed);
    const float xt = std::clamp(x_.load(std::memory_order_relaxed), 0.0f, 1.0f);
    const float yt = std::clamp(y_.load(std::memory_order_relaxed), 0.0f, 1.0f);
    const float mt = std::clamp(mix_.load(std::memory_order_relaxed), 0.0f, 1.0f);

    if (prog != curProgram_) {
        curProgram_ = prog;
        resetState();
        wet_ = 0.0f;
    }
    const bool started = touch && !wasTouch_;
    if (started) {
        capStart_ = histW_;
        capT_ = 0;
        tapePos_ = static_cast<double>(histW_);
        tapeSpeed_ = 1.0f;
        vinylPos_ = 0;
        gatePhase_ = 0;
        grainSlot_ = -1;
        prevY_ = yt;
        ys_ = yt;
    }
    wasTouch_ = touch;

    const float sr = static_cast<float>(sr_);
    const float wetCoef = 1.0f - std::exp(-1.0f / (0.006f * sr));     // ~6 мс
    const float xyCoef = 1.0f - std::exp(-1.0f / (0.025f * sr));      // ~25 мс
    const bool tails = hasTails(prog);
    // Без касания и без хвостов — эффект полностью выключен (кроме записи истории).
    const bool idle = !touch && wet_ < 1e-4f && !tails;
    const int fade = std::max(8, sr_ / 1000);
    const float beat = beatsToFrames(1.0f, bpm);

    for (int i = 0; i < n; ++i) {
        float l = io[2 * i], r = io[2 * i + 1];
        hist_[0][static_cast<size_t>(histW_ % histSize_)] = l;
        hist_[1][static_cast<size_t>(histW_ % histSize_)] = r;
        ++histW_;
        if (idle) continue;

        xs_ += (xt - xs_) * xyCoef;
        ys_ += (yt - ys_) * xyCoef;
        wet_ += ((touch ? 1.0f : 0.0f) - wet_) * wetCoef;
        const float x = xs_, y = ys_, w = wet_;
        const float dy = y - prevY_;   // движение пальца по Y (скретч)
        prevY_ = y;
        float ol = l, orr = r;
        const bool upd = --coefCountdown_ <= 0;
        if (upd) coefCountdown_ = 16;

        // ---- общие куски ----
        // Дилей со сглаженным временем; send — что подаём, fb — обратная связь. Возвращает выход линии.
        auto delayTap = [&](float target, float sendL, float sendR, float fb, float& dL, float& dR, float glide) {
            target = std::clamp(target, 16.0f, static_cast<float>(dlSize_ - 4));
            if (dlTime_ <= 0) dlTime_ = target;
            dlTime_ += (target - dlTime_) * glide;
            dL = delayRead(0, dlTime_);
            dR = delayRead(1, dlTime_);
            delayWrite(sendL + dL * fb, sendR + dR * fb);
        };
        // Фильтр «Cutoff & Resonance» по Y; type: 0 LPF, 1 HPF, 2 BPF+ (с перегрузом).
        auto yFilter = [&](int type, float in0, float in1, float& o0, float& o1) {
            if (upd) {
                const float fc = type == 0 ? lpfCut(y) : type == 1 ? hpfCut(y) : bpfCut(y);
                fc_.set(fc, type == 2 ? 2.0f + y * 6.0f : yReso(y), sr);
            }
            const SvfOut o = type == 0 ? SvfOut::Low : type == 1 ? SvfOut::High : SvfOut::Band;
            o0 = soft(svf(f_[0], fc_, in0, o));
            o1 = soft(svf(f_[1], fc_, in1, o));
            if (type == 2) {
                o0 = std::tanh(o0 * 2.5f) * 0.8f;
                o1 = std::tanh(o1 * 2.5f) * 0.8f;
            }
        };
        // Петля лупера: позиция 0..len в записи с момента касания.
        auto loopAt = [&](int ch, double pos) { return hist(ch, static_cast<double>(capStart_) + pos); };
        // Фаза LFO по темпу (X — скорость), 0..1.
        auto lfoStep = [&](float xs) {
            lfo_ += 1.0 / std::max(16.0f, beatsToFrames(pick(kLfoBeats, xs), bpm));
            lfo_ -= std::floor(lfo_);
            return static_cast<float>(lfo_);
        };

        switch (prog) {
            // ====================================================== 1 FILTER
            case kKpMorphFilt: {
                // FLt.1: X — частота (и форма: ФНЧ → полоса → ФВЧ), Y — резонанс.
                if (upd) fc_.set(40.0f * std::pow(2.0f, x * 8.6f), yReso(y) * 1.4f, sr);
                float lo, bp, hp, lo2, bp2, hp2;
                svf3(f_[0], fc_, l, lo, bp, hp);
                svf3(f_[1], fc_, r, lo2, bp2, hp2);
                const float a = (1.0f - x) * (1.0f - x), b = 2.0f * x * (1.0f - x) * 1.6f, c = x * x;
                ol = soft(lo * a + bp * b + hp * c);
                orr = soft(lo2 * a + bp2 * b + hp2 * c);
                break;
            }
            case kKpLpf:
            case kKpHpf:
            case kKpBpf: {
                // FLt.2/3: X — частота, Y — резонанс. FLt.4: X — частота, Y — баланс.
                if (upd) {
                    const float fc = 30.0f * std::pow(2.0f, x * 9.3f);
                    fc_.set(fc, prog == kKpBpf ? 3.0f : yReso(y) * 1.4f, sr);
                }
                const SvfOut o = prog == kKpLpf ? SvfOut::Low : prog == kKpHpf ? SvfOut::High : SvfOut::Band;
                ol = soft(svf(f_[0], fc_, l, o));
                orr = soft(svf(f_[1], fc_, r, o));
                if (prog == kKpBpf) {
                    ol = mix(l, ol * 2.0f, y);
                    orr = mix(r, orr * 2.0f, y);
                }
                break;
            }
            case kKp72dbLpf: {
                // FLt.5: шесть звеньев по 12 дБ — очень крутой срез. X — частота, Y — резонанс.
                if (upd) {
                    const float fc = 30.0f * std::pow(2.0f, x * 9.3f);
                    for (int k = 0; k < 6; ++k) fc6_[k].set(fc, k == 5 ? yReso(y) : 0.54f, sr);
                }
                for (int k = 0; k < 6; ++k) {
                    ol = svf(f6_[0][k], fc6_[k], ol, SvfOut::Low);
                    orr = svf(f6_[1][k], fc6_[k], orr, SvfOut::Low);
                }
                ol = soft(ol);
                orr = soft(orr);
                break;
            }
            case kKpLpfDelay:
            case kKpHpfDelay:
            case kKpBpfDelay: {
                // FLt.6–8: X — время задержки (по темпу), Y — фильтр (частота и резонанс).
                float fl, fr;
                yFilter(prog - kKpLpfDelay, l, r, fl, fr);
                fl = mix(l, fl, w);
                fr = mix(r, fr, w);
                float dL, dR;
                delayTap(beatsToFrames(pick(kDelayBeats, x), bpm), fl * w, fr * w, 0.5f, dL, dR, 0.002f);
                ol = fl + dL * 0.7f;
                orr = fr + dR * 0.7f;
                break;
            }
            case kKpLpfReverb:
            case kKpHpfReverb: {
                // FLt.9 / FL.10: X — глубина ревёрба, Y — фильтр.
                float fl, fr;
                yFilter(prog == kKpLpfReverb ? 0 : 1, l, r, fl, fr);
                fl = mix(l, fl, w);
                fr = mix(r, fr, w);
                float rl, rr;
                reverb((fl + fr) * 0.5f * w * 0.03f, 0.84f, 0.3f, rl, rr);
                ol = fl + rl * x * 2.2f;
                orr = fr + rr * x * 2.2f;
                break;
            }

            // ====================================================== 2 SPACE (хвосты доигрывают)
            case kKpDelay:
            case kKpPingPong: {
                // dLY.1 / dL.11: X — время (по темпу), Y — глубина (повторы и уровень).
                float dL, dR;
                const float fb = 0.15f + y * 0.75f;
                const float target = std::clamp(beatsToFrames(pick(kDelayBeats, x), bpm), 16.0f, static_cast<float>(dlSize_ - 4));
                if (dlTime_ <= 0) dlTime_ = target;
                dlTime_ += (target - dlTime_) * 0.002f;
                dL = delayRead(0, dlTime_);
                dR = delayRead(1, dlTime_);
                if (prog == kKpPingPong) delayWrite((l + r) * 0.5f * w + dR * fb, dL * fb);
                else delayWrite(l * w + dL * fb, r * w + dR * fb);
                const float lvl = 0.4f + 0.5f * y;
                ol = l + dL * lvl;
                orr = r + dR * lvl;
                break;
            }
            case kKpEchoBreak: {
                // dLY.2: пока держишь — живой звук обрывается, слышно только эхо. X — время, Y — повторы.
                float dL, dR;
                delayTap(beatsToFrames(pick(kDelayBeats, x), bpm), l * w, r * w, 0.2f + y * 0.75f, dL, dR, 0.002f);
                ol = l * (1.0f - w) + dL;
                orr = r * (1.0f - w) + dR;
                break;
            }
            case kKpTapeEcho:
            case kKpDubEcho: {
                // dLY.6: плёнка (дрожит время, мягкий перегруз), Y — глубина.
                // dLY.7: даб-эхо, долгие повторы, Y — тембр повторов.
                lfo_ += 0.7 / sr_;
                lfo_ -= std::floor(lfo_);
                float target = beatsToFrames(pick(kDelayBeats, x), bpm);
                if (prog == kKpTapeEcho) target *= 1.0f + 0.004f * static_cast<float>(std::sin(kTwoPi * lfo_));
                if (upd) fc_.set(prog == kKpDubEcho ? 400.0f * std::pow(2.0f, y * 4.0f) : 4500.0f, 0.7f, sr);
                target = std::clamp(target, 16.0f, static_cast<float>(dlSize_ - 4));
                if (dlTime_ <= 0) dlTime_ = target;
                dlTime_ += (target - dlTime_) * 0.0004f;   // плёнка «тянет» высоту при смене времени
                const float dL = delayRead(0, dlTime_), dR = delayRead(1, dlTime_);
                const float fb = prog == kKpDubEcho ? 0.78f : 0.2f + y * 0.7f;
                delayWrite(std::tanh(l * w + svf(fbLp_[0], fc_, dL, SvfOut::Low) * fb),
                           std::tanh(r * w + svf(fbLp_[1], fc_, dR, SvfOut::Low) * fb));
                const float lvl = prog == kKpDubEcho ? 0.8f : 0.4f + 0.5f * y;
                ol = l + dL * lvl;
                orr = r + dR * lvl;
                break;
            }
            case kKpPumpReverb: {
                // rEV.1: ревёрб «качается» на каждую долю. X — время ревёрба, Y — глубина качания.
                gatePhase_ += 1.0 / std::max(16.0f, beat);
                gatePhase_ -= std::floor(gatePhase_);
                const float ph = static_cast<float>(gatePhase_);
                const float pump = 1.0f - y * (1.0f - std::min(1.0f, ph * ph * 3.0f));
                ampEnv_ += (pump - ampEnv_) * (1.0f - std::exp(-1.0f / (0.002f * sr)));
                float rl, rr;
                reverb((l + r) * 0.5f * w * 0.03f, 0.7f + x * 0.28f, 0.25f, rl, rr);
                ol = l + rl * 1.6f * ampEnv_;
                orr = r + rr * 1.6f * ampEnv_;
                break;
            }
            case kKpFreezeReverb: {
                // rEV.2: пока держишь — хвост замирает. X — тембр, Y — баланс сухой/ревёрб.
                const float room = touch ? 0.9985f : 0.8f;
                float rl, rr;
                reverb((l + r) * 0.5f * (touch ? 0.012f : 0.0f), room, 0.05f + (1.0f - x) * 0.6f, rl, rr);
                ol = mix(l, l * 0.3f + rl * 2.0f, y);
                orr = mix(r, r * 0.3f + rr * 2.0f, y);
                break;
            }
            case kKpGateReverb: {
                // rEV.7: гейтованный ревёрб. X — время гейта, Y — глубина.
                const float a = std::fabs(l) + std::fabs(r);
                if (a > 0.08f && touch) gateHold_ = static_cast<int>((0.05f + x * 0.45f) * sr);
                const float target = gateHold_ > 0 ? 1.0f : 0.0f;
                if (gateHold_ > 0) --gateHold_;
                gateEnv_ += (target - gateEnv_) * (1.0f - std::exp(-1.0f / (0.004f * sr)));
                float rl, rr;
                reverb((l + r) * 0.5f * w * 0.04f, 0.9f, 0.2f, rl, rr);
                ol = l + rl * 2.4f * y * gateEnv_;
                orr = r + rr * 2.4f * y * gateEnv_;
                break;
            }
            case kKpJetSpace:
            case kKpJetMinus: {
                // Mod.3 JET+ / Mod.4 JET-: «реактивный» флэнджер без LFO. X — задержка, Y — обратная связь.
                const float target = (0.0002f + x * x * 0.009f) * sr;
                if (dlTime_ <= 0) dlTime_ = target;
                dlTime_ += (target - dlTime_) * 0.003f;
                const float fb = (prog == kKpJetMinus ? -1.0f : 1.0f) * y * 0.93f;
                const float dL = delayRead(0, dlTime_), dR = delayRead(1, dlTime_ + 2.0f);
                delayWrite(l + dL * fb, r + dR * fb);
                ol = (l + dL) * 0.65f;
                orr = (r + dR) * 0.65f;
                break;
            }
            case kKpGrainShift: {
                // Grn.1: на каждом шаге X-сетки повторяется крошечный кусок — «зерно». Y — длина зерна.
                const int64_t period = std::max<int64_t>(64, static_cast<int64_t>(beatsToFrames(pick(kGrainBeats, x), bpm)));
                const int64_t slot = capT_ / period;
                if (slot != grainSlot_) {
                    grainSlot_ = slot;
                    grainSrc_ = histW_ - period;
                }
                const int64_t glen = std::max<int64_t>(32, static_cast<int64_t>(static_cast<float>(period) * (0.08f + 0.92f * y)));
                const int64_t d = (capT_ % period) % glen;
                const float env = seam(d, glen, std::min<int>(fade, static_cast<int>(glen / 4)));
                ol = histAt(0, grainSrc_ + d) * env;
                orr = histAt(1, grainSrc_ + d) * env;
                ++capT_;
                break;
            }

            // ====================================================== 3 REPEAT (лупер: X — Looper Beat)
            case kKpLooperFR:
            case kKpBreakLoop:
            case kKpLoopRvsDelay:
            case kKpFreezeLoop:
            case kKpHighLooper:
            case kKpShuttleLoop:
            case kKpKp2Looper:
            case kKpVinylLoop:
            case kKpLoopBreaker: {
                if (!touch) break;   // отпустили — сразу живой звук
                const int64_t len = static_cast<int64_t>(loopLen(x, bpm));
                ++capT_;
                if (capT_ <= len) break;   // первый круг — записываем петлю, звучит живой звук
                const int64_t d = capT_ % len;
                const int64_t k = capT_ / len;
                const float env = seam(d, len, fade);
                const double L = static_cast<double>(len);
                float a, b;
                switch (prog) {
                    case kKpLooperFR: {
                        // LoP.1: Y — назад (внизу) / вперёд (вверху).
                        const double pos = y < 0.5f ? L - 1.0 - d : static_cast<double>(d);
                        a = loopAt(0, pos) * env;
                        b = loopAt(1, pos) * env;
                        break;
                    }
                    case kKpBreakLoop: {
                        // LoP.2: каждый круг петля тормозит до остановки. Y — время торможения.
                        const double t = static_cast<double>(d);
                        const double stop = y < 0.02f ? 1e12 : L * (0.15 + 0.85 * (1.0 - y)) * 2.0;
                        const double tt = std::min(t, stop);
                        const double pos = tt - tt * tt / (2.0 * stop);
                        const float g = static_cast<float>(std::max(0.0, 1.0 - t / stop));
                        a = loopAt(0, pos) * env * std::min(1.0f, g * 3.0f);
                        b = loopAt(1, pos) * env * std::min(1.0f, g * 3.0f);
                        break;
                    }
                    case kKpLoopRvsDelay: {
                        // LoP.3: петля + её же отражение задом наперёд. Y — ФВЧ.
                        const double rv = L - 1.0 - d;
                        float fl = loopAt(0, static_cast<double>(d)) + loopAt(0, rv) * 0.5f;
                        float fr = loopAt(1, static_cast<double>(d)) + loopAt(1, rv) * 0.5f;
                        if (upd) fc_.set(20.0f * std::pow(2.0f, y * 8.5f), 0.8f, sr);
                        a = svf(f_[0], fc_, fl, SvfOut::High) * env;
                        b = svf(f_[1], fc_, fr, SvfOut::High) * env;
                        break;
                    }
                    case kKpFreezeLoop:
                    case kKpKp2Looper: {
                        a = loopAt(0, static_cast<double>(d)) * env;
                        b = loopAt(1, static_cast<double>(d)) * env;
                        if (prog == kKpFreezeLoop) {
                            // LoP.4: Y — баланс живой звук / петля.
                            a = mix(l, a, 0.2f + 0.8f * y);
                            b = mix(r, b, 0.2f + 0.8f * y);
                        } else {
                            // LoP.9: Y — ФНЧ (вверху открыт).
                            if (upd) fc_.set(150.0f * std::pow(2.0f, y * 7.0f), 1.2f, sr);
                            a = svf(f_[0], fc_, a, SvfOut::Low);
                            b = svf(f_[1], fc_, b, SvfOut::Low);
                        }
                        break;
                    }
                    case kKpHighLooper: {
                        // LoP.5: петля вдвое быстрее (октавой выше). Y — баланс.
                        const int64_t d2 = (2 * d) % len;
                        const float e2 = seam(d2, len, fade) * env;
                        a = mix(l, loopAt(0, static_cast<double>(d2)) * e2, 0.2f + 0.8f * y);
                        b = mix(r, loopAt(1, static_cast<double>(d2)) * e2, 0.2f + 0.8f * y);
                        break;
                    }
                    case kKpShuttleLoop: {
                        // LoP.6: туда-обратно, как челнок. Y — ФНЧ.
                        const double pos = (k % 2 == 0) ? L - 1.0 - d : static_cast<double>(d);
                        if (upd) fc_.set(150.0f * std::pow(2.0f, y * 7.0f), 1.2f, sr);
                        a = svf(f_[0], fc_, loopAt(0, pos), SvfOut::Low) * env;
                        b = svf(f_[1], fc_, loopAt(1, pos), SvfOut::Low) * env;
                        break;
                    }
                    case kKpLoopBreaker: {
                        // LoP.8: движение пальца по Y «скретчит» петлю.
                        vinylPos_ += 1.0 + static_cast<double>(dy) * L;   // весь ход по Y = вся петля
                        vinylPos_ = std::fmod(vinylPos_, L);
                        if (vinylPos_ < 0) vinylPos_ += L;
                        a = loopAt(0, vinylPos_);
                        b = loopAt(1, vinylPos_);
                        break;
                    }
                    default: {   // kKpVinylLoop
                        // LP.10: Y — скорость и направление, как пластинка: внизу назад, в центре стоп, вверху ×2.
                        const double speed = (y * 2.0 - 1.0) * 2.0;
                        vinylPos_ = std::fmod(vinylPos_ + speed, L);
                        if (vinylPos_ < 0) vinylPos_ += L;
                        const float g = std::min(1.0f, static_cast<float>(std::fabs(speed)) * 3.0f);
                        a = loopAt(0, vinylPos_) * g;
                        b = loopAt(1, vinylPos_) * g;
                        break;
                    }
                }
                ol = a;
                orr = b;
                break;
            }
            case kKpSliceLoop: {
                // LoP.7: X — длина кусочка, Y — какой кусок последнего такта (до касания) играть.
                if (!touch) break;
                const int64_t len = static_cast<int64_t>(loopLen(x, bpm));
                const int64_t bar = std::min<int64_t>(static_cast<int64_t>(beat * 4.0f), histSize_ / 2);
                const int64_t slices = std::max<int64_t>(1, bar / len);
                const int64_t idx = std::min<int64_t>(slices - 1, static_cast<int64_t>(y * static_cast<float>(slices)));
                const int64_t d = capT_ % len;
                const int64_t src = capStart_ - bar + idx * len;
                const float env = seam(d, len, fade);
                ol = histAt(0, src + d) * env;
                orr = histAt(1, src + d) * env;
                ++capT_;
                break;
            }

            // ====================================================== 4 MOD
            case kKpSweepNoise: {
                // Шум через полосовой фильтр — переход/«взлёт». X — частота (вправо выше),
                // Y — хвост: сколько шума уходит в дилей и ревёрб и как долго он тянется.
                if (upd) fc_.set(60.0f * std::pow(2.0f, x * 8.2f), 3.0f, sr);
                const float nl = svf(f_[0], fc_, noise(), SvfOut::Band) * fc_.k * 1.4f * w;
                const float nr = svf(f_[1], fc_, noise(), SvfOut::Band) * fc_.k * 1.4f * w;
                // дилей 3/16 пинг-понгом
                const float target = std::clamp(beatsToFrames(0.75f, bpm), 16.0f, static_cast<float>(dlSize_ - 4));
                if (dlTime_ <= 0) dlTime_ = target;
                dlTime_ += (target - dlTime_) * 0.002f;
                const float dL = delayRead(0, dlTime_), dR = delayRead(1, dlTime_);
                const float fb = 0.3f + y * 0.6f;
                delayWrite((nl + nr) * 0.5f * y + dR * fb, dL * fb);
                float rl, rr;
                reverb((nl + nr + dL + dR) * 0.5f * 0.03f * y, 0.75f + y * 0.23f, 0.3f, rl, rr);
                ol = l + nl + (dL + rl * 1.6f) * y;
                orr = r + nr + (dR + rr * 1.6f) * y;
                break;
            }
            case kKpPitchDelay: {
                // Md.18: сдвиг высоты ±12 полутонов (X) и дилей с повторами (Y — глубина), хвост доигрывает.
                const double semis = std::round((x - 0.5f) * 24.0f);
                const double ratio = std::pow(2.0, semis / 12.0);
                const double win = 0.05 * sr_;
                const float pl = pitchTap(0, pitchPh_, ratio, win, true);
                const float pr = pitchTap(1, pitchPh_, ratio, win, false);
                const float sl = mix(l, pl, w), sr2 = mix(r, pr, w);
                float dL, dR;
                delayTap(beatsToFrames(0.75f, bpm), sl * w * y, sr2 * w * y, 0.25f + y * 0.6f, dL, dR, 0.002f);
                ol = sl + dL * 0.8f;
                orr = sr2 + dR * 0.8f;
                break;
            }
            case kKpVinylBreak:
            case kKpBreakVerb: {
                // Mod.1: пластинку останавливают рукой. X — время торможения, Y — скретч.
                // Mod.2: то же по темпу и с ревёрбом (хвост доигрывает).
                float vl = l, vr = r;
                if (touch) {
                    const float brake = prog == kKpBreakVerb ? beatsToFrames(0.25f * std::pow(2.0f, x * 4.0f), bpm) / sr
                                                             : 0.08f + x * 2.9f;
                    tapeSpeed_ = std::max(0.0f, tapeSpeed_ - 1.0f / (brake * sr));
                    tapePos_ += tapeSpeed_ + static_cast<double>(dy) * sr * 0.6;   // палец по Y крутит пластинку
                    const double maxBack = histSize_ - 4;
                    if (static_cast<double>(histW_) - tapePos_ > maxBack) tapePos_ = static_cast<double>(histW_) - maxBack;
                    if (tapePos_ > static_cast<double>(histW_ - 2)) tapePos_ = static_cast<double>(histW_ - 2);
                    const float g = std::min(1.0f, tapeSpeed_ * 4.0f + std::fabs(dy) * 400.0f);
                    vl = hist(0, tapePos_ - 1) * g;
                    vr = hist(1, tapePos_ - 1) * g;
                }
                vl = mix(l, vl, w);
                vr = mix(r, vr, w);
                if (prog == kKpBreakVerb) {
                    float rl, rr;
                    reverb((vl + vr) * 0.5f * w * 0.03f, 0.88f, 0.25f, rl, rr);
                    vl += rl * 1.5f;
                    vr += rr * 1.5f;
                }
                ol = vl;
                orr = vr;
                break;
            }
            case kKpManuPhaser: {
                // Mod.5: фазер без LFO. X — частота, Y — резонанс.
                const float fc = 150.0f * std::pow(2.0f, x * 5.8f);
                const float t = std::tan(kPi * std::min(fc, sr * 0.45f) / sr);
                const float a = (t - 1.0f) / (t + 1.0f);
                const float fb = y * 0.85f;
                float in[2] = {l, r};
                for (int c = 0; c < 2; ++c) {
                    float v = in[c] + phaserFb_[c] * fb;
                    for (int s = 0; s < 6; ++s) {
                        const float o = a * v + apState_[c][s];
                        apState_[c][s] = v - a * o;
                        v = o;
                    }
                    phaserFb_[c] = v;
                    in[c] = (in[c] + v) * 0.6f;
                }
                ol = in[0];
                orr = in[1];
                break;
            }
            case kKpTalkFilter:
            case kKpDigiTalk: {
                // Mod.6 / Mod.7: «говорящий» фильтр. X — первая форманта, Y — вторая.
                if (upd) {
                    fc2_[0].set(240.0f * std::pow(2.0f, x * 2.0f), 7.0f, sr);    // 240–960 Гц
                    fc2_[1].set(650.0f * std::pow(2.0f, y * 2.1f), 9.0f, sr);    // 650–2800 Гц
                }
                float in[2] = {l, r};
                if (prog == kKpDigiTalk) {
                    // цифровой: 6 бит и частота дискретизации /6
                    crushCount_ += 1.0f;
                    if (crushCount_ >= 6.0f) {
                        crushCount_ -= 6.0f;
                        crushHold_[0] = std::round(l * 32.0f) / 32.0f;
                        crushHold_[1] = std::round(r * 32.0f) / 32.0f;
                    }
                    in[0] = crushHold_[0];
                    in[1] = crushHold_[1];
                }
                float out[2];
                for (int c = 0; c < 2; ++c)
                    out[c] = soft((svf(f2_[c][0], fc2_[0], in[c], SvfOut::Band) * fc2_[0].k +
                                   svf(f2_[c][1], fc2_[1], in[c], SvfOut::Band) * fc2_[1].k * 0.8f) * 4.0f);
                ol = out[0];
                orr = out[1];
                break;
            }
            case kKpDuckComp: {
                // Mod.8: «качает» от собственных ударов. X — чувствительность, Y — глубина.
                const float a = std::max(std::fabs(l), std::fabs(r)) * (1.0f + x * 10.0f);
                env_ += (a - env_) * (a > env_ ? 1.0f - std::exp(-1.0f / (0.001f * sr)) : 1.0f - std::exp(-1.0f / (0.18f * sr)));
                const float g = 1.0f - y * std::min(1.0f, env_);
                ampEnv_ += (g - ampEnv_) * (1.0f - std::exp(-1.0f / (0.003f * sr)));
                ol = l * ampEnv_;
                orr = r * ampEnv_;
                break;
            }
            case kKpCompressor: {
                // Mod.9: X — чувствительность (порог ниже), Y — атака (вверху медленная — щелчок удара остаётся).
                const float thresh = std::pow(10.0f, (-x * 36.0f) / 20.0f);
                compress(std::max(std::fabs(l), std::fabs(r)), thresh, 0.1f + y * 60.0f, 150.0f);
                const float makeup = 1.0f + x * 2.5f;
                ol = l * compGain_ * makeup;
                orr = r * compGain_ * makeup;
                break;
            }

            // ====================================================== 5 LFO
            case kKpJagFilter: {
                // LFO.1: пила по темпу открывает ФНЧ. X — скорость, Y — пила назад (внизу) / вперёд (вверху).
                const float ph = lfoStep(x);
                const float m = y < 0.5f ? 1.0f - ph : ph;
                if (upd) fc_.set(150.0f * std::pow(2.0f, m * 6.8f), 4.0f, sr);
                ol = soft(svf(f_[0], fc_, l, SvfOut::Low));
                orr = soft(svf(f_[1], fc_, r, SvfOut::Low));
                break;
            }
            case kKpLfoLpf:
            case kKpLfoHpf:
            case kKpLfoBpf: {
                // LFO.2–4: фильтр качается по темпу. X — скорость, Y — резонанс.
                const float m = 0.5f - 0.5f * static_cast<float>(std::cos(kTwoPi * lfoStep(x)));
                if (upd) {
                    const float fc = prog == kKpLfoLpf ? 200.0f * std::pow(2.0f, m * 6.3f)
                                   : prog == kKpLfoHpf ? 40.0f * std::pow(2.0f, m * 7.0f)
                                                       : 200.0f * std::pow(2.0f, m * 5.5f);
                    fc_.set(fc, prog == kKpLfoBpf ? 2.0f + y * 8.0f : yReso(y) * 1.3f, sr);
                }
                const SvfOut o = prog == kKpLfoLpf ? SvfOut::Low : prog == kKpLfoHpf ? SvfOut::High : SvfOut::Band;
                ol = soft(svf(f_[0], fc_, l, o));
                orr = soft(svf(f_[1], fc_, r, o));
                if (prog == kKpLfoBpf) {
                    ol = std::tanh(ol * 2.5f) * 0.8f;
                    orr = std::tanh(orr * 2.5f) * 0.8f;
                }
                break;
            }
            case kKpLfoHpfDelay: {
                // LFO.5: ФВЧ качается (такт) + дилей. X — время дилея, Y — уровень ФВЧ и глубина дилея.
                lfo_ += 1.0 / std::max(16.0f, beat * 4.0f);
                lfo_ -= std::floor(lfo_);
                const float m = 0.5f - 0.5f * static_cast<float>(std::cos(kTwoPi * lfo_));
                if (upd) fc_.set(60.0f * std::pow(2.0f, m * 6.5f * (0.3f + 0.7f * y)), 3.0f, sr);
                float fl = std::tanh(svf(f_[0], fc_, l, SvfOut::High) * 1.5f);
                float fr = std::tanh(svf(f_[1], fc_, r, SvfOut::High) * 1.5f);
                fl = mix(l, fl, w);
                fr = mix(r, fr, w);
                float dL, dR;
                delayTap(beatsToFrames(pick(kDelayBeats, x), bpm), fl * w, fr * w, 0.2f + y * 0.6f, dL, dR, 0.002f);
                ol = fl + dL * (0.3f + 0.6f * y);
                orr = fr + dR * (0.3f + 0.6f * y);
                break;
            }
            case kKpInfiniteHpf: {
                // LFO.6: ФВЧ бесконечно поднимается (два «голоса» внахлёст). X — скорость, Y — резонанс.
                const float ph = lfoStep(x);
                const float ph2 = ph + 0.5f - std::floor(ph + 0.5f);
                if (upd) {
                    fc2_[0].set(30.0f * std::pow(2.0f, ph * 9.0f), 1.0f + y * 7.0f, sr);
                    fc2_[1].set(30.0f * std::pow(2.0f, ph2 * 9.0f), 1.0f + y * 7.0f, sr);
                }
                const float g1 = std::sin(kPi * ph), g2 = std::sin(kPi * ph2);
                ol = std::tanh((svf(f2_[0][0], fc2_[0], l, SvfOut::High) * g1 + svf(f2_[0][1], fc2_[1], l, SvfOut::High) * g2) * 1.3f);
                orr = std::tanh((svf(f2_[1][0], fc2_[0], r, SvfOut::High) * g1 + svf(f2_[1][1], fc2_[1], r, SvfOut::High) * g2) * 1.3f);
                break;
            }
            case kKpRandomLpf: {
                // LFO.7: частота ФНЧ прыгает случайно по темпу. X — скорость, Y — глубина.
                const float ph = lfoStep(x);
                const int64_t cyc = static_cast<int64_t>(ph < static_cast<float>(gatePhase_));
                gatePhase_ = ph;
                if (cyc || lfoCycle_ < 0) {
                    lfoCycle_ = 0;
                    sh_ = 0.5f + 0.5f * noise();
                }
                shS_ += (sh_ - shS_) * (1.0f - std::exp(-1.0f / (0.004f * sr)));
                if (upd) fc_.set(18000.0f * std::pow(2.0f, -shS_ * y * 7.5f), 3.0f, sr);
                ol = svf(f_[0], fc_, l, SvfOut::Low);
                orr = svf(f_[1], fc_, r, SvfOut::Low);
                break;
            }
            case kKpGateLpf: {
                // LF.26: звук рубится ритмом по 1/16 (X — один из 8 ритмов), Y — ФНЧ (частота и резонанс).
                gatePhase_ += 1.0 / std::max(16.0f, beat * 0.25f);
                const int step = static_cast<int>(gatePhase_) % 16;
                const int pat = std::min(7, static_cast<int>(x * 8.0f));
                const float target = (kGatePatterns[pat] >> (15 - step)) & 1u ? 1.0f : 0.0f;
                ampEnv_ += (target - ampEnv_) * (1.0f - std::exp(-1.0f / (0.0015f * sr)));
                float fl, fr;
                yFilter(0, l, r, fl, fr);
                ol = fl * ampEnv_;
                orr = fr * ampEnv_;
                break;
            }
            case kKpYoiYoi: {
                // LFO.9: «йой-йой» — высота и громкость качаются по темпу. X — скорость, Y — сила.
                const float s = static_cast<float>(std::sin(kTwoPi * lfoStep(x)));
                const float d = 2.0f + (0.5f + 0.5f * s) * y * 0.008f * sr;
                ol = delayRead(0, d);
                orr = delayRead(1, d);
                delayWrite(l, r);
                const float g = 1.0f - y * 0.35f * (0.5f - 0.5f * s);
                ol *= g;
                orr *= g;
                break;
            }
            case kKpFlanger: {
                // LF.10: X — скорость LFO (по темпу), Y — обратная связь.
                const float m = 0.5f + 0.5f * static_cast<float>(std::sin(kTwoPi * lfoStep(x)));
                const float d = (0.0006f + m * 0.006f) * sr;
                const float fb = y * 0.9f;
                const float dL = delayRead(0, d), dR = delayRead(1, d + 3.0f);
                delayWrite(l + dL * fb, r + dR * fb);
                ol = (l + dL) * 0.65f;
                orr = (r + dR) * 0.65f;
                break;
            }
            default: break;
        }

        if (!tails) {
            // Касание включает эффект плавно: смешиваем «сухой» и обработанный сигнал.
            ol = l + (ol - l) * w;
            orr = r + (orr - r) * w;
        }
        // DRY/WET программы (сглажено, без щелчков при повороте ручки)
        mixS_ += (mt - mixS_) * xyCoef;
        if (mixS_ < 0.9999f) {
            ol = l + (ol - l) * mixS_;
            orr = r + (orr - r) * mixS_;
        }
        if (!std::isfinite(ol) || !std::isfinite(orr)) { resetState(); ol = l; orr = r; }
        io[2 * i] = ol;
        io[2 * i + 1] = orr;
    }
}

}  // namespace mpc
