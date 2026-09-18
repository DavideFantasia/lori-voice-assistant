package com.example.localvoice.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

class CallAction(private val context: Context) {

    fun call(contactName: String): String {
        val contentResolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        // Cerca il nome usando LIKE (case insensitive) per permettere match parziali
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
        } catch (e: SecurityException) {
            Log.e("CallAction", "Permesso READ_CONTACTS mancante", e)
            return "Non ho il permesso per leggere la rubrica"
        }

        if (phoneNumber != null) {
            return try {
                // Prova ad avviare la chiamata diretta
                val intent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Chiamo $matchedName"
            } catch (e: SecurityException) {
                // Fallback: se manca il permesso di chiamata diretta, apre il tastierino numerico
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Preparo la chiamata per $matchedName"
            }
        }

        return "Non ho trovato $contactName in rubrica"
    }
}