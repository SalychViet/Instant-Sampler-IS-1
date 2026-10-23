package com.example.mpcpad.sample

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/** Прослушивание фрагмента семпла в редакторе (мимо движка). */
class Preview {
    private companion object {
        const val MAX_SECONDS = 30
    }

    private var track: AudioTrack? = null

    /** [rate] — скорость/высота как у пада: 2^((PITCH + DETUNE/100) / 12). */
    fun play(clip: SampleClip, startFrame: Int, endFrame: Int, rate: Float = 1f) {
        stop()
        val s = startFrame.coerceIn(0, clip.frames)
        val e = endFrame.coerceIn(s, minOf(clip.frames, s + clip.sampleRate * MAX_SECONDS))
        if (e - s < 2) return
        val count = (e - s) * clip.channels
        val mask = if (clip.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(clip.sampleRate)
                        .setChannelMask(mask)
                        .build()
                )
                .setBufferSizeInBytes(count * 4)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(clip.data, clip.channels * s, count, AudioTrack.WRITE_BLOCKING)
            if (rate != 1f) {
                t.playbackRate = (clip.sampleRate * rate).toInt().coerceIn(4000, 2 * AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC))
            }
            t.play()
            track = t
        } catch (ex: Exception) {
            track = null
        }
    }

    fun stop() {
        val t = track ?: return
        track = null
        try {
            t.stop()
        } catch (_: Exception) {
        }
        t.release()
    }
}
