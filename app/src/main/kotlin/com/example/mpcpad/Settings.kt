package com.example.mpcpad

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Настройки приложения. Значения — Compose-состояние (UI перерисовывается сам) и сохраняются
 * в SharedPreferences. До [init] работают значения по умолчанию (например, в превью/тестах).
 */
object Settings {
    private var prefs: SharedPreferences? = null

    /** Сетка квантизации в тиках (96 PPQ): 0 выкл, 96 = 1/4, 48 = 1/8, 24 = 1/16, 12 = 1/32, 32 = 1/8T, 16 = 1/16T. */
    val QUANTIZE_GRIDS = listOf(0, 96, 48, 32, 24, 16, 12)
    fun quantizeLabel(ticks: Int) = when (ticks) {
        0 -> "OFF"; 96 -> "1/4"; 48 -> "1/8"; 32 -> "1/8T"; 24 -> "1/16"; 16 -> "1/16T"; 12 -> "1/32"
        else -> "$ticks"
    }

    // ---- обрезка тишины при записи/импорте ----
    private val _trimStart = mutableStateOf(true)
    private val _trimEnd = mutableStateOf(false)
    private val _trimDb = mutableFloatStateOf(-50f)
    private val _recGainDb = mutableFloatStateOf(0f)

    // ---- секвенсор ----
    private val _bpm = mutableFloatStateOf(120f)
    private val _quantize = mutableIntStateOf(24)
    private val _metroMode = mutableIntStateOf(0)       // 0 выкл / 1 всегда / 2 при записи
    private val _metroLevel = mutableFloatStateOf(0.7f)
    private val _countInBars = mutableIntStateOf(1)
    private val _stepVelocity = mutableFloatStateOf(0.8f)

    // ---- общее ----
    private val _master = mutableFloatStateOf(0.8f)
    private val _pLock = mutableStateOf(false)
    private val _fullLevel = mutableStateOf(false)

    // ---- синхронизация ----
    private val _linkEnabled = mutableStateOf(false)
    private val _latencyMs = mutableFloatStateOf(0f)

    var trimStart: Boolean get() = _trimStart.value; set(v) { _trimStart.value = v; putBool("trimStart", v) }
    var trimEnd: Boolean get() = _trimEnd.value; set(v) { _trimEnd.value = v; putBool("trimEnd", v) }
    /** Порог «тишины» для автообрезки, дБFS. */
    var trimDb: Float get() = _trimDb.floatValue; set(v) { _trimDb.floatValue = v; putFloat("trimDb", v) }
    var recGainDb: Float get() = _recGainDb.floatValue; set(v) { _recGainDb.floatValue = v; putFloat("recGainDb", v) }
    var bpm: Float get() = _bpm.floatValue; set(v) { _bpm.floatValue = v; putFloat("bpm", v) }
    var quantize: Int get() = _quantize.intValue; set(v) { _quantize.intValue = v; putInt("quantize", v) }
    var metroMode: Int get() = _metroMode.intValue; set(v) { _metroMode.intValue = v; putInt("metroMode", v) }
    var metroLevel: Float get() = _metroLevel.floatValue; set(v) { _metroLevel.floatValue = v; putFloat("metroLevel", v) }
    var countInBars: Int get() = _countInBars.intValue; set(v) { _countInBars.intValue = v; putInt("countInBars", v) }
    var stepVelocity: Float get() = _stepVelocity.floatValue; set(v) { _stepVelocity.floatValue = v; putFloat("stepVelocity", v) }
    var master: Float get() = _master.floatValue; set(v) { _master.floatValue = v; putFloat("master", v) }
    /** P-LOCK: параметры шагов (VOL/PAN/PITCH/…) играют и правятся удержанием шага в STEP. */
    var pLock: Boolean get() = _pLock.value; set(v) { _pLock.value = v; putBool("pLock", v) }
    var fullLevel: Boolean get() = _fullLevel.value; set(v) { _fullLevel.value = v; putBool("fullLevel", v) }
    var linkEnabled: Boolean get() = _linkEnabled.value; set(v) { _linkEnabled.value = v; putBool("linkEnabled", v) }
    /** Сдвиг звука относительно сетки Ableton Link, мс: плюс — играть раньше. */
    var latencyMs: Float get() = _latencyMs.floatValue; set(v) { _latencyMs.floatValue = v; putFloat("latencyMs", v) }

    fun init(context: Context) {
        val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        _trimStart.value = p.getBoolean("trimStart", true)
        _trimEnd.value = p.getBoolean("trimEnd", false)
        _trimDb.floatValue = p.getFloat("trimDb", -50f).coerceIn(-72f, -20f)
        _recGainDb.floatValue = p.getFloat("recGainDb", 0f).coerceIn(-12f, 24f)
        _bpm.floatValue = p.getFloat("bpm", 120f).coerceIn(30f, 300f)
        _quantize.intValue = p.getInt("quantize", 24).takeIf { it in QUANTIZE_GRIDS } ?: 24
        _metroMode.intValue = p.getInt("metroMode", 0).coerceIn(0, 2)
        _metroLevel.floatValue = p.getFloat("metroLevel", 0.7f).coerceIn(0f, 1f)
        _countInBars.intValue = p.getInt("countInBars", 1).coerceIn(0, 4)
        _stepVelocity.floatValue = p.getFloat("stepVelocity", 0.8f).coerceIn(0.05f, 1f)
        _master.floatValue = p.getFloat("master", 0.8f).coerceIn(0f, 1.2f)
        _pLock.value = p.getBoolean("pLock", false)
        _fullLevel.value = p.getBoolean("fullLevel", false)
        _linkEnabled.value = p.getBoolean("linkEnabled", false)
        _latencyMs.floatValue = p.getFloat("latencyMs", 0f).coerceIn(-100f, 100f)
    }

    private fun putBool(k: String, v: Boolean) { prefs?.edit()?.putBoolean(k, v)?.apply() }
    private fun putFloat(k: String, v: Float) { prefs?.edit()?.putFloat(k, v)?.apply() }
    private fun putInt(k: String, v: Int) { prefs?.edit()?.putInt(k, v)?.apply() }
}
