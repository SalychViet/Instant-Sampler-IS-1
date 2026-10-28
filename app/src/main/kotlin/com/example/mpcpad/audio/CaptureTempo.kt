// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.sqrt

/**
 * CAPTURE (ISL-1): темп сыгранного «вслепую» — без метронома и воспроизведения.
 * Перебираем темпы 70–180 BPM и ищем тот, при котором удары лучше всего ложатся на сетку
 * шестнадцатых от первого удара. Мелкая сетка «ловит» удары случайно чаще, поэтому из оценки
 * вычитается ожидаемое случайное совпадение — иначе всегда выигрывал бы самый быстрый темп.
 */
object CaptureTempo {
    private const val SIGMA = 0.018   // допуск попадания в долю, с

    /** [onsets] — моменты ударов в секундах (по возрастанию). null — ударов слишком мало. */
    fun detect(onsets: DoubleArray): Double? {
        if (onsets.size < 3) return null
        var bestBpm = 0.0
        var best = Double.NEGATIVE_INFINITY
        var bpm = 70.0
        while (bpm <= 180.0) {
            val adj = score(onsets, bpm)
            if (adj > best + 1e-6) {
                best = adj
                bestBpm = bpm
            }
            bpm += 0.25
        }
        // Вдвое быстрее/медленнее — та же сетка (восьмые при 140 = шестнадцатые при 70). Если такой темп
        // объясняет удары почти так же хорошо, берём тот, что ближе к привычным 120.
        var pick = bestBpm
        for (guess in doubleArrayOf(bestBpm * 2, bestBpm / 2)) {
            // уточняем рядом (±1.5 BPM): удвоенный темп не всегда попадает точно в шаг перебора
            var alt = guess
            var altFit = Double.NEGATIVE_INFINITY
            var b = guess - 1.5
            while (b <= guess + 1.5) {
                if (b in 70.0..180.0) {
                    val f = fit(onsets, b)
                    if (f > altFit) { altFit = f; alt = b }
                }
                b += 0.25
            }
            // сравниваем само попадание в сетку (без поправки на случайность — она у мелкой сетки больше)
            if (altFit >= fit(onsets, bestBpm) * 0.95 && abs(alt - 120) < abs(pick - 120)) pick = alt
        }
        return pick
    }

    /** Насколько удары попадают в сетку шестнадцатых темпа [bpm] (от первого удара). */
    private fun fit(onsets: DoubleArray, bpm: Double): Double {
        val t0 = onsets[0]
        val s16 = 60.0 / bpm / 4.0
        var f = 0.0
        for (t in onsets) {
            val x = (t - t0) / s16
            val d = abs(x - round(x)) * s16
            f += exp(-(d / SIGMA) * (d / SIGMA))
        }
        return f
    }

    /** Попадание минус ожидаемое случайное (равномерное d в [0, s16/2]). */
    private fun score(onsets: DoubleArray, bpm: Double): Double {
        val s16 = 60.0 / bpm / 4.0
        val chance = minOf(1.0, SIGMA * sqrt(Math.PI) / s16)
        return fit(onsets, bpm) - onsets.size * chance
    }
}
