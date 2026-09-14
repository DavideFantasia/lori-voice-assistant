package com.example.localvoice.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.localvoice.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class SpotifyAction(private val context: Context) {

    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    // Cache in memoria del token per evitare di richiederlo ad ogni comando
    private var cachedToken: String? = null
    private var tokenExpiryTime: Long = 0

    fun playTopResult(query: String, onComplete: (Boolean) -> Unit) {
        scope.launch {
            try {
                // 1. Ottieni il Token di accesso (usando la cache se ancora valido)
                val token = getAccessToken()
                if (token == null) {
                    Log.e(TAG, "Impossibile ottenere il token di Spotify. Controlla Client ID e Secret.")
                    onComplete(false)
                    return@launch
                }

                // 2. Esegui la ricerca della traccia
                val encodedQuery = Uri.encode(query)
                val searchUrl = "https://api.spotify.com/v1/search?q=$encodedQuery&type=track&limit=1"

                val searchRequest = Request.Builder()
                    .url(searchUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                client.newCall(searchRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Errore nella ricerca Spotify: ${response.code}")
                        onComplete(false)
                        return@launch
                    }

                    val responseBody = response.body?.string() ?: return@launch
                    val json = JSONObject(responseBody)
                    val tracks = json.optJSONObject("tracks")
                    val items = tracks?.optJSONArray("items")

                    if (items != null && items.length() > 0) {
                        val firstTrack = items.getJSONObject(0)
                        val trackUri = firstTrack.optString("uri") // es. "spotify:track:..."

                        if (trackUri.isNotEmpty()) {
                            // 3. Apri l'app Spotify con l'URI ufficiale
                            openSpotifyUri(trackUri)
                            onComplete(true)
                            return@launch
                        }
                    }

                    // Nessun risultato trovato
                    onComplete(false)
                }

            } catch (e: IOException) {
                Log.e(TAG, "Errore di rete durante la chiamata a Spotify", e)
                onComplete(false)
            }
        }
    }

    private fun getAccessToken(): String? {
        // Se il token è ancora valido (con 5 minuti di margine), riusalo
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiryTime) {
            return cachedToken
        }

        val clientId = BuildConfig.SPOTIFY_CLIENT_ID
        val clientSecret = BuildConfig.SPOTIFY_CLIENT_SECRET

        if (clientId.isBlank() || clientSecret.isBlank()) {
            Log.e(TAG, "Client ID o Client Secret di Spotify non configurati in local.properties!")
            return null
        }

        val credentials = "$clientId:$clientSecret"
        val base64Credentials = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)

        val mediaType = "application/x-www-form-urlencoded".toMediaType()
        val body = "grant_type=client_credentials".toRequestBody(mediaType)

        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .addHeader("Authorization", "Basic $base64Credentials")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null

                val responseString = response.body?.string() ?: return null
                val json = JSONObject(responseString)

                cachedToken = json.optString("access_token")
                val expiresInSeconds = json.optLong("expires_in", 3600)

                // Imposta la scadenza (sottraggono 300 secondi di sicurezza)
                tokenExpiryTime = System.currentTimeMillis() + ((expiresInSeconds - 300) * 1000)

                return cachedToken
            }
        } catch (e: Exception) {
            Log.e(TAG, "Eccezione richiesta token Spotify", e)
            return null
        }
    }

    private fun openSpotifyUri(uriString: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    companion object {
        private const val TAG = "LocalVoiceSpotify"
    }
}