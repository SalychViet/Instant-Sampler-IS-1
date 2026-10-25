// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * Проект «из коробки» (только Pro): assets/default_project.zip — project.json и pads/ ровно в том виде,
 * в каком их хранит приложение. При первом запуске после установки распаковывается в filesDir,
 * а дальше грузится обычным путём (ProjectStore.load, PadStore.loadAll).
 */
object DefaultProject {
    private const val ASSET = "default_project.zip"

    /** Звать до загрузки проекта и настройки движка. true — проект установлен. */
    fun installIfFresh(context: Context): Boolean {
        if (Edition.FREE) return false
        val dir = context.filesDir
        val project = File(dir, "project.json")
        val pads = File(dir, "pads")
        // Уже есть свой проект или свои пады — ничего не трогаем.
        if (project.exists() || (pads.list()?.isNotEmpty() == true)) return false
        return try {
            context.assets.open(ASSET).use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val name = e.name
                        // Только то, что ожидаем: project.json и pads/<файл>, без путей наружу.
                        val ok = name == "project.json" ||
                            (name.startsWith("pads/") && !name.contains("..") && name.count { it == '/' } == 1)
                        if (e.isDirectory || !ok) continue
                        val out = File(dir, name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            // Темп проекта — в настройки (их читает движок при старте).
            val bpm = JSONObject(project.readText()).optDouble("bpm", Double.NaN)
            if (!bpm.isNaN()) Settings.bpm = bpm.toFloat().coerceIn(30f, 300f)
            true
        } catch (_: java.io.FileNotFoundException) {
            false   // в сборке нет проекта по умолчанию
        } catch (_: Exception) {
            project.delete()   // полураспакованное не оставляем: запустимся с обычным китом
            false
        }
    }
}
