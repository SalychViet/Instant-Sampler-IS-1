// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.mpcpad.MainActivity
import com.example.mpcpad.sample.PadSampler
import java.io.File

/**
 * Foreground-сервис записи. Два режима:
 *  START — обычная запись в файл (Recorder),
 *  ARM   — «запись в пады» (ArmedCapture), пока пользователь держит пэд.
 * Для внутреннего звука нужен MediaProjection (согласие пользователя передаётся через resultCode/data).
 */
class CaptureService : Service() {
    companion object {
        private const val ACTION_START = "com.example.mpcpad.capture.START"
        private const val ACTION_STOP = "com.example.mpcpad.capture.STOP"
        private const val ACTION_ARM = "com.example.mpcpad.capture.ARM"
        private const val ACTION_DISARM = "com.example.mpcpad.capture.DISARM"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_RATE = "rate"
        private const val EXTRA_CHANNELS = "channels"
        private const val EXTRA_THRESHOLD = "threshold"
        private const val EXTRA_COUNT_IN = "countIn"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "capture"
        private const val NOTIF_ID = 42

        fun start(context: Context, config: RecorderConfig, resultCode: Int = 0, data: Intent? = null) =
            launch(context, ACTION_START, config, resultCode, data)

        fun arm(context: Context, config: RecorderConfig, resultCode: Int = 0, data: Intent? = null) =
            launch(context, ACTION_ARM, config, resultCode, data)

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))
        }

        fun disarm(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_DISARM))
        }

        private fun launch(context: Context, action: String, config: RecorderConfig, resultCode: Int, data: Intent?) {
            val intent = Intent(context, CaptureService::class.java)
                .setAction(action)
                .putExtra(EXTRA_SOURCE, config.source.ordinal)
                .putExtra(EXTRA_RATE, config.sampleRate)
                .putExtra(EXTRA_CHANNELS, config.channels)
                .putExtra(EXTRA_THRESHOLD, config.thresholdDb ?: Float.NaN)
                .putExtra(EXTRA_COUNT_IN, config.countInMs)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
            if (data != null) intent.putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private class Prepared(val config: RecorderConfig, val record: AudioRecord)

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var recorder: Recorder? = null
    private var armed: ArmedCapture? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_ARM -> arm(intent)
            ACTION_STOP -> {
                val r = recorder
                val a = armed
                when {
                    r != null -> r.requestStop()
                    a != null -> a.stop()
                    else -> shutdown()
                }
            }
            ACTION_DISARM -> {
                val a = armed
                if (a != null) a.stop() else if (recorder == null) shutdown()
            }
            else -> if (recorder == null && armed == null) shutdown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recorder?.requestStop()
        armed?.stop()
        projection?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
        }
        projection = null
        super.onDestroy()
    }

    private fun startCapture(intent: Intent) {
        if (recorder != null || armed != null) return
        val p = prepare(intent, armedMode = false) ?: return
        val rec = Recorder(p.config, RecordingsStore.newFile(this), p.record) { file, error ->
            main.post { onRecorderDone(file, error) }
        }
        recorder = rec
        rec.start()
    }

    private fun arm(intent: Intent) {
        if (recorder != null || armed != null) return
        val p = prepare(intent, armedMode = true) ?: return
        val cap = ArmedCapture(
            p.config,
            p.record,
            onClip = { pad, data, frames, channels, rate, capped ->
                PadSampler.onRecorded(pad, data, frames, channels, rate, capped, p.config.source)
            },
            onEnded = { error -> main.post { onArmedEnded(error) } },
        )
        armed = cap
        PadCapture.attach(cap, p.config.source)
        cap.start()
    }

    /** Поднимает foreground, получает MediaProjection (для INTERNAL) и открывает AudioRecord. */
    private fun prepare(intent: Intent, armedMode: Boolean): Prepared? {
        val config = intent.toConfig()
        val internal = config.source == CaptureSource.INTERNAL
        try {
            createChannel()
            val type = if (internal) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else micType()
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(internal, armedMode), type)
            if (internal) {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                if (code != android.app.Activity.RESULT_OK || data == null) {
                    failWith("Audio capture was not allowed", armedMode)
                    return null
                }
                val manager = getSystemService(MediaProjectionManager::class.java)
                val p = manager.getMediaProjection(code, data)
                projection = p
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        val reason = "Audio capture stopped by the system"
                        recorder?.requestStop(reason)
                        armed?.stop(reason)
                    }
                }, main)
            }
            return try {
                Prepared(config, Recorder.buildAudioRecord(config, projection))
            } catch (e: SecurityException) {
                failWith("No permission to record audio: ${e.message}", armedMode)
                null
            } catch (e: Exception) {
                failWith(e.message ?: "Could not start recording", armedMode)
                null
            }
        } catch (e: Exception) {
            failWith("Could not start the recording service: ${e.message}", armedMode)
            return null
        }
    }

    private fun micType(): Int =
        if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST

    private fun onRecorderDone(file: File?, error: String?) {
        recorder = null
        CaptureController.finished(file, error)
        RecordingsStore.prune(this)
        shutdown()
    }

    private fun onArmedEnded(error: String?) {
        armed = null
        PadCapture.detach(error)
        shutdown()
    }

    private fun failWith(message: String, armedMode: Boolean) {
        recorder = null
        armed = null
        if (armedMode) PadCapture.cancelArming(message) else CaptureController.finished(null, message)
        shutdown()
    }

    private fun shutdown() {
        projection?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
        }
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Запись звука", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(internal: Boolean, armedMode: Boolean): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stop = PendingIntent.getService(this, 0, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), flags)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        val source = if (internal) "внутренний звук телефона" else "микрофон"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (armedMode) "IS-1 — запись в пады включена" else "IS-1 — идёт запись")
            .setContentText(
                if (armedMode) "Источник: $source. Удерживайте пад, чтобы записать семпл" else "Запись: $source"
            )
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, if (armedMode) "Отключить" else "Стоп", stop)
            .build()
    }

    private fun Intent.toConfig(): RecorderConfig {
        val source = CaptureSource.entries.getOrElse(getIntExtra(EXTRA_SOURCE, 0)) { CaptureSource.MIC }
        return RecorderConfig(
            source = source,
            sampleRate = getIntExtra(EXTRA_RATE, 48_000),
            channels = getIntExtra(EXTRA_CHANNELS, 2).coerceIn(1, 2),
            thresholdDb = getFloatExtra(EXTRA_THRESHOLD, Float.NaN).takeUnless { it.isNaN() },
            countInMs = getIntExtra(EXTRA_COUNT_IN, 0),
        )
    }
}
