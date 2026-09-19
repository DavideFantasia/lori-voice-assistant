package com.example.localvoice.actions

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

sealed class BluetoothResult {
    data object SuccessOn : BluetoothResult()
    data object SuccessOff : BluetoothResult()
    data object RequiresManualSettings : BluetoothResult()
    data object MissingPermission : BluetoothResult()
}

class BluetoothAction(private val context: Context) {

    fun setEnabled(turnOn: Boolean): BluetoothResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            openBluetoothSettings()
            return BluetoothResult.RequiresManualSettings
        }

        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED

        // Gestione esplicita del permesso mancante
        if (!hasPermission) {
            return BluetoothResult.MissingPermission
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            openBluetoothSettings()
            return BluetoothResult.RequiresManualSettings
        }

        val toggled = try {
            @Suppress("DEPRECATION")
            if (turnOn) adapter.enable() else adapter.disable()
        } catch (e: SecurityException) {
            false
        }

        return if (toggled) {
            if (turnOn) BluetoothResult.SuccessOn else BluetoothResult.SuccessOff
        } else {
            openBluetoothSettings()
            BluetoothResult.RequiresManualSettings
        }
    }

    private fun openBluetoothSettings() {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}