package com.example.mpcpad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mpcpad.Settings
import com.example.mpcpad.audio.AudioEngine
import com.example.mpcpad.audio.BAR_TICKS
import com.example.mpcpad.audio.ExportStore
import com.example.mpcpad.audio.PPQ
import com.example.mpcpad.sample.SampleClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * EXPORT: мастер (все пады + KAOSS) ровно на длину Song — или текущего паттерна, если цепочка не включена.
 * Запись идёт в реальном времени с самого начала; готовое можно сохранить WAV/MP3 в «Музыка/IS-1»
 * или вставить в любой пад.
 */
@Composable
fun ExportScreen(app: AppViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Длина фиксируется при открытии: во время записи цепочка не меняется.
    val ticks = remember { app.songLengthTicks() }
    val bars = ticks / BAR_TICKS
    val isSong = remember { app.songIndex >= 0 && app.song.isNotEmpty() }
    val what = if (isSong) "SONG · ${app.song.size} PAT" else "PATTERN ${patternName(app.seqPattern)}"
    val seconds = ticks * 60f / (Settings.bpm * PPQ)

    var state by remember { mutableIntStateOf(AudioEngine.exportState()) }
    var progress by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos {
                state = AudioEngine.exportState()
                val t = AudioEngine.exportTarget()
                progress = if (state == 3) 1f else if (t > 0 && state == 2) AudioEngine.exportFrames().toFloat() / t else 0f
            }
        }
    }

    fun close() {
        when (state) {
            1, 2 -> AudioEngine.exportCancel()
            3 -> AudioEngine.exportFree()
        }
        app.exportOpen = false
    }

    fun save(mp3: Boolean) {
        busy = if (mp3) "ENCODING MP3…" else "SAVING WAV…"
        scope.launch {
            val name = withContext(Dispatchers.IO) { ExportStore.save(ctx.applicationContext, mp3) }
            busy = null
            message = if (name != null) "SAVED: ${ExportStore.FOLDER}/$name" else "SAVE FAILED"
        }
    }

    fun toPad() {
        busy = "PREPARING…"
        scope.launch {
            val data = withContext(Dispatchers.IO) { AudioEngine.exportRead() }
            busy = null
            if (data == null || data.isEmpty()) {
                message = "NOT ENOUGH MEMORY"
                return@launch
            }
            val clip = SampleClip(data, 2, AudioEngine.sampleRate())
            app.clipboard = SampleClipboard(clip, "EXPORT", null, true, -1)
            app.pasteArmed = true
            AudioEngine.exportFree()
            app.exportOpen = false
            app.switchView(Mode.PAD, false)
            app.flash("EXPORT COPIED → TOUCH A PAD", 4000)
        }
    }

    Box(
        modifier
            .background(androidx.compose.ui.graphics.Color(0xB3000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (state != 2 && busy == null) close()
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
                Text("EXPORT MASTER", style = Skin.Lcd.copy(fontSize = 10.sp))
                Text(
                    String.format("%s · %d BAR%s · %.1f S", what, bars, if (bars == 1) "" else "S", seconds),
                    style = Skin.Lcd.copy(fontSize = 15.sp),
                    maxLines = 1,
                )
                val status = busy ?: message ?: when (state) {
                    1, 2 -> String.format("● RECORDING %d%%", (progress * 100).toInt())
                    3 -> "DONE · SAVE OR SEND TO PAD"
                    else -> "PLAYS FROM START WITH FX · RECORDS"
                }
                Text(status, style = Skin.Lcd.copy(fontSize = 11.sp), maxLines = 1)
                // полоса прогресса
                Box(Modifier.fillMaxWidth().height(5.dp).background(Skin.LcdInkDim.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Skin.LcdInk))
                }
            }
            val idle = busy == null
            when (state) {
                3 -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HwButton("WAV", Modifier.weight(1f), enabled = idle, height = 36.dp) { save(false) }
                        HwButton("MP3", Modifier.weight(1f), enabled = idle, height = 36.dp) { save(true) }
                        HwButton("TO PAD", Modifier.weight(1f), enabled = idle, height = 36.dp) { toPad() }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HwButton("AGAIN", Modifier.weight(1f), enabled = idle, height = 30.dp) {
                            AudioEngine.exportFree()
                            message = null
                            if (!AudioEngine.exportStart(ticks, Settings.bpm)) message = "NOT ENOUGH MEMORY"
                        }
                        HwButton("CLOSE", Modifier.weight(1f), enabled = idle, height = 30.dp) { close() }
                    }
                }
                1, 2 -> HwButton("STOP", Modifier.fillMaxWidth(), led = true, ledColor = Skin.LedRed, height = 36.dp) {
                    AudioEngine.exportCancel()
                    message = "CANCELLED"
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HwButton("CANCEL", Modifier.weight(1f), height = 36.dp) { close() }
                    HwButton("● RECORD", Modifier.weight(1f), led = true, ledColor = Skin.LedRed, height = 36.dp) {
                        message = null
                        if (!AudioEngine.exportStart(ticks, Settings.bpm)) message = "NOT ENOUGH MEMORY (MAX 10 MIN)"
                    }
                }
            }
        }
    }
}
