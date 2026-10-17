package com.example.mpcpad.capture

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Папка с записями захвата. Храним последние [KEEP] файлов. */
object RecordingsStore {
    private const val KEEP = 30

    fun dir(context: Context): File = File(context.filesDir, "recordings").apply { mkdirs() }

    fun newFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir(context), "rec_$stamp.wav")
    }

    /** Новые сверху; пустые WAV (только заголовок, 44 байта) пропускаем. */
    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.extension.equals("wav", ignoreCase = true) && f.length() > 44 }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun prune(context: Context) {
        list(context).drop(KEEP).forEach { it.delete() }
    }
}
