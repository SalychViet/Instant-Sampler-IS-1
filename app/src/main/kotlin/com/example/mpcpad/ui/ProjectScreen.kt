// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mpcpad.Edition
import com.example.mpcpad.Settings
import com.example.mpcpad.sample.PadLibrary

/**
 * PRJCT: сохранить текущий проект в файл .sal или открыть сохранённый.
 * В файле — все 64 пада (семплы и параметры), паттерны, песня, движения и запись эффектов KAOSS, темп.
 */
@Composable
fun ProjectScreen(app: AppViewModel, actions: SampleActions, modifier: Modifier = Modifier) {
    val idle = !app.projectBusy
    Box(
        modifier
            .background(Color(0xB3000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (idle) app.projectOpen = false
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(330.dp)
                .background(Skin.LcdFrame, RoundedCornerShape(10.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Skin.LcdBgTop, RoundedCornerShape(5.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("PROJECT", style = Skin.Lcd.copy(fontSize = 10.sp))
                Text(app.projectName.ifBlank { "UNTITLED" }.uppercase(), style = Skin.Lcd.copy(fontSize = 15.sp), maxLines = 1)
                val samples = PadLibrary.infos.count { it != null }
                Text(
                    String.format("%d PADS · %.1f BPM", samples, Settings.bpm),
                    style = Skin.Lcd.copy(fontSize = 11.sp),
                    maxLines = 1,
                )
                Text(
                    if (app.projectBusy) "WORKING…" else "FILE: .sal · ALL PADS, PATTERNS, SONG, FX",
                    style = Skin.Lcd.copy(fontSize = 9.sp),
                    maxLines = 1,
                )
            }
            if (Edition.FREE) {
                // Free: только автосохранение одного проекта.
                Text(
                    "Your project is saved automatically. Saving and loading .sal project files is in IS-1 PRO.",
                    color = Color(0xFFB0B4BA),
                    fontSize = 11.sp,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HwButton("SAVE", Modifier.weight(1f), enabled = idle, height = 36.dp) { actions.saveProject() }
                    HwButton("LOAD", Modifier.weight(1f), enabled = idle, height = 36.dp) { actions.openProject() }
                }
                Text(
                    "LOAD replaces the current project — save it first.",
                    color = Color(0xFFB0B4BA),
                    fontSize = 10.sp,
                )
            }
            HwButton("CLOSE", Modifier.fillMaxWidth(), enabled = idle, height = 30.dp) { app.projectOpen = false }
        }
    }
}
