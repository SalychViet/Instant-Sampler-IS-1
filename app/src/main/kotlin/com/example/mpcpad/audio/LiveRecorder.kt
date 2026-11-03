// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.audio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.example.mpcpad.Settings
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LIVE REC (ISL-1): всё, что слышно (пады, петли, секвенсор, KAOSS, лимитер), пишется в WAV 24 бит
 * в папку из SETUP (по умолчанию «Музыка/ISL-1 LIVE») — без ограничения длины. Готовый файл появляется,
 * когда запись остановлена.
 */
object LiveRecorder {
    const val FOLDER = "Music/ISL-1 LIVE"

    @Volatile var recording = false
        private set
    /** Имя последнего файла и сколько раз звук терялся (поток записи не успел). */
    @Volatile var lastName = ""
        private set
    @Volatile var dropouts = 0
        private set

    private var thread: Thread? = null
    @Volatile private var stopRequested = false

    /** Где окажутся записи — для экрана SETUP и сообщений. */
    fun folderLabel(): String {
        val tree = Settings.liveFolder
        if (tree.isEmpty()) return "$FOLDER (по умолчанию)"
        return try {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(tree))   // «primary:Music/X» или «1234-ABCD:X»
            val vol = id.substringBefore(':')
            val path = id.substringAfter(':')
            (if (vol == "primary") "" else "SD: ") + path.ifEmpty { "(корень памяти)" }
        } catch (_: Exception) {
            tree
        }
    }

    /** Файл записи: куда писать и что сделать в конце (записано ли что-то). */
    private class Target(val pfd: ParcelFileDescriptor, val finish: (ok: Boolean) -> Unit)

    /** Своя папка (SETUP): пишем «….part», в конце переименовываем в .wav — недописанный файл не спутать с готовым. */
    private fun openInFolder(context: Context, tree: String, base: String): Target? {
        val resolver = context.contentResolver
        return try {
            val treeUri = Uri.parse(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            val doc = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", "$base.part") ?: return null
            val pfd = resolver.openFileDescriptor(doc, "rw") ?: run {
                DocumentsContract.deleteDocument(resolver, doc)
                return null
            }
            Target(pfd) { ok ->
                if (ok) DocumentsContract.renameDocument(resolver, doc, "$base.wav")
                else DocumentsContract.deleteDocument(resolver, doc)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** По умолчанию: «Музыка/ISL-1 LIVE» через MediaStore (файл скрыт, пока пишется). */
    private fun openInMusic(context: Context, base: String): Target? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$base.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, FOLDER)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } catch (_: Exception) {
            null
        } ?: return null
        val pfd = try {
            resolver.openFileDescriptor(uri, "rw")
        } catch (_: Exception) {
            null
        }
        if (pfd == null) {
            resolver.delete(uri, null, null)
            return null
        }
        return Target(pfd) { ok ->
            if (ok) resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            else resolver.delete(uri, null, null)
        }
    }

    /** Начать запись. false — файл не создать. */
    fun start(context: Context): Boolean {
        if (recording) return true
        if (thread?.isAlive == true) return false   // прошлый файл ещё дописывается
        val app = context.applicationContext
        val base = "LIVE_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val tree = Settings.liveFolder
        val target = (if (tree.isNotEmpty()) openInFolder(app, tree, base) else null) ?: openInMusic(app, base) ?: return false
        val rate = AudioEngine.sampleRate()
        lastName = "$base.wav"
        dropouts = 0
        stopRequested = false
        AudioEngine.liveStart()
        recording = true
        thread = Thread({ writeLoop(target, rate) }, "LiveRec").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
        return true
    }

    /** Остановить: дописывается остаток, файл закрывается и появляется в папке. */
    fun stop() {
        if (!recording) return
        AudioEngine.liveStop()
        stopRequested = true
        recording = false
    }

    private fun writeLoop(target: Target, rate: Int) {
        val out = FileOutputStream(target.pfd.fileDescriptor)
        val ch = out.channel
        val floats = FloatArray(2 * 8192)
        val bytes = ByteBuffer.allocate(floats.size * 3).order(ByteOrder.LITTLE_ENDIAN)
        var frames = 0L
        try {
            ch.write(header(rate, 0L))
            while (true) {
                val n = AudioEngine.liveRead(floats)
                if (n < 0) {
                    dropouts++
                    continue
                }
                if (n == 0) {
                    if (stopRequested) break   // движок уже не пишет (liveStop), остаток забран
                    Thread.sleep(20)
                    continue
                }
                bytes.clear()
                for (i in 0 until n * 2) {
                    val v = (floats[i].coerceIn(-1f, 1f) * 8388607f).toInt()
                    bytes.put(v.toByte()).put((v shr 8).toByte()).put((v shr 16).toByte())
                }
                bytes.flip()
                while (bytes.hasRemaining()) ch.write(bytes)
                frames += n
            }
            ch.position(0)
            ch.write(header(rate, frames))
        } catch (_: Exception) {
        } finally {
            try { out.close() } catch (_: Exception) {}
            try { target.pfd.close() } catch (_: Exception) {}
            try { target.finish(frames > 0) } catch (_: Exception) {}
        }
    }

    /** Заголовок WAV: PCM 24 бит, стерео. */
    private fun header(rate: Int, frames: Long): ByteBuffer {
        val data = (frames * 6).coerceAtMost(0xFFFF_FFFFL - 36)
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + data).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2)
            putInt(rate); putInt(rate * 6); putShort(6); putShort(24)
            put("data".toByteArray()); putInt(data.toInt())
            flip()
        }
    }
}
