// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.audio

/**
 * Программа KAOSS: короткий код (набор + номер), имя и что делают оси X и Y.
 * Порядок ДОЛЖЕН совпадать с KaossProgram в KaossFx.h.
 */
class KaossProgram(val code: String, val name: String, val x: String, val y: String) {
    val title get() = "$code $name"
}

object Kaoss {
    /** Наборы на кнопках 1–5, по 10 программ; повторное нажатие кнопки листает программы набора. */
    val BANKS = listOf("FILTER", "SPACE", "REPEAT", "MOD", "LFO")
    const val PER_BANK = 10

    val PROGRAMS = listOf(
        // 1 FILTER
        KaossProgram("F01", "MORPH SWEEP", "CUTOFF", "RESONANCE"),
        KaossProgram("F02", "LOWPASS", "CUTOFF", "RESONANCE"),
        KaossProgram("F03", "HIGHPASS", "CUTOFF", "RESONANCE"),
        KaossProgram("F04", "BANDPASS", "CUTOFF", "MIX"),
        KaossProgram("F05", "STEEP LOWPASS", "CUTOFF", "RESONANCE"),
        KaossProgram("F06", "DARK ECHO", "DELAY TIME", "LPF CUT&RESO"),
        KaossProgram("F07", "THIN ECHO", "DELAY TIME", "HPF CUT&RESO"),
        KaossProgram("F08", "BAND ECHO", "DELAY TIME", "BPF CUT&RESO"),
        KaossProgram("F09", "DARK HALL", "REVERB DEPTH", "LPF CUT&RESO"),
        KaossProgram("F10", "AIR HALL", "REVERB DEPTH", "HPF CUT&RESO"),
        // 2 SPACE
        KaossProgram("S01", "SYNC DELAY", "DELAY TIME", "DEPTH"),
        KaossProgram("S02", "ECHO DROP", "DELAY TIME", "FEEDBACK"),
        KaossProgram("S03", "TAPE DELAY", "DELAY TIME", "DEPTH"),
        KaossProgram("S04", "DUB SPACE", "DELAY TIME", "TONE"),
        KaossProgram("S05", "PING PONG", "DELAY TIME", "DEPTH"),
        KaossProgram("S06", "PUMP VERB", "REVERB TIME", "PUMP DEPTH"),
        KaossProgram("S07", "ICE VERB", "TONE", "MIX"),
        KaossProgram("S08", "GATED VERB", "GATE TIME", "DEPTH"),
        KaossProgram("S09", "JET SWOOSH", "DELAY TIME", "FEEDBACK"),
        KaossProgram("S10", "GRAIN SPRAY", "CYCLE SPEED", "LENGTH"),
        // 3 REPEAT
        KaossProgram("R01", "FLIP LOOP", "LOOP LENGTH", "REV < > FWD"),
        KaossProgram("R02", "BRAKE LOOP", "LOOP LENGTH", "BRAKE TIME"),
        KaossProgram("R03", "MIRROR LOOP", "LOOP LENGTH", "HPF CUTOFF"),
        KaossProgram("R04", "HOLD LOOP", "LOOP LENGTH", "MIX"),
        KaossProgram("R05", "OCTAVE LOOP", "LOOP LENGTH", "MIX"),
        KaossProgram("R06", "SHUTTLE LOOP", "LOOP LENGTH", "LPF CUTOFF"),
        KaossProgram("R07", "SLICE PICK", "SLICE LENGTH", "SLICE POS"),
        KaossProgram("R08", "SCRATCH LOOP", "LOOP LENGTH", "SCRATCH"),
        KaossProgram("R09", "WARM LOOP", "LOOP LENGTH", "LPF CUTOFF"),
        KaossProgram("R10", "VINYL LOOP", "LOOP LENGTH", "REV < > FWD"),
        // 4 MOD
        KaossProgram("M01", "NOISE RISER", "SWEEP", "TAIL (DLY+RVB)"),
        KaossProgram("M02", "RECORD STOP", "BRAKE TIME", "SCRATCH"),
        KaossProgram("M03", "STOP & VERB", "BRAKE TIME", "SCRATCH"),
        KaossProgram("M04", "METAL RING", "RING FREQ", "HPF CUT&RESO"),
        KaossProgram("M05", "HAND PHASER", "FREQUENCY", "RESONANCE"),
        KaossProgram("M06", "TALK BOX", "1ST FORMANT", "2ND FORMANT"),
        KaossProgram("M07", "ROBO TALK", "1ST FORMANT", "2ND FORMANT"),
        KaossProgram("M08", "SIDE PUMP", "SENSITIVITY", "DEPTH"),
        KaossProgram("M09", "SQUASH", "SENSITIVITY", "ATTACK"),
        KaossProgram("M10", "PITCH ECHO", "PITCH SHIFT", "DELAY DEPTH"),
        // 5 LFO
        KaossProgram("L01", "DIRTY ECHO", "DELAY TIME", "BPF CUT&RESO"),
        KaossProgram("L02", "WOBBLE LP", "LFO SPEED", "RESONANCE"),
        KaossProgram("L03", "CRUSH STUTTER", "LOOP LENGTH", "CRUSH & DELAY"),
        KaossProgram("L04", "WOBBLE BAND", "LFO SPEED", "RESONANCE"),
        KaossProgram("L05", "SWEEP ECHO", "DELAY TIME", "HPF & DELAY"),
        KaossProgram("L06", "ENDLESS RISE", "LFO SPEED", "RESONANCE"),
        KaossProgram("L07", "RANDOM STEP", "LFO SPEED", "LFO DEPTH"),
        KaossProgram("L08", "RHYTHM GATE", "GATE PATTERN", "LPF CUT&RESO"),
        KaossProgram("L09", "BIT CRUSH", "RATE", "BITS"),
        KaossProgram("L10", "DRIVE", "DRIVE", "TONE"),
    )
}

/**
 * Запись манипуляций KAOSS (REC в режиме KAOSS) на длину Song: значения по тикам позиции в цепочке.
 * Между точками действует последнее значение.
 */
class FxAutomation(
    val ticks: IntArray,
    val program: IntArray,
    val x: FloatArray,
    val y: FloatArray,
    val touch: BooleanArray,
    val lengthTicks: Int,
) {
    /** Индекс точки, действующей на тике [tick] (с переходом через начало). */
    fun indexAt(tick: Int): Int {
        if (ticks.isEmpty()) return -1
        val t = tick.mod(lengthTicks)
        var lo = 0
        var hi = ticks.size - 1
        if (t < ticks[0]) return ticks.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (ticks[mid] <= t) lo = mid else hi = mid - 1
        }
        return lo
    }
}
