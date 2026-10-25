// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.sample

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mpcpad.Settings
import com.example.mpcpad.audio.SampleOps
import com.example.mpcpad.capture.CaptureController
import com.example.mpcpad.capture.CaptureSource
import com.example.mpcpad.capture.CaptureState
import com.example.mpcpad.capture.RecorderConfig
import com.example.mpcpad.capture.RecordingsStore
import com.example.mpcpad.ui.padName
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Состояние и операции режима SAMPLE: настройки записи, загруженный семпл, выделение (start/end),
 * chop-маркеры, правки с undo, назначение на пады. Тяжёлая работа — на Dispatchers.Default.
 */
class SampleViewModel(app: Application) : AndroidViewModel(app) {

    // ---- настройки записи ----
    var source by mutableStateOf(CaptureSource.MIC)
    var micStereo by mutableStateOf(false)
    var thresholdOn by mutableStateOf(false)
    var thresholdDb by mutableFloatStateOf(-30f)
    var countIn by mutableStateOf(false)

    // ---- редактируемый семпл ----
    var clip by mutableStateOf<SampleClip?>(null); private set
    var peaks by mutableStateOf<FloatArray?>(null); private set
    /** Выделение (маркеры start/end) в долях 0..1 от длины семпла. */
    var selStart by mutableFloatStateOf(0f); private set
    var selEnd by mutableFloatStateOf(1f); private set
    /** Chop-маркеры, доли 0..1, отсортированы. */
    var marks by mutableStateOf<List<Float>>(emptyList()); private set
    var chopEdit by mutableStateOf(false)

    /** Имя загруженного семпла (для LCD и имён срезов). */
    var clipName by mutableStateOf("SAMPLE"); private set

    var message by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var canUndo by mutableStateOf(false); private set
    var recordings by mutableStateOf<List<File>>(emptyList()); private set

    val capture: StateFlow<CaptureState> = CaptureController.state

    private class Snapshot(val clip: SampleClip, val start: Float, val end: Float, val marks: List<Float>)
    private val undoStack = ArrayDeque<Snapshot>()
    private val preview = Preview()

    init {
        refreshRecordings()
        viewModelScope.launch {
            CaptureController.state.collect { s ->
                val c = s.clip
                if (c != null && CaptureController.consume(c.id)) loadFile(c.file)
                val err = s.error
                if (err != null) {
                    message = err
                    CaptureController.clearError()
                }
            }
        }
    }

    // ============================ запись ============================

    fun buildConfig() = RecorderConfig(
        source = source,
        sampleRate = 48_000,
        channels = if (source == CaptureSource.INTERNAL || micStereo) 2 else 1,
        thresholdDb = if (thresholdOn) thresholdDb else null,
        countInMs = if (countIn) 3000 else 0,
    )

    fun showMessage(text: String?) { message = text }

    fun refreshRecordings() {
        recordings = RecordingsStore.list(getApplication())
    }

    // ============================ загрузка ============================

    fun loadFile(file: File) = load(PadLibrary.displayName(file.name.removePrefix("rec_"))) {
        val c = SampleOps.parseWav(file.readBytes()) ?: throw IOException("Cannot read the recording")
        AudioDecoder.Result(c, false)
    }

    fun importUri(uri: Uri) = load(PadLibrary.displayName(displayNameOf(uri))) {
        AudioDecoder.decode(getApplication(), uri)
    }

    /** Импорт файла сразу на пад (с автообрезкой тишины); результат приходит событием [PadSampler.events]. */
    fun importToPad(uri: Uri, pad: Int) {
        viewModelScope.launch {
            try {
                val name = PadLibrary.displayName(displayNameOf(uri))
                val r = withContext(Dispatchers.Default) { AudioDecoder.decode(getApplication(), uri) }
                PadSampler.importClip(pad, r.clip, name, r.truncated)
            } catch (e: Exception) {
                PadSampler.reject(pad, e.message ?: "Cannot load the file")
            }
        }
    }

    /** Загружает в редактор семпл, лежащий на паде (например, только что записанный удержанием). */
    fun loadFromPad(pad: Int) {
        val name = PadLibrary.infos[pad]?.name ?: "SAMPLE"
        load(name) {
            val bytes = PadStore.readWav(getApplication(), pad) ?: throw IOException("Pad ${padName(pad)} has no sample")
            val c = SampleOps.parseWav(bytes) ?: throw IOException("Cannot read the pad sample")
            AudioDecoder.Result(c, false)
        }
    }

    private fun displayNameOf(uri: Uri): String = try {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment ?: "IMPORT"

    private fun load(name: String, loader: () -> AudioDecoder.Result) {
        if (busy) return
        busy = true
        stopPreview()
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.Default) { loader() }
                undoStack.clear()
                canUndo = false
                clipName = name
                show(r.clip)
                message = if (r.truncated) "File longer than ${AudioDecoder.MAX_SECONDS} s — truncated"
                else "Loaded: %.2f s, %d Hz, %s".format(
                    r.clip.durationMs / 1000.0, r.clip.sampleRate, if (r.clip.channels == 1) "mono" else "stereo"
                )
            } catch (e: Exception) {
                message = e.message ?: "Cannot load the file"
            } finally {
                busy = false
                refreshRecordings()
            }
        }
    }

    fun clear() {
        stopPreview()
        pushUndo()
        clip = null
        peaks = null
        marks = emptyList()
        selStart = 0f
        selEnd = 1f
    }

    // ============================ выделение и chop ============================

    fun setSelection(start: Float, end: Float) {
        val s = start.coerceIn(0f, 1f)
        val e = end.coerceIn(0f, 1f)
        if (e - s < MIN_SELECTION) return
        selStart = s
        selEnd = e
    }

    fun autoChop(sensitivity: Float = 0.5f) {
        val c = clip ?: return noSample()
        viewModelScope.launch {
            val pos = withContext(Dispatchers.Default) { SampleOps.detectTransients(c, sensitivity) }
            val list = pos.map { it / c.frames.toFloat() }.filter { it in 0.001f..0.999f }.take(MAX_SLICES - 1)
            marks = list
            message = if (list.isEmpty()) "No transients found — try ÷4/÷8/÷16 or place markers by hand"
            else "Slices found: ${list.size + 1}"
        }
    }

    fun equalChop(parts: Int) {
        if (clip == null) return noSample()
        val n = parts.coerceIn(2, MAX_SLICES)
        marks = (1 until n).map { it / n.toFloat() }
        message = "Sliced evenly: $n"
    }

    fun addMark(fraction: Float) {
        if (clip == null) return
        if (marks.size >= MAX_SLICES - 1) {
            message = "Max $MAX_SLICES slices (one bank of pads)"
            return
        }
        marks = (marks + fraction.coerceIn(0.001f, 0.999f)).sorted()
    }

    /** Удаляет ближайший маркер, если он ближе [tolerance] (в долях длины). */
    fun removeNearestMark(fraction: Float, tolerance: Float): Boolean {
        val nearest = marks.minByOrNull { kotlin.math.abs(it - fraction) } ?: return false
        if (kotlin.math.abs(nearest - fraction) > tolerance) return false
        marks = marks - nearest
        return true
    }

    fun clearMarks() { marks = emptyList() }

    // ============================ правки ============================

    private fun selFrames(c: SampleClip): Pair<Int, Int> =
        (selStart * c.frames).roundToInt().coerceIn(0, c.frames) to
            (selEnd * c.frames).roundToInt().coerceIn(0, c.frames)

    fun normalize() = edit("Normalized to −0.2 dB", keepView = true) { c ->
        c.copy().also { SampleOps.normalize(it.data, it.channels, 0, it.frames, 0.98f) }
    }

    fun fadeIn() = edit("Fade in on selection", keepView = true) { c ->
        val (s, e) = selFrames(c)
        c.copy().also { SampleOps.fadeIn(it.data, it.channels, s, e) }
    }

    fun fadeOut() = edit("Fade out on selection", keepView = true) { c ->
        val (s, e) = selFrames(c)
        c.copy().also { SampleOps.fadeOut(it.data, it.channels, s, e) }
    }

    fun reverse() = edit("Selection reversed", keepView = true) { c ->
        val (s, e) = selFrames(c)
        c.copy().also { SampleOps.reverse(it.data, it.channels, s, e) }
    }

    fun trimToSelection() = edit("Trimmed to selection", keepView = false) { c ->
        val (s, e) = selFrames(c)
        if (e - s < 2) null else c.slice(s, e)
    }

    fun trimSilence() = edit("Silence trimmed at both ends", keepView = false) { c ->
        val bounds = SampleOps.findNonSilent(c.data, c.channels, -50f)
        if (bounds == null) null else c.slice(bounds[0], bounds[1])
    }

    private fun edit(done: String, keepView: Boolean, op: (SampleClip) -> SampleClip?) {
        val c = clip ?: return noSample()
        if (busy) return
        busy = true
        stopPreview()
        val before = Snapshot(c, selStart, selEnd, marks)
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.Default) { op(c) }
                if (r == null) {
                    message = "Nothing to change: sample is silent or selection too short"
                } else {
                    undoStack.addLast(before)
                    while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
                    canUndo = true
                    if (keepView) show(r, selStart, selEnd, marks) else show(r)
                    message = done
                    previewSelection()   // результат правки слышно сразу
                }
            } catch (e: Exception) {
                message = "Error: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private fun pushUndo() {
        val c = clip ?: return
        undoStack.addLast(Snapshot(c, selStart, selEnd, marks))
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        canUndo = true
    }

    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        stopPreview()
        show(s.clip, s.start, s.end, s.marks)
        canUndo = undoStack.isNotEmpty()
        message = "Undone"
        previewSelection()
    }

    private fun show(newClip: SampleClip, start: Float = 0f, end: Float = 1f, newMarks: List<Float> = emptyList()) {
        clip = newClip
        selStart = start
        selEnd = end
        marks = newMarks
        peaks = null
        viewModelScope.launch {
            val p = withContext(Dispatchers.Default) {
                SampleOps.computePeaks(newClip.data, newClip.channels, PEAK_BINS)
            }
            if (clip === newClip) peaks = p
        }
    }

    private fun noSample() { message = "Record or load a sample first" }

    // ============================ предпрослушивание ============================

    /** Высота прослушивания (PITCH + DETUNE пада), задаёт экран EDIT. */
    var previewRate = 1f

    fun previewSelection() {
        val c = clip ?: return noSample()
        val (s, e) = selFrames(c)
        preview.play(c, s, e, previewRate)
    }

    fun stopPreview() = preview.stop()

    override fun onCleared() {
        preview.stop()
        super.onCleared()
    }

    // ============================ назначение на пады ============================

    /** Выделенную область — на один пад. [onAssigned] вызывается на главном потоке. */
    fun assignToPad(pad: Int, onAssigned: (Int) -> Unit) {
        val c = clip ?: return noSample()
        val (s, e) = selFrames(c)
        if (e - s < MIN_ASSIGN_FRAMES) {
            message = "Selection too short"
            return
        }
        val trimmed = AutoTrim.apply(c.slice(s, e))
        if (trimmed == null) {
            message = "Selection is below the auto-trim threshold (%.0f dB) — lower TRIM or move markers".format(Settings.trimDb)
            return
        }
        val part = trimmed.clip
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) { putOnPad(pad, part, clipName) }
            if (ok) {
                onAssigned(pad)
                message = "Assigned to pad ${padName(pad)} (%.2f s)".format(part.durationMs / 1000.0) +
                    if (trimmed.headMs + trimmed.tailMs > 0)
                        " · silence trimmed: ${trimmed.headMs} ms head, ${trimmed.tailMs} ms tail" else ""
            } else {
                message = "Could not assign to pad ${padName(pad)} — try again"
            }
        }
    }

    /** Режет весь семпл по chop-маркерам и раскладывает срезы по падам банка начиная с [firstPad]. */
    fun assignChops(firstPad: Int, onAssigned: (Int) -> Unit) {
        val c = clip ?: return noSample()
        if (marks.isEmpty()) {
            message = "Place markers first: AUTO or ÷4/÷8/÷16"
            return
        }
        val cuts = listOf(0) + marks.map { (it * c.frames).roundToInt() } + listOf(c.frames)
        val slices = cuts.zipWithNext().filter { (a, b) -> b - a >= MIN_ASSIGN_FRAMES }.take(MAX_SLICES)
        viewModelScope.launch {
            var done = 0
            withContext(Dispatchers.Default) {
                slices.forEachIndexed { i, (a, b) ->
                    val pad = firstPad + i
                    if (pad < 64 && putOnPad(pad, c.slice(a, b), "%s_%02d".format(clipName.take(14), i + 1))) {
                        done++
                        launch(Dispatchers.Main) { onAssigned(pad) }
                    }
                }
            }
            message = "Slices on pads ${padName(firstPad)}…${padName(firstPad + (done - 1).coerceAtLeast(0))}: $done"
        }
    }

    private fun putOnPad(pad: Int, part: SampleClip, name: String): Boolean =
        PadSampler.assignToPad(pad, part, name)

    companion object {
        const val PEAK_BINS = 1200
        const val MAX_SLICES = 16
        const val MAX_UNDO = 6
        private const val MIN_SELECTION = 0.002f
        private const val MIN_ASSIGN_FRAMES = 16
    }
}
