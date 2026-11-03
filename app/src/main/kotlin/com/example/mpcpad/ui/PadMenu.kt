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
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** ISL-1: меню пада (удержание 1,5 с в режиме PAD): COPY, SLICE, EQ и — у one-shot — огибающая высоты. */
/** 1200 → «1.2K», 120 → «120». */
private fun hzLabel(hz: Float) = if (hz >= 1000f) "%.1fK".format(hz / 1000f) else "%.0f".format(hz)

/** Частота по положению ручки: логарифмически между [lo] и [hi]. */
private fun hzAt(n: Float, lo: Float, hi: Float) = lo * (hi / lo).pow(n)
private fun knobAt(hz: Float, lo: Float, hi: Float) = if (hi <= lo) 0f else (ln(hz / lo) / ln(hi / lo)).coerceIn(0f, 1f)

/** Три полосы: LP и HP включаются кнопками, колокол работает между ними и молчит на 0 дБ. */
@Composable
private fun PadEq(app: AppViewModel, pad: Int) {
    val u = app.pads[pad]
    val (bellLo, bellHi) = app.bellRange(u)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HwButton("HP", Modifier.width(52.dp), led = u.eqHpOn, height = 34.dp) {
            app.setEq(pad) { it.copy(eqHpOn = !it.eqHpOn) }
        }
        Knob("HP HZ", knobAt(u.eqHpHz, EQ_MIN_HZ, EQ_MAX_HZ), { n ->
            val top = if (u.eqLpOn) u.eqLpHz else EQ_MAX_HZ
            app.setEq(pad) { it.copy(eqHpHz = hzAt(n, EQ_MIN_HZ, EQ_MAX_HZ).coerceAtMost(top)) }
        }, diameter = 38.dp)
        Knob("BELL HZ", knobAt(u.eqBellHz, bellLo, bellHi), { n ->
            app.setEq(pad) { it.copy(eqBellHz = hzAt(n, bellLo, bellHi)) }
        }, diameter = 38.dp)
        Knob("BELL DB", (u.eqBellDb + 18f) / 36f, { n ->
            // Около нуля — ровно ноль: так колокол выключается и не нагружает звук.
            val db = (n * 36f - 18f).let { if (abs(it) < 0.25f) 0f else it }
            app.setEq(pad) { it.copy(eqBellDb = db) }
        }, diameter = 38.dp, defaultValue = 0.5f, brass = true)
        Knob("LP HZ", knobAt(u.eqLpHz, EQ_MIN_HZ, EQ_MAX_HZ), { n ->
            val bottom = if (u.eqHpOn) u.eqHpHz else EQ_MIN_HZ
            app.setEq(pad) { it.copy(eqLpHz = hzAt(n, EQ_MIN_HZ, EQ_MAX_HZ).coerceAtLeast(bottom)) }
        }, diameter = 38.dp)
        HwButton("LP", Modifier.width(52.dp), led = u.eqLpOn, height = 34.dp) {
            app.setEq(pad) { it.copy(eqLpOn = !it.eqLpOn) }
        }
    }
}

@Composable
fun PadMenu(app: AppViewModel, onCopy: (Int) -> Unit, modifier: Modifier = Modifier) {
    val pad = app.padMenu
    fun close() { app.padMenu = -1 }
    fun openWindow(env: Boolean) {
        close()
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
                .width(440.dp)
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
                val u = app.pads[pad]
                val eq = listOfNotNull(
                    if (u.eqHpOn) "HP ${hzLabel(u.eqHpHz)}" else null,
                    if (u.eqBellDb != 0f) "BELL ${hzLabel(u.eqBellHz)} %+.1f DB".format(u.eqBellDb) else null,
                    if (u.eqLpOn) "LP ${hzLabel(u.eqLpHz)}" else null,
                ).joinToString(" · ").ifEmpty { "EQ OFF" }
                Text("PAD ${padName(pad)} · $eq", style = Skin.Lcd.copy(fontSize = 13.sp), maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HwButton("COPY", Modifier.weight(1f), height = 38.dp) {
                    close()
                    onCopy(pad)
                }
                // У петли SLICE — на экране, меню открывается оттуда.
                if (app.pads[pad].mode != PlayMode.LOOP) {
                    HwButton("SLICE", Modifier.weight(1f), height = 38.dp) { openWindow(env = false) }
                    HwButton("PTCH ENV", Modifier.weight(1f), height = 38.dp) { openWindow(env = true) }
                }
            }
            PadEq(app, pad)
            HwButton("CLOSE", Modifier.fillMaxWidth(), height = 30.dp) { close() }
        }
    }
}
