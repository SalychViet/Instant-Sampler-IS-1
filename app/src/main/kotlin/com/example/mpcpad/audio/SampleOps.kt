// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.audio

import com.example.mpcpad.sample.SampleClip

/** Операции над семплами в C++ (вне аудио-потока). */
object SampleOps {
    init {
        System.loadLibrary("mpcengine")
    }

    /** Пары (min, max) для отрисовки waveform: 2 * bins значений. */
    fun computePeaks(data: FloatArray, channels: Int, bins: Int): FloatArray =
        nativeComputePeaks(data, channels, bins) ?: FloatArray(bins * 2)

    fun normalize(data: FloatArray, channels: Int, startFrame: Int, endFrame: Int, targetPeak: Float = 0.98f): Float =
        nativeNormalize(data, channels, startFrame, endFrame, targetPeak)

    fun fadeIn(data: FloatArray, channels: Int, startFrame: Int, endFrame: Int) =
        nativeFadeIn(data, channels, startFrame, endFrame)

    fun fadeOut(data: FloatArray, channels: Int, startFrame: Int, endFrame: Int) =
        nativeFadeOut(data, channels, startFrame, endFrame)

    fun reverse(data: FloatArray, channels: Int, startFrame: Int, endFrame: Int) =
        nativeReverse(data, channels, startFrame, endFrame)

    /** [start, end) звучащей части или null, если всё тише порога. */
    fun findNonSilent(data: FloatArray, channels: Int, thresholdDb: Float): IntArray? {
        val linear = Math.pow(10.0, (thresholdDb / 20f).toDouble()).toFloat()
        val r = nativeFindNonSilent(data, channels, linear)
        return if (r.size == 2) r else null
    }

    fun detectTransients(clip: SampleClip, sensitivity: Float, minGapMs: Float = 60f): IntArray =
        nativeDetectTransients(clip.data, clip.channels, clip.sampleRate, sensitivity, minGapMs)

    fun encodeWav16(clip: SampleClip): ByteArray? = nativeEncodeWav16(clip.data, clip.channels, clip.sampleRate)

    /** WAV/AIFF → SampleClip, null если формат не поддержан. */
    fun parseWav(bytes: ByteArray): SampleClip? {
        val info = IntArray(2)
        val data = nativeParseWav(bytes, info) ?: return null
        return SampleClip(data, info[0], info[1])
    }

    private external fun nativeComputePeaks(data: FloatArray, channels: Int, bins: Int): FloatArray?
    private external fun nativeNormalize(data: FloatArray, channels: Int, startFrame: Int, endFrame: Int, target: Float): Float
    private external fun nativeFadeIn(data: FloatArray, channels: Int, startFrame: Int, endFrame: Int)
    private external fun nativeFadeOut(data: FloatArray, channels: Int, startFrame: Int, endFrame: Int)
    private external fun nativeReverse(data: FloatArray, channels: Int, startFrame: Int, endFrame: Int)
    private external fun nativeFindNonSilent(data: FloatArray, channels: Int, threshold: Float): IntArray
    private external fun nativeDetectTransients(data: FloatArray, channels: Int, sampleRate: Int, sensitivity: Float, minGapMs: Float): IntArray
    private external fun nativeEncodeWav16(data: FloatArray, channels: Int, sampleRate: Int): ByteArray?
    private external fun nativeParseWav(bytes: ByteArray, info: IntArray): FloatArray?
}
