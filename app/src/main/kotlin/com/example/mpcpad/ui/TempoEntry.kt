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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mpcpad.Settings
import com.example.mpcpad.audio.AudioEngine

/** Ввод темпа цифрами: открывается долгим нажатием на BPM на экране. 30–300 BPM, шаг 0.1. */
@Composable
fun TempoEntry(app: AppViewModel, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    val value = text.toFloatOrNull()
    val valid = value != null && value in 30f..300f
    Box(
        modifier
            .background(Color(0xB3000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { app.tempoEntry = false },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(250.dp)
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
                Text(
                    if (text.isEmpty()) String.format("%.1f", Settings.bpm) else text,
                    style = Skin.Lcd.copy(fontSize = 26.sp, color = if (text.isEmpty()) Skin.LcdInkDim else Skin.LcdInk),
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
                Text("TEMPO BPM", style = Skin.Lcd.copy(fontSize = 10.sp), modifier = Modifier.align(Alignment.TopStart))
            }
            val rows = listOf(listOf("7", "8", "9"), listOf("4", "5", "6"), listOf("1", "2", "3"), listOf(".", "0", "⌫"))
            for (r in rows) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in r) HwButton(k, Modifier.weight(1f), height = 38.dp) {
                    text = when (k) {
                        "⌫" -> text.dropLast(1)
                        "." -> if ("." in text || text.isEmpty()) text else "$text."
                        else -> if (text.length >= 5 || (text.substringAfter('.', "").length >= 1 && "." in text)) text else text + k
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HwButton("CANCEL", Modifier.weight(1f), height = 34.dp) { app.tempoEntry = false }
                HwButton("OK", Modifier.weight(1f), led = valid, ledColor = Skin.LedGreen, enabled = valid, height = 34.dp) {
                    val v = value ?: return@HwButton
                    Settings.bpm = v
                    AudioEngine.seqSetBpm(v)
                    app.flash(String.format("TEMPO %.1f", v), 1200)
                    app.tempoEntry = false
                }
            }
            if (text.isNotEmpty() && !valid) Text("30 – 300 BPM", color = Color(0xFFE57373), fontSize = 11.sp)
        }
    }
}
