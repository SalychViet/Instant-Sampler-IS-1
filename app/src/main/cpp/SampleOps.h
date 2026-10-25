// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

#pragma once
#include <cstdint>
#include <vector>

namespace mpc {
namespace ops {

// Все функции работают с interleaved float. Диапазоны задаются в КАДРАХ [startFrame, endFrame),
// выходящие за границы значения обрезаются. Вызываются вне аудио-потока.

// min/max по каждой из `bins` корзин (по всем каналам). out: 2*bins значений [min0,max0,min1,max1,...].
void computePeaks(const float* data, int channels, int64_t frames, int bins, float* out);

// Масштабирует область так, чтобы её пик стал равен targetPeak. Возвращает применённый gain
// (1.0, если область пустая или тихая).
float normalize(float* data, int channels, int64_t frames, int64_t startFrame, int64_t endFrame,
                float targetPeak);

// Линейное нарастание 0→1 / затухание 1→0 по всей области.
void fadeIn(float* data, int channels, int64_t frames, int64_t startFrame, int64_t endFrame);
void fadeOut(float* data, int channels, int64_t frames, int64_t startFrame, int64_t endFrame);

void reverseRegion(float* data, int channels, int64_t frames, int64_t startFrame, int64_t endFrame);

// Границы неотфильтрованной части: первый и (последний+1) кадр, где |x| >= threshold.
// false — весь семпл тише порога.
bool findNonSilent(const float* data, int channels, int64_t frames, float threshold,
                   int64_t* outStart, int64_t* outEnd);

// Позиции атак (в кадрах), кроме нулевой. sensitivity 0..1 (выше = больше срабатываний),
// minGapMs — минимальный интервал между срабатываниями.
std::vector<int64_t> detectTransients(const float* data, int channels, int64_t frames,
                                      int sampleRate, float sensitivity, float minGapMs);

}  // namespace ops
}  // namespace mpc
