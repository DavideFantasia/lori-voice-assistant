package com.example.localvoice
import kotlinx.coroutines.flow.MutableStateFlow

object VoiceUIState {
    val transcriptionFlow = MutableStateFlow("")
    val audioVolumeFlow = MutableStateFlow(0f)

    fun reset() {
        transcriptionFlow.value = ""
        audioVolumeFlow.value = 0f
    }
}