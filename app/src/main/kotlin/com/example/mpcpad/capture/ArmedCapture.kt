// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.capture

import android.media.AudioRecord
import android.os.Process
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/**
 * «Взведённый» захват: AudioRecord крутится постоянно, а семпл собирается только пока держат пэд.
 * Кольцо из [PREROLL_CHUNKS] чанков по 10 мс даёт пре-ролл (атака не теряется),
 * после отпускания пишем ещё [TAIL_CHUNKS] чанков хвоста.
 */
class ArmedCapture(
    private val config: RecorderConfig,
    private val record: AudioRecord,
    /** (pad, samples, frames, channels, sampleRate, capped) */
    private val onClip: (Int, ShortArray, Int, Int, Int, Boolean) -> Unit,
    private val onEnded: (String?) -> Unit,
) {
    companion object {
        const val MAX_HOLD_SECONDS = 30
        const val PREROLL_CHUNKS = 5
        const val TAIL_CHUNKS = 4
    }

    private sealed interface Cmd {
        class Begin(val pad: Int) : Cmd
        object End : Cmd
    }

    private val commands = ConcurrentLinkedQueue<Cmd>()
    private var thread: Thread? = null

    @Volatile private var stopRequested = false
    @Volatile private var stopReason: String? = null

    @Volatile var level = 0f
        private set
    @Volatile var holdingPad = -1
        private set
    @Volatile var heldMs = 0L
        private set
    /** Сколько подряд держим пэд, а источник отдаёт чистые нули. */
    @Volatile var silentHeldMs = 0L
        private set

    fun start() {
        thread = Thread({ run() }, "mpc-armed-capture").also { it.start() }
    }

    fun stop(reason: String? = null) {
        if (reason != null && stopReason == null) stopReason = reason
        stopRequested = true
    }

    fun begin(pad: Int) {
        commands.add(Cmd.Begin(pad))
    }

    fun end() {
        commands.add(Cmd.End)
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

        var buf = ShortArray(rate * ch)
        var size = 0
        val maxSamples = rate * ch * MAX_HOLD_SECONDS
        var collecting = false
        var curPad = -1
        var tailLeft = -1
        var error: String? = null

        fun append(src: ShortArray, n: Int) {
            if (size + n > buf.size) {
                buf = buf.copyOf(minOf(maxOf(buf.size * 2, size + n), maxSamples + chunk.size))
            }
            System.arraycopy(src, 0, buf, size, n)
            size += n
        }

        fun finishClip(capped: Boolean) {
            val frames = size / ch
            val pad = curPad
            val data = buf.copyOf(size)
            collecting = false
            curPad = -1
            tailLeft = -1
            size = 0
            holdingPad = -1
            heldMs = 0
            silentHeldMs = 0
            if (frames > 0 && pad >= 0) onClip(pad, data, frames, ch, rate, capped)
        }

        try {
            record.startRecording()
            while (!stopRequested) {
                val n = record.read(chunk, 0, chunk.size)
                if (n < 0) {
                    error = "Ошибка чтения аудио (код $n)"
                    break
                }
                if (n == 0) continue
                var peak = 0
                for (i in 0 until n) peak = maxOf(peak, abs(chunk[i].toInt()))
                level = peak / 32768f

                while (true) {
                    when (val c = commands.poll() ?: break) {
                        is Cmd.Begin -> {
                            if (collecting) finishClip(false)
                            collecting = true
                            curPad = c.pad
                            tailLeft = -1
                            size = 0
                            holdingPad = c.pad
                            silentHeldMs = 0
                            // Пре-ролл: всё, что накопилось в кольце, — в начало семпла.
                            val first = (ringHead - ringCount + PREROLL_CHUNKS) % PREROLL_CHUNKS
                            for (k in 0 until ringCount) {
                                val idx = (first + k) % PREROLL_CHUNKS
                                append(ring[idx], ringLen[idx])
                            }
                            ringCount = 0
                        }
                        Cmd.End -> if (collecting && tailLeft < 0) tailLeft = TAIL_CHUNKS
                    }
                }

                if (collecting) {
                    append(chunk, n)
                    val chunkMs = (n / ch).toLong() * 1000 / rate
                    heldMs = (size / ch).toLong() * 1000 / rate
                    silentHeldMs = if (peak == 0) silentHeldMs + chunkMs else 0
                    if (tailLeft >= 0) {
                        tailLeft--
                        if (tailLeft <= 0) finishClip(false)
                    } else if (size >= maxSamples) {
                        finishClip(true)
                    }
                } else {
                    System.arraycopy(chunk, 0, ring[ringHead], 0, n)
                    ringLen[ringHead] = n
                    ringHead = (ringHead + 1) % PREROLL_CHUNKS
                    if (ringCount < PREROLL_CHUNKS) ringCount++
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
            level = 0f
            if (collecting) finishClip(false)
            onEnded(error ?: stopReason)
        }
    }
}
