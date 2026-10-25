// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.audio

import com.example.mpcpad.sample.PadLibrary
import com.example.mpcpad.sample.SampleClip
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/** Синтезированный стартовый кит банка A: чтобы пэды звучали сразу после установки. */
object DefaultKit {
    private const val SR = 44100
    private var rnd = Random(42)

    /** Хэты: закрытый глушит открытый. */
    val CHOKE_PADS = listOf(3, 4)

    /** Тоны — в режиме LOOP, чтобы был слышен GATE/LOOP. */
    val LOOP_PADS = 12..15

    val names = listOf(
        "Kick", "Snare", "Clap", "HH closed",
        "HH open", "Tom low", "Tom mid", "Tom high",
        "Rim", "Sub kick", "Snare 2", "Crash",
        "Tone C", "Tone E", "Tone G", "Tone C+",
    )

    fun nameOf(globalPad: Int): String = if (globalPad in names.indices) names[globalPad] else "—"

    fun initialMode(globalPad: Int): Int = if (globalPad in LOOP_PADS) PlayMode.LOOP else PlayMode.ONE_SHOT

    fun load() {
        val kit = build()
        kit.forEachIndexed { pad, data ->
            AudioEngine.loadSample(pad, data, 1, SR)
            PadLibrary.set(pad, "S_" + PadLibrary.displayName(names[pad]), SampleClip(data, 1, SR))
        }
        for (pad in CHOKE_PADS) AudioEngine.setParam(pad, PadParam.CHOKE, 1f)
        for (pad in LOOP_PADS) {
            AudioEngine.setParam(pad, PadParam.MODE, PlayMode.LOOP.toFloat())
            AudioEngine.setParam(pad, PadParam.RELEASE_MS, 120f)
        }
    }

    /** Звук стартового кита для пада 0..15 (например, чтобы вернуть его через UNDO). */
    fun clipFor(pad: Int): SampleClip? = build().getOrNull(pad)?.let { SampleClip(it, 1, SR) }

    private fun build(): List<FloatArray> {
        rnd = Random(42)   // один и тот же шум при каждой сборке
        return listOf(
            kick(),
            snare(),
            clap(),
            hat(0.08, 60.0),
            hat(0.5, 9.0),
            kick(110.0, 80.0, 9.0, 0.45),
            kick(150.0, 90.0, 9.0, 0.4),
            kick(200.0, 100.0, 9.0, 0.35),
            rim(),
            kick(40.0, 90.0, 5.0, 0.8),
            snare(0.18, 230.0),
            hat(1.0, 3.5),
            tone(130.81),
            tone(164.81),
            tone(196.0),
            tone(261.63),
        )
    }

    private fun buf(sec: Double) = FloatArray((SR * sec).toInt())

    private fun noise() = rnd.nextDouble() * 2 - 1

    private fun kick(f0: Double = 48.0, sweep: Double = 120.0, dec: Double = 7.0, len: Double = 0.5): FloatArray {
        val o = buf(len)
        var ph = 0.0
        for (i in o.indices) {
            val t = i / SR.toDouble()
            ph += 2 * PI * (f0 + sweep * exp(-t * 30)) / SR
            o[i] = tanh(sin(ph) * exp(-t * dec) * 1.4).toFloat()
        }
        return o
    }

    private fun snare(len: Double = 0.3, tone: Double = 190.0): FloatArray {
        val o = buf(len)
        for (i in o.indices) {
            val t = i / SR.toDouble()
            o[i] = (noise() * exp(-t * 16) * 0.55 + sin(2 * PI * tone * t) * exp(-t * 22) * 0.55).toFloat()
        }
        return o
    }

    private fun clap(): FloatArray {
        val o = buf(0.35)
        val bursts = doubleArrayOf(0.0, 0.011, 0.022)
        for (i in o.indices) {
            val t = i / SR.toDouble()
            var env = exp(-t * 14) * 0.5
            for (b in bursts) if (t >= b) env += exp(-(t - b) * 90) * 0.5
            o[i] = (noise() * env * 0.7).toFloat()
        }
        return o
    }

    /** Шум через простейший ФВЧ (разность соседних отсчётов). */
    private fun hat(len: Double, dec: Double): FloatArray {
        val o = buf(len)
        var prev = 0.0
        for (i in o.indices) {
            val t = i / SR.toDouble()
            val n = noise()
            o[i] = ((n - prev) * 0.5 * exp(-t * dec)).toFloat()
            prev = n
        }
        return o
    }

    private fun rim(): FloatArray {
        val o = buf(0.1)
        for (i in o.indices) {
            val t = i / SR.toDouble()
            o[i] = (sin(2 * PI * 1700 * t) * exp(-t * 90) * 0.6 + noise() * exp(-t * 120) * 0.2).toFloat()
        }
        return o
    }

    /** Ровно k периодов в буфере — чтобы петля замыкалась без щелчка. */
    private fun tone(f: Double): FloatArray {
        val k = maxOf(1, (f * 0.5).roundToInt())
        val n = (SR * k / f).roundToInt()
        return FloatArray(n) { i ->
            var s = 0.0
            for (h in 1 until 5) s += sin(2 * PI * h * k * i / n) / h
            (s * 0.3).toFloat()
        }
    }
}
