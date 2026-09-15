# Lori
**Un Assistente Vocale in Locale, Privacy Focus per GrapheneOS**

LocalVoice è un progetto Android/Kotlin per un assistente vocale attivabile tramite keyword, 
progettato per operare **100% on-device** (nessuna API cloud richiesta) e ottimizzato per l'uso in background. 

Ideale per sistemi operativi focalizzati sulla privacy come GrapheneOS.

## Funzionalità Principali
* **Wake-word offline:** Basato sulla pipeline di [openWakeWord](https://github.com/dscripka/openWakeWord) (modelli ONNX).
* **Speech-to-Text (STT) offline:** Basato su [Vosk Android](https://alphacephei.com/vosk/).
* **Nessun tracciamento:** Nessuna dipendenza da Google Play Services, nessun account richiesto e nessun permesso `INTERNET` nel manifest (eccezione fatta per l'integrazione opzionale con Spotify).
* **Gestione Audio Indipendente:** Moduli di ascolto continui ed endpointing nativo per un'acquisizione pulita della voce.

## Architettura

```text
openWakeWord (ascolto passivo continuo)
   │  [keyword rilevata]
   ▼
AudioCue (beep di conferma — "sto ascoltando")
   │
   ▼
Vosk SpeechService (STT con endpointing automatico)
   │  [testo riconosciuto]
   ▼
CommandParser (regex/regole locali)
   │
   ▼
ActionExecutor (esecuzione comando locale)
   │
   ▼
TextToSpeech (TTS di sistema per la conferma)
   │
   ▼
[Ritorno in ascolto continuo]
```

---

## Prerequisiti e Setup GrapheneOS

Per far funzionare correttamente LocalVoice su GrapheneOS, sono necessarie alcune configurazioni di sistema:

1. **Motore TTS (Sintesi Vocale):** GrapheneOS include un motore TTS predefinito eslusivamente in inglese. Installa un motore open-source (come **RHVoice** o **eSpeak-NG** via F-Droid) per altre lingue, impostandolo da *Impostazioni > Sistema > Lingue e input > Output sintesi vocale*.
2. **Batteria:** L'esecuzione continua in background richiede la rimozione delle restrizioni energetiche. Vai in *Impostazioni > App > LocalVoice > Batteria* e seleziona **"Non limitata"** (accessibile anche tramite l'apposita scorciatoia nella `MainActivity`).
3. **Assistente Predefinito (Opzionale):** Per impostare *Lori* come assistente di sistema, vai in *Impostazioni > App > App predefinite > App di assistenza digitale* (o usa il pulsante in app).

---

## Installazione e Configurazione

L'app richiede l'inserimento manuale dei modelli IA prima della compilazione. Se i modelli mancano, l'app funzionerà comunque degradando silenziosamente i servizi mancanti.

### 1. Configurazione Wake-word
Per usare una keyword personalizzata in italiano:
1. Addestra il tuo modello esportandolo in formato `.onnx`.
2. Posiziona il file in `app/src/main/assets/`.
3. Aggiorna il nome del file nella variabile `keywordAsset` all'interno di `ListeningForegroundService.kt`.

### 2. Configurazione STT (Vosk)
Il modello di riconoscimento vocale va scaricato manualmente a causa delle dimensioni (~50MB).
1. Si consiglia un modello "small" da [Vosk Models](https://alphacephei.com/vosk/models) (es. `vosk-model-small-it-0.22`).
2. Estrai l'archivio.
3. Rinomina la cartella estratta in `model-it-small` (o aggiorna `MODEL_ASSET_PATH` nel codice) e copiala in `app/src/main/assets/`.

---

## Comandi Supportati

I comandi sono elaborati localmente (tramite Regex) da `CommandParser.kt` ed eseguiti da `ActionExecutor.kt`. È possibile estendere facilmente la lista.

| Comando | Azione | Note |
| :--- | :--- | :--- |
| `"cerca [query] su internet"` | Ricerca Web | Apre il browser predefinito |
| `"imposta un timer di [N] [min/sec/ore]"` | Timer | Usa `AlarmManager` (richiede permessi sveglie e notifiche) |
| `"accendi/spegni bluetooth"` | Bluetooth | Su Android 13+ apre il prompt di conferma di sistema |
| `"prossima [canzone]" / "successiva"` | Media Control | `KEYCODE_MEDIA_NEXT` |
| `"precedente"` | Media Control | `KEYCODE_MEDIA_PREVIOUS` |
| `"pausa" / "ferma tutto"` | Media Control | `KEYCODE_MEDIA_PAUSE` |
| `"volume al [X]"` | Volume | Imposta il volume media alla % indicata |
| `"alza/abbassa il volume"` | Volume | Regolazione relativa |
| `"metti [ricerca]"` | Spotify | Cerca tramite API e apre l'app Spotify via Intent |

*Nota sul riconoscimento vocale:* Vosk è configurato in **Grammar mode**, limitando il riconoscimento alle parole chiave dei comandi per massimizzare la precisione e la velocità. Questo può ridurre l'accuratezza nelle query a testo libero (come le ricerche web). Per disattivarlo, rimuovi `GRAMMAR_JSON` dall'inizializzazione del `Recognizer` in `VoskSttEngine.kt`.
