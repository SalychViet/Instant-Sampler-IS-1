package com.example.mpcpad.sample

import androidx.compose.runtime.mutableStateListOf
import com.example.mpcpad.audio.SampleOps
import java.util.Locale

/** Что лежит на пэде — для LCD и мини-waveform. */
class PadSampleInfo(
    val name: String,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val peaks: FloatArray,
    /** Где начинается/кончается звук (доля длины, с запасом) — для T-HEAD/T-TAIL без порчи семпла. */
    val soundStart: Float = 0f,
    val soundEnd: Float = 1f,
)

/** Наблюдаемый (Compose) список 64 пэдов: null — пэд пуст. */
object PadLibrary {
    const val PEAK_BINS = 160

    val infos = mutableStateListOf<PadSampleInfo?>().apply { repeat(64) { add(null) } }

    fun set(pad: Int, name: String, clip: SampleClip) {
        if (pad !in 0 until 64) return
        val frames = clip.frames.coerceAtLeast(1)
        val ms = clip.sampleRate / 1000
        val b = AutoTrim.detect(clip)
        infos[pad] = PadSampleInfo(
            name,
            clip.durationMs,
            clip.sampleRate,
            clip.channels,
            SampleOps.computePeaks(clip.data, clip.channels, PEAK_BINS),
            soundStart = if (b == null) 0f else (b[0] - ms * AutoTrim.HEAD_MARGIN_MS).coerceAtLeast(0).toFloat() / frames,
            soundEnd = if (b == null) 1f else (b[1] + ms * AutoTrim.TAIL_MARGIN_MS).coerceAtMost(frames).toFloat() / frames,
        )
    }

    fun clear(pad: Int) {
        if (pad in 0 until 64) infos[pad] = null
    }

    /** Имя файла → короткое имя для LCD: без расширения, ВЕРХНИЙ регистр, только буквы/цифры/_/-. */
    fun displayName(raw: String, max: Int = 18): String {
        val base = raw.substringBeforeLast('.')
        val cleaned = base.uppercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .trim('_')
        return cleaned.ifEmpty { "SAMPLE" }.take(max)
    }
}
