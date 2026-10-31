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
import com.example.mpcpad.audio.PlayMode

/** ISL-1: меню пада (удержание 2 с в режиме PAD): COPY, SLICE и — у one-shot — огибающая высоты. */
@Composable
fun PadMenu(app: AppViewModel, onCopy: (Int) -> Unit, modifier: Modifier = Modifier) {
    val pad = app.padMenu
    fun close() { app.padMenu = -1 }
    fun openWindow(env: Boolean) {
        close()
        app.kaossMode = false
        app.partsShowEnv = env
        if (env && !app.pads[pad].pEnvOn) app.setPitchEnvOn(pad, true)
        app.openLoopParts(pad)
    }
    Box(
        modifier
            .background(Color(0xB3000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(300.dp)
                .background(Skin.LcdFrame, RoundedCornerShape(10.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Skin.LcdBgTop, RoundedCornerShape(5.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("PAD ${padName(pad)}", style = Skin.Lcd.copy(fontSize = 15.sp), maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HwButton("COPY", Modifier.weight(1f), height = 38.dp) {
                    close()
                    onCopy(pad)
                }
                HwButton("SLICE", Modifier.weight(1f), height = 38.dp) { openWindow(env = false) }
                if (app.pads[pad].mode != PlayMode.LOOP) {
                    HwButton("PTCH ENV", Modifier.weight(1f), height = 38.dp) { openWindow(env = true) }
                }
            }
            HwButton("CLOSE", Modifier.fillMaxWidth(), height = 30.dp) { close() }
        }
    }
}
