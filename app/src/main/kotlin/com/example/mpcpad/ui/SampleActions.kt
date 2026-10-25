// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

package com.example.mpcpad.ui

/** Действия, которым нужна Activity (разрешения, диалог MediaProjection, выбор файла). */
interface SampleActions {
    fun startRecording()
    fun stopRecording()
    /** Импорт файла в редактор. */
    fun importFile()
    /** Импорт файла сразу на пад [pad]. */
    fun importToPad(pad: Int)
    /** Взвести захват для записи в пады удержанием (источник — [AppViewModel.sampleSource]). */
    fun armPadCapture()
    fun disarmPadCapture()
    /** SETUP → SAVE BANK: выбрать, куда сохранить файл банка [bank] (0–3). */
    fun saveBank(bank: Int)
    /** SETUP → IMPORT BANK: выбрать файл банка и загрузить его в банк [bank] (0–3). */
    fun importBank(bank: Int)
    /** PRJCT: выбрать, куда сохранить проект (.sal). */
    fun saveProject()
    /** PRJCT: выбрать файл проекта .sal и открыть его. */
    fun openProject()
}
