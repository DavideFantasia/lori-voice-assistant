package com.example.localvoice.service

import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService


/**
 * Classe per la creazione di un pulsante nei Quick Settings per attivare e disattivare
 * l'ascolto passivo dell'app direttamente dalla barra dei quick settings
 */

class ListeningTileService : TileService() {

    private val prefs by lazy {
        getSharedPreferences("lori_prefs", Context.MODE_PRIVATE)
    }

    // Chiamato ogni volta che il pannello Quick Settings diventa visibile
    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    // Chiamato quando l'utente tocca il pulsante nel menù a tendina
    override fun onClick() {
        super.onClick()

        // Recupera lo stato attuale, partendo da false come default
        val isCurrentlyEnabled = prefs.getBoolean("is_listening_enabled", false)
        val newState = !isCurrentlyEnabled

        // Salva il nuovo stato nelle SharedPreferences, replicando la logica della MainActivity
        prefs.edit().putBoolean("is_listening_enabled", newState).apply()

        // Avvia o ferma il servizio in background
        val intent = Intent(this, ListeningForegroundService::class.java)
        if (newState) {
            startForegroundService(intent) // Richiede che i permessi siano già stati concessi
        } else {
            stopService(intent) //[cite: 1]
        }

        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isEnabled = prefs.getBoolean("is_listening_enabled", false)

        if (isEnabled) {
            tile.state = Tile.STATE_ACTIVE
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                tile.subtitle = "Attivo"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                tile.subtitle = "Spento"
            }
        }

        // Applica visivamente le modifiche al pulsante
        tile.updateTile()
    }
}