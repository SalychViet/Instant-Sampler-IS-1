package com.example.mpcpad.sample

/** Семпл в памяти: float PCM, interleaved. */
class SampleClip(val data: FloatArray, val channels: Int, val sampleRate: Int) {
    val frames: Int get() = data.size / channels
    val durationMs: Long get() = frames.toLong() * 1000 / sampleRate

    fun copy(): SampleClip = SampleClip(data.copyOf(), channels, sampleRate)

    fun slice(startFrame: Int, endFrame: Int): SampleClip {
        val s = startFrame.coerceIn(0, frames)
        val e = endFrame.coerceIn(s, frames)
        return SampleClip(data.copyOfRange(channels * s, channels * e), channels, sampleRate)
    }
}
