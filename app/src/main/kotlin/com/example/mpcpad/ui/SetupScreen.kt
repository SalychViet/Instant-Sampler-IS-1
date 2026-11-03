// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mpcpad.BuildConfig
import com.example.mpcpad.Settings
import com.example.mpcpad.audio.AudioEngine
import com.example.mpcpad.audio.LinkNetwork
import com.example.mpcpad.audio.LiveRecorder
import com.example.mpcpad.sample.PadLibrary
import kotlin.math.roundToInt

/** Экран SETUP: банки в файл и из файла, синхронизация с Ableton Link и сдвиг задержки. */
@Composable
fun SetupScreen(app: AppViewModel, actions: SampleActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier
            .background(Bg)
            // Поглощаем касания, чтобы не нажимались пады под экраном.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SETUP", color = TextMain, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Chip("ЗАКРЫТЬ", active = true, compact = true) { app.setupOpen = false }
        }

        // ISL-1: настройки петель
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .background(Panel, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ParamSlider(
                label = "LOOP OFFSET",
                valueText = "%+.0f мс".format(Settings.loopOffsetMs),
                value = Settings.loopOffsetMs,
                range = -150f..150f,
                steps = 299,
            ) { Settings.loopOffsetMs = it.roundToInt().toFloat() }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("−5 мс", compact = true) { Settings.loopOffsetMs = (Settings.loopOffsetMs - 5f).coerceAtLeast(-150f) }
                Chip("0", compact = true) { Settings.loopOffsetMs = 0f }
                Chip("+5 мс", compact = true) { Settings.loopOffsetMs = (Settings.loopOffsetMs + 5f).coerceAtMost(150f) }
            }
            Spacer(Modifier.height(4.dp))
            // Уровень входа для записи петель и семплов — отдельно для микрофона и звука телефона.
            for (src in listOf(com.example.mpcpad.capture.CaptureSource.MIC, com.example.mpcpad.capture.CaptureSource.INTERNAL)) {
                val int = src == com.example.mpcpad.capture.CaptureSource.INTERNAL
                ParamSlider(
                    label = if (int) "IN INT (звук телефона)" else "IN MIC (микрофон)",
                    valueText = "%+.0f дБ".format(Settings.inputGainDb(src)),
                    value = Settings.inputGainDb(src),
                    range = -12f..24f,
                    steps = 35,
                ) {
                    val v = it.roundToInt().toFloat()
                    if (int) Settings.recGainIntDb = v else Settings.recGainDb = v
                }
            }
            Spacer(Modifier.height(4.dp))
            // Фейдер CLICK на панели ISL-1 занят SEQ VOL — громкость метронома здесь.
            ParamSlider(
                label = "CLICK",
                valueText = "%.0f%%".format(Settings.metroLevel * 100),
                value = Settings.metroLevel,
                range = 0f..1f,
                steps = 19,
            ) {
                Settings.metroLevel = it
                AudioEngine.seqSetMetronome(Settings.metroMode, it)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LOOP QUANTIZE", color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Chip(if (Settings.loopQuantize) "ВКЛ" else "ВЫКЛ", active = Settings.loopQuantize) {
                    Settings.loopQuantize = !Settings.loopQuantize
                    app.flash("LOOP QUANTIZE " + if (Settings.loopQuantize) "ON" else "OFF")
                }
            }
            Text(
                "Включено — удары в новой петле сдвигаются к сетке, звук склеивается плавно. " +
                    "На уже записанные петли не влияет.",
                color = TextDim,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LOOP TRANSPORT QUANTIZE", color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Chip(if (Settings.loopTransportQuant) "ВКЛ" else "ВЫКЛ", active = Settings.loopTransportQuant) {
                    Settings.loopTransportQuant = !Settings.loopTransportQuant
                    app.flash("LOOP TRANSPORT QUANTIZE " + if (Settings.loopTransportQuant) "ON" else "OFF")
                }
            }
            Text(
                "Включено — выключенная касанием петля доигрывает до конца такта. " +
                    "Выключено — останавливается сразу.",
                color = TextDim,
                fontSize = 12.sp,
            )
            ParamSlider(
                label = "СИЛА КВАНТИЗАЦИИ",
                valueText = "%.0f %%".format(Settings.loopQuantStrength * 100),
                value = Settings.loopQuantStrength,
                range = 0f..1f,
                steps = 19,
            ) { Settings.loopQuantStrength = it }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("СЕТКА", color = TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                for ((name, div) in listOf("1/4" to 1, "1/8" to 2, "1/16" to 4, "1/32" to 8)) {
                    Chip(name, active = Settings.loopQuantGrid == div, compact = true) { Settings.loopQuantGrid = div }
                }
            }
            val lat = AudioEngine.outputLatencyMs()
            Text(
                (if (lat > 0) "Задержка выхода телефона: ~%.0f мс — учитывается сама. ".format(lat) else "") +
                    "Если вторая петля всё равно запаздывает — двигайте вправо (+), если спешит — влево (−).",
                color = TextDim,
                fontSize = 12.sp,
            )
        }

        // LIVE REC: куда сохранять записи всего, что слышно
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .background(Panel, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("LIVE REC — ПАПКА ДЛЯ ЗАПИСЕЙ", color = TextMain, fontSize = 14.sp)
            Text(
                "Сейчас: " + LiveRecorder.folderLabel() + ". Файл LIVE_дата_время.wav появляется там, когда запись остановлена.",
                color = TextDim,
                fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("ВЫБРАТЬ ПАПКУ", enabled = !app.liveRec) { actions.pickLiveFolder() }
                if (Settings.liveFolder.isNotEmpty()) Chip("ПО УМОЛЧАНИЮ", enabled = !app.liveRec) {
                    Settings.liveFolder = ""
                    app.flash("LIVE REC → " + LiveRecorder.folderLabel(), 2500)
                }
            }
        }

        // 1. IMPORT BANK — 2. SAVE BANK: 16 семплов банка вместе с параметрами (громкость, пан, высота…)
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .background(Panel, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("1. IMPORT BANK", color = TextMain, fontSize = 14.sp)
            Text(
                "Выберите банк, затем файл банка (.isbank): все 16 падов банка заменятся звуками и настройками из файла.",
                color = TextDim,
                fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (b in 0 until 4) Chip(('A' + b).toString(), enabled = !app.bankBusy) { actions.importBank(b) }
            }
            Spacer(Modifier.height(6.dp))
            Text("2. SAVE BANK", color = TextMain, fontSize = 14.sp)
            Text(
                "Выберите банк — его семплы и параметры (VOL, PAN, PITCH, DETUNE, START/END, ATK/REL, режим, CHOKE) " +
                    "сохранятся в один файл. В скобках — сколько падов со звуком.",
                color = TextDim,
                fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (b in 0 until 4) {
                    val n = (b * 16 until b * 16 + 16).count { PadLibrary.infos[it] != null }
                    Chip("${'A' + b} ($n)", enabled = !app.bankBusy && n > 0) { actions.saveBank(b) }
                }
            }
            if (app.bankBusy) Text("Работаю с файлом банка…", color = Color(0xFFFFB020), fontSize = 12.sp)
        }

        Column(
            Modifier
                .widthIn(max = 560.dp)
                .background(Panel, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ableton Link", color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Chip(if (Settings.linkEnabled) "ВКЛ" else "ВЫКЛ", active = Settings.linkEnabled) {
                    LinkNetwork.setEnabled(context, !Settings.linkEnabled)
                    app.flash("ABLETON LINK " + if (Settings.linkEnabled) "ON" else "OFF")
                }
            }
            val status = when {
                !Settings.linkEnabled -> "Синхронизация темпа, такта и старта/стопа с Ableton Live и другими Link-приложениями."
                app.linkPeers == 0 -> "Ищу участников… Телефон и компьютер должны быть в одной Wi-Fi сети, в Ableton включите LINK (слева вверху)."
                else -> "Подключено: ${app.linkPeers} · темп %.1f BPM · PLAY/STOP общие для сессии".format(Settings.bpm)
            }
            Text(status, color = if (Settings.linkEnabled && app.linkPeers > 0) Color(0xFF4CAF50) else TextDim, fontSize = 12.sp)
        }

        Column(
            Modifier
                .widthIn(max = 560.dp)
                .background(Panel, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            ParamSlider(
                label = "Latency",
                valueText = "%+.0f мс".format(Settings.latencyMs),
                value = Settings.latencyMs,
                range = -100f..100f,
                steps = 199,
            ) { LinkNetwork.setLatency(it.roundToInt().toFloat()) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("−1 мс", compact = true) { LinkNetwork.setLatency((Settings.latencyMs - 1f).coerceAtLeast(-100f)) }
                Chip("0", compact = true) { LinkNetwork.setLatency(0f) }
                Chip("+1 мс", compact = true) { LinkNetwork.setLatency((Settings.latencyMs + 1f).coerceAtMost(100f)) }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Сдвиг звука телефона относительно сетки Link. Если телефон звучит позже Ableton — двигайте вправо (+), " +
                    "если раньше — влево (−). Удобно подбирать по метроному в обоих приложениях.",
                color = TextDim,
                fontSize = 12.sp,
            )
        }

        Column(
            Modifier
                .widthIn(max = 560.dp)
                .background(Panel, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Отсчёт перед записью (REC)", color = TextMain, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (bars in listOf(0, 1, 2, 4)) {
                    Chip(if (bars == 0) "ВЫКЛ" else "$bars ТАКТ", active = Settings.countInBars == bars, compact = true) {
                        Settings.countInBars = bars
                        AudioEngine.seqSetCountIn(bars)
                    }
                }
            }
            Text("С Ableton Link отсчёт не нужен: старт идёт по такту сессии.", color = TextDim, fontSize = 12.sp)
        }

        AboutPanel()
    }
}

/** О программе: версия, лицензия GPLv3 и ссылка на исходный код (этого требует GPL). */
@Composable
private fun AboutPanel() {
    val uri = LocalUriHandler.current
    Column(
        Modifier
            .widthIn(max = 560.dp)
            .background(Panel, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("О программе", color = TextMain, fontSize = 14.sp)
        Text(
            "IS-1 ${BuildConfig.VERSION_NAME} · © 2026 Salavat (SALYCH MUSIC)",
            color = TextDim,
            fontSize = 12.sp,
        )
        Text(
            "Свободная программа: её можно распространять и изменять по лицензии GNU GPL версии 3 " +
                "или (на ваш выбор) любой более поздней. Предоставляется БЕЗ КАКИХ-ЛИБО ГАРАНТИЙ.",
            color = TextDim,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("ИСХОДНЫЙ КОД", compact = true) { runCatching { uri.openUri(BuildConfig.SOURCE_URL) } }
            Chip("ЛИЦЕНЗИЯ GPLv3", compact = true) { runCatching { uri.openUri("https://www.gnu.org/licenses/gpl-3.0.html") } }
        }
        Text(BuildConfig.SOURCE_URL, color = TextDim, fontSize = 11.sp)
        Text(
            "Использует: Ableton Link (GPL-2.0+), Asio (Boost 1.0), LAME MP3 encoder (LGPL-2.0+, lame.sourceforge.io), " +
                "Oboe, AndroidX, Jetpack Compose, Kotlin (Apache-2.0). Ableton Link — товарный знак Ableton AG; " +
                "IS-1 не связан с Ableton. Подробности — THIRD_PARTY_NOTICES.md в исходном коде.",
            color = TextDim,
            fontSize = 11.sp,
        )
    }
}
