package com.example.mpcpad.sample

import android.content.Context
import com.example.mpcpad.audio.AudioEngine
import com.example.mpcpad.audio.SampleOps
import java.io.File

/** Семплы пэдов на диске: pads/pad_NN.wav + pad_NN.txt с именем. */
object PadStore {
    private fun dir(context: Context): File = File(context.filesDir, "pads").apply { mkdirs() }
    private fun file(context: Context, pad: Int) = File(dir(context), String.format("pad_%02d.wav", pad))
    private fun nameFile(context: Context, pad: Int) = File(dir(context), String.format("pad_%02d.txt", pad))

    /** Атомарно: пишем во временный файл и переименовываем. */
    fun save(context: Context, pad: Int, clip: SampleClip, name: String? = null): Boolean {
        val bytes = SampleOps.encodeWav16(clip) ?: return false
        val target = file(context, pad)
        val tmp = File(target.parentFile, target.name + ".tmp")
        return try {
            tmp.writeBytes(bytes)
            val ok = tmp.renameTo(target)
            if (ok && name != null) nameFile(context, pad).writeText(name)
            ok
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    fun readWav(context: Context, pad: Int): ByteArray? = try {
        file(context, pad).takeIf { it.isFile }?.readBytes()
    } catch (_: Exception) {
        null
    }

    /** Загружает все сохранённые пэды в движок. Возвращает число загруженных. */
    fun loadAll(context: Context): Int {
        var loaded = 0
        for (pad in 0 until 64) {
            val f = file(context, pad)
            if (!f.isFile) continue
            val clip = try {
                SampleOps.parseWav(f.readBytes())
            } catch (_: Exception) {
                null
            } ?: continue
            if (!AudioEngine.loadSample(pad, clip.data, clip.channels, clip.sampleRate)) continue
            loaded++
            val name = try {
                nameFile(context, pad).takeIf { it.isFile }?.readText()
            } catch (_: Exception) {
                null
            }
            PadLibrary.set(pad, name?.takeIf { it.isNotBlank() } ?: "SAMPLE", clip)
        }
        return loaded
    }
}
