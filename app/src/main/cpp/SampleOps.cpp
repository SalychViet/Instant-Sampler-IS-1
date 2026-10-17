#include "SampleOps.h"

#include <algorithm>
#include <cmath>

namespace mpc {
namespace ops {
namespace {

// Приводит область к допустимой; false, если она пуста.
bool clampRegion(int64_t frames, int64_t& s, int64_t& e) {
    s = std::clamp<int64_t>(s, 0, frames);
    e = std::clamp<int64_t>(e, 0, frames);
    return e > s;
}

}  // namespace

void computePeaks(const float* data, int channels, int64_t frames, int bins, float* out) {
    if (!out || bins <= 0) return;
    for (int b = 0; b < 2 * bins; ++b) out[b] = 0.0f;
    if (!data || channels < 1 || frames <= 0) return;

    for (int b = 0; b < bins; ++b) {
        int64_t from = frames * b / bins;
        int64_t to = frames * (b + 1) / bins;
        if (to <= from) to = std::min<int64_t>(from + 1, frames);   // frames < bins
        if (from >= frames) break;
        float mn = 0.0f, mx = 0.0f;
        for (int64_t i = from; i < to; ++i) {
            for (int c = 0; c < channels; ++c) {
                const float x = data[i * channels + c];
                if (x < mn) mn = x;
                if (x > mx) mx = x;
            }
        }
        out[2 * b] = mn;
        out[2 * b + 1] = mx;
    }
}

float normalize(float* data, int channels, int64_t frames, int64_t s, int64_t e, float target) {
    if (!data || channels < 1 || !clampRegion(frames, s, e)) return 1.0f;
    float peak = 0.0f;
    for (int64_t i = s * channels; i < e * channels; ++i) peak = std::max(peak, std::fabs(data[i]));
    if (peak < 1e-6f) return 1.0f;
    const float gain = target / peak;
    for (int64_t i = s * channels; i < e * channels; ++i) data[i] *= gain;
    return gain;
}

void fadeIn(float* data, int channels, int64_t frames, int64_t s, int64_t e) {
    if (!data || channels < 1 || !clampRegion(frames, s, e) || e - s < 2) return;
    const double len = static_cast<double>(e - s - 1);
    for (int64_t i = s; i < e; ++i) {
        const float g = static_cast<float>(static_cast<double>(i - s) / len);
        for (int c = 0; c < channels; ++c) data[i * channels + c] *= g;
    }
}

void fadeOut(float* data, int channels, int64_t frames, int64_t s, int64_t e) {
    if (!data || channels < 1 || !clampRegion(frames, s, e) || e - s < 2) return;
    const double len = static_cast<double>(e - s - 1);
    for (int64_t i = s; i < e; ++i) {
        const float g = static_cast<float>(1.0 - static_cast<double>(i - s) / len);
        for (int c = 0; c < channels; ++c) data[i * channels + c] *= g;
    }
}

void reverseRegion(float* data, int channels, int64_t frames, int64_t s, int64_t e) {
    if (!data || channels < 1 || !clampRegion(frames, s, e)) return;
    int64_t a = s, b = e - 1;
    while (a < b) {
        for (int c = 0; c < channels; ++c) std::swap(data[a * channels + c], data[b * channels + c]);
        ++a;
        --b;
    }
}

bool findNonSilent(const float* data, int channels, int64_t frames, float threshold, int64_t* outStart,
                   int64_t* outEnd) {
    if (!data || channels < 1 || frames <= 0) return false;
    auto loud = [&](int64_t i) {
        for (int c = 0; c < channels; ++c)
            if (std::fabs(data[i * channels + c]) >= threshold) return true;
        return false;
    };
    int64_t first = 0;
    while (first < frames && !loud(first)) ++first;
    if (first >= frames) return false;
    int64_t last = frames - 1;
    while (last > first && !loud(last)) --last;
    if (outStart) *outStart = first;
    if (outEnd) *outEnd = last + 1;
    return true;
}

std::vector<int64_t> detectTransients(const float* data, int channels, int64_t frames, int sampleRate,
                                      float sensitivity, float minGapMs) {
    std::vector<int64_t> result;
    if (!data || channels < 1 || frames <= 0 || sampleRate <= 0) return result;

    const int64_t hop = std::max<int64_t>(1, static_cast<int64_t>(sampleRate) * 2 / 1000);  // 2 мс
    const int64_t nHops = frames / hop;
    if (nHops < 4) return result;

    // Огибающая: средний |x| на каждый hop + префиксные суммы для среднего по окну истории.
    std::vector<float> env(static_cast<size_t>(nHops));
    float maxEnv = 0.0f;
    for (int64_t h = 0; h < nHops; ++h) {
        double sum = 0.0;
        for (int64_t i = h * hop; i < (h + 1) * hop; ++i)
            for (int c = 0; c < channels; ++c) sum += std::fabs(data[i * channels + c]);
        env[static_cast<size_t>(h)] = static_cast<float>(sum / static_cast<double>(hop * channels));
        maxEnv = std::max(maxEnv, env[static_cast<size_t>(h)]);
    }
    if (maxEnv < 1e-4f) return result;

    std::vector<double> prefix(static_cast<size_t>(nHops) + 1, 0.0);
    for (int64_t h = 0; h < nHops; ++h)
        prefix[static_cast<size_t>(h) + 1] = prefix[static_cast<size_t>(h)] + env[static_cast<size_t>(h)];

    const float sens = std::clamp(sensitivity, 0.0f, 1.0f);
    const float ratio = 6.0f - 4.0f * sens;                 // во сколько раз атака громче предыстории
    const float floorLevel = (0.20f - 0.17f * sens) * maxEnv;  // ниже этого уровня не срабатываем
    const int64_t historyHops = 50;                          // 100 мс
    const int64_t minGapHops =
        std::max<int64_t>(1, static_cast<int64_t>(minGapMs * static_cast<float>(sampleRate) / 1000.0f) / hop);

    int64_t last = -minGapHops;
    for (int64_t i = 1; i < nHops; ++i) {
        const int64_t a = std::max<int64_t>(0, i - historyHops);
        const double mean = (prefix[static_cast<size_t>(i)] - prefix[static_cast<size_t>(a)]) /
                            static_cast<double>(i - a);
        const float e = env[static_cast<size_t>(i)];
        if (e > floorLevel && e > ratio * static_cast<float>(mean) && e > env[static_cast<size_t>(i) - 1]) {
            if (i - last >= minGapHops) {
                result.push_back(i * hop);    // начало hop, в котором выросла энергия (не позже атаки)
                last = i;
            }
        }
    }
    return result;
}

}  // namespace ops
}  // namespace mpc
