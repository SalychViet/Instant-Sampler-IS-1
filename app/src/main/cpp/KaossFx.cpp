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
constexpr float kGateBeats[] = {0.5f, 0.25f, 1.0f / 6.0f, 0.125f, 1.0f / 16.0f};

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

}  // namespace

KaossFx::KaossFx() { setSampleRate(sr_); }

void KaossFx::setSampleRate(int sr) {
    sr_ = sr > 0 ? sr : 48000;
    histSize_ = sr_ * 4;
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
        dubLp_[c].reset();
        toneLp_[c].reset();
        lofiBp_[c].reset();
        for (auto& s : apState_[c]) s = 0;
        phaserFb_[c] = 0;
        flFb_[c] = 0;
        crushHold_[c] = 0;
    }
    dlTime_ = 0;
    coefCountdown_ = 0;
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

float KaossFx::beatsToFrames(float beats, float bpm) const {
    return beats * 60.0f * static_cast<float>(sr_) / std::max(20.0f, bpm);
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
    }
    wasTouch_ = touch;

    const float sr = static_cast<float>(sr_);
    const float wetCoef = 1.0f - std::exp(-1.0f / (0.006f * sr));     // ~6 мс
    const float xyCoef = 1.0f - std::exp(-1.0f / (0.025f * sr));      // ~25 мс
    const bool tails = prog == kKpDelay || prog == kKpPingPong || prog == kKpReverb || prog == kKpDubEcho;
    // Без касания и без хвостов — эффект полностью выключен (кроме записи истории).
    const bool idle = !touch && wet_ < 1e-4f && !tails;

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
            // ---------------------------------------------------- FILTER
            case kKpLpf:
            case kKpHpf:
            case kKpBpf: {
                if (--coefCountdown_ <= 0) {
                    coefCountdown_ = 16;
                    const float fc = 30.0f * std::pow(2.0f, x * 9.3f);   // 30 Гц … 19 кГц
                    fc_.set(fc, 0.6f + y * (prog == kKpBpf ? 12.0f : 9.0f), sr);
                }
                const SvfOut o = prog == kKpLpf ? SvfOut::Low : prog == kKpHpf ? SvfOut::High : SvfOut::Band;
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

            // ---------------------------------------------------- SPACE (хвосты доигрывают)
            case kKpDelay:
            case kKpPingPong:
            case kKpDubEcho: {
                float target;
                if (prog == kKpDubEcho) target = (0.05f + x * x * 0.85f) * sr;   // свободное время 50…900 мс
                else target = beatsToFrames(pick(kDelayBeats, x), bpm);
                target = std::clamp(target, 16.0f, static_cast<float>(dlSize_ - 4));
                if (dlTime_ <= 0) dlTime_ = target;
                dlTime_ += (target - dlTime_) * (prog == kKpDubEcho ? 0.0004f : 0.002f);   // плавно: «тянущийся» дилей
                const float fb = prog == kKpDubEcho ? 0.35f + y * 0.75f : y * 0.92f;
                const float dL = delayRead(0, dlTime_), dR = delayRead(1, dlTime_);
                const float sendL = l * w, sendR = r * w;
                float inL, inR;
                if (prog == kKpPingPong) {
                    inL = (sendL + sendR) * 0.5f + dR * fb;
                    inR = dL * fb;
                } else if (prog == kKpDubEcho) {
                    if (--coefCountdown_ <= 0) { coefCountdown_ = 64; fc_.set(2200.0f, 0.7f, sr); }
                    inL = std::tanh(sendL + svf(dubLp_[0], fc_, dL, SvfOut::Low) * fb);
                    inR = std::tanh(sendR + svf(dubLp_[1], fc_, dR, SvfOut::Low) * fb);
                } else {
                    inL = sendL + dL * fb;
                    inR = sendR + dR * fb;
                }
                dl_[0][static_cast<size_t>(dlW_)] = inL;
                dl_[1][static_cast<size_t>(dlW_)] = inR;
                dlW_ = (dlW_ + 1) % dlSize_;
                ol = l + dL * 0.7f;
                orr = r + dR * 0.7f;
                break;
            }
            case kKpReverb: {
                const float room = 0.70f + x * 0.28f;
                const float damp = 0.2f + (1.0f - y) * 0.5f;
                const float in = (l + r) * 0.5f * w * 0.03f;
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
                const float mix = 0.6f + y * 1.4f;
                ol = l + out[0] * mix;
                orr = r + out[1] * mix;
                break;
            }

            // ---------------------------------------------------- REPEAT
            case kKpBeatRepeat:
            case kKpRoll: {
                if (!touch) break;   // отпустили — сразу живой звук (wet ведёт к dry)
                const int fade = std::max(8, sr_ / 1000);
                int64_t len;
                if (prog == kKpBeatRepeat) {
                    len = static_cast<int64_t>(beatsToFrames(pick(kRepeatBeats, x), bpm));
                } else {
                    // ROLL: каждый повтор вдвое короче — «раскрутка» к концу фразы.
                    if (rollLen_ == 0) rollLen_ = static_cast<int>(beatsToFrames(pick(kRepeatBeats, x * 0.5f), bpm));
                    if (capT_ - rollCycleStart_ >= rollLen_ && capT_ >= rollLen_) {
                        rollCycleStart_ = capT_;
                        rollLen_ = std::max(static_cast<int>(beatsToFrames(1.0f / 32.0f, bpm)), rollLen_ / 2);
                    }
                    len = rollLen_;
                }
                len = std::clamp<int64_t>(len, 64, histSize_ / 2);
                const int64_t d = capT_ % len;
                const float env = capT_ >= len ? seam(d, len, fade) : 1.0f;
                const float rl = histAt(0, capStart_ + d) * env, rr = histAt(1, capStart_ + d) * env;
                // Y внизу — повтор подмешан тихо, вверху — только повтор
                ol = l + (rl - l) * (0.35f + 0.65f * y);
                orr = r + (rr - r) * (0.35f + 0.65f * y);
                ++capT_;
                break;
            }
            case kKpReverse: {
                if (!touch) break;
                const int fade = std::max(8, sr_ / 500);
                const int64_t len = std::clamp<int64_t>(
                    static_cast<int64_t>(beatsToFrames(pick(kDelayBeats, 0.25f + x * 0.75f), bpm)), 256, histSize_ / 3);
                const int64_t k = capT_ / len, d = capT_ % len;
                if (k >= 1) {
                    const int64_t src = capStart_ + k * len - 1 - d;   // предыдущий кусок задом наперёд
                    const float env = seam(d, len, fade);
                    const float rl = histAt(0, src) * env, rr = histAt(1, src) * env;
                    ol = l + (rl - l) * (0.4f + 0.6f * y);
                    orr = r + (rr - r) * (0.4f + 0.6f * y);
                }
                ++capT_;
                break;
            }
            case kKpTapeStop: {
                if (!touch) break;
                // X — время торможения (0.1…3 с), Y — глубина (вверху — до полной остановки).
                const float brake = 0.1f + x * 2.9f;
                const float floorSpeed = 1.0f - y;
                tapeSpeed_ = std::max(floorSpeed, tapeSpeed_ - 1.0f / (brake * sr));
                tapePos_ += tapeSpeed_;
                const double maxBack = histSize_ - 4;
                if (static_cast<double>(histW_) - tapePos_ > maxBack) tapePos_ = static_cast<double>(histW_) - maxBack;
                const float g = std::min(1.0f, tapeSpeed_ * 4.0f + 0.0f);
                ol = hist(0, tapePos_ - 1) * g;
                orr = hist(1, tapePos_ - 1) * g;
                break;
            }

            // ---------------------------------------------------- MOD
            case kKpFlanger: {
                const double rate = 0.05 + x * x * 6.0;
                lfo_ += rate / sr_;
                if (lfo_ >= 1.0) lfo_ -= 1.0;
                const float m = 0.5f + 0.5f * static_cast<float>(std::sin(kTwoPi * lfo_));
                const float d = (0.0007f + m * 0.006f) * sr;
                const float fb = 0.2f + y * 0.7f;
                const float dL = delayRead(0, d), dR = delayRead(1, d + 3.0f);
                dl_[0][static_cast<size_t>(dlW_)] = l + dL * fb;
                dl_[1][static_cast<size_t>(dlW_)] = r + dR * fb;
                dlW_ = (dlW_ + 1) % dlSize_;
                ol = (l + dL) * 0.7f;
                orr = (r + dR) * 0.7f;
                break;
            }
            case kKpPhaser: {
                const double rate = 0.05 + x * x * 5.0;
                lfo_ += rate / sr_;
                if (lfo_ >= 1.0) lfo_ -= 1.0;
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
                if (osc_ >= 1.0) osc_ -= 1.0;
                const float s = static_cast<float>(std::sin(kTwoPi * osc_));
                ol = l + (l * s - l) * (0.3f + 0.7f * y);
                orr = r + (r * s - r) * (0.3f + 0.7f * y);
                break;
            }
            case kKpTranceGate: {
                const float period = beatsToFrames(pick(kGateBeats, x), bpm);
                gatePhase_ += 1.0 / std::max(16.0f, period);
                if (gatePhase_ >= 1.0) gatePhase_ -= 1.0;
                const float target = gatePhase_ < 0.5 ? 1.0f : 1.0f - y;
                gateEnv_ += (target - gateEnv_) * (1.0f - std::exp(-1.0f / (0.002f * sr)));
                ol = l * gateEnv_;
                orr = r * gateEnv_;
                break;
            }

            // ---------------------------------------------------- LO-FI
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
            case kKpDistortion: {
                const float drive = 1.0f + x * x * 50.0f;
                const float norm = 1.0f / std::tanh(drive);
                if (--coefCountdown_ <= 0) {
                    coefCountdown_ = 32;
                    fc_.set(700.0f * std::pow(2.0f, y * 4.5f), 0.7f, sr);
                }
                ol = svf(toneLp_[0], fc_, std::tanh(l * drive) * norm, SvfOut::Low) * 0.8f;
                orr = svf(toneLp_[1], fc_, std::tanh(r * drive) * norm, SvfOut::Low) * 0.8f;
                break;
            }
            case kKpLofi: {
                // «Старое радио»: полоса сужается по X, шум и треск растут по Y.
                if (--coefCountdown_ <= 0) {
                    coefCountdown_ = 32;
                    fc_.set(1400.0f, 0.8f + x * 4.0f, sr);
                }
                noise_ = noise_ * 1664525u + 1013904223u;
                const float nz = (static_cast<float>(noise_ >> 9) / 4194304.0f - 1.0f);
                const float crackle = ((noise_ >> 3) % 6000u == 0u) ? 0.6f : 0.0f;
                const float add = (nz * 0.02f + crackle) * y;
                ol = svf(lofiBp_[0], fc_, l, SvfOut::Band) * 2.2f + add;
                orr = svf(lofiBp_[1], fc_, r, SvfOut::Band) * 2.2f + add;
                break;
            }
            case kKpPitch: {
                // Гранулярный сдвиг высоты: X — от −12 до +12 полутонов, Y — доля эффекта.
                const float semis = std::round((x - 0.5f) * 24.0f);
                const double ratio = std::pow(2.0, semis / 12.0);
                const double win = 0.06 * sr_;
                pitchPh_ += (1.0 - ratio) / win;
                if (pitchPh_ >= 1.0) pitchPh_ -= 1.0;
                if (pitchPh_ < 0.0) pitchPh_ += 1.0;
                const double ph2 = std::fmod(pitchPh_ + 0.5, 1.0);
                const double base = static_cast<double>(histW_ - 1) - 2.0;
                const float g1 = std::sin(kPi * static_cast<float>(pitchPh_));
                const float g2 = std::sin(kPi * static_cast<float>(ph2));
                const float pl = hist(0, base - pitchPh_ * win) * g1 + hist(0, base - ph2 * win) * g2;
                const float pr = hist(1, base - pitchPh_ * win) * g1 + hist(1, base - ph2 * win) * g2;
                ol = l + (pl - l) * (0.5f + 0.5f * y);
                orr = r + (pr - r) * (0.5f + 0.5f * y);
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
