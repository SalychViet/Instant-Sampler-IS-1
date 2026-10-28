// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.sample

import com.example.mpcpad.audio.SampleOps
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ISL-1, SETUP → LOOP QUANTIZE: удары (транзиенты) записанной петли встают на ближайшую 1/16.
 * Кусок от удара до следующего удара переезжает целиком; если он длиннее нового места — обрезается
 * (с коротким затуханием), если короче — дальше тишина до следующего удара.
 */
object LoopQuantize {
    /** [steps] — сколько шестнадцатых в круге петли (доли × 4). */
    fun apply(clip: SampleClip, steps: Int): SampleClip {
        val n = clip.frames
        if (steps < 2 || n < steps * 8) return clip
        val step = n.toDouble() / steps
        val found = SampleOps.detectTransients(clip, 0.5f, 50f).filter { it in 1 until n }.sorted()
        // Куда встаёт каждый удар; удары у самого конца круга (сетка = n) и дубли на одной черте не двигаем.
        val src = ArrayList<Int>()
        val dst = ArrayList<Int>()
        for (t in found) {
            val q = ((t / step).roundToInt() * step).roundToInt()
            if (q >= n || (dst.isNotEmpty() && q <= dst.last())) continue
            src.add(t)
            dst.add(q)
        }
        if (src.isEmpty()) return clip

        val ch = clip.channels
        val out = FloatArray(n * ch)
        val fade = (clip.sampleRate / 500).coerceAtLeast(1)   // 2 мс
        fun place(s0: Int, sEnd: Int, d0: Int, dEnd: Int) {
            var s = s0
            var d = d0
            if (d < 0) { s -= d; d = 0 }   // кусок до первого удара мог «уехать» левее начала
            val len = min(sEnd - s, dEnd - d)
            if (len <= 0) return
            System.arraycopy(clip.data, s * ch, out, d * ch, len * ch)
            if (len < sEnd - s0 || d + len < dEnd) SampleOps.fadeOut(out, ch, d + len - min(fade, len), d + len)
        }
        // До первого удара — сдвигаем вместе с ним (конец куска упирается в новое место удара).
        place(0, src[0], dst[0] - src[0], dst[0])
        for (k in src.indices) {
            val sEnd = if (k + 1 < src.size) src[k + 1] else n
            val dEnd = if (k + 1 < dst.size) dst[k + 1] else n
            place(src[k], sEnd, dst[k], dEnd)
        }
        return SampleClip(out, ch, clip.sampleRate)
    }
}
