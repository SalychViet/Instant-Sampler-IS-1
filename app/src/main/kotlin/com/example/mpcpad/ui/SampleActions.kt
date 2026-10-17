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
}
