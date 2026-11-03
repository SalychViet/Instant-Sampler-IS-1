// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.sample

import android.content.Context
import android.net.Uri
import com.example.mpcpad.ProjectStore
import com.example.mpcpad.audio.SampleOps
import com.example.mpcpad.ui.PadUi
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Один пад из файла банка: семпл (null — пустой пад), имя и параметры. */
class BankSlot(val clip: SampleClip?, val name: String, val ui: PadUi, val parts: PadParts? = null)

/**
 * Файл банка IS-1 (SETUP → SAVE / IMPORT BANK): .isbank — zip с bank.json и pad_00…pad_15.wav.
 * Пады хранятся по позиции 1–16 внутри банка, поэтому банк A можно загрузить в любой из A–D.
 * Всё здесь — работа с файлами, звать не из UI-потока.
 */
object BankStore {
    private const val FORMAT = "is1-bank"

    /** Записать 16 падов банка [bank] в [uri]. Возвращает, сколько падов со звуком сохранено. */
    fun save(context: Context, uri: Uri, bank: Int, pads: List<PadUi>, parts: List<PadParts> = emptyList()): Int {
        var count = 0
        val arr = JSONArray()
        val out = context.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("cannot open file")
        ZipOutputStream(out).use { zip ->
            for (slot in 0 until 16) {
                val pad = bank * 16 + slot
                val wav = PadStore.readWav(context, pad).takeIf { PadLibrary.infos[pad] != null }
                if (wav != null) {
                    zip.putNextEntry(ZipEntry(wavName(slot)))
                    zip.write(wav)
                    zip.closeEntry()
                    count++
                }
                arr.put(
                    JSONObject()
                        .put("slot", slot)
                        .put("name", PadLibrary.infos[pad]?.name ?: "")
                        .put("hasSample", wav != null)
                        .put("params", ProjectStore.padToJson(pads[pad]))
                )
            }
            val partsJson = JSONArray()
            for (p in parts) {
                val wav = SampleOps.encodeWav16(p.original) ?: continue
                val slot = p.pad - bank * 16
                zip.putNextEntry(ZipEntry(origName(slot)))
                zip.write(wav)
                zip.closeEntry()
                partsJson.put(p.toJson(slot))
            }
            val root = JSONObject().put("format", FORMAT).put("version", 1).put("pads", arr).put("parts", partsJson)
            zip.putNextEntry(ZipEntry("bank.json"))
            zip.write(root.toString(1).toByteArray())
            zip.closeEntry()
        }
        return count
    }

    /** Прочитать файл банка: 16 слотов. null — это не файл банка IS-1. */
    fun load(context: Context, uri: Uri): List<BankSlot>? {
        val files = HashMap<String, ByteArray>()
        val input = context.contentResolver.openInputStream(uri) ?: return null
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory && (e.name == "bank.json" || e.name.endsWith(".wav"))) {
                    val buf = ByteArrayOutputStream()
                    zip.copyTo(buf)
                    files[e.name.substringAfterLast('/')] = buf.toByteArray()
                }
            }
        }
        val json = files["bank.json"] ?: return null
        val root = JSONObject(String(json))
        if (root.optString("format") != FORMAT) return null
        val arr = root.optJSONArray("pads") ?: return null
        val partsOf = HashMap<Int, PadParts>()
        root.optJSONArray("parts")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val slot = o.optInt("pad", -1)
                val orig = files[origName(slot)]?.let { SampleOps.parseWav(it) } ?: continue
                partsOf[slot] = PadParts.fromJson(o, slot, orig)
            }
        }
        val slots = MutableList(16) { BankSlot(null, "", PadUi()) }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val slot = o.optInt("slot", -1)
            if (slot !in 0 until 16) continue
            val clip = files[wavName(slot)]?.let { SampleOps.parseWav(it) }
            val ui = o.optJSONObject("params")?.let { ProjectStore.padFromJson(it) } ?: PadUi()
            slots[slot] = BankSlot(clip, o.optString("name").ifBlank { "SAMPLE" }, ui, partsOf[slot])
        }
        return slots
    }

    private fun wavName(slot: Int) = String.format("pad_%02d.wav", slot)
    private fun origName(slot: Int) = String.format("orig_%02d.wav", slot)
}
