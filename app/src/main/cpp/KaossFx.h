#pragma once
#include <atomic>
#include <cstdint>
#include <vector>

namespace mpc {

// Программы KAOSS — по образцу KORG KAOSS PAD KP3+ (имена и оси X/Y — из таблицы программ KP3+).
// Значения ДОЛЖНЫ совпадать с Kaoss.PROGRAMS в Kotlin.
// Пять наборов по 10 (кнопки 1–5): номер программы = набор * 10 + позиция.
enum KaossProgram : int {
    // 1 FILTER — FLt.1 … FL.10
    kKpMorphFilt = 0, kKpLpf, kKpHpf, kKpBpf, kKp72dbLpf, kKpLpfDelay, kKpHpfDelay, kKpBpfDelay, kKpLpfReverb, kKpHpfReverb,
    // 2 SPACE — дилеи, ревербы и особые программы KP3+
    kKpDelay, kKpEchoBreak, kKpTapeEcho, kKpDubEcho, kKpPingPong, kKpPumpReverb, kKpFreezeReverb, kKpGateReverb, kKpJetSpace, kKpGrainShift,
    // 3 REPEAT — LoP.1 … LP.10
    kKpLooperFR, kKpBreakLoop, kKpLoopRvsDelay, kKpFreezeLoop, kKpHighLooper, kKpShuttleLoop, kKpSliceLoop, kKpLoopBreaker, kKpKp2Looper, kKpVinylLoop,
    // 4 MOD — Sweep Noise (с хвостом дилея и ревёрба) + программы Modulation KP3+
    kKpSweepNoise, kKpVinylBreak, kKpBreakVerb, kKpJetMinus, kKpManuPhaser, kKpTalkFilter, kKpDigiTalk, kKpDuckComp, kKpCompressor, kKpPitchDelay,
    // 5 LFO — LFO.1 … LF.10 (вместо LFO.8 — LF.26 Audio Gate & LPF)
    kKpJagFilter, kKpLfoLpf, kKpLfoHpf, kKpLfoBpf, kKpLfoHpfDelay, kKpInfiniteHpf, kKpRandomLpf, kKpGateLpf, kKpYoiYoi, kKpFlanger,
    kKpCount
};

// Мастер-эффект в духе KAOSS PAD: одна программа, управление X/Y (0..1) и касанием.
// Касание включает эффект; у дилеев и ревербов после отпускания доигрывают «хвосты».
//
// Потоки: setProgram/setXY/setTouch — из любого потока (атомики);
// setSampleRate — пока аудио-поток не работает (аллокации); process — аудио-поток.
class KaossFx {
public:
    KaossFx();
    void setSampleRate(int sr);

    void setProgram(int p) { program_.store(p, std::memory_order_relaxed); }
    void setXY(float x, float y) {
        x_.store(x, std::memory_order_relaxed);
        y_.store(y, std::memory_order_relaxed);
    }
    void setTouch(bool on) { touch_.store(on, std::memory_order_relaxed); }
    // DRY/WET: 1 — как задумано программой, 0 — эффект не слышен.
    void setMix(float m) { mix_.store(m, std::memory_order_relaxed); }

    // io: interleaved stereo, обрабатывается на месте. bpm — для эффектов с синхронизацией.
    void process(float* io, int numFrames, float bpm);

    static bool hasTails(int program);

private:
    struct Svf {
        float ic1 = 0, ic2 = 0;
        void reset() { ic1 = ic2 = 0; }
    };
    struct SvfCoef {
        float a1 = 0, a2 = 0, a3 = 0, k = 1;
        void set(float fc, float q, float sr);
    };
    enum class SvfOut { Low, Band, High, Notch };
    static float svf(Svf& s, const SvfCoef& c, float v0, SvfOut out);
    static void svf3(Svf& s, const SvfCoef& c, float v0, float& lo, float& bp, float& hp);
    float loopLen(float x, float bpm) const;   // Looper Beat по X, кадры
    void compress(float level, float thresh, float attackMs, float releaseMs);
    float pitchTap(int ch, double& phase, double ratio, double win, bool advance);

    void resetState();
    float hist(int ch, double pos) const;   // чтение истории с интерполяцией (абсолютная позиция)
    float histAt(int ch, int64_t pos) const;
    float delayRead(int ch, float delayFrames) const;
    void delayWrite(float l, float r);
    float beatsToFrames(float beats, float bpm) const;
    float noise();
    void reverb(float in, float room, float damp, float& outL, float& outR);

    int sr_ = 48000;
    std::atomic<int> program_{kKpLpf};
    std::atomic<float> x_{0.5f}, y_{0.5f};
    std::atomic<bool> touch_{false};
    std::atomic<float> mix_{1.0f};
    float mixS_ = 1.0f;

    int curProgram_ = -1;
    float xs_ = 0.5f, ys_ = 0.5f;   // сглаженные X/Y
    float wet_ = 0.0f;              // плавное включение эффекта
    bool wasTouch_ = false;

    // История входа (повторы, реверс, скретч, питч): кольцо, абсолютный счётчик кадров.
    std::vector<float> hist_[2];
    int64_t histW_ = 0;
    int histSize_ = 1;

    // Линия задержки (дилеи, флэнджер, хорус, гребёнка…)
    std::vector<float> dl_[2];
    int dlW_ = 0;
    int dlSize_ = 1;
    float dlTime_ = 0;   // сглаженное время, кадры
    Svf fbLp_[2];

    // Ревёрб (Freeverb)
    static constexpr int kCombs = 8, kAllpasses = 4;
    std::vector<float> comb_[2][kCombs];
    int combIdx_[2][kCombs] = {};
    float combStore_[2][kCombs] = {};
    std::vector<float> ap_[2][kAllpasses];
    int apIdx_[2][kAllpasses] = {};
    float gateEnv_ = 0;
    int gateHold_ = 0;

    // Фильтры
    Svf f_[2];
    Svf f2_[2][3];
    SvfCoef fc_;
    SvfCoef fc2_[3];
    int coefCountdown_ = 0;
    float env_ = 0;

    // Лупер и «пластинка»
    int64_t capStart_ = 0;
    int64_t capT_ = 0;
    double tapePos_ = 0;
    float tapeSpeed_ = 1;

    // Модуляция
    double lfo_ = 0;
    float apState_[2][6] = {};
    float phaserFb_[2] = {};
    double gatePhase_ = 0;
    float ampEnv_ = 1;

    // Lo-fi
    float crushHold_[2] = {};
    float crushCount_ = 0;
    uint32_t noise_ = 22222;

    // KP3: 72 дБ/окт, лупер, скретч по Y, компрессор, случайный LFO
    Svf f6_[2][6];
    SvfCoef fc6_[6];
    float prevY_ = 0.5f;
    double vinylPos_ = 0;
    float compGain_ = 1;
    float compEnv_ = 0;
    float sh_ = 0, shS_ = 0;
    double pitchPh_ = 0;
    int64_t lfoCycle_ = -1;
    int64_t grainSrc_ = 0;
    int64_t grainSlot_ = -1;
};

}  // namespace mpc
