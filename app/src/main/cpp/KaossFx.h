#pragma once
#include <atomic>
#include <cstdint>
#include <vector>

namespace mpc {

// Программы KAOSS. Значения ДОЛЖНЫ совпадать с Kaoss.PROGRAMS в Kotlin.
// Пять наборов по 10 (кнопки 1–5): номер программы = набор * 10 + позиция.
enum KaossProgram : int {
    // 1 FILTER
    kKpLpf = 0, kKpHpf, kKpBpf, kKpDjFilter, kKpNotch, kKpComb, kKpFormant, kKpAutoFilter, kKpEnvFilter, kKpIsolator,
    // 2 SPACE
    kKpDelay, kKpPingPong, kKpReverb, kKpDubEcho, kKpTapeEcho, kKpMultiTap, kKpHall, kKpRoom, kKpGatedVerb, kKpFreeze,
    // 3 REPEAT
    kKpBeatRepeat, kKpRoll, kKpReverse, kKpTapeStop, kKpGlitch, kKpScratch, kKpSlicer, kKpPitchRepeat, kKpSpinback, kKpBarLoop,
    // 4 MOD
    kKpFlanger, kKpPhaser, kKpRingMod, kKpSweepNoise, kKpChorus, kKpTremolo, kKpAutoPan, kKpVibrato, kKpRotary, kKpSiren,
    // 5 LO-FI
    kKpBitCrush, kKpDistortion, kKpLofi, kKpPitch, kKpFuzz, kKpWavefolder, kKpVinyl, kKpPump, kKpOctaver, kKpRobot,
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

    void resetState();
    float hist(int ch, double pos) const;   // чтение истории с интерполяцией (абсолютная позиция)
    float histAt(int ch, int64_t pos) const;
    float delayRead(int ch, float delayFrames) const;
    void delayWrite(float l, float r);
    float beatsToFrames(float beats, float bpm) const;
    float noise();
    void reverb(float in, float room, float damp, float& outL, float& outR);
    float pitchTap(int ch, double& phase, double ratio, double win, bool advance);
    void autoGain(float inL, float inR, float& l, float& r);

    int sr_ = 48000;
    std::atomic<int> program_{kKpLpf};
    std::atomic<float> x_{0.5f}, y_{0.5f};
    std::atomic<bool> touch_{false};

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

    // Повторы и скретч
    int64_t capStart_ = 0;
    int64_t capT_ = 0;
    int rollLen_ = 0;
    int64_t rollCycleStart_ = 0;
    double tapePos_ = 0;
    float tapeSpeed_ = 1;
    int64_t glitchSrc_ = 0;
    int64_t glitchSlotStart_ = 0;
    bool glitchOn_ = false;
    double scratchPos_ = 0;
    double scratchVel_ = 0;

    // Модуляция
    double lfo_ = 0, lfo2_ = 0;
    float apState_[2][6] = {};
    float phaserFb_[2] = {};
    double osc_ = 0;
    double gatePhase_ = 0;
    float ampEnv_ = 1;

    // Lo-fi
    float crushHold_[2] = {};
    float crushCount_ = 0;
    uint32_t noise_ = 22222;
    float agIn_ = 0, agOut_ = 0;

    // Питч
    double pitchPh_ = 0, pitchPh2_ = 0;
};

}  // namespace mpc
