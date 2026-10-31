// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.sample

import com.example.mpcpad.audio.SampleOps
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * ISL-1, SETUP → LOOP QUANTIZE: удары (транзиенты) записанной петли сдвигаются к сетке на долю [apply] strength.
 * Звук не режется: кусок между ударами склеивается с соседним плавным переходом, а если кусок стал длиннее,
 * недостающее берётся из его же конца (там затухание, следующего удара в нём нет) — без тишины и щелчков.
 */
object LoopQuantize {
    private const val MIN_GAP_MS = 15   // удары ближе друг к другу считаются одним
    private const val LEAD_MS = 2       // склейка заканчивается чуть раньше удара, чтобы не задеть атаку
    private const val SPLICE_MS = 4     // переход перед ударом
    private const val FILL_SPLICE_MS = 10   // переход внутри куска

    /**
     * [beats] — доли в круге петли, [perBeat] — делений сетки на долю (1 = 1/4 … 8 = 1/32),
     * [strength] — 0..1: на какую часть расстояния до сетки сдвинуть удар.
     */
    fun apply(clip: SampleClip, beats: Int, perBeat: Int, strength: Float): SampleClip {
        val n = clip.frames
        val steps = beats * perBeat
        if (steps < 2 || n < steps * 8 || strength <= 0f) return clip
        val step = n.toDouble() / steps
        val sr = clip.sampleRate
        val minGap = sr * MIN_GAP_MS / 1000
        val lead = sr * LEAD_MS / 1000
        // Точки склейки: src — где в записи, dst — где в петле.
        val src = arrayListOf(0)
        val dst = arrayListOf(0)
        for (t in SampleOps.detectTransients(clip, 0.5f, 50f).filter { it in 1 until n }.sorted()) {
            val grid = ((t / step).roundToInt() * step).roundToInt()
            if (grid >= n) continue   // удар у самого конца круга не двигаем
            val q = t + ((grid - t) * strength).roundToInt()
            val cut = t - lead
            val to = q - lead
            if (to <= 0 || cut <= 0) continue
            if (src.size > 1 && (to - dst.last() < minGap || cut - src.last() < minGap)) continue
            src.add(cut)
            dst.add(to)
        }
        if (src.size < 2) return clip
        src.add(n)
        dst.add(n)
        return render(clip, src, dst)
    }

    /** Собирает петлю: кусок src[k]..src[k+1] встаёт на место dst[k]..dst[k+1]. */
    private fun render(clip: SampleClip, src: List<Int>, dst: List<Int>): SampleClip {
        val n = clip.frames
        val ch = clip.channels
        val sr = clip.sampleRate
        val out = FloatArray(n * ch)
        fun window(x: Float): Float = sin(x * PI.toFloat() / 2f).let { it * it }

        fun grain(from: Int, to: Int, len: Int, fadeIn: Int, fadeOut: Int) {
            for (i in 0 until len) {
                val d = to + i
                val s = from + i
                if (d < 0 || d >= n || s < 0 || s >= n) continue
                var g = 1f
                if (i < fadeIn) g *= window((i + 0.5f) / fadeIn)
                if (len - 1 - i < fadeOut) g *= window((len - i - 0.5f) / fadeOut)
                for (c in 0 until ch) out[d * ch + c] += clip.data[s * ch + c] * g
            }
        }

        // Длина перехода на каждой склейке: на краях круга перехода нет.
        val splice = sr * SPLICE_MS / 1000
        val xf = IntArray(src.size)
        for (k in 1 until src.size - 1) {
            val shortest = min(min(src[k] - src[k - 1], src[k + 1] - src[k]), min(dst[k] - dst[k - 1], dst[k + 1] - dst[k]))
            xf[k] = (shortest / 4).coerceIn(1, splice)
        }
        for (k in 0 until src.size - 1) {
            val s0 = src[k]
            val s1 = src[k + 1]
            val d0 = dst[k]
            val d1 = dst[k + 1]
            val size = s1 - s0
            val gap = (d1 - d0) - size
            val inX = xf[k]
            val outX = xf[k + 1]
            if (gap <= 0) {
                grain(s0 - inX, d0 - inX, d1 - d0 + inX, inX, outX)
                continue
            }
            // Кусок стал длиннее: конец куска читается ещё раз и ровно доходит до следующего удара.
            val x = min(sr * FILL_SPLICE_MS / 1000, size / 4).coerceAtLeast(1)
            if (gap + x <= size) {
                val take = max(gap + x, min(3 * x, size))
                val headEnd = d1 - take + x
                grain(s0 - inX, d0 - inX, headEnd - d0 + inX, inX, x)
                grain(s1 - take, d1 - take, take, x, outX)
            } else {
                grain(s0 - inX, d0 - inX, size + inX, inX, x)
                var at = d0 + size - x
                while (true) {
                    val take = min(d1 - at, size)
                    val last = take == d1 - at
                    grain(s1 - take, at, take, x, if (last) outX else x)
                    if (last) break
                    at += take - x
                }
            }
        }
        return SampleClip(out, ch, sr)
    }
}
