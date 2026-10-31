// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mpcpad.Trial

/** ISL-1 TRY: срок вышел — вместо приложения этот экран. */
@Composable
fun TrialExpiredScreen() {
    Box(Modifier.fillMaxSize().background(Color(0xFF0E0F11)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("ISL-1 TRY", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Пробный период закончился", color = Color(0xFFFFB020), fontSize = 18.sp)
            Text(
                "${Trial.DAYS} дней пробной версии истекли, приложение больше не работает.",
                color = Color(0xFFB8BCC2),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
