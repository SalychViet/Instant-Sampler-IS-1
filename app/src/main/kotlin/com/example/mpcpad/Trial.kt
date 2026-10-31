// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * ISL-1 TRY: все функции на [DAYS] дней с первого запуска, потом приложение не работает.
 * Перевод даты назад не продлевает срок: считаем от самого позднего времени, которое уже видели.
 * Проверка только на телефоне: без сервера переустановка начинает срок заново.
 */
object Trial {
    const val DAYS = BuildConfig.TRIAL_DAYS
    val ON = DAYS > 0
    private const val DAY_MS = 86_400_000L

    var expired by mutableStateOf(false)
        private set
    var daysLeft by mutableStateOf(DAYS)
        private set

    /** Обновить срок. Вызывать при запуске, при возврате в приложение и изредка, пока оно открыто. */
    fun check(context: Context) {
        if (!ON) return
        val p = context.getSharedPreferences("trial", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        val first = minOf(p.getLong("first", now), installed)
        val last = maxOf(p.getLong("last", now), now)
        val over = p.getBoolean("over", false) || last - first >= DAYS * DAY_MS
        p.edit().putLong("first", first).putLong("last", last).putBoolean("over", over).apply()
        expired = over
        daysLeft = (DAYS - ((last - first) / DAY_MS).toInt()).coerceIn(0, DAYS)
    }
}
