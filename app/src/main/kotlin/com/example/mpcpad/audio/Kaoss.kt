package com.example.mpcpad.audio

/**
 * Программа KAOSS: код и имя как на KORG KAOSS PAD KP3+, что делают оси X и Y.
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
        // 1 FILTER — FLt.1 … FL.10
        KaossProgram("FLt.1", "MORPHING FILTER", "CUTOFF", "RESONANCE"),
        KaossProgram("FLt.2", "LOW PASS FILTER", "CUTOFF", "RESONANCE"),
        KaossProgram("FLt.3", "HIGH PASS FILTER", "CUTOFF", "RESONANCE"),
        KaossProgram("FLt.4", "BAND PASS FILTER", "CUTOFF", "MIX"),
        KaossProgram("FLt.5", "72dB/oct LPF", "CUTOFF", "RESONANCE"),
        KaossProgram("FLt.6", "LPF & DELAY", "DELAY TIME", "LPF CUT&RESO"),
        KaossProgram("FLt.7", "HPF & DELAY", "DELAY TIME", "HPF CUT&RESO"),
        KaossProgram("FLt.8", "BPF+ & DELAY", "DELAY TIME", "BPF CUT&RESO"),
        KaossProgram("FLt.9", "LPF & REVERB", "REVERB DEPTH", "LPF CUT&RESO"),
        KaossProgram("FL.10", "HPF & REVERB", "REVERB DEPTH", "HPF CUT&RESO"),
        // 2 SPACE — дилеи, ревербы и особые программы KP3+
        KaossProgram("dLY.1", "DELAY", "DELAY TIME", "DEPTH"),
        KaossProgram("dLY.2", "ECHO BREAK", "DELAY TIME", "FEEDBACK"),
        KaossProgram("dLY.6", "TAPE ECHO", "DELAY TIME", "DEPTH"),
        KaossProgram("dLY.7", "DUB ECHO", "DELAY TIME", "TONE"),
        KaossProgram("dL.11", "PING PONG DELAY", "DELAY TIME", "DEPTH"),
        KaossProgram("rEV.1", "PUMP REVERB", "REVERB TIME", "PUMP DEPTH"),
        KaossProgram("rEV.2", "FREEZE REVERB", "TONE", "MIX"),
        KaossProgram("rEV.7", "GATE REVERB", "GATE TIME", "DEPTH"),
        KaossProgram("Mod.3", "JET +", "DELAY TIME", "FEEDBACK"),
        KaossProgram("Grn.1", "GRAIN SHIFTER", "CYCLE SPEED", "LENGTH"),
        // 3 REPEAT — LoP.1 … LP.10
        KaossProgram("LoP.1", "LOOPER FWD/REV", "LOOPER BEAT", "REV < > FWD"),
        KaossProgram("LoP.2", "BREAK LOOPER", "LOOPER BEAT", "BREAK TIME"),
        KaossProgram("LoP.3", "LOOPER & REV DELAY", "LOOPER BEAT", "HPF CUTOFF"),
        KaossProgram("LoP.4", "FREEZE LOOPER", "LOOPER BEAT", "MIX"),
        KaossProgram("LoP.5", "HIGH LOOPER", "LOOPER BEAT", "MIX"),
        KaossProgram("LoP.6", "SHUTTLE LOOPER", "LOOPER BEAT", "LPF CUTOFF"),
        KaossProgram("LoP.7", "SLICE LOOPER", "LOOPER BEAT", "SLICE POS"),
        KaossProgram("LoP.8", "LOOP BREAKER", "LOOPER BEAT", "SCRATCH"),
        KaossProgram("LoP.9", "KP2 LOOPER", "LOOPER BEAT", "LPF CUTOFF"),
        KaossProgram("LP.10", "VINYL LOOPER", "LOOPER BEAT", "REV < > FWD"),
        // 4 MOD — Sweep Noise + программы Modulation KP3+
        KaossProgram("SWP.1", "SWEEP NOISE", "SWEEP", "TAIL (DLY+RVB)"),
        KaossProgram("Mod.1", "VINYL BREAK", "BREAK TIME", "SCRATCH"),
        KaossProgram("Mod.2", "BREAK REVERB", "BREAK TIME", "SCRATCH"),
        KaossProgram("Md.15", "RING MOD & HPF", "RING MOD FREQ", "HPF CUT&RESO"),
        KaossProgram("Mod.5", "MANUAL PHASER", "FREQUENCY", "RESONANCE"),
        KaossProgram("Mod.6", "TALK FILTER", "1ST FORMANT", "2ND FORMANT"),
        KaossProgram("Mod.7", "DIGI TALK", "1ST FORMANT", "2ND FORMANT"),
        KaossProgram("Mod.8", "DUCKING COMP", "SENSITIVITY", "DEPTH"),
        KaossProgram("Mod.9", "COMPRESSOR", "SENSITIVITY", "ATTACK"),
        KaossProgram("Md.18", "PITCH SHIFTER & DELAY", "PITCH SHIFT", "DELAY DEPTH"),
        // 5 LFO — LFO.1 … LF.10 (вместо LFO.8 — LF.26)
        KaossProgram("LFO.1", "JAG FILTER", "LFO SPEED", "REV < > FWD"),
        KaossProgram("LFO.2", "LFO LPF", "LFO SPEED", "RESONANCE"),
        KaossProgram("LFO.3", "LFO HPF", "LFO SPEED", "RESONANCE"),
        KaossProgram("LFO.4", "LFO BPF+", "LFO SPEED", "RESONANCE"),
        KaossProgram("LFO.5", "LFO HPF+ & DELAY", "DELAY TIME", "HPF & DELAY"),
        KaossProgram("LFO.6", "INFINITE LFO HPF+", "LFO SPEED", "RESONANCE"),
        KaossProgram("LFO.7", "RANDOM LFO LPF", "LFO SPEED", "LFO DEPTH"),
        KaossProgram("LF.26", "AUDIO GATE & LPF", "GATE PATTERN", "LPF CUT&RESO"),
        KaossProgram("LoF.1", "BIT CRUSH", "RATE", "BITS"),
        KaossProgram("LoF.2", "DISTORTION", "DRIVE", "TONE"),
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
