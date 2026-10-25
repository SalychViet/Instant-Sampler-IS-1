// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad

import android.content.Context
import com.example.mpcpad.audio.AudioEngine
import com.example.mpcpad.audio.FxAutomation
import com.example.mpcpad.audio.PadParam
import com.example.mpcpad.ui.AppViewModel
import com.example.mpcpad.ui.Motion
import com.example.mpcpad.ui.MotionPoint
import com.example.mpcpad.ui.PadUi
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Проект между запусками: параметры падов, все 64 паттерна (ноты, P-LOCK, длина), цепочка Song,
 * выбранный паттерн, движения MOTION и запись эффектов KAOSS. Семплы хранит PadStore, настройки — Settings.
 *
 * Сохраняется, когда приложение уходит с экрана (MainActivity.onStop), загружается в MpcApp.onCreate —
 * до старта звука, поэтому паттерны пишутся в движок напрямую. Тот же JSON лежит в файле проекта .sal
 * (ProjectFile) — там к нему добавлены семплы и темп.
 */
object ProjectStore {
    private const val FILE = "project.json"

    // Загруженное — забирает AppViewModel при создании.
    var pads: List<PadUi>? = null; private set
    var motions: List<Motion?> = listOf(null, null, null, null); private set
    var fxAuto: FxAutomation? = null; private set
    var kaossProgram = 0; private set
    var pattern = 0; private set
    var kaossMix: List<Float> = List(50) { 1f }; private set
    var name = ""; private set

    /** Проект целиком (кроме семплов) в JSON. Читает движок — звать из UI-потока. */
    fun toJson(app: AppViewModel): JSONObject {
        val root = JSONObject()
        root.put("version", 1)
        root.put("bpm", Settings.bpm.toDouble())
        root.put("name", app.projectName)
        root.put("pattern", app.seqPattern)
        root.put("kaossProgram", app.kaossProgram)
        root.put("kaossMix", JSONArray().apply { for (m in app.kaossMix) put(m.toDouble()) })
        root.put("pads", JSONArray().apply { for (u in app.pads) put(padToJson(u)) })

        val pats = JSONArray()
        for (p in 0 until 64) {
            var notes = AudioEngine.seqReadPattern(p)
            var tries = 0
            while (notes == null && tries++ < 5) notes = AudioEngine.seqReadPattern(p)
            val bars = AudioEngine.seqPatternBars(p)
            if (notes.isNullOrEmpty() && bars == 1) continue
            val ev = JSONArray()
            val lk = JSONArray()
            for (n in notes.orEmpty()) {
                ev.put((n.tick.toLong() and 0xFFFF) or ((n.pad.toLong() and 0xFF) shl 16) or
                    ((n.velocity.toLong() and 0xFF) shl 24) or ((n.length.toLong() and 0xFFFF) shl 32))
                lk.put(n.locks)
            }
            pats.put(JSONObject().put("p", p).put("bars", bars).put("ev", ev).put("lk", lk))
        }
        root.put("patterns", pats)

        val song = AudioEngine.songList()
        root.put("song", JSONArray(song))
        root.put("songOn", app.songIndex >= 0)

        root.put("motions", JSONArray().apply {
            for (m in app.motions) put(
                if (m == null) JSONObject.NULL else JSONObject().put("len", m.lengthMs).put("pts", JSONArray().apply {
                    for (pt in m.points) put(JSONArray().put(pt.t).put(pt.x.toDouble()).put(pt.y.toDouble()).put(pt.touch))
                })
            )
        })
        app.fxAuto?.let { a ->
            root.put("fxAuto", JSONObject().put("len", a.lengthTicks).put("pts", JSONArray().apply {
                for (i in a.ticks.indices) put(
                    JSONArray().put(a.ticks[i]).put(a.program[i]).put(a.x[i].toDouble()).put(a.y[i].toDouble()).put(a.touch[i])
                )
            }))
        }

        return root
    }

    fun save(context: Context, app: AppViewModel) {
        try {
            val f = File(context.filesDir, FILE)
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(toJson(app).toString())
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }

    /** Загрузить и сразу применить к движку (паттерны, цепочка, параметры падов). */
    fun load(context: Context) {
        val f = File(context.filesDir, FILE)
        if (!f.isFile) return
        try {
            applyJson(JSONObject(f.readText()))
        } catch (_: Exception) {
        }
    }

    /**
     * Применить проект к движку и запомнить состояние для AppViewModel. Паттерны пишутся напрямую —
     * только пока аудио-поток остановлен (при старте или при загрузке .sal).
     */
    fun applyJson(root: JSONObject) {
        // Чего нет в файле — того нет и в проекте (иначе от прошлого проекта остались бы движения и эффекты).
        motions = listOf(null, null, null, null)
        fxAuto = null
        name = root.optString("name", "")
        pattern = root.optInt("pattern", 0).coerceIn(0, 63)
        kaossProgram = root.optInt("kaossProgram", 0)
        root.optJSONArray("kaossMix")?.let { a -> kaossMix = List(50) { if (it < a.length()) a.getDouble(it).toFloat() else 1f } }

        root.optJSONArray("pads")?.let { arr ->
            val list = List(64) { i -> if (i < arr.length()) padFromJson(arr.getJSONObject(i)) else PadUi() }
            pads = list
            list.forEachIndexed { pad, u -> applyToEngine(pad, u) }
        }

        root.optJSONArray("patterns")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ev = o.getJSONArray("ev")
                val lk = o.optJSONArray("lk")
                AudioEngine.seqLoadPattern(
                    o.getInt("p"),
                    LongArray(ev.length()) { ev.getLong(it) },
                    LongArray(ev.length()) { lk?.optLong(it, 0L) ?: 0L },
                    o.optInt("bars", 1),
                )
            }
        }

        root.optJSONArray("song")?.let { s ->
            if (s.length() > 0) {
                AudioEngine.songClear()
                for (i in 0 until s.length()) AudioEngine.songAppend(s.getInt(i))
                AudioEngine.songEnable(root.optBoolean("songOn", true))
            }
        }
        AudioEngine.seqSelect(pattern, immediate = true)

        root.optJSONArray("motions")?.let { arr ->
            motions = List(4) { i ->
                val o = if (i < arr.length()) arr.optJSONObject(i) else null
                o?.let {
                    val pts = it.getJSONArray("pts")
                    Motion(
                        List(pts.length()) { k ->
                            val a = pts.getJSONArray(k)
                            MotionPoint(a.getLong(0), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getBoolean(3))
                        },
                        it.getLong("len"),
                    )
                }
            }
        }
        root.optJSONObject("fxAuto")?.let { o ->
            val pts = o.getJSONArray("pts")
            val n = pts.length()
            fxAuto = FxAutomation(
                IntArray(n) { pts.getJSONArray(it).getInt(0) },
                IntArray(n) { pts.getJSONArray(it).getInt(1) },
                FloatArray(n) { pts.getJSONArray(it).getDouble(2).toFloat() },
                FloatArray(n) { pts.getJSONArray(it).getDouble(3).toFloat() },
                BooleanArray(n) { pts.getJSONArray(it).getBoolean(4) },
                o.getInt("len"),
            )
        }
    }

    private fun applyToEngine(pad: Int, u: PadUi) {
        AudioEngine.setParam(pad, PadParam.VOLUME, u.volume)
        AudioEngine.setParam(pad, PadParam.PAN, u.pan)
        AudioEngine.setParam(pad, PadParam.PITCH, u.pitch)
        AudioEngine.setParam(pad, PadParam.DETUNE, u.detune)
        AudioEngine.setParam(pad, PadParam.START, u.start)
        AudioEngine.setParam(pad, PadParam.END, u.end)
        AudioEngine.setParam(pad, PadParam.MODE, u.mode.toFloat())
        AudioEngine.setParam(pad, PadParam.REVERSE, if (u.reverse) 1f else 0f)
        AudioEngine.setParam(pad, PadParam.CHOKE, com.example.mpcpad.ui.chokeEngineValue(pad, u.choke))
        AudioEngine.setParam(pad, PadParam.ATTACK_MS, u.attackMs)
        AudioEngine.setParam(pad, PadParam.RELEASE_MS, u.releaseMs)
    }

    internal fun padToJson(u: PadUi) = JSONObject()
        .put("vol", u.volume.toDouble()).put("pan", u.pan.toDouble()).put("pitch", u.pitch.toDouble()).put("detune", u.detune.toDouble())
        .put("start", u.start.toDouble()).put("end", u.end.toDouble()).put("mode", u.mode)
        .put("rev", u.reverse).put("choke", u.choke).put("rel", u.releaseMs.toDouble())
        .put("atk", u.attackMs.toDouble()).put("loopDiv", u.loopDiv)

    internal fun padFromJson(o: JSONObject) = PadUi(
        volume = o.optDouble("vol", 1.0).toFloat(),
        pan = o.optDouble("pan", 0.0).toFloat(),
        pitch = o.optDouble("pitch", 0.0).toFloat(),
        detune = o.optDouble("detune", 0.0).toFloat(),
        start = o.optDouble("start", 0.0).toFloat(),
        end = o.optDouble("end", 1.0).toFloat(),
        mode = o.optInt("mode", 0),
        reverse = o.optBoolean("rev", false),
        choke = o.optInt("choke", 0),
        releaseMs = o.optDouble("rel", 15.0).toFloat(),
        attackMs = o.optDouble("atk", 0.0).toFloat(),
        loopDiv = o.optInt("loopDiv", -1),
    )
}
