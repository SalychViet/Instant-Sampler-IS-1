// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad

import android.app.Application
import com.example.mpcpad.audio.AudioEngine
import com.example.mpcpad.audio.DefaultKit
import com.example.mpcpad.audio.LinkNetwork
import com.example.mpcpad.sample.PadSampler
import com.example.mpcpad.sample.PadStore

class MpcApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        DefaultProject.installIfFresh(this)   // Pro: при первом запуске — проект из коробки
        PadSampler.init(this)
        AudioEngine.create()
        AudioEngine.setMasterVolume(Settings.master)
        AudioEngine.seqSetBpm(Settings.bpm)
        AudioEngine.seqSetQuantize(Settings.quantize)
        AudioEngine.seqSetMetronome(Settings.metroMode, Settings.metroLevel)
        AudioEngine.seqSetCountIn(Settings.countInBars)
        AudioEngine.seqSetLocksEnabled(true)   // параметры шагов звучат всегда
        LinkNetwork.apply(this)
        DefaultKit.load()
        ProjectStore.load(this)   // паттерны, цепочка и параметры падов с прошлого раза
        // Сохранённые семплы пэдов грузим в фоне, чтобы не задерживать старт.
        Thread({ PadStore.loadAll(this) }, "mpc-padstore").start()
    }
}
