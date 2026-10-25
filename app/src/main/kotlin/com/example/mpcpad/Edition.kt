package com.example.mpcpad

/**
 * Версия приложения: Pro (полная) или Free (бесплатная). Ограничения Free:
 * один банк (A) — 16 падов и 16 паттернов, паттерн до 2 тактов, песня до 4 паттернов,
 * в каждом наборе KAOSS программы 1–5, одна память MOTION, без записи эффектов,
 * RESAMPLE до 10 с, EXPORT только MP3 128 кбит/с и до 1 минуты, без сохранения/загрузки проектов
 * и сохранения банков, без Ableton Link.
 */
object Edition {
    val FREE: Boolean = BuildConfig.FREE

    val banks get() = if (FREE) 1 else 4
    val maxBars get() = if (FREE) 2 else 4
    val maxSong get() = if (FREE) 4 else Int.MAX_VALUE
    val kaossPerBank get() = if (FREE) 5 else 10
    val motions get() = if (FREE) 1 else 4
    val resampleSeconds get() = if (FREE) 10 else 30
    val exportSeconds get() = if (FREE) 60 else 600
    val mp3Kbps get() = if (FREE) 128 else 320

    /** Сообщение на экране, когда нажали то, что есть только в Pro. */
    fun pro(what: String) = "$what · IS-1 PRO"
}
