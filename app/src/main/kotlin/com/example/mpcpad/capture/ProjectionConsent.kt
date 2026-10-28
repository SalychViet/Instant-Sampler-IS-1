// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.capture

import android.content.Intent

/**
 * Разрешение на захват звука телефона (системное окно MediaProjection), выданное в этом запуске
 * приложения. Пока оно есть, переключение источника на звук телефона не показывает окно снова
 * (до Android 13 включительно разрешение можно использовать повторно). Отказ системы — забываем
 * и спрашиваем заново.
 */
object ProjectionConsent {
    @Volatile var resultCode = 0
        private set
    @Volatile var data: Intent? = null
        private set

    val granted: Boolean get() = data != null

    fun save(code: Int, intent: Intent) {
        resultCode = code
        data = Intent(intent)
    }

    fun clear() {
        resultCode = 0
        data = null
    }
}
