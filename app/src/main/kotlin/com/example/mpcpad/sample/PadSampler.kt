package com.example.mpcpad.sample

import android.app.Application
import com.example.mpcpad.Settings
import com.example.mpcpad.audio.AudioEngine
import com.example.mpcpad.audio.PadParam
import com.example.mpcpad.audio.SampleOps
import com.example.mpcpad.capture.CaptureSource
import com.example.mpcpad.ui.padName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

sealed interface SamplerEvent {
    class Assigned(
        val pad: Int,
        val name: String,
        val clip: SampleClip,
        val headTrimMs: Long,
        val tailTrimMs: Long,
        val capped: Boolean,
    ) : SamplerEvent

    class Rejected(val pad: Int, val reason: String) : SamplerEvent
}

/**
 * Назначение записанного/импортированного звука на пэд: усиление, автообрезка,
 * микро-фейды от щелчков, загрузка в движок и сохранение на диск.
 */
object PadSampler {
    private const val MIN_FRAMES_MS = 30

    private lateinit var app: Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _events = MutableSharedFlow<SamplerEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SamplerEvent> = _events.asSharedFlow()

    fun init(application: Application) {
        app = application
    }

    /** Из ArmedCapture (поток записи): 16-bit PCM → float с учётом GAIN. */
    fun onRecorded(
        pad: Int,
        shorts: ShortArray,
        frames: Int,
        channels: Int,
        rate: Int,
        capped: Boolean,
        source: CaptureSource,
    ) {
        scope.launch {
            val gain = Math.pow(10.0, (Settings.recGainDb / 20f).toDouble()).toFloat()
            val n = frames * channels
            val data = FloatArray(n) { i -> (shorts[i] / 32768f * gain).coerceIn(-1f, 1f) }
            if (shorts.none { it.toInt() != 0 }) {
                val reason = if (source == CaptureSource.INTERNAL) {
                    "Источник молчит: ничего не играло или приложение запрещает захват (DRM)"
                } else {
                    "Ничего не записано: тишина"
                }
                _events.emit(SamplerEvent.Rejected(pad, reason))
                return@launch
            }
            process(pad, SampleClip(data, channels, rate), "REC_" + padName(pad), capped)
        }
    }

    fun importClip(pad: Int, clip: SampleClip, name: String, truncated: Boolean) {
        scope.launch { process(pad, clip, name, truncated) }
    }

    fun reject(pad: Int, reason: String) {
        scope.launch { _events.emit(SamplerEvent.Rejected(pad, reason)) }
    }

    /** Синхронно: в движок + на диск + в библиотеку. false — движок не принял семпл. */
    fun assignToPad(pad: Int, clip: SampleClip, name: String): Boolean {
        if (!AudioEngine.loadSample(pad, clip.data, clip.channels, clip.sampleRate)) return false
        AudioEngine.setParam(pad, PadParam.START, 0f)
        AudioEngine.setParam(pad, PadParam.END, 1f)
        PadStore.save(app, pad, clip, name)
        PadLibrary.set(pad, name, clip)
        return true
    }

    private suspend fun process(pad: Int, raw: SampleClip, name: String, capped: Boolean) {
        val channels = raw.channels
        val rate = raw.sampleRate
        val trimmed = AutoTrim.apply(raw)
        if (trimmed == null) {
            _events.emit(
                SamplerEvent.Rejected(
                    pad,
                    String.format("Слишком тихо для порога %.0f дБ — поднимите GAIN или опустите TRIM", Settings.trimDb),
                )
            )
            return
        }
        // Обрезка могла вернуть тот же объект — копируем, чтобы фейды не испортили исходник.
        val clip = if (trimmed.clip === raw) raw.copy() else trimmed.clip
        val msFrames = rate / 1000
        if (clip.frames < msFrames * MIN_FRAMES_MS) {
            _events.emit(SamplerEvent.Rejected(pad, "Слишком короткая запись"))
            return
        }
        val n = clip.frames
        SampleOps.fadeIn(clip.data, channels, 0, minOf(n, msFrames))
        SampleOps.fadeOut(clip.data, channels, maxOf(0, n - msFrames * 3), n)
        if (assignToPad(pad, clip, name)) {
            _events.emit(SamplerEvent.Assigned(pad, name, clip, trimmed.headMs, trimmed.tailMs, capped))
        } else {
            _events.emit(SamplerEvent.Rejected(pad, "Не удалось назначить на пад " + padName(pad)))
        }
    }
}
