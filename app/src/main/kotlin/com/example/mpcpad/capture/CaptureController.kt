package com.example.mpcpad.capture

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class CaptureSource { MIC, INTERNAL }

enum class CaptureStatus { IDLE, COUNT_IN, ARMED, RECORDING }

data class RecorderConfig(
    val source: CaptureSource,
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
    /** Порог автостарта в dBFS (например -30). null — писать сразу. */
    val thresholdDb: Float? = null,
    val countInMs: Int = 0,
)

data class RecordedClip(val file: File, val id: Long)

data class CaptureState(
    val status: CaptureStatus = CaptureStatus.IDLE,
    val elapsedMs: Long = 0,
    val countInLeftMs: Long = 0,
    /** Сколько подряд приходит цифровая тишина (признак запрета захвата источником). */
    val silentMs: Long = 0,
    val error: String? = null,
    val clip: RecordedClip? = null,
)

/**
 * Процессный синглтон: мост между CaptureService/Recorder (рабочие потоки) и UI.
 * Состояние — StateFlow (обновляется ~10 раз/с); уровень для VU-метра — volatile-поле (читается каждый кадр).
 */
object CaptureController {
    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    /** Пик последнего блока, 0..1. */
    @Volatile var level: Float = 0f

    private var nextId = 1L
    private var consumedId = 0L

    val isBusy: Boolean get() = _state.value.status != CaptureStatus.IDLE

    fun publish(
        status: CaptureStatus,
        elapsedMs: Long = 0,
        countInLeftMs: Long = 0,
        silentMs: Long = 0,
    ) {
        _state.update { it.copy(status = status, elapsedMs = elapsedMs, countInLeftMs = countInLeftMs, silentMs = silentMs, error = null) }
    }

    /** Запись завершена: [file] — готовый WAV (или null), [error] — сообщение для пользователя. */
    @Synchronized
    fun finished(file: File?, error: String?) {
        level = 0f
        val clip = file?.let { RecordedClip(it, nextId++) }
        _state.value = CaptureState(status = CaptureStatus.IDLE, error = error, clip = clip)
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** true — клип с этим id ещё не забирали (защита от повторной загрузки после пересоздания ViewModel). */
    @Synchronized
    fun consume(id: Long): Boolean {
        if (id <= consumedId) return false
        consumedId = id
        return true
    }
}
