package com.example.localvoice.actions

import android.content.Context
import android.content.Intent
import android.net.Uri

class WebSearchAction(private val context: Context) {

    fun search(query: String) {
        // Nessuna API key, nessun tracciamento: apre semplicemente il browser
        // predefinito dell'utente (idealmente un motore privacy-friendly
        // impostato di default sul dispositivo, es. DuckDuckGo/Startpage).
        val uri = Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(query))
        val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(browserIntent)
    }
}
