package com.example.localvoice

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

// GESTORE CICLO DI VITA PER LA UI SOVRAPPOSTA
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class SessionLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    fun performRestore(savedState: Bundle?) {
        savedStateRegistryController.performRestore(savedState)
    }

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }
}

class LocalVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return LoriVoiceInteractionSession(this)
    }
}

/**
 * Sessione che si apre quando l'utente attiva l'assistente esplicitamente
 * (long-press home / gesture) — separata dal flusso "wake word in background",
 * che invece parte da ListeningForegroundService.
 */
class LoriVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    companion object {
        private var instance: LoriVoiceInteractionSession? = null

        fun closeUI() {
            instance?.hide() // Chiude il Bottom Sheet
        }
    }
    private val lifecycleOwner = SessionLifecycleOwner()
    // Aggiungiamo un flusso per comunicare a Compose il cambio di tema in tempo reale
    private val useSystemThemeFlow = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        instance = this
        lifecycleOwner.performRestore(null)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateContentView(): android.view.View {
        val composeView = androidx.compose.ui.platform.ComposeView(context).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

            // Chiama le extension functions di Kotlin direttamente sulla View
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            setContent {
                // Osserviamo il flusso del tema
                val useSystemTheme by useSystemThemeFlow.collectAsState()

                // Passiamo la variabile al tema personalizzato
                com.example.localvoice.ui.theme.LoriTheme(useSystemTheme = useSystemTheme) {

                    // PUNTA ai flussi dell'oggetto globale VoiceUIState
                    val transcription by VoiceUIState.transcriptionFlow.collectAsState()
                    val volume by VoiceUIState.audioVolumeFlow.collectAsState()

                    com.example.localvoice.ui.screens.AssistantOverlayScreen(
                        transcriptionText = transcription,
                        audioVolume = volume
                    )
                }
            }
        }
        return composeView
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        // Rilegge le SharedPreferences ogni volta che l'overlay si apre.
        // Così se l'utente ha appena cambiato il tema nell'app, il Bottom Sheet si adegua subito!
        val prefs = context.getSharedPreferences("lori_prefs", Context.MODE_PRIVATE)
        useSystemThemeFlow.value = prefs.getBoolean("use_system_theme", true)

        // Risveglia Compose
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onHide() {
        super.onHide()
        // Resetta il testo e l'onda audio per la prossima attivazione
        VoiceUIState.reset()
        // Mette in pausa Compose
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null // Pulisce il riferimento
        // Distrugge l'albero di Compose
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
