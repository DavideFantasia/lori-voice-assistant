package com.example.localvoice.actions

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/**
 * Invia eventi media-key di sistema (come un tasto fisico "next/previous"
 * su cuffie o auto) — vengono ricevuti dall'app che detiene attualmente il
 * focus della sessione media (Spotify, YouTube Music, un podcast player,
 * ecc.), senza dover integrare API specifiche per ciascuna app.
 */
class MediaAction(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun next() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    /** Pausa esplicita (non toggle): se già in pausa non fa nulla di visibile. */
    fun pause() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)

    private fun sendMediaKey(keyCode: Int) {
        val eventTime = System.currentTimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
    }
}
