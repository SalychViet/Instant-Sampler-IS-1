package com.example.mpcpad.audio

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** EXPORT: готовая запись мастера из движка -> файл в «Музыка/IS-1» (видно в любом плеере и файловом менеджере). */
object ExportStore {
    const val FOLDER = "Music/IS-1"

    /** Сохранить WAV (16 бит) или MP3 (320 kbps, во Free — 128). Возвращает имя файла или null. Не из UI-потока. */
    fun save(ctx: Context, mp3: Boolean): String? {
        val ext = if (mp3) "mp3" else "wav"
        val name = "IS-1_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "." + ext
        val tmp = File(ctx.cacheDir, "export.$ext")
        try {
            if (!AudioEngine.exportWrite(tmp.absolutePath, mp3, com.example.mpcpad.Edition.mp3Kbps)) return null
            val cr = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (mp3) "audio/mpeg" else "audio/x-wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/IS-1")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
            try {
                val out = cr.openOutputStream(uri) ?: throw java.io.IOException("no stream")
                out.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                cr.update(uri, values, null, null)
            } catch (e: Exception) {
                cr.delete(uri, null, null)
                return null
            }
            return name
        } finally {
            tmp.delete()
        }
    }
}
