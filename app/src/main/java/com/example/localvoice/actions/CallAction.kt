package com.example.localvoice.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

class CallAction(private val context: Context) {

    fun call(contactName: String): String {
        // Controllo preventivo del permesso di lettura rubrica
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.e("CallAction", "Permesso READ_CONTACTS mancante")
            return "Non ho il permesso per leggere la rubrica"
        }

        val contentResolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$contactName%")

        var phoneNumber: String? = null
        var matchedName: String? = null

        try {
            contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    matchedName = cursor.getString(nameIndex)
                    phoneNumber = cursor.getString(numberIndex)
                }
            }
        } catch (e: Exception) {
            Log.e("CallAction", "Errore durante la lettura dei contatti", e)
            return "Si è verificato un errore nella lettura della rubrica"
        }

        if (phoneNumber != null) {
            return try {
                val intent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Chiamo $matchedName"
            } catch (e: SecurityException) {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Preparo la chiamata per $matchedName, non ho il permesso per avviarla direttamente"
            }
        }

        return "Non ho trovato $contactName in rubrica"
    }
}