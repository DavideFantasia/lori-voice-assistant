package com.example.localvoice.actions

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

class BluetoothAction(private val context: Context) {

    /**
     * A partire da Android 13 (API 33), BluetoothAdapter.enable()/disable()
     * sono no-op per qualunque app non privilegiata: ritornano SEMPRE false,
     * indipendentemente dai permessi concessi. È una restrizione voluta da
     * Google (le app non possono più alterare silenziosamente lo stato del
     * Bluetooth), non un bug risolvibile lato nostro.
     *
     * Quindi: se il dispositivo è Android 13+, saltiamo direttamente
     * all'apertura delle impostazioni Bluetooth di sistema — è l'unica
     * strada disponibile per qualunque app di terze parti su Android
     * moderno, incluso questo assistente.
     *
     * @return true se il Bluetooth è stato effettivamente attivato/disattivato
     *         a livello di codice (possibile solo su Android 12 e precedenti),
     *         false se invece è stato aperto il pannello impostazioni per
     *         completamento manuale dall'utente.
     */
    fun setEnabled(turnOn: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            openBluetoothSettings()
            return false
        }

        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            openBluetoothSettings()
            return false
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            openBluetoothSettings()
            return false
        }

        val toggled = try {
            @Suppress("DEPRECATION")
            if (turnOn) adapter.enable() else adapter.disable()
        } catch (e: SecurityException) {
            false
        }

        if (!toggled) openBluetoothSettings()
        return toggled
    }

    private fun openBluetoothSettings() {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
