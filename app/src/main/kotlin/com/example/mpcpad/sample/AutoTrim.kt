// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.sample

import com.example.mpcpad.Settings
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Автообрезка тишины в начале/конце семпла.
 *
 * Звук ищется по RMS-огибающей окнами по 5 мс, а не по отдельным отсчётам: один щелчок или шум
 * микрофона больше не «держат» тишину. Порог — самый высокий из трёх:
 *  - TRIM (Settings.trimDb, дБFS),
 *  - шумовой фон записи + 10 дБ (оценка по самым тихим окнам), но не выше пика − 30 дБ —
 *    чтобы запись без пауз (нет «тишины» для оценки фона) не съедалась,
 *  - пик семпла − 45 дБ.
 *
 * Начало уточняется до отсчёта: первый отсчёт громче порога в окрестности найденного окна, затем —
 * назад к ближайшему переходу через ноль (не дальше 2 мс), чтобы срез не щёлкал. Конец — последний
 * отсчёт громче половины порога в последнем звучащем окне.
 */
object AutoTrim {
    const val HEAD_MARGIN_MS = 1
    const val TAIL_MARGIN_MS = 20
    private const val WINDOW_MS = 5
    private const val CLICK_MS = 60

    class Result(val clip: SampleClip, val headMs: Long, val tailMs: Long)

    /** Границы звучащей части [start, end) в кадрах или null, если звука нет. */
    fun detect(clip: SampleClip, thresholdDb: Float = Settings.trimDb): IntArray? {
        val ch = clip.channels
        val frames = clip.frames
        if (frames <= 0) return null
        val win = max(1, clip.sampleRate * WINDOW_MS / 1000)
        val nWin = (frames + win - 1) / win
        val rms = FloatArray(nWin)
        var peak = 0f
        for (w in 0 until nWin) {
            val s = w * win
            val e = minOf(frames, s + win)
            var sum = 0.0
            for (i in s until e) for (c in 0 until ch) {
                val v = clip.data[i * ch + c]
                sum += v * v
                if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
            }
            rms[w] = sqrt(sum / ((e - s) * ch)).toFloat()
        }
        if (peak <= 0f) return null

        val sorted = rms.sortedArray()
        val noise = sorted[(sorted.size * 0.1f).toInt().coerceIn(0, sorted.size - 1)]
        val db = { x: Float -> 20f * log10(max(x, 1e-9f)) }
        val peakDb = db(peak)
        var thrDb = maxOf(thresholdDb, minOf(db(noise) + 10f, peakDb - 30f), peakDb - 45f)
        thrDb = minOf(thrDb, peakDb - 6f)   // громкий звук никогда не считается тишиной
        var thr = 10f.pow(thrDb / 20f)

        var first = -1
        for (w in 0 until nWin) if (rms[w] >= thr) { first = w; break }
        if (first < 0) return null
        var last = first
        for (w in nWin - 1 downTo first) if (rms[w] >= thr) { last = w; break }

        // Выше порога только щелчок (палец по стеклу рядом с микрофоном громче самого звука):
        // опускаем порог к шумовому фону, иначе от записи останется один щелчок.
        if ((last - first + 1) * WINDOW_MS < CLICK_MS) {
            val low = 10f.pow(maxOf(thresholdDb, db(noise) + 6f) / 20f)
            if (low < thr) {
                thr = low
                for (w in 0 until nWin) if (rms[w] >= thr) { first = w; break }
                for (w in nWin - 1 downTo first) if (rms[w] >= thr) { last = w; break }
            }
        }

        fun amp(i: Int): Float {
            var m = 0f
            for (c in 0 until ch) m = max(m, kotlin.math.abs(clip.data[i * ch + c]))
            return m
        }
        // Начало — точно до отсчёта: окно до найденного и само окно.
        var onset = first * win
        for (i in maxOf(0, (first - 1) * win) until minOf(frames, (first + 1) * win)) {
            if (amp(i) >= thr * 0.7f) { onset = i; break }
        }
        // …и назад к переходу через ноль (первый канал), не дальше 2 мс.
        val maxBack = clip.sampleRate / 500
        var z = onset
        val sign0 = clip.data[onset * ch] >= 0f
        while (z > 0 && onset - z < maxBack && (clip.data[(z - 1) * ch] >= 0f) == sign0) z--
        onset = z
        // Конец — последний отсчёт громче половины порога в последнем звучащем окне.
        var end = minOf(frames, (last + 1) * win)
        for (i in end - 1 downTo last * win) {
            if (amp(i) >= thr * 0.5f) { end = i + 1; break }
        }
        return intArrayOf(onset, maxOf(end, onset + 1))
    }

    /** null — семпл целиком тише порога. */
    fun apply(
        clip: SampleClip,
        trimStart: Boolean = Settings.trimStart,
        trimEnd: Boolean = Settings.trimEnd,
        thresholdDb: Float = Settings.trimDb,
    ): Result? {
        val bounds = detect(clip, thresholdDb) ?: return null
        val msFrames = clip.sampleRate / 1000
        var s = 0
        var e = clip.frames
        if (trimStart) s = maxOf(0, bounds[0] - msFrames * HEAD_MARGIN_MS)
        if (trimEnd) e = minOf(clip.frames, bounds[1] + msFrames * TAIL_MARGIN_MS)
        if (e <= s) return null
        val out = if (s == 0 && e == clip.frames) clip else clip.slice(s, e)
        return Result(
            out,
            headMs = s.toLong() * 1000 / clip.sampleRate,
            tailMs = (clip.frames - e).toLong() * 1000 / clip.sampleRate,
        )
    }
}
