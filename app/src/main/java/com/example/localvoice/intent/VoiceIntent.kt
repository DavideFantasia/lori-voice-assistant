package com.example.localvoice.intent

sealed class VoiceIntent {
    data class WebSearch(val query: String) : VoiceIntent()
    data class SetTimer(val seconds: Long, val label: String? = null) : VoiceIntent()
    data class BluetoothToggle(val turnOn: Boolean) : VoiceIntent()
    data object NextTrack : VoiceIntent()
    data object PreviousTrack : VoiceIntent()
    data object PauseMedia : VoiceIntent()
    data class SetVolume(val percent: Int) : VoiceIntent()
    data class AdjustVolume(val increase: Boolean) : VoiceIntent()
    data class PlayMusic(val query: String) : VoiceIntent()
    data class CallContact(val contactName: String) : VoiceIntent()
    data object Unknown : VoiceIntent()
}
