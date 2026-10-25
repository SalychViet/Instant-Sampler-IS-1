// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.audio

import android.content.Context
import android.net.wifi.WifiManager
import com.example.mpcpad.Settings

/**
 * Включение Ableton Link. Link находит участников через multicast в Wi-Fi,
 * а многие телефоны по умолчанию отбрасывают multicast — поэтому держим MulticastLock, пока Link включён.
 */
object LinkNetwork {
    private var lock: WifiManager.MulticastLock? = null

    fun apply(context: Context) {
        setEnabled(context, Settings.linkEnabled && !com.example.mpcpad.Edition.FREE)   // во Free Link нет
        AudioEngine.linkSetLatency(Settings.latencyMs)
    }

    fun setEnabled(context: Context, on: Boolean) {
        Settings.linkEnabled = on
        if (on) acquire(context) else release()
        AudioEngine.linkSetEnabled(on)
        // Новая сессия Link стартует со 120 BPM — отдаём ей свой темп. Если в сети уже есть
        // участники, Link всё равно подстроит нас под общий темп сессии.
        if (on) AudioEngine.seqSetBpm(Settings.bpm)
    }

    fun setLatency(ms: Float) {
        Settings.latencyMs = ms
        AudioEngine.linkSetLatency(ms)
    }

    private fun acquire(context: Context) {
        if (lock?.isHeld == true) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        lock = wifi.createMulticastLock("mpcpad-link").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun release() {
        lock?.let { if (it.isHeld) it.release() }
        lock = null
    }
}
