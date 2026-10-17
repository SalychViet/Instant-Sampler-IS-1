package com.example.mpcpad.sample

import com.example.mpcpad.Settings
import com.example.mpcpad.audio.SampleOps

/** Автообрезка тишины в начале/конце записанного семпла. */
object AutoTrim {
    const val HEAD_MARGIN_MS = 5
    const val TAIL_MARGIN_MS = 15

    class Result(val clip: SampleClip, val headMs: Long, val tailMs: Long)

    /** null — семпл целиком тише порога (или обрезать нечего осмысленно). */
    fun apply(
        clip: SampleClip,
        trimStart: Boolean = Settings.trimStart,
        trimEnd: Boolean = Settings.trimEnd,
        thresholdDb: Float = Settings.trimDb,
    ): Result? {
        val bounds = SampleOps.findNonSilent(clip.data, clip.channels, thresholdDb) ?: return null
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
