// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.capture

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Потоковая запись 16-bit PCM WAV: заголовок дописывается в [finish]. */
class WavStreamWriter(private val file: File, private val sampleRate: Int, private val channels: Int) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var finished = false
    private var scratch = ByteArray(0)

    val frames: Long get() = dataBytes / (channels * 2L)

    init {
        raf.setLength(0)
        raf.write(header(0))
    }

    fun writeShorts(samples: ShortArray, count: Int) {
        check(!finished) { "writer already finished" }
        val n = count.coerceIn(0, samples.size)
        if (n == 0) return
        if (scratch.size < n * 2) scratch = ByteArray(n * 2)
        var j = 0
        for (i in 0 until n) {
            val v = samples[i].toInt()
            scratch[j++] = (v and 0xff).toByte()
            scratch[j++] = ((v shr 8) and 0xff).toByte()
        }
        raf.write(scratch, 0, n * 2)
        dataBytes += n * 2L
    }

    fun finish() {
        if (finished) return
        finished = true
        raf.seek(0)
        raf.write(header(dataBytes))
        raf.close()
    }

    override fun close() = finish()

    private fun header(dataSize: Long): ByteArray {
        val size = dataSize.coerceAtMost(0xFFFFFFFFL - 36)
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + size).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channels * 2)
            putShort((channels * 2).toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(size.toInt())
        }.array()
    }
}
