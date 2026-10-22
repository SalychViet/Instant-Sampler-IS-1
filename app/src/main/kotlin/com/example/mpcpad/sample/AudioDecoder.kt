package com.example.mpcpad.sample

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.example.mpcpad.audio.SampleOps
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteOrder

/**
 * Импорт файла в SampleClip. WAV/AIFF разбираем сами (C++), остальное (mp3, m4a, ogg, flac…) — MediaCodec.
 * Не больше [MAX_SECONDS] секунд; моно/стерео (лишние каналы отбрасываются).
 */
object AudioDecoder {
    const val MAX_SECONDS = 120
    private const val MAX_FILE_BYTES = 150L * 1024 * 1024

    class Result(val clip: SampleClip, val truncated: Boolean)

    private enum class Container { WAV, AIFF, OTHER }

    /** Бросает IOException с понятным пользователю текстом. */
    fun decode(context: Context, uri: Uri): Result {
        val resolver = context.contentResolver
        val container = resolver.openInputStream(uri)?.use { ins ->
            val head = ByteArray(12)
            if (readFully(ins, head) < 12) {
                Container.OTHER
            } else {
                val a = String(head, 0, 4, Charsets.US_ASCII)
                val b = String(head, 8, 4, Charsets.US_ASCII)
                when {
                    a == "RIFF" && b == "WAVE" -> Container.WAV
                    a == "FORM" && (b == "AIFF" || b == "AIFC") -> Container.AIFF
                    else -> Container.OTHER
                }
            }
        } ?: throw IOException("Cannot open the file")

        if (container == Container.OTHER) return decodeCompressed(context, uri)

        val bytes = resolver.openInputStream(uri)?.use { readLimited(it) }
            ?: throw IOException("Cannot open the file")
        val clip = SampleOps.parseWav(bytes) ?: throw IOException(
            if (container == Container.WAV) {
                "Unsupported WAV (need PCM 8/16/24/32-bit or float)"
            } else {
                "Unsupported AIFF (need uncompressed PCM 8–32-bit or float; no ulaw/ima4)"
            }
        )
        return limit(clip)
    }

    private fun limit(clip: SampleClip): Result {
        val max = clip.sampleRate * MAX_SECONDS
        return if (clip.frames > max) Result(clip.slice(0, max), true) else Result(clip, false)
    }

    private fun readFully(ins: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun readLimited(ins: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_FILE_BYTES) throw IOException("File too large (over 150 MB)")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Накопитель 16-bit отсчётов с потолком. */
    private class SampleSink(private val maxSamples: Int) {
        var data = ShortArray(64 * 1024)
        var size = 0
        val full: Boolean get() = size >= maxSamples

        fun append(v: Short) {
            if (size >= maxSamples) return
            if (size == data.size) data = data.copyOf(minOf(data.size * 2, maxSamples))
            data[size++] = v
        }
    }

    private fun decodeCompressed(context: Context, uri: Uri): Result {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) throw IOException("No audio track in the file")
            extractor.selectTrack(track)
            var inCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            val sink = SampleSink(rate * MAX_SECONDS * minOf(inCh, 2))
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var truncated = false
            while (!outputDone && !truncated) {
                if (!inputDone) {
                    val idx = c.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = c.getInputBuffer(idx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(idx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = c.dequeueOutputBuffer(info, 10_000)
                if (out >= 0) {
                    val ob = c.getOutputBuffer(out)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    ob.order(ByteOrder.nativeOrder())
                    val outCh = minOf(inCh, 2)
                    if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        val fb = ob.asFloatBuffer()
                        while (fb.remaining() >= inCh && !sink.full) {
                            for (ch in 0 until inCh) {
                                val v = fb.get()
                                if (ch < outCh) sink.append((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                            }
                        }
                    } else {
                        val sb = ob.asShortBuffer()
                        while (sb.remaining() >= inCh && !sink.full) {
                            for (ch in 0 until inCh) {
                                val v = sb.get()
                                if (ch < outCh) sink.append(v)
                            }
                        }
                    }
                    if (sink.full) truncated = true
                    c.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = c.outputFormat
                    inCh = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    pcmEncoding = if (nf.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        nf.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                }
            }
            if (sink.size == 0) throw IOException("Cannot decode the file")
            val channels = minOf(inCh, 2)
            val data = FloatArray(sink.size) { sink.data[it] / 32768f }
            return Result(SampleClip(data, channels, rate), truncated)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Cannot decode the file: ${e.message}")
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }
}
