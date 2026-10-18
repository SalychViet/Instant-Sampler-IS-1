package com.example.mpcpad.audio

/** Индексы параметров. Должны совпадать с enum ParamId в Types.h. */
object PadParam {
    const val VOLUME = 0
    const val PAN = 1
    const val PITCH = 2
    const val START = 3
    const val END = 4
    const val MODE = 5
    const val REVERSE = 6
    const val CHOKE = 7
    const val ATTACK_MS = 8
    const val RELEASE_MS = 9
}

/** Должны совпадать с PlayMode в Types.h. */
object PlayMode {
    const val ONE_SHOT = 0
    const val GATE = 1
    const val LOOP = 2
}

/** Операции секвенсора. Должны совпадать с enum SeqOp в Sequencer.h. */
object SeqOp {
    const val PLAY = 0
    const val STOP = 1
    const val SET_RECORDING = 2
    const val SET_BPM = 3
    const val SET_QUANTIZE = 4
    const val SET_METRONOME = 5
    const val SET_COUNT_IN = 6
    const val SELECT = 7
    const val TOGGLE_STEP = 8
    const val CLEAR_PATTERN = 9
    const val ERASE_PAD = 10
    const val SET_LENGTH = 11
    const val COPY_PATTERN = 12
    const val SET_LOCK = 13
    const val CLEAR_LOCKS = 14
    const val SET_LOCKS_ENABLED = 15
}

/**
 * Параметры шага (P-LOCK), упакованные в 63 бита так же, как packLocks в Sequencer.cpp:
 * [0..6] маска, vol 7, pan 7, pitch 6, start 10, end 10, attack 8, release 8.
 */
object StepLocks {
    private val PARAMS = intArrayOf(
        PadParam.VOLUME, PadParam.PAN, PadParam.PITCH, PadParam.START, PadParam.END, PadParam.ATTACK_MS, PadParam.RELEASE_MS,
    )
    private val BITS = intArrayOf(7, 7, 6, 10, 10, 8, 8)

    /** paramId → значение для закреплённых параметров. */
    fun decode(w: Long): Map<Int, Float> {
        val mask = (w and 0x7F).toInt()
        if (mask == 0) return emptyMap()
        val out = HashMap<Int, Float>()
        var shift = 7
        for (i in PARAMS.indices) {
            val bits = BITS[i]
            val maxv = ((1 shl bits) - 1).toFloat()
            val n = ((w ushr shift) and ((1L shl bits) - 1)).toFloat() / maxv
            if (mask and (1 shl i) != 0) {
                out[PARAMS[i]] = when (i) {
                    0 -> n * 2f
                    1 -> n * 2f - 1f
                    2 -> Math.round(-24f + n * 63f).toFloat()
                    3, 4 -> n
                    5 -> n * 255f
                    else -> 5f + n * n * 1995f
                }
            }
            shift += bits
        }
        return out
    }
}

const val PPQ = 96                 // тиков на четвертную (kPpq в C++)
const val BAR_TICKS = PPQ * 4

/** Одно событие паттерна. */
data class SeqNote(val tick: Int, val pad: Int, val velocity: Int, val length: Int, val locks: Long = 0L)

/** Снимок состояния транспорта (из упакованного слова нативного секвенсора). */
data class SeqState(
    val position: Int = 0,        // следующий тик внутри паттерна
    val playing: Boolean = false,
    val recording: Boolean = false,
    val countIn: Boolean = false,
    val pattern: Int = 0,
    val queued: Int = -1,         // -1 = нет очереди
    val countInTicksLeft: Int = 0,
    val linkWaiting: Boolean = false,   // Link: ждём границу такта сессии
) {
    companion object {
        fun from(w: Long) = SeqState(
            position = (w and 0xFFFF).toInt(),
            playing = (w shr 16) and 1L != 0L,
            recording = (w shr 17) and 1L != 0L,
            countIn = (w shr 18) and 1L != 0L,
            pattern = ((w shr 24) and 0xFF).toInt(),
            queued = ((w shr 32) and 0xFF).toInt() - 1,
            countInTicksLeft = ((w shr 40) and 0xFFFF).toInt(),
            linkWaiting = (w shr 19) and 1L != 0L,
        )
    }
}

/**
 * Тонкая обёртка над нативным движком. Пады 0..63 (банк * 16 + пад).
 * Все вызовы неблокирующие: просто кладут команду в lock-free очередь.
 */
object AudioEngine {
    init { System.loadLibrary("mpcengine") }

    @Volatile private var handle = 0L

    @Synchronized fun create() { if (handle == 0L) handle = nativeCreate() }

    @Synchronized fun destroy() {
        val h = handle
        handle = 0L
        if (h != 0L) nativeDestroy(h)
    }

    fun start(): Boolean = handle != 0L && nativeStart(handle)
    fun stop() { if (handle != 0L) nativeStop(handle) }

    fun noteOn(pad: Int, velocity: Float) { if (handle != 0L) nativeNoteOn(handle, pad, velocity) }
    fun noteOff(pad: Int) { if (handle != 0L) nativeNoteOff(handle, pad) }
    fun allNotesOff() { if (handle != 0L) nativeAllNotesOff(handle) }
    fun setParam(pad: Int, id: Int, value: Float) { if (handle != 0L) nativeSetParam(handle, pad, id, value) }
    fun setMasterVolume(v: Float) { if (handle != 0L) nativeSetMaster(handle, v) }

    /** data: interleaved float PCM [-1..1], channels = 1 или 2. */
    fun loadSample(pad: Int, data: FloatArray, channels: Int, sampleRate: Int): Boolean =
        handle != 0L && nativeLoadSample(handle, pad, data, channels, sampleRate)

    // ---- секвенсор ----
    private fun seq(op: Int, pat: Int = 0, pad: Int = 0, a: Int = 0, b: Int = 0, f: Float = 0f) {
        if (handle != 0L) nativeSeq(handle, op, pat, pad, a, b, f)
    }

    fun seqPlay() = seq(SeqOp.PLAY)
    fun seqStop() = seq(SeqOp.STOP)
    fun seqSetRecording(on: Boolean) = seq(SeqOp.SET_RECORDING, a = if (on) 1 else 0)
    fun seqSetBpm(bpm: Float) = seq(SeqOp.SET_BPM, f = bpm)
    /** gridTicks: 0 = без квантизации; 24 = 1/16, 48 = 1/8, 96 = 1/4, 12 = 1/32, 16 = 1/16T. */
    fun seqSetQuantize(gridTicks: Int) = seq(SeqOp.SET_QUANTIZE, a = gridTicks)
    /** mode: 0 выкл, 1 всегда, 2 только при записи. */
    fun seqSetMetronome(mode: Int, level: Float) = seq(SeqOp.SET_METRONOME, a = mode, f = level)
    fun seqSetCountIn(bars: Int) = seq(SeqOp.SET_COUNT_IN, a = bars)
    /** immediate = true — переключить сразу, иначе в конце текущего цикла (если секвенсор играет). */
    fun seqSelect(pattern: Int, immediate: Boolean = false) =
        seq(SeqOp.SELECT, pat = pattern, a = if (immediate) 1 else 0)
    fun seqToggleStep(pattern: Int, pad: Int, startTick: Int, widthTicks: Int, velocity: Float = 0.8f) =
        seq(SeqOp.TOGGLE_STEP, pat = pattern, pad = pad, a = startTick, b = widthTicks, f = velocity)
    fun seqClearPattern(pattern: Int) = seq(SeqOp.CLEAR_PATTERN, pat = pattern)
    fun seqErasePad(pattern: Int, pad: Int) = seq(SeqOp.ERASE_PAD, pat = pattern, pad = pad)
    fun seqSetLength(pattern: Int, bars: Int) = seq(SeqOp.SET_LENGTH, pat = pattern, a = bars)
    /** Копия паттерна целиком (ноты, параметры шагов, длина). */
    fun seqCopyPattern(from: Int, to: Int) = seq(SeqOp.COPY_PATTERN, pat = to, a = from)
    /** P-LOCK: закрепить значение параметра за нотами пада в шаге [tick, tick+width). */
    fun seqSetLock(pattern: Int, pad: Int, tick: Int, width: Int, paramId: Int, value: Float) =
        seq(SeqOp.SET_LOCK, pat = pattern, pad = pad, a = tick, b = width or (paramId shl 16), f = value)
    fun seqClearLocks(pattern: Int, pad: Int, tick: Int, width: Int) =
        seq(SeqOp.CLEAR_LOCKS, pat = pattern, pad = pad, a = tick, b = width)
    fun seqSetLocksEnabled(on: Boolean) = seq(SeqOp.SET_LOCKS_ENABLED, a = if (on) 1 else 0)

    fun seqState(): SeqState = if (handle != 0L) SeqState.from(nativeSeqState(handle)) else SeqState()
    fun seqPatternBars(pattern: Int): Int = if (handle != 0L) nativeSeqPatternBars(handle, pattern) else 1

    private val countsBuf = IntArray(64)
    /** Число нот в каждом из 64 паттернов (копия; вызывать из одного потока). */
    @Synchronized fun seqPatternCounts(): IntArray {
        if (handle != 0L) nativeSeqPatternCounts(handle, countsBuf)
        return countsBuf.copyOf()
    }

    private val snapshotBuf = LongArray(768)
    private val locksBuf = LongArray(768)
    /** Снимок паттерна; null — не удалось получить согласованный (повторить позже). Вызывать из одного потока. */
    @Synchronized fun seqReadPattern(pattern: Int): List<SeqNote>? {
        if (handle == 0L) return emptyList()
        val n = nativeSeqCopyPatternLocks(handle, pattern, snapshotBuf, locksBuf)
        if (n < 0) return null
        return List(n) {
            val w = snapshotBuf[it]
            SeqNote(
                tick = (w and 0xFFFF).toInt(),
                pad = ((w shr 16) and 0xFF).toInt(),
                velocity = ((w shr 24) and 0xFF).toInt(),
                length = ((w shr 32) and 0xFFFF).toInt(),
                locks = locksBuf[it],
            )
        }
    }

    // ---- Ableton Link ----
    /** С Link старт/стоп и темп становятся общими для сессии (seqPlay/seqStop/seqSetBpm уходят в Link). */
    fun linkSetEnabled(on: Boolean) { if (handle != 0L) nativeLinkSetEnabled(handle, on) }
    /** Сдвиг относительно сетки Link, мс: плюс — звук раньше. */
    fun linkSetLatency(ms: Float) { if (handle != 0L) nativeLinkSetLatency(handle, ms) }
    fun linkPeers(): Int = if (handle != 0L) nativeLinkPeers(handle) else 0
    fun linkTempo(): Float = if (handle != 0L) nativeLinkTempo(handle) else 120f

    /** Бит i = пад i сейчас звучит. */
    fun activeMask(): Long = if (handle != 0L) nativeActiveMask(handle) else 0L
    fun masterPeak(): Float = if (handle != 0L) nativeMasterPeak(handle) else 0f
    fun activeVoices(): Int = if (handle != 0L) nativeActiveVoices(handle) else 0
    fun xruns(): Int = if (handle != 0L) nativeXRunCount(handle) else 0
    fun describe(): String = if (handle != 0L) nativeDescribe(handle) else "no engine"

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(h: Long)
    private external fun nativeStart(h: Long): Boolean
    private external fun nativeStop(h: Long)
    private external fun nativeNoteOn(h: Long, pad: Int, vel: Float): Boolean
    private external fun nativeNoteOff(h: Long, pad: Int): Boolean
    private external fun nativeAllNotesOff(h: Long)
    private external fun nativeSetParam(h: Long, pad: Int, id: Int, value: Float): Boolean
    private external fun nativeSetMaster(h: Long, v: Float): Boolean
    private external fun nativeLoadSample(h: Long, pad: Int, data: FloatArray, channels: Int, rate: Int): Boolean
    private external fun nativeActiveMask(h: Long): Long
    private external fun nativeMasterPeak(h: Long): Float
    private external fun nativeActiveVoices(h: Long): Int
    private external fun nativeSeq(h: Long, op: Int, pat: Int, pad: Int, a: Int, b: Int, f: Float): Boolean
    private external fun nativeSeqState(h: Long): Long
    private external fun nativeSeqCopyPattern(h: Long, pat: Int, out: LongArray): Int
    private external fun nativeSeqCopyPatternLocks(h: Long, pat: Int, out: LongArray, locks: LongArray): Int
    private external fun nativeSeqPatternBars(h: Long, pat: Int): Int
    private external fun nativeSeqPatternCounts(h: Long, out: IntArray)
    private external fun nativeLinkSetEnabled(h: Long, on: Boolean)
    private external fun nativeLinkSetLatency(h: Long, ms: Float)
    private external fun nativeLinkPeers(h: Long): Int
    private external fun nativeLinkTempo(h: Long): Float
    private external fun nativeXRunCount(h: Long): Int
    private external fun nativeDescribe(h: Long): String
}
