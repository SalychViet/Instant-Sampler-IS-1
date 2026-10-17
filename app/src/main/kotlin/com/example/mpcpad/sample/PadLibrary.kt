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
)

/** Наблюдаемый (Compose) список 64 пэдов: null — пэд пуст. */
object PadLibrary {
    const val PEAK_BINS = 160

    val infos = mutableStateListOf<PadSampleInfo?>().apply { repeat(64) { add(null) } }

    fun set(pad: Int, name: String, clip: SampleClip) {
        if (pad !in 0 until 64) return
        infos[pad] = PadSampleInfo(
            name,
            clip.durationMs,
            clip.sampleRate,
            clip.channels,
            SampleOps.computePeaks(clip.data, clip.channels, PEAK_BINS),
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
