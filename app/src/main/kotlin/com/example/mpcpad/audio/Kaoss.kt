package com.example.mpcpad.audio

/** Программа KAOSS: имя и что делают оси. Порядок ДОЛЖЕН совпадать с KaossProgram в KaossFx.h. */
class KaossProgram(val name: String, val x: String, val y: String)

object Kaoss {
    /** Наборы на кнопках 1–5, по 4 программы; повторное нажатие кнопки листает программы набора. */
    val BANKS = listOf("FILTER", "SPACE", "REPEAT", "MOD", "LO-FI")
    const val PER_BANK = 4

    val PROGRAMS = listOf(
        KaossProgram("LOW PASS", "CUTOFF", "RESO"),
        KaossProgram("HIGH PASS", "CUTOFF", "RESO"),
        KaossProgram("BAND PASS", "FREQ", "Q"),
        KaossProgram("DJ FILTER", "LP < > HP", "RESO"),
        KaossProgram("DELAY", "TIME (SYNC)", "FEEDBACK"),
        KaossProgram("PING PONG", "TIME (SYNC)", "FEEDBACK"),
        KaossProgram("REVERB", "SIZE", "MIX"),
        KaossProgram("DUB ECHO", "TIME", "FEEDBACK"),
        KaossProgram("BEAT REPEAT", "1/4 > 1/64", "MIX"),
        KaossProgram("ROLL", "START LEN", "MIX"),
        KaossProgram("REVERSE", "LENGTH", "MIX"),
        KaossProgram("TAPE STOP", "BRAKE TIME", "DEPTH"),
        KaossProgram("FLANGER", "RATE", "FEEDBACK"),
        KaossProgram("PHASER", "RATE", "FEEDBACK"),
        KaossProgram("RING MOD", "FREQ", "MIX"),
        KaossProgram("TRANCE GATE", "RATE (SYNC)", "DEPTH"),
        KaossProgram("BIT CRUSH", "RATE", "BITS"),
        KaossProgram("DISTORTION", "DRIVE", "TONE"),
        KaossProgram("LO-FI RADIO", "NARROW", "NOISE"),
        KaossProgram("PITCH", "-12 > +12", "MIX"),
    )
}
