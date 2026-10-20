#include "KaossFx.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace mpc {
namespace {

constexpr float kPi = 3.14159265358979f;
constexpr double kTwoPi = 6.283185307179586;

// Деления для эффектов с синхронизацией (в долях-четвертях), X выбирает по порядку.
constexpr float kDelayBeats[] = {0.125f, 0.25f, 1.0f / 3.0f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f};
constexpr float kRepeatBeats[] = {1.0f, 0.5f, 1.0f / 3.0f, 0.25f, 1.0f / 6.0f, 0.125f, 1.0f / 16.0f};
constexpr float kSliceBeats[] = {0.5f, 0.25f, 1.0f / 6.0f, 0.125f, 1.0f / 16.0f};
constexpr float kBarBeats[] = {1.0f, 2.0f, 4.0f, 8.0f};

template <size_t N>
float pick(const float (&a)[N], float x) {
    const int i = std::clamp(static_cast<int>(x * N), 0, static_cast<int>(N) - 1);
    return a[i];
}

// Ритмы слайсера: 16 шагов, 1 — звук проходит.
constexpr uint16_t kSlicerPatterns[4] = {0b1011011010110110, 0b1110111011101110, 0b1001001010010010, 0b1101101101101101};

// Freeverb: длины гребёнок/allpass при 44.1 кГц.
constexpr int kCombLen[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
constexpr int kApLen[4] = {556, 441, 341, 225};
constexpr int kStereoSpread = 23;

// Форманты гласных A, E, I, O, U (Гц).
constexpr float kFormants[5][3] = {
    {800, 1150, 2900}, {400, 1600, 2700}, {350, 2300, 3000}, {450, 800, 2830}, {325, 700, 2530}};

// Огибающая на стыках повторов: убирает щелчки.
float seam(int64_t d, int64_t len, int fade) {
    if (len <= 2 * fade) return 1.0f;
    const float a = std::min<float>(1.0f, static_cast<float>(d) / fade);
    const float b = std::min<float>(1.0f, static_cast<float>(len - d) / fade);
    return std::min(a, b);
}

float mix(float dry, float wet, float amount) { return dry + (wet - dry) * amount; }

}  // namespace

bool KaossFx::hasTails(int p) {
    switch (p) {
        case kKpDelay: case kKpPingPong: case kKpReverb: case kKpDubEcho: case kKpTapeEcho:
        case kKpMultiTap: case kKpHall: case kKpRoom: case kKpGatedVerb: case kKpFreeze:
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
        for (auto& s : apState_[c]) s = 0;
        phaserFb_[c] = 0;
        crushHold_[c] = 0;
    }
    dlTime_ = 0;
    coefCountdown_ = 0;
    env_ = 0;
    gateEnv_ = 0;
    gateHold_ = 0;
    agIn_ = agOut_ = 0;
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

// Гранулярный сдвиг высоты по истории: две головки со сдвигом в полокна, треугольные окна.
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

// Перегруз делает звук громче — подтягиваем выход к громкости входа (RMS, ~60 мс).
void KaossFx::autoGain(float inL, float inR, float& l, float& r) {
    const float c = 1.0f - std::exp(-1.0f / (0.06f * static_cast<float>(sr_)));
    agIn_ += ((inL * inL + inR * inR) * 0.5f - agIn_) * c;
    agOut_ += ((l * l + r * r) * 0.5f - agOut_) * c;
    const float g = std::clamp(std::sqrt((agIn_ + 1e-9f) / (agOut_ + 1e-9f)), 0.05f, 1.5f);
    l *= g;
    r *= g;
}

void KaossFx::process(float* io, int n, float bpm) {
    const int prog = std::clamp(program_.load(std::memory_order_relaxed), 0, kKpCount - 1);
    const bool touch = touch_.load(std::memory_order_relaxed);
    const float xt = std::clamp(x_.load(std::memory_order_relaxed), 0.0f, 1.0f);
    const float yt = std::clamp(y_.load(std::memory_order_relaxed), 0.0f, 1.0f);

    if (prog != curProgram_) {
        curProgram_ = prog;
        resetState();
        wet_ = 0.0f;
    }
    const bool started = touch && !wasTouch_;
    if (started) {
        capStart_ = histW_;
        capT_ = 0;
        rollCycleStart_ = 0;
        rollLen_ = 0;
        tapePos_ = static_cast<double>(histW_);
        tapeSpeed_ = 1.0f;
        gatePhase_ = 0;
        glitchOn_ = false;
        glitchSlotStart_ = 0;
        scratchPos_ = static_cast<double>(histW_);
        scratchVel_ = 1.0;
    }
    wasTouch_ = touch;

    const float sr = static_cast<float>(sr_);
    const float wetCoef = 1.0f - std::exp(-1.0f / (0.006f * sr));     // ~6 мс
    const float xyCoef = 1.0f - std::exp(-1.0f / (0.025f * sr));      // ~25 мс
    const bool tails = hasTails(prog);
    // Без касания и без хвостов — эффект полностью выключен (кроме записи истории).
    const bool idle = !touch && wet_ < 1e-4f && !tails;
    const int fade = std::max(8, sr_ / 1000);

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
        float ol = l, orr = r;

        switch (prog) {
            // ====================================================== 1 FILTER
            case kKpLpf:
            case kKpHpf:
            case kKpBpf:
            case kKpNotch: {
                if (--coefCountdown_ <= 0) {
                    coefCountdown_ = 16;
                    const float fc = 30.0f * std::pow(2.0f, x * 9.3f);   // 30 Гц … 19 кГц
                    fc_.set(fc, prog == kKpNotch ? 0.5f + y * 3.0f : 0.6f + y * (prog == kKpBpf ? 12.0f : 9.0f), sr);
                }
                const SvfOut o = prog == kKpLpf ? SvfOut::Low : prog == kKpHpf ? SvfOut::High
                               : prog == kKpBpf ? SvfOut::Band : SvfOut::Notch;
                const float g = prog == kKpBpf ? 1.6f : 1.0f;
                ol = svf(f_[0], fc_, l, o) * g;
                orr = svf(f_[1], fc_, r, o) * g;
                break;
            }
            case kKpDjFilter: {
                // Левее центра — ФНЧ закрывается, правее — ФВЧ; в центре звук не тронут.
                const bool hp = x > 0.5f;
                if (--coefCountdown_ <= 0) {
                    coefCountdown_ = 16;
                    const float d = std::fabs(x - 0.5f) * 2.0f;
                    const float fc = hp ? 20.0f * std::pow(2.0f, d * 8.6f) : 20000.0f * std::pow(2.0f, -d * 8.4f);
                    fc_.set(fc, 0.7f + y * 7.0f, sr);
                }
                ol = svf(f_[0], fc_, l, hp ? SvfOut::High : SvfOut::Low);
                orr = svf(f_[1], fc_, r, hp ? SvfOut::High : SvfOut::Low);
                break;
            }
            case kKpComb: {
                // Резонирующая гребёнка: X — высота тона, Y — резонанс.
                const float f = 40.0f * std::pow(2.0f, x * 5.5f);
                const float d = sr / f;
                const float fb = 0.5f + y * 0.47f;
                const float dL = delayRead(0, d), dR = delayRead(1, d);
                delayWrite(l + dL * fb, r + dR * fb);
                ol = (l + dL) * (1.0f - fb * 0.5f);
                orr = (r + dR) * (1.0f - fb * 0.5f);
                break;
            }
            case kKpFormant: {
                // «Говорящий» фильтр: X — гласная A-E-I-O-U, Y — резонанс.
                if (--coefCountdown_ <= 0) {
                    coefCountdown_ = 16;
                    const float pos = x * 4.0f;
                    const int a = std::min(3, static_cast<int>(pos));
                    const float t = pos - a;
                    for (int k = 0; k < 3; ++k) {
                        const float f = kFormants[a][k] * (1.0f - t) + kFormants[a + 1][k] * t;
                        fc2_[k].set(f, 4.0f + y * 14.0f, sr);
                    }
                }
                float s[2] = {0, 0};
                const float in[2] = {l, r};
                for (int c = 0; c < 2; ++c)
                    for (int k = 0; k < 3; ++k) s[c] += svf(f2_[c][k], fc2_[k], in[c], SvfOut::Band) * (k == 0 ? 1.0f : 0.6f);
                ol = s[0] * 1.6f;
                orr = s[1] * 1.6f;
                break;
            }
            case kKpAutoFilter: {
                // ФНЧ качается по темпу: X — скорость (1/4…1/32), Y — глубина.
                const float period = beatsToFrames(pick(kSliceBeats, x), bpm);
                lfo_ += 1.0 / std::max(16.0f, period);
                lfo_ -= std::floor(lfo_);
                if (--coefCountdown_ <= 0) {
                    coefCountdown_ = 16;
                    const float m = 0.5f - 0.5f * static_cast<float>(std::cos(kTwoPi * lfo_));
                    fc_.set(18000.0f * std::pow(2.0f, -m * y * 8.0f), 2.5f, sr);
                }
                ol = svf(f_[0], fc_, l, SvfOut::Low);
                orr = svf(f_[1], fc_, r, SvfOut::Low);
                break;
            }
            case kKpEnvFilter: {
                // Автовау: громкость открывает полосовой фильтр. X — чувствительность, Y — резонанс.
                const float a = std::fabs(l) + std::fabs(r);
                const float c = a > env_ ? 1.0f - std::exp(-1.0f / (0.004f * sr)) : 1.0f - std::exp(-1.0f / (0.12f * sr));
                env_ += (a - env_) * c;
                if (--coefCountdown_ <= 0) {
                    coefCountdown_ = 16;
                    const float e = std::min(1.0f, env_ * (1.0f + x * 12.0f));
                    fc_.set(250.0f * std::pow(2.0f, e * 5.0f), 2.0f + y * 10.0f, sr);
                }
                ol = svf(f_[0], fc_, l, SvfOut::Band) * 2.0f;
                orr = svf(f_[1], fc_, r, SvfOut::Band) * 2.0f;
                break;
            }
            case kKpIsolator: {
                // DJ-изолятор: X слева направо гасит низ → середину → верх, Y — насколько глушить.
                if (--coefCountdown_ <= 0) {
                    coefCountdown_ = 64;
                    fc2_[0].set(250.0f, 0.7f, sr);
                    fc2_[1].set(3500.0f, 0.7f, sr);
                }
                const float kill = 1.0f - y;
                const float gLo = x < 0.33f ? kill : 1.0f;
                const float gMid = (x >= 0.33f && x < 0.66f) ? kill : 1.0f;
                const float gHi = x >= 0.66f ? kill : 1.0f;
                const float in[2] = {l, r};
                float out[2];
                for (int c = 0; c < 2; ++c) {
                    const float lo = svf(f2_[c][0], fc2_[0], in[c], SvfOut::Low);
                    const float hi = svf(f2_[c][1], fc2_[1], in[c], SvfOut::High);
                    out[c] = lo * gLo + (in[c] - lo - hi) * gMid + hi * gHi;
                }
                ol = out[0];
                orr = out[1];
                break;
            }

            // ====================================================== 2 SPACE (хвосты доигрывают)
            case kKpDelay:
            case kKpPingPong:
            case kKpDubEcho:
            case kKpTapeEcho: {
                float target;
                if (prog == kKpDubEcho || prog == kKpTapeEcho) target = (0.05f + x * x * 0.85f) * sr;   // 50…900 мс
                else target = beatsToFrames(pick(kDelayBeats, x), bpm);
                if (prog == kKpTapeEcho) {
                    // «Плёнка»: время дрожит (wow & flutter)
                    lfo_ += 0.7 / sr_;
                    lfo_ -= std::floor(lfo_);
                    target *= 1.0f + 0.004f * static_cast<float>(std::sin(kTwoPi * lfo_));
                }
                target = std::clamp(target, 16.0f, static_cast<float>(dlSize_ - 4));
                if (dlTime_ <= 0) dlTime_ = target;
                dlTime_ += (target - dlTime_) * (prog == kKpDelay || prog == kKpPingPong ? 0.002f : 0.0004f);
                const float fb = prog == kKpDubEcho ? 0.35f + y * 0.75f : y * 0.92f;
                const float dL = delayRead(0, dlTime_), dR = delayRead(1, dlTime_);
                const float sendL = l * w, sendR = r * w;
                if (prog == kKpPingPong) {
                    delayWrite((sendL + sendR) * 0.5f + dR * fb, dL * fb);
                } else if (prog == kKpDubEcho || prog == kKpTapeEcho) {
                    if (--coefCountdown_ <= 0) {
                        coefCountdown_ = 64;
                        fc_.set(prog == kKpDubEcho ? 2200.0f : 4500.0f, 0.7f, sr);
                    }
                    delayWrite(std::tanh(sendL + svf(fbLp_[0], fc_, dL, SvfOut::Low) * fb),
                               std::tanh(sendR + svf(fbLp_[1], fc_, dR, SvfOut::Low) * fb));
                } else {
                    delayWrite(sendL + dL * fb, sendR + dR * fb);
                }
                ol = l + dL * 0.7f;
                orr = r + dR * 0.7f;
                break;
            }
            case kKpMultiTap: {
                // Три отвода (1/8, 1/4, 3/8 такта×X), Y — обратная связь.
                const float base = beatsToFrames(0.25f + x * 0.75f, bpm);
                const float t1 = std::min(base, dlSize_ / 3.2f);
                const float a = delayRead(0, t1) * 0.6f + delayRead(1, t1 * 2.0f) * 0.45f + delayRead(0, t1 * 3.0f) * 0.3f;
                const float b = delayRead(1, t1) * 0.6f + delayRead(0, t1 * 2.0f) * 0.45f + delayRead(1, t1 * 3.0f) * 0.3f;
                const float fb = y * 0.6f;
                delayWrite(l * w + a * fb, r * w + b * fb);
                ol = l + a;
                orr = r + b;
                break;
            }
            case kKpReverb:
            case kKpHall:
            case kKpRoom:
            case kKpFreeze: {
                float room, damp, gainIn = 0.03f;
                if (prog == kKpHall) { room = 0.86f + x * 0.12f; damp = 0.15f + (1.0f - y) * 0.3f; }
                else if (prog == kKpRoom) { room = 0.55f + x * 0.2f; damp = 0.4f + (1.0f - y) * 0.4f; }
                else if (prog == kKpFreeze) {
                    // Заморозка: пока держишь — хвост бесконечный, вход почти не идёт.
                    room = touch ? 0.999f : 0.75f;
                    damp = 0.05f;
                    gainIn = touch ? 0.03f * (1.0f - y) : 0.0f;
                } else { room = 0.70f + x * 0.28f; damp = 0.2f + (1.0f - y) * 0.5f; }
                float rl, rr;
                reverb((l + r) * 0.5f * (prog == kKpFreeze ? 1.0f : w) * gainIn, room, damp, rl, rr);
                const float m = prog == kKpFreeze ? 0.6f + x * 1.4f : 0.6f + y * 1.4f;
                ol = l + rl * m;
                orr = r + rr * m;
                break;
            }
            case kKpGatedVerb: {
                // Гейтованный ревёрб 80-х: хвост обрывается через X (60…500 мс), Y — размер.
                const float a = std::fabs(l) + std::fabs(r);
                if (a > 0.08f && touch) gateHold_ = static_cast<int>((0.06f + x * 0.44f) * sr);
                const float target = gateHold_ > 0 ? 1.0f : 0.0f;
                if (gateHold_ > 0) --gateHold_;
                gateEnv_ += (target - gateEnv_) * (1.0f - std::exp(-1.0f / (0.004f * sr)));
                float rl, rr;
                reverb((l + r) * 0.5f * w * 0.04f, 0.8f + y * 0.18f, 0.2f, rl, rr);
                ol = l + rl * 1.8f * gateEnv_;
                orr = r + rr * 1.8f * gateEnv_;
                break;
            }

            // ====================================================== 3 REPEAT
            case kKpBeatRepeat:
            case kKpRoll:
            case kKpBarLoop:
            case kKpPitchRepeat: {
                if (!touch) break;   // отпустили — сразу живой звук
                int64_t len;
                if (prog == kKpRoll) {
                    // каждый повтор вдвое короче — «раскрутка» к концу фразы
                    if (rollLen_ == 0) rollLen_ = static_cast<int>(beatsToFrames(pick(kRepeatBeats, x * 0.5f), bpm));
                    if (capT_ - rollCycleStart_ >= rollLen_ && capT_ >= rollLen_) {
                        rollCycleStart_ = capT_;
                        rollLen_ = std::max(static_cast<int>(beatsToFrames(1.0f / 32.0f, bpm)), rollLen_ / 2);
                    }
                    len = rollLen_;
                } else if (prog == kKpBarLoop) {
                    len = static_cast<int64_t>(beatsToFrames(pick(kBarBeats, x), bpm));
                } else {
                    len = static_cast<int64_t>(beatsToFrames(pick(kRepeatBeats, x), bpm));
                }
                len = std::clamp<int64_t>(len, 64, histSize_ / 2);
                const int64_t d = capT_ % len;
                const float env = capT_ >= len ? seam(d, len, fade) : 1.0f;
                float rl, rr;
                if (prog == kKpPitchRepeat && capT_ >= len) {
                    // каждый повтор ниже на Y×12 полутонов (до октавы на повтор)
                    const int64_t k = capT_ / len;
                    const double rate = std::pow(2.0, -static_cast<double>(y) * static_cast<double>(k) / 2.0);
                    const double pos = static_cast<double>(capStart_) + static_cast<double>(d) * std::max(0.05, rate);
                    rl = hist(0, pos) * env;
                    rr = hist(1, pos) * env;
                    ol = rl;
                    orr = rr;
                } else {
                    rl = histAt(0, capStart_ + d) * env;
                    rr = histAt(1, capStart_ + d) * env;
                    const float amt = prog == kKpBarLoop ? 1.0f : 0.35f + 0.65f * y;
                    ol = mix(l, rl, amt);
                    orr = mix(r, rr, amt);
                }
                ++capT_;
                break;
            }
            case kKpReverse: {
                if (!touch) break;
                const int64_t len = std::clamp<int64_t>(
                    static_cast<int64_t>(beatsToFrames(pick(kDelayBeats, 0.25f + x * 0.75f), bpm)), 256, histSize_ / 3);
                const int64_t k = capT_ / len, d = capT_ % len;
                if (k >= 1) {
                    const int64_t src = capStart_ + k * len - 1 - d;   // предыдущий кусок задом наперёд
                    const float env = seam(d, len, 2 * fade);
                    ol = mix(l, histAt(0, src) * env, 0.4f + 0.6f * y);
                    orr = mix(r, histAt(1, src) * env, 0.4f + 0.6f * y);
                }
                ++capT_;
                break;
            }
            case kKpTapeStop: {
                if (!touch) break;
                // X — время торможения (0.1…3 с), Y — глубина (вверху — до полной остановки).
                const float brake = 0.1f + x * 2.9f;
                tapeSpeed_ = std::max(1.0f - y, tapeSpeed_ - 1.0f / (brake * sr));
                tapePos_ += tapeSpeed_;
                const double maxBack = histSize_ - 4;
                if (static_cast<double>(histW_) - tapePos_ > maxBack) tapePos_ = static_cast<double>(histW_) - maxBack;
                const float g = std::min(1.0f, tapeSpeed_ * 4.0f);
                ol = hist(0, tapePos_ - 1) * g;
                orr = hist(1, tapePos_ - 1) * g;
                break;
            }
            case kKpSpinback: {
                if (!touch) break;
                // «Перемотка пластинки назад»: разгоняется до X×4, Y — затухание.
                tapeSpeed_ = std::min(1.0f + x * 3.0f, tapeSpeed_ + 6.0f / sr);
                tapePos_ -= tapeSpeed_;
                const double minPos = static_cast<double>(histW_) - (histSize_ - 4);
                if (tapePos_ < minPos) tapePos_ = minPos;
                const float g = 1.0f - y * std::min(1.0f, static_cast<float>(capT_) / sr);
                ol = hist(0, tapePos_) * g;
                orr = hist(1, tapePos_) * g;
                ++capT_;
                break;
            }
            case kKpGlitch: {
                if (!touch) break;
                // На каждой доле X-сетки решаем: играть живой звук или повторить случайный кусок прошлого.
                const int64_t len = std::clamp<int64_t>(static_cast<int64_t>(beatsToFrames(pick(kRepeatBeats, x), bpm)), 64, histSize_ / 8);
                if (capT_ - glitchSlotStart_ >= len || capT_ == 0) {
                    glitchSlotStart_ = capT_;
                    noise_ = noise_ * 1664525u + 1013904223u;
                    glitchOn_ = (noise_ >> 8) % 100 < static_cast<uint32_t>(30 + y * 65);
                    const int64_t back = len * (1 + static_cast<int64_t>((noise_ >> 3) % 4));
                    glitchSrc_ = histW_ - back;
                }
                if (glitchOn_) {
                    const int64_t d = capT_ - glitchSlotStart_;
                    const float env = seam(d, len, fade);
                    ol = histAt(0, glitchSrc_ + d) * env;
                    orr = histAt(1, glitchSrc_ + d) * env;
                }
                ++capT_;
                break;
            }
            case kKpScratch: {
                if (!touch) break;
                // Скретч: X — положение «иглы» в последней доле, движение пальца = движение пластинки.
                const double window = beatsToFrames(1.0f, bpm);
                const double target = static_cast<double>(capStart_) - window + static_cast<double>(x) * window;
                const double v = (target - scratchPos_) * 0.002;
                scratchVel_ += (std::clamp(v, -4.0, 4.0) - scratchVel_) * 0.05;
                scratchPos_ += scratchVel_;
                const float g = std::min(1.0f, static_cast<float>(std::fabs(scratchVel_)) * 3.0f);
                ol = mix(l * (1.0f - y), hist(0, scratchPos_) * g, w);
                orr = mix(r * (1.0f - y), hist(1, scratchPos_) * g, w);
                ++capT_;
                break;
            }
            case kKpSlicer: {
                // Ритмичная нарезка: X — деление, Y — один из 4 ритмов.
                const float period = beatsToFrames(pick(kSliceBeats, x), bpm);
                gatePhase_ += 1.0 / std::max(16.0f, period);
                const int step = static_cast<int>(gatePhase_) % 16;
                const int pat = std::min(3, static_cast<int>(y * 4.0f));
                const float target = (kSlicerPatterns[pat] >> (15 - step)) & 1u ? 1.0f : 0.0f;
                ampEnv_ += (target - ampEnv_) * (1.0f - std::exp(-1.0f / (0.0015f * sr)));
                ol = l * ampEnv_;
                orr = r * ampEnv_;
                break;
            }

            // ====================================================== 4 MOD
            case kKpFlanger:
            case kKpChorus:
            case kKpVibrato: {
                const double rate = prog == kKpChorus ? 0.1 + x * 3.0 : 0.05 + x * x * 6.0;
                lfo_ += rate / sr_;
                lfo_ -= std::floor(lfo_);
                const float m = 0.5f + 0.5f * static_cast<float>(std::sin(kTwoPi * lfo_));
                const float m2 = 0.5f + 0.5f * static_cast<float>(std::sin(kTwoPi * (lfo_ + 0.25)));
                if (prog == kKpFlanger) {
                    const float d = (0.0007f + m * 0.006f) * sr;
                    const float fb = 0.2f + y * 0.7f;
                    const float dL = delayRead(0, d), dR = delayRead(1, d + 3.0f);
                    delayWrite(l + dL * fb, r + dR * fb);
                    ol = (l + dL) * 0.7f;
                    orr = (r + dR) * 0.7f;
                } else if (prog == kKpChorus) {
                    const float depth = (0.002f + y * 0.008f) * sr;
                    const float base = 0.015f * sr;
                    const float dL = delayRead(0, base + m * depth), dR = delayRead(1, base + m2 * depth);
                    delayWrite(l, r);
                    ol = (l + dL) * 0.75f;
                    orr = (r + dR) * 0.75f;
                } else {
                    const float depth = (0.0005f + y * 0.006f) * sr;
                    ol = delayRead(0, 2.0f + m * depth);
                    orr = delayRead(1, 2.0f + m * depth);
                    delayWrite(l, r);
                }
                break;
            }
            case kKpPhaser: {
                const double rate = 0.05 + x * x * 5.0;
                lfo_ += rate / sr_;
                lfo_ -= std::floor(lfo_);
                const float m = 0.5f + 0.5f * static_cast<float>(std::sin(kTwoPi * lfo_));
                const float fc = 200.0f * std::pow(2.0f, m * 3.6f);
                const float t = std::tan(kPi * fc / sr);
                const float a = (t - 1.0f) / (t + 1.0f);
                const float fb = y * 0.75f;
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
            case kKpRingMod: {
                const double f = 30.0 * std::pow(2.0, x * 6.2);   // 30 Гц … 2.2 кГц
                osc_ += f / sr_;
                osc_ -= std::floor(osc_);
                const float s = static_cast<float>(std::sin(kTwoPi * osc_));
                ol = mix(l, l * s, 0.3f + 0.7f * y);
                orr = mix(r, r * s, 0.3f + 0.7f * y);
                break;
            }
            case kKpSweepNoise: {
                // Переход: белый шум через фильтр. X — частота (вправо — «взлёт»), Y — громкость и резонанс.
                if (--coefCountdown_ <= 0) {
                    coefCountdown_ = 16;
                    fc_.set(60.0f * std::pow(2.0f, x * 8.2f), 0.8f + y * 5.0f, sr);
                }
                const float nL = noise(), nR = noise();
                const float lvl = (0.08f + y * 0.35f) * w;
                ol = l + svf(f_[0], fc_, nL, SvfOut::Band) * lvl * 2.0f;
                orr = r + svf(f_[1], fc_, nR, SvfOut::Band) * lvl * 2.0f;
                break;
            }
            case kKpTremolo:
            case kKpAutoPan: {
                // X — скорость по темпу (1/2…1/32), Y — глубина.
                const float period = beatsToFrames(pick(kSliceBeats, x), bpm);
                lfo_ += 1.0 / std::max(16.0f, period);
                lfo_ -= std::floor(lfo_);
                const float s = static_cast<float>(std::sin(kTwoPi * lfo_));
                if (prog == kKpTremolo) {
                    const float g = 1.0f - y * (0.5f - 0.5f * s);
                    ol = l * g;
                    orr = r * g;
                } else {
                    const float p = s * y;   // -1..1
                    ol = l * std::sqrt(0.5f * (1.0f - p)) * 1.414f;
                    orr = r * std::sqrt(0.5f * (1.0f + p)) * 1.414f;
                }
                break;
            }
            case kKpRotary: {
                // «Лесли»: доплер + вращение по каналам. X — скорость (медленно/быстро), Y — глубина.
                const double target = 0.8 + x * 6.0;
                lfo2_ += (target - lfo2_) * 0.00005;   // разгон рупора
                lfo_ += lfo2_ / sr_;
                lfo_ -= std::floor(lfo_);
                const float s = static_cast<float>(std::sin(kTwoPi * lfo_));
                const float c = static_cast<float>(std::cos(kTwoPi * lfo_));
                const float depth = (0.0003f + 0.0015f * y) * sr;
                const float dL = delayRead(0, 3.0f + (1.0f + s) * depth);
                const float dR = delayRead(1, 3.0f + (1.0f - s) * depth);
                delayWrite(l, r);
                ol = dL * (1.0f - 0.35f * y * c);
                orr = dR * (1.0f + 0.35f * y * c);
                break;
            }
            case kKpSiren: {
                // Сирена/синт поверх микса: X — высота, Y — скорость «воя».
                lfo_ += (0.2 + y * 8.0) / sr_;
                lfo_ -= std::floor(lfo_);
                const double f = 120.0 * std::pow(2.0, x * 4.0) * (1.0 + 0.5 * std::sin(kTwoPi * lfo_));
                osc_ += f / sr_;
                osc_ -= std::floor(osc_);
                const float saw = static_cast<float>(osc_ * 2.0 - 1.0);
                if (--coefCountdown_ <= 0) {
                    coefCountdown_ = 32;
                    fc_.set(2500.0f, 0.8f, sr);
                }
                const float v = svf(f_[0], fc_, saw, SvfOut::Low) * 0.25f * w;
                ol = l + v;
                orr = r + v;
                break;
            }

            // ====================================================== 5 LO-FI
            case kKpBitCrush: {
                const float hold = 1.0f + x * x * 47.0f;
                crushCount_ += 1.0f;
                if (crushCount_ >= hold) {
                    crushCount_ -= hold;
                    const float bits = 16.0f - y * 13.0f;
                    const float step = 2.0f / std::pow(2.0f, bits);
                    crushHold_[0] = std::round(l / step) * step;
                    crushHold_[1] = std::round(r / step) * step;
                }
                ol = crushHold_[0];
                orr = crushHold_[1];
                break;
            }
            case kKpDistortion:
            case kKpFuzz:
            case kKpWavefolder: {
                // Драйв растёт по X — громкость автоматически возвращается к исходной (autoGain).
                const float drive = prog == kKpFuzz ? 2.0f + x * x * 120.0f : prog == kKpWavefolder ? 1.0f + x * 6.0f : 1.0f + x * x * 50.0f;
                if (--coefCountdown_ <= 0) {
                    coefCountdown_ = 32;
                    fc_.set(700.0f * std::pow(2.0f, y * 4.5f), 0.7f, sr);
                }
                float sl, sr2;
                if (prog == kKpFuzz) {
                    sl = std::clamp(l * drive, -0.7f, 0.7f) + 0.15f * std::clamp(l * drive * 0.3f, 0.0f, 0.5f);
                    sr2 = std::clamp(r * drive, -0.7f, 0.7f) + 0.15f * std::clamp(r * drive * 0.3f, 0.0f, 0.5f);
                } else if (prog == kKpWavefolder) {
                    sl = std::sin(l * drive * 1.6f);
                    sr2 = std::sin(r * drive * 1.6f);
                } else {
                    sl = std::tanh(l * drive);
                    sr2 = std::tanh(r * drive);
                }
                ol = svf(f_[0], fc_, sl, SvfOut::Low);
                orr = svf(f_[1], fc_, sr2, SvfOut::Low);
                autoGain(l, r, ol, orr);
                break;
            }
            case kKpLofi: {
                // «Старое радио»: полоса сужается по X, шум и треск растут по Y.
                if (--coefCountdown_ <= 0) {
                    coefCountdown_ = 32;
                    fc_.set(1400.0f, 0.8f + x * 4.0f, sr);
                }
                const float nz = noise();
                const float crackle = ((noise_ >> 3) % 6000u == 0u) ? 0.6f : 0.0f;
                const float add = (nz * 0.02f + crackle) * y;
                ol = svf(f_[0], fc_, l, SvfOut::Band) * 2.2f + add;
                orr = svf(f_[1], fc_, r, SvfOut::Band) * 2.2f + add;
                break;
            }
            case kKpVinyl: {
                // Пластинка: «плывёт» высота (X — wow), глуше верх, треск (Y).
                lfo_ += 0.55 / sr_;
                lfo_ -= std::floor(lfo_);
                const float d = (0.002f + x * 0.004f * (0.5f + 0.5f * static_cast<float>(std::sin(kTwoPi * lfo_)))) * sr;
                const float dL = delayRead(0, d), dR = delayRead(1, d);
                delayWrite(l, r);
                if (--coefCountdown_ <= 0) {
                    coefCountdown_ = 64;
                    fc_.set(5500.0f - y * 2500.0f, 0.7f, sr);
                }
                const float nz = noise();
                const float crackle = ((noise_ >> 5) % 2500u == 0u) ? 0.5f * nz : 0.0f;
                ol = svf(f_[0], fc_, dL, SvfOut::Low) + (crackle + nz * 0.004f) * y;
                orr = svf(f_[1], fc_, dR, SvfOut::Low) + (crackle + nz * 0.004f) * y;
                break;
            }
            case kKpPump: {
                // «Качание» как от сайдчейна на каждую долю: X — глубина, Y — длина подъёма.
                const float period = beatsToFrames(1.0f, bpm);
                gatePhase_ += 1.0 / std::max(16.0f, period);
                gatePhase_ -= std::floor(gatePhase_);
                const float rise = 0.15f + y * 0.8f;
                const float ph = static_cast<float>(gatePhase_);
                const float shape = ph < rise ? std::pow(ph / rise, 2.0f) : 1.0f;
                const float g = 1.0f - x * (1.0f - shape);
                ampEnv_ += (g - ampEnv_) * (1.0f - std::exp(-1.0f / (0.001f * sr)));
                ol = l * ampEnv_;
                orr = r * ampEnv_;
                break;
            }
            case kKpPitch:
            case kKpOctaver: {
                const double win = 0.06 * sr_;
                if (prog == kKpPitch) {
                    // X — от −12 до +12 полутонов, Y — доля эффекта.
                    const double semis = std::round((x - 0.5f) * 24.0f);
                    const double ratio = std::pow(2.0, semis / 12.0);
                    const float pl = pitchTap(0, pitchPh_, ratio, win, true);
                    const float pr = pitchTap(1, pitchPh_, ratio, win, false);
                    ol = mix(l, pl, 0.5f + 0.5f * y);
                    orr = mix(r, pr, 0.5f + 0.5f * y);
                } else {
                    // Октавер: −1 октава (и −2 по X) подмешивается снизу, Y — сколько.
                    const float s1l = pitchTap(0, pitchPh_, 0.5, win, true);
                    const float s1r = pitchTap(1, pitchPh_, 0.5, win, false);
                    const float s2l = pitchTap(0, pitchPh2_, 0.25, win * 1.5, true);
                    const float s2r = pitchTap(1, pitchPh2_, 0.25, win * 1.5, false);
                    const float sub = 0.4f + 0.8f * y;
                    ol = l * 0.8f + (s1l * (1.0f - x) + s2l * x) * sub;
                    orr = r * 0.8f + (s1r * (1.0f - x) + s2r * x) * sub;
                }
                break;
            }
            case kKpRobot: {
                // Робот: короткая гребёнка на высоте X + кольцевая модуляция, Y — доля.
                const float f = 60.0f * std::pow(2.0f, x * 2.8f);
                const float d = sr / f;
                const float dL = delayRead(0, d), dR = delayRead(1, d);
                delayWrite(l + dL * 0.85f, r + dR * 0.85f);
                osc_ += f / sr_;
                osc_ -= std::floor(osc_);
                const float s = 0.6f + 0.4f * static_cast<float>(std::sin(kTwoPi * osc_));
                ol = mix(l, dL * s * 0.5f, 0.4f + 0.6f * y);
                orr = mix(r, dR * s * 0.5f, 0.4f + 0.6f * y);
                break;
            }
            default: break;
        }

        if (!tails) {
            // Касание включает эффект плавно: смешиваем «сухой» и обработанный сигнал.
            ol = l + (ol - l) * w;
            orr = r + (orr - r) * w;
        }
        if (!std::isfinite(ol) || !std::isfinite(orr)) { resetState(); ol = l; orr = r; }
        io[2 * i] = ol;
        io[2 * i + 1] = orr;
    }
}

}  // namespace mpc
