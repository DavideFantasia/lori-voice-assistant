package com.example.localvoice

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.localvoice.service.ListeningForegroundService

// ========== UI ============
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.localvoice.ui.theme.LoriTheme
import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import android.content.SharedPreferences
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.DisposableEffect
// ============================

/**
 * Schermata principale — non fa parte del flusso "wake word invisibile",
 * serve per: (1) concedere i permessi/eccezioni necessarie, (2) testare
 * parser+azioni+TTS con testo digitato, indipendentemente da wake-word/STT
 * che sono ancora placeholder Noop, (3) avviare/fermare manualmente il
 * foreground service per osservare la cascata VAD in Logcat.
 */
class MainActivity : ComponentActivity() {
    // Definizione dei permessi in base alla versione di Android
    private var hasPromptedAssistant = false
    private var hasPromptedBattery = false
    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()

    // Registra il launcher per la richiesta dei permessi standard
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            Toast.makeText(
                this,
                "Permessi limitati. Lori potrebbe non rispondere.",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            // Se i permessi base sono concessi, passa a quelli speciali
            checkSpecialPermissions()
        }
    }

    // Launcher per il ruolo di Assistente Vocale
    private val assistantRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Dopo aver chiesto il ruolo di assistente, chiedi la batteria
        requestBatteryExemption()
    }

    private lateinit var prefs: SharedPreferences
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inizializza le preferenze
        prefs = getSharedPreferences("lori_prefs", Context.MODE_PRIVATE)
        val isListening = prefs.getBoolean("is_listening_enabled", false)
        val useSystemTheme = prefs.getBoolean("use_system_theme", false)
        // Allinea immediatamente il servizio al valore salvato
        // (così se era spento, si assicura che il servizio non parta/venga killato)
        toggleListeningService(isListening)

        setContent {
            // Stato reattivo per aggiornare il tema in tempo reale
            var themeState by remember { mutableStateOf(useSystemTheme) }

            LoriTheme(useSystemTheme = themeState) { // Passa lo stato al tema
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        initialListeningState = isListening,
                        onToggleListening = { isEnabled ->
                            prefs.edit().putBoolean("is_listening_enabled", isEnabled).apply()
                            toggleListeningService(isEnabled)
                        },
                        // Nuovi parametri per la gestione del tema
                        useSystemTheme = themeState,
                        onThemeChange = { isSystem ->
                            themeState = isSystem
                            prefs.edit().putBoolean("use_system_theme", isSystem).apply()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // 1. Controlla prima i permessi standard
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
            return // Si ferma qui finché l'utente non ha gestito i permessi base
        }
        //Controlla l'esenzione della Batteria
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            if (!hasPromptedBattery) {
                hasPromptedBattery = true
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Esenzione batteria da attivare manualmente", Toast.LENGTH_LONG).show()
                }
            }
        }
        //Controlla il Ruolo di Assistente
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            val isAssistant = roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true

            if (!isAssistant) {
                if (!hasPromptedAssistant) {
                    hasPromptedAssistant = true
                    Toast.makeText(this, "Imposta Lori come App di assistenza digitale", Toast.LENGTH_LONG).show()
                    try {
                        // Apre direttamente la schermata "App di assistenza digitale" / "Voice input"
                        startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                    } catch (e: Exception) {
                        // Fallback generico per le app predefinite
                        startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                    }
                }
                return // Si ferma qui. Quando l'utente torna all'app, onResume ripartirà da capo
            }
        }
    }

    private fun checkSpecialPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == false) {
                try {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                    assistantRoleLauncher.launch(intent)
                } catch (e: Exception) {
                    // Fallback se il RoleManager di sistema va in crash
                    val fallbackIntent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                    startActivity(fallbackIntent)
                    Toast.makeText(this, "Seleziona Lori come App Assistente Digitale", Toast.LENGTH_LONG).show()
                }
                return
            }
        }
        // se già assistente, passa alla batteria
        requestBatteryExemption()
    }

    // 3. Controllo e richiesta esenzione Batteria (Background)
    private fun requestBatteryExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Esenzione batteria da attivare manualmente nelle impostazioni", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun toggleListeningService(enable: Boolean) {
        val intent = Intent(this, ListeningForegroundService::class.java)
        if (enable) {
            startForegroundService(intent) // Richiede permessi gestiti in precedenza
        } else {
            stopService(intent)
        }
    }
}

@Composable
fun MainScreen(
    initialListeningState: Boolean,
    onToggleListening: (Boolean) -> Unit,
    useSystemTheme: Boolean,
    onThemeChange: (Boolean) -> Unit
) {
    var isListeningEnabled by remember { mutableStateOf(initialListeningState) }
    var ttsEngineName by remember { mutableStateOf("Verifica in corso...") }
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("lori_prefs", Context.MODE_PRIVATE)

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
            if (key == "is_listening_enabled") {
                // Aggiorna lo stato di Compose se la preferenza cambia da un'altra parte (es. Quick Settings)
                isListeningEnabled = sharedPrefs.getBoolean(key, false)
            }
        }

        prefs.registerOnSharedPreferenceChangeListener(listener)

        // Sincronizza subito lo stato in caso sia cambiato mentre l'app era in pausa
        isListeningEnabled = prefs.getBoolean("is_listening_enabled", false)

        onDispose {
            // Rimuove l'ascoltatore per evitare memory leak quando il composable viene distrutto
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    // Recupera dinamicamente il nome del TTS al primo avvio della UI
    LaunchedEffect(Unit) {
        try {
            // Inizializza temporaneamente il TTS con un listener vuoto
            val tts = TextToSpeech(context) {}
            val defaultEnginePackage = tts.defaultEngine
            val engines = tts.engines

            // Cerca il nome leggibile corrispondente al pacchetto predefinito
            ttsEngineName = engines?.firstOrNull { it.name == defaultEnginePackage }?.label
                ?: defaultEnginePackage
                        ?: "Nessun TTS di sistema"

            tts.shutdown() // Libera subito le risorse
        } catch (e: Exception) {
            ttsEngineName = "Sconosciuto"
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Lori", style = MaterialTheme.typography.headlineLarge)

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Text("Ascolto 'Hey Lori' in background")
                Switch(
                    checked = isListeningEnabled,
                    onCheckedChange = {
                        isListeningEnabled = it
                        onToggleListening(it)
                    }
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Stato Modelli", style = MaterialTheme.typography.titleMedium)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Riga STT
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Modello STT:", fontFamily = FontFamily.Monospace)
                        Text("Vosk IT - Pronto", color = MaterialTheme.colorScheme.secondary)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Nuova Riga TTS
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Modello TTS:", fontFamily = FontFamily.Monospace)
                        Text(ttsEngineName, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

        }
        IconButton(
            onClick = { showSettings = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding() // Evita che l'icona si nasconda sotto l'orologio o la batteria
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Impostazioni",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("Impostazioni Lori") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Usa colori di sistema")
                        Switch(
                            checked = useSystemTheme,
                            onCheckedChange = onThemeChange
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSettings = false }) {
                        Text("Chiudi")
                    }
                }
            )
        }
    }
}