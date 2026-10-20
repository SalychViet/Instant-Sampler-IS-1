package com.example.mpcpad.audio

/** Программа KAOSS: имя и что делают оси. Порядок ДОЛЖЕН совпадать с KaossProgram в KaossFx.h. */
class KaossProgram(val name: String, val x: String, val y: String)

object Kaoss {
    /** Наборы на кнопках 1–5, по 10 программ; повторное нажатие кнопки листает программы набора. */
    val BANKS = listOf("FILTER", "SPACE", "REPEAT", "MOD", "LO-FI")
    const val PER_BANK = 10

    val PROGRAMS = listOf(
        // 1 FILTER
        KaossProgram("LOW PASS", "CUTOFF", "RESO"),
        KaossProgram("HIGH PASS", "CUTOFF", "RESO"),
        KaossProgram("BAND PASS", "FREQ", "Q"),
        KaossProgram("DJ FILTER", "LP < > HP", "RESO"),
        KaossProgram("NOTCH", "FREQ", "WIDTH"),
        KaossProgram("COMB", "PITCH", "RESO"),
        KaossProgram("TALK (VOWEL)", "A-E-I-O-U", "RESO"),
        KaossProgram("AUTO FILTER", "RATE (SYNC)", "DEPTH"),
        KaossProgram("ENV FILTER", "SENS", "RESO"),
        KaossProgram("ISOLATOR", "LOW|MID|HIGH", "KILL"),
        // 2 SPACE
        KaossProgram("DELAY", "TIME (SYNC)", "FEEDBACK"),
        KaossProgram("PING PONG", "TIME (SYNC)", "FEEDBACK"),
        KaossProgram("REVERB", "SIZE", "MIX"),
        KaossProgram("DUB ECHO", "TIME", "FEEDBACK"),
        KaossProgram("TAPE ECHO", "TIME", "FEEDBACK"),
        KaossProgram("MULTI TAP", "SPACING", "FEEDBACK"),
        KaossProgram("HALL", "SIZE", "BRIGHT"),
        KaossProgram("ROOM", "SIZE", "BRIGHT"),
        KaossProgram("GATED VERB", "GATE TIME", "SIZE"),
        KaossProgram("FREEZE", "LEVEL", "HOLD"),
        // 3 REPEAT
        KaossProgram("BEAT REPEAT", "1/4 > 1/64", "MIX"),
        KaossProgram("ROLL", "START LEN", "MIX"),
        KaossProgram("REVERSE", "LENGTH", "MIX"),
        KaossProgram("TAPE STOP", "BRAKE TIME", "DEPTH"),
        KaossProgram("GLITCH", "GRID", "AMOUNT"),
        KaossProgram("SCRATCH", "NEEDLE", "CUT DRY"),
        KaossProgram("SLICER", "RATE (SYNC)", "PATTERN"),
        KaossProgram("PITCH REPEAT", "1/4 > 1/64", "PITCH DROP"),
        KaossProgram("SPINBACK", "SPEED", "FADE"),
        KaossProgram("BAR LOOP", "1-2-4-8 BEATS", "—"),
        // 4 MOD
        KaossProgram("FLANGER", "RATE", "FEEDBACK"),
        KaossProgram("PHASER", "RATE", "FEEDBACK"),
        KaossProgram("RING MOD", "FREQ", "MIX"),
        KaossProgram("SWEEP NOISE", "SWEEP", "LEVEL"),
        KaossProgram("CHORUS", "RATE", "DEPTH"),
        KaossProgram("TREMOLO", "RATE (SYNC)", "DEPTH"),
        KaossProgram("AUTO PAN", "RATE (SYNC)", "WIDTH"),
        KaossProgram("VIBRATO", "RATE", "DEPTH"),
        KaossProgram("ROTARY", "SLOW > FAST", "DEPTH"),
        KaossProgram("SIREN", "PITCH", "WAIL SPEED"),
        // 5 LO-FI
        KaossProgram("BIT CRUSH", "RATE", "BITS"),
        KaossProgram("DISTORTION", "DRIVE", "TONE"),
        KaossProgram("LO-FI RADIO", "NARROW", "NOISE"),
        KaossProgram("PITCH", "-12 > +12", "MIX"),
        KaossProgram("FUZZ", "DRIVE", "TONE"),
        KaossProgram("WAVEFOLDER", "FOLD", "TONE"),
        KaossProgram("VINYL", "WOW", "AGE"),
        KaossProgram("PUMP", "DEPTH", "RELEASE"),
        KaossProgram("OCTAVER", "-1 > -2 OCT", "SUB LEVEL"),
        KaossProgram("ROBOT", "PITCH", "MIX"),
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
