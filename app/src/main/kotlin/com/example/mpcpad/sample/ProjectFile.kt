// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.sample

import android.content.Context
import android.net.Uri
import com.example.mpcpad.audio.SampleOps
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Части пада (SLICE) для файла: исходник целиком, включённые части и границы. */
class PadParts(val pad: Int, val original: SampleClip, val mask: Int, val bounds: List<Float>?) {
    fun toJson(index: Int): JSONObject = JSONObject().put("pad", index).put("mask", mask).apply {
        bounds?.let { b -> put("bounds", JSONArray().apply { b.forEach { put(it.toDouble()) } }) }
    }

    companion object {
        fun fromJson(o: JSONObject, pad: Int, original: SampleClip) = PadParts(
            pad, original, o.optInt("mask", 0b1111),
            o.optJSONArray("bounds")?.takeIf { it.length() == 3 }?.let { a -> List(3) { a.getDouble(it).toFloat() } },
        )
    }
}

/** Прочитанный файл проекта: JSON проекта, семплы 64 падов (null — пад пуст) и части падов. */
class ProjectBundle(val json: JSONObject, val clips: List<SampleClip?>, val names: List<String>, val parts: List<PadParts>)

/**
 * Файл проекта IS-1 (.sal, кнопка PRJCT): zip с project.json (как ProjectStore + темп) и pads/pad_NN.wav.
 * Работа с файлами — не из UI-потока.
 */
object ProjectFile {
    private const val FORMAT = "is1-project"

    /** Записать проект [json] и все семплы падов в [uri]. Возвращает число падов со звуком. */
    fun save(context: Context, uri: Uri, json: JSONObject, parts: List<PadParts> = emptyList()): Int {
        var count = 0
        val names = JSONArray()
        val out = context.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("cannot open file")
        ZipOutputStream(out).use { zip ->
            for (pad in 0 until 64) {
                val info = PadLibrary.infos[pad]
                val wav = if (info != null) PadStore.readWav(context, pad) else null
                names.put(if (wav != null) info?.name ?: "SAMPLE" else "")
                if (wav == null) continue
                zip.putNextEntry(ZipEntry(wavName(pad)))
                zip.write(wav)
                zip.closeEntry()
                count++
            }
            val partsJson = JSONArray()
            for (p in parts) {
                val wav = SampleOps.encodeWav16(p.original) ?: continue
                zip.putNextEntry(ZipEntry(origName(p.pad)))
                zip.write(wav)
                zip.closeEntry()
                partsJson.put(p.toJson(p.pad))
            }
            json.put("format", FORMAT).put("padNames", names).put("parts", partsJson)
            zip.putNextEntry(ZipEntry("project.json"))
            zip.write(json.toString().toByteArray())
            zip.closeEntry()
        }
        return count
    }

    /** Прочитать файл проекта. null — это не проект IS-1. */
    fun load(context: Context, uri: Uri): ProjectBundle? {
        var json: JSONObject? = null
        val clips = arrayOfNulls<SampleClip>(64)
        val originals = arrayOfNulls<SampleClip>(64)
        val input = context.contentResolver.openInputStream(uri) ?: return null
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val buf = ByteArrayOutputStream()
                zip.copyTo(buf)
                val name = e.name
                if (name == "project.json") {
                    json = JSONObject(String(buf.toByteArray()))
                } else if (name.startsWith("pads/pad_") && name.endsWith(".wav")) {
                    val pad = name.removePrefix("pads/pad_").removeSuffix(".wav").toIntOrNull() ?: continue
                    if (pad in 0 until 64) clips[pad] = SampleOps.parseWav(buf.toByteArray())
                } else if (name.startsWith("pads/orig_") && name.endsWith(".wav")) {
                    val pad = name.removePrefix("pads/orig_").removeSuffix(".wav").toIntOrNull() ?: continue
                    if (pad in 0 until 64) originals[pad] = SampleOps.parseWav(buf.toByteArray())
                }
            }
        }
        val root = json ?: return null
        if (root.optString("format") != FORMAT) return null
        val n = root.optJSONArray("padNames")
        val names = List(64) { n?.optString(it, "").orEmpty().ifBlank { "SAMPLE" } }
        val parts = ArrayList<PadParts>()
        root.optJSONArray("parts")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val pad = o.optInt("pad", -1)
                originals.getOrNull(pad)?.let { parts.add(PadParts.fromJson(o, pad, it)) }
            }
        }
        return ProjectBundle(root, clips.toList(), names, parts)
    }

    private fun wavName(pad: Int) = String.format("pads/pad_%02d.wav", pad)
    private fun origName(pad: Int) = String.format("pads/orig_%02d.wav", pad)
}
