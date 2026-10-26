// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.mpcpad.audio.AudioEngine
import com.example.mpcpad.capture.CaptureController
import com.example.mpcpad.capture.CaptureService
import com.example.mpcpad.capture.CaptureSource
import com.example.mpcpad.capture.PadCapture
import com.example.mpcpad.capture.RecorderConfig
import com.example.mpcpad.sample.SampleViewModel
import com.example.mpcpad.ui.AppViewModel
import com.example.mpcpad.ui.MpcRoot
import com.example.mpcpad.ui.SampleActions

class MainActivity : ComponentActivity(), SampleActions {

    private val sampleVm: SampleViewModel by viewModels()
    private val appVm: AppViewModel by viewModels()

    /** Диалог MediaProjection запущен ради взведения захвата для падов (а не обычной записи). */
    private var pendingArm = false
    /** Куда класть файл, выбранный в системном диалоге импорта (-1 — в редактор). */
    private var pendingImportPad = -1
    /** Банк (0–3) для открытого диалога SAVE/IMPORT BANK. */
    private var pendingBank = 0

    /** Что выполнить после выдачи разрешения на микрофон. */
    private var afterMicPermission: (() -> Unit)? = null
    /** Конфигурация записи, ожидающая согласия MediaProjection. */
    private var pendingInternalConfig: RecorderConfig? = null

    // Лаунчеры обязаны быть зарегистрированы до STARTED — поэтому полями.
    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val next = afterMicPermission
            afterMicPermission = null
            if (granted) next?.invoke()
            else sampleVm.showMessage(
                "No microphone permission. Allow it in Settings → Apps → IS-1 → Permissions."
            )
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* уведомление необязательно */ }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val config = pendingInternalConfig
            val arm = pendingArm
            pendingInternalConfig = null
            pendingArm = false
            val data = result.data
            if (config != null && result.resultCode == Activity.RESULT_OK && data != null) {
                if (arm) CaptureService.arm(this, config, result.resultCode, data)
                else CaptureService.start(this, config, result.resultCode, data)
            } else {
                val msg = "Internal audio capture cancelled: confirm access in the system dialog"
                if (arm) PadCapture.cancelArming(msg) else sampleVm.showMessage(msg)
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val pad = pendingImportPad
            pendingImportPad = -1
            if (uri != null) {
                val name = displayName(uri)
                if (name?.endsWith(".sal", ignoreCase = true) == true) {
                    // Файл проекта выбрали через IMPORT — открываем его как проект, а не как семпл.
                    if (Edition.FREE) appVm.flash(Edition.pro("PROJECT FILES"), 2500)
                    else appVm.loadProject(applicationContext, uri, name)
                } else if (pad >= 0) sampleVm.importToPad(uri, pad) else sampleVm.importUri(uri)
            }
        }

    private val saveBankLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) appVm.saveBank(applicationContext, uri, pendingBank)
        }

    private val importBankLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) appVm.importBank(applicationContext, uri, pendingBank)
        }

    private val saveProjectLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) appVm.saveProject(applicationContext, uri, displayName(uri) ?: "IS-1.sal")
        }

    private val openProjectLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) appVm.loadProject(applicationContext, uri, displayName(uri) ?: "IS-1.sal")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // экран не засыпает
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { MpcRoot(actions = this@MainActivity) }
        }
    }

    // Поток вывода живёт только пока приложение на экране; запись при этом идёт в foreground-сервисе.
    override fun onStart() {
        super.onStart()
        AudioEngine.start()
    }

    override fun onStop() {
        ProjectStore.save(this, appVm)   // проект переживает выход из приложения
        AudioEngine.allNotesOff()   // чтобы Gate/Loop-голоса не «залипали» после возврата
        AudioEngine.seqStop()
        // Микрофон в фоне не держим. Внутренний звук оставляем взведённым: музыка в другом приложении
        // продолжает играть, пока вы возвращаетесь сюда и удерживаете пад.
        if (PadCapture.isBusy && PadCapture.state.value.source == CaptureSource.MIC) CaptureService.disarm(this)
        AudioEngine.stop()
        super.onStop()
    }

    // ---------------- SampleActions ----------------

    override fun startRecording() {
        if (CaptureController.isBusy) return
        if (PadCapture.isBusy) {
            sampleVm.showMessage("Turn off SAMPLE on the panel first: the mic is busy recording to pads")
            return
        }
        val config = sampleVm.buildConfig()
        sampleVm.showMessage(null)
        requestNotificationPermissionOnce()
        withMicPermission {
            when (config.source) {
                CaptureSource.MIC -> CaptureService.start(this, config)
                CaptureSource.INTERNAL -> {
                    pendingInternalConfig = config
                    val manager = getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(manager.createScreenCaptureIntent())
                }
            }
        }
    }

    override fun stopRecording() {
        CaptureService.stop(this)
    }

    override fun importToPad(pad: Int) {
        pendingImportPad = pad
        importLauncher.launch(arrayOf("*/*"))
    }

    override fun armPadCapture() {
        if (PadCapture.isBusy || CaptureController.isBusy) return
        val source = appVm.sampleSource
        val config = RecorderConfig(
            source = source,
            sampleRate = 48_000,
            channels = if (source == CaptureSource.INTERNAL) 2 else 1,
        )
        requestNotificationPermissionOnce()
        withMicPermission {
            PadCapture.markArming(source)
            when (source) {
                CaptureSource.MIC -> CaptureService.arm(this, config)
                CaptureSource.INTERNAL -> {
                    pendingInternalConfig = config
                    pendingArm = true
                    val manager = getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(manager.createScreenCaptureIntent())
                }
            }
        }
    }

    override fun disarmPadCapture() {
        CaptureService.disarm(this)
    }

    override fun importFile() {
        pendingImportPad = -1
        // «*/*»: часть файловых менеджеров не помечает AIFF/FLAC/OPUS как audio/*; формат проверяется по содержимому.
        importLauncher.launch(arrayOf("*/*"))
    }

    override fun saveBank(bank: Int) {
        pendingBank = bank
        val date = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date())
        saveBankLauncher.launch("IS-1_BANK_${'A' + bank}_$date.zip")
    }

    override fun importBank(bank: Int) {
        pendingBank = bank
        // Мессенджеры и облака часто отдают zip как octet-stream — показываем все файлы.
        importBankLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    override fun saveProject() {
        val base = appVm.projectName.ifBlank {
            "IS-1_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date())
        }
        saveProjectLauncher.launch("$base.sal")
    }

    override fun openProject() {
        // .sal — свой формат, у системы нет для него MIME-типа: показываем все файлы.
        openProjectLauncher.launch(arrayOf("*/*"))
    }

    /** Имя выбранного файла (как его видит пользователь). */
    private fun displayName(uri: android.net.Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    private fun withMicPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            afterMicPermission = action
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
