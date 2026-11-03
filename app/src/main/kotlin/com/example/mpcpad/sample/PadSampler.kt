// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

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

    /** ISL-1 LOOPER: запись петли — как есть, без автообрезки (иначе собьётся длина такта). */
    class LoopRecorded(val pad: Int, val clip: SampleClip, val capped: Boolean, val prerollFrames: Int) : SamplerEvent
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

    /** Контекст приложения — для записи петель ISL-1 на диск из AppViewModel. */
    val appContext: android.content.Context get() = app

    /** Пады, запись на которые идёт в режиме LOOPER: результат уходит событием LoopRecorded. */
    val loopPads: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet())

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
        val asLoop = loopPads.remove(pad)
        val preroll = if (asLoop) com.example.mpcpad.capture.PadCapture.lastPrerollFrames else 0
        scope.launch {
            val gain = Math.pow(10.0, (Settings.inputGainDb(source) / 20f).toDouble()).toFloat()
            val n = frames * channels
            val data = FloatArray(n) { i -> (shorts[i] / 32768f * gain).coerceIn(-1f, 1f) }
            if (shorts.none { it.toInt() != 0 }) {
                val reason = if (asLoop && source == CaptureSource.INTERNAL) {
                    "Вход INT слышит только другие приложения, и там тишина. Свой звук в луп: KAOSS → RESMPL"
                } else if (source == CaptureSource.INTERNAL) {
                    "Источник молчит: ничего не играло или приложение запрещает захват (DRM)"
                } else {
                    "Ничего не записано: тишина"
                }
                _events.emit(SamplerEvent.Rejected(pad, reason))
                return@launch
            }
            PreLowCut.apply(data, rate, channels)
            if (asLoop) {
                _events.emit(SamplerEvent.LoopRecorded(pad, SampleClip(data, channels, rate), capped, preroll))
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
        val msFrames = rate / 1000
        // Обрезка съела почти всё — лучше целая запись, чем никакой.
        val keepRaw = trimmed.clip.frames < msFrames * MIN_FRAMES_MS
        val clip = if (trimmed.clip === raw || keepRaw) raw.copy() else trimmed.clip
        if (clip.frames < msFrames * MIN_FRAMES_MS) {
            _events.emit(SamplerEvent.Rejected(pad, "Слишком короткая запись"))
            return
        }
        val n = clip.frames
        SampleOps.fadeIn(clip.data, channels, 0, minOf(n, msFrames))
        SampleOps.fadeOut(clip.data, channels, maxOf(0, n - msFrames * 3), n)
        if (assignToPad(pad, clip, name)) {
            val headMs = if (keepRaw) 0L else trimmed.headMs
            val tailMs = if (keepRaw) 0L else trimmed.tailMs
            _events.emit(SamplerEvent.Assigned(pad, name, clip, headMs, tailMs, capped))
        } else {
            _events.emit(SamplerEvent.Rejected(pad, "Не удалось назначить на пад " + padName(pad)))
        }
    }
}
