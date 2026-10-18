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
 */
object AutoTrim {
    const val HEAD_MARGIN_MS = 5
    const val TAIL_MARGIN_MS = 30
    private const val WINDOW_MS = 5

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
        val thr = 10f.pow(thrDb / 20f)

        var first = -1
        for (w in 0 until nWin) if (rms[w] >= thr) { first = w; break }
        if (first < 0) return null
        var last = first
        for (w in nWin - 1 downTo first) if (rms[w] >= thr) { last = w; break }
        return intArrayOf(first * win, minOf(frames, (last + 1) * win))
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
