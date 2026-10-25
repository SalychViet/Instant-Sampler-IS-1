// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PadCaptureState(
    val armed: Boolean = false,
    val arming: Boolean = false,
    val source: CaptureSource = CaptureSource.MIC,
    val message: String? = null,
)

/** Режим «захват на пэд»: держишь пэд — пишется звук, отпустил — семпл на пэде. */
object PadCapture {
    @Volatile
    private var capture: ArmedCapture? = null

    private val _state = MutableStateFlow(PadCaptureState())
    val state: StateFlow<PadCaptureState> = _state.asStateFlow()

    val isArmed: Boolean get() = capture != null
    val isBusy: Boolean get() = capture != null || _state.value.arming
    val holdingPad: Int get() = capture?.holdingPad ?: -1
    val heldMs: Long get() = capture?.heldMs ?: 0L
    val silentHeldMs: Long get() = capture?.silentHeldMs ?: 0L
    val level: Float get() = capture?.level ?: 0f

    fun markArming(source: CaptureSource) {
        _state.value = PadCaptureState(arming = true, source = source)
    }

    fun cancelArming(message: String?) {
        _state.value = PadCaptureState(message = message)
    }

    fun attach(c: ArmedCapture, source: CaptureSource) {
        capture = c
        _state.value = PadCaptureState(armed = true, source = source)
    }

    fun detach(error: String?) {
        capture = null
        _state.value = PadCaptureState(message = error)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun begin(pad: Int) {
        capture?.begin(pad)
    }

    fun end() {
        capture?.end()
    }
}
