// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.sample

import com.example.mpcpad.Settings
import kotlin.math.cos
import kotlin.math.sin

/**
 * ISL-1, фейдер PreLC: срез низа записываемого звука, 24 дБ на октаву. Уже записанные семплы не меняются.
 * Состояние фильтра живёт в объекте: записью можно идти кусками ([process]).
 */
class PreLowCut(hz: Float, rate: Int, private val channels: Int) {
    private val b0 = DoubleArray(2)
    private val b1 = DoubleArray(2)
    private val a1 = DoubleArray(2)
    private val a2 = DoubleArray(2)
    private val z = DoubleArray(2 * channels * 2)   // ступень, канал, z1/z2

    init {
        val w = 2.0 * Math.PI * hz / rate
        for ((i, q) in doubleArrayOf(0.54119610, 1.30656296).withIndex()) {
            val alpha = sin(w) / (2.0 * q)
            val a0 = 1.0 + alpha
            b0[i] = (1.0 + cos(w)) / 2.0 / a0
            b1[i] = -(1.0 + cos(w)) / a0
            a1[i] = -2.0 * cos(w) / a0
            a2[i] = (1.0 - alpha) / a0
        }
    }

    private fun tick(x: Double, c: Int): Double {
        var v = x
        for (i in 0 until 2) {
            val k = (i * channels + c) * 2
            val y = b0[i] * v + z[k]
            z[k] = b1[i] * v - a1[i] * y + z[k + 1]
            z[k + 1] = b0[i] * v - a2[i] * y
            v = y
        }
        return v
    }

    fun process(data: FloatArray) {
        for (i in data.indices) data[i] = tick(data[i].toDouble(), i % channels).toFloat()
    }

    fun process(data: ShortArray, n: Int) {
        for (i in 0 until n) data[i] = tick(data[i].toDouble(), i % channels).toInt().coerceIn(-32768, 32767).toShort()
    }

    companion object {
        /** Фильтр по текущему положению фейдера; null — фейдер внизу, запись без изменений. */
        fun current(rate: Int, channels: Int): PreLowCut? =
            Settings.preLcHz.takeIf { it > 0f }?.let { PreLowCut(it, rate, channels) }

        /** Запись целиком (звук с выхода приложения). */
        fun apply(data: FloatArray, rate: Int, channels: Int) {
            current(rate, channels)?.process(data)
        }
    }
}
