package com.example.mpcpad.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Process
import java.io.File
import kotlin.math.abs
import kotlin.math.log10

/**
 * Запись в WAV в отдельном потоке: count-in → (ожидание порога) → запись до stop/лимита.
 * Пока ждём порог, держим кольцо из [PREROLL_CHUNKS] чанков по 10 мс, чтобы не съесть атаку.
 */
class Recorder(
    private val config: RecorderConfig,
    private val outFile: File,
    private val record: AudioRecord,
    /** (готовый файл или null, сообщение для пользователя) */
    private val onFinished: (File?, String?) -> Unit,
) {
    companion object {
        private const val MAX_SECONDS = 600
        private const val PREROLL_CHUNKS = 5

        /** Создаёт AudioRecord под источник. Бросает IllegalStateException с понятным текстом. */
        fun buildAudioRecord(config: RecorderConfig, projection: MediaProjection?): AudioRecord {
            val mask = if (config.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(config.sampleRate)
                .setChannelMask(mask)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(config.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuf > 0) { "Устройство не поддерживает запись ${config.sampleRate} Гц" }
            val bufBytes = maxOf(minBuf * 2, config.sampleRate * config.channels * 2 / 5)
            val builder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufBytes)
            when (config.source) {
                CaptureSource.MIC -> builder.setAudioSource(MediaRecorder.AudioSource.MIC)
                CaptureSource.INTERNAL -> {
                    val p = checkNotNull(projection) { "Нет MediaProjection для захвата внутреннего звука" }
                    val capture = AudioPlaybackCaptureConfiguration.Builder(p)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .excludeUid(Process.myUid())
                        .build()
                    builder.setAudioPlaybackCaptureConfig(capture)
                }
            }
            val record = builder.build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalStateException("Не удалось открыть источник звука (AudioRecord не инициализирован)")
            }
            return record
        }
    }

    @Volatile private var stopRequested = false
    @Volatile private var stopReason: String? = null
    private var thread: Thread? = null

    fun start() {
        val t = Thread({ run() }, "mpc-recorder")
        thread = t
        t.start()
    }

    fun requestStop(reason: String? = null) {
        if (reason != null && stopReason == null) stopReason = reason
        stopRequested = true
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val ch = config.channels
        val rate = config.sampleRate
        val chunkFrames = rate / 100
        val chunk = ShortArray(chunkFrames * ch)
        val ring = Array(PREROLL_CHUNKS) { ShortArray(chunk.size) }
        val ringLen = IntArray(PREROLL_CHUNKS)
        var ringHead = 0
        var ringCount = 0
        var writer: WavStreamWriter? = null
        var error: String? = null
        var lastPublish = 0L

        try {
            val countIn = config.countInMs.toLong()
            if (countIn > 0) {
                val t0 = System.nanoTime()
                while (!stopRequested) {
                    val left = countIn - (System.nanoTime() - t0) / 1_000_000
                    if (left <= 0) break
                    CaptureController.publish(CaptureStatus.COUNT_IN, countInLeftMs = left)
                    Thread.sleep(50)
                }
            }
            if (!stopRequested) {
                val w = WavStreamWriter(outFile, rate, ch)
                writer = w
                record.startRecording()
                var triggered = config.thresholdDb == null
                CaptureController.publish(if (triggered) CaptureStatus.RECORDING else CaptureStatus.ARMED)
                var silentMs = 0L
                var elapsedMs = 0L
                val maxFrames = rate.toLong() * MAX_SECONDS
                while (!stopRequested) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n < 0) {
                        error = "Ошибка чтения аудио (код $n)"
                        break
                    }
                    if (n == 0) continue
                    var peak = 0
                    for (i in 0 until n) peak = maxOf(peak, abs(chunk[i].toInt()))
                    CaptureController.level = peak / 32768f
                    val chunkMs = (n / ch).toLong() * 1000 / rate
                    // Цифровой ноль подряд — признак того, что источник запрещает захват.
                    silentMs = if (peak == 0) silentMs + chunkMs else 0

                    if (!triggered) {
                        val db = 20f * log10(maxOf(peak, 1) / 32768f)
                        if (db >= (config.thresholdDb ?: Float.NEGATIVE_INFINITY)) {
                            triggered = true
                            val first = (ringHead - ringCount + PREROLL_CHUNKS) % PREROLL_CHUNKS
                            for (k in 0 until ringCount) {
                                val idx = (first + k) % PREROLL_CHUNKS
                                w.writeShorts(ring[idx], ringLen[idx])
                            }
                            CaptureController.publish(CaptureStatus.RECORDING)
                        } else {
                            System.arraycopy(chunk, 0, ring[ringHead], 0, n)
                            ringLen[ringHead] = n
                            ringHead = (ringHead + 1) % PREROLL_CHUNKS
                            if (ringCount < PREROLL_CHUNKS) ringCount++
                        }
                    }
                    if (triggered) {
                        w.writeShorts(chunk, n)
                        elapsedMs = w.frames * 1000 / rate
                        if (w.frames >= maxFrames) {
                            stopReason = stopReason ?: "Достигнут лимит записи (10 мин)"
                            break
                        }
                    }
                    val now = System.nanoTime()
                    if (now - lastPublish > 100_000_000L) {
                        lastPublish = now
                        CaptureController.publish(
                            if (triggered) CaptureStatus.RECORDING else CaptureStatus.ARMED,
                            elapsedMs = elapsedMs,
                            silentMs = silentMs,
                        )
                    }
                }
            }
        } catch (e: Exception) {
            error = "Сбой записи: ${e.message}"
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
            val frames = writer?.frames ?: 0L
            try {
                writer?.finish()
            } catch (_: Exception) {
            }
            val ok = frames > 0
            if (!ok) outFile.delete()
            val message = error ?: stopReason ?: if (!ok) {
                if (config.thresholdDb != null) "Порог не был превышен — запись не началась" else "Ничего не записано"
            } else {
                null
            }
            onFinished(if (ok) outFile else null, message)
        }
    }
}
