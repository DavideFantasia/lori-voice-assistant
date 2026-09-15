package com.example.localvoice.intent

/**
 * Parser di comandi a regole. Per un set fisso di azioni (ricerca web, timer,
 * bluetooth, media, volume) un LLM è overkill e costa moltissima batteria/RAM
 * per nessun beneficio reale: un matcher regex copre tranquillamente l'MVP e
 * gira in microsecondi.
 *
 * Aggiungi nuovi comandi aggiungendo nuovi pattern qui. Se noti che un
 * comando che "dovrebbe" funzionare torna Unknown, è quasi sempre perché
 * manca un verbo/sinonimo o un formato numerico qui sotto — non serve
 * un motore diverso, basta allargare il pattern.
 */
class CommandParser {

    private val searchPattern = Regex(
        """(?:cerca|trova)\s+(.+?)\s+(?:su internet|online|sul web)""",
        RegexOption.IGNORE_CASE
    )

    private val searchPatternSimple = Regex(
        """(?:cerca|trova)\s+(.+)""",
        RegexOption.IGNORE_CASE
    )

    private val timerPattern = Regex(
        """(?:(?:imposta|avvia|metti|fai partire|crea|punta)\s+)?(?:(?:un|una)\s+)?(?:timer|sveglia)(?:\s+di)?\s+(\d+|[a-z ]+)\s*(secondi|secondo|minuti|minuto|ore|ora)""",
        RegexOption.IGNORE_CASE
    )

    private val bluetoothOnPattern = Regex(
        """(?:accendi|attiva)(?:\s+il)?\s+bluetooth""", RegexOption.IGNORE_CASE
    )

    private val bluetoothOffPattern = Regex(
        """(?:spegni|disattiva)(?:\s+il)?\s+bluetooth""", RegexOption.IGNORE_CASE
    )

    private val nextTrackPattern = Regex(
        """\b(?:prossima|successiva)\b|(?:canzone|traccia)\s+(?:successiva|dopo)|salta(?:\s+la)?\s+(?:canzone|traccia)|cambia\s+(?:canzone|traccia)|vai\s+avanti""",
        RegexOption.IGNORE_CASE
    )

    private val previousTrackPattern = Regex(
        """\bprecedente\b|(?:canzone|traccia)\s+(?:di\s+)?prima|torna(?:\s+alla)?\s+(?:canzone|traccia)\s+precedente|torna\s+indietro""",
        RegexOption.IGNORE_CASE
    )

    private val pauseMediaPattern = Regex(
        """\bpausa\b|ferma(?:\s+tutto|\s+la\s+musica)?|metti(?:\s+in)?\s+pausa|\bsilenzio\b|stoppa""",
        RegexOption.IGNORE_CASE
    )

    private val volumeSetPattern = Regex(
        """(?:porta\s+il\s+)?volume\s+(?:a|al)?\s*(\d{1,3}|[a-z ]+)\s*%?""",
        RegexOption.IGNORE_CASE
    )

    private val volumeUpPattern = Regex(
        """(?:alza|aumenta)(?:\s+il)?\s+volume""", RegexOption.IGNORE_CASE
    )

    private val volumeDownPattern = Regex(
        """(?:abbassa|diminuisci)(?:\s+il)?\s+volume""", RegexOption.IGNORE_CASE
    )

    private val playMusicPattern = Regex(
        """(?:metti|suona|riproduci|ascoltiamo)\s+(.+)""", RegexOption.IGNORE_CASE
    )

    // Numeri scritti a parole più comuni per un timer vocale — copre i casi
    // pratici (nessuno dice "imposta un timer di quarantasette minuti").
    // Aggiungi qui altre voci se ti serve un numero specifico che manca.
    private val wordToNumber = mapOf(
        "un" to 1, "uno" to 1, "una" to 1,
        "due" to 2, "tre" to 3, "quattro" to 4, "cinque" to 5,
        "sei" to 6, "sette" to 7, "otto" to 8, "nove" to 9, "dieci" to 10,
        "undici" to 11, "dodici" to 12, "quindici" to 15,
        "venti" to 20, "venticinque" to 25, "trenta" to 30,
        "quaranta" to 40, "quarantacinque" to 45, "cinquanta" to 50,
        "sessanta" to 60, "settanta" to 70, "ottanta" to 80, "novanta" to 90,
        "cento" to 100
    )

    private fun parseAmount(raw: String): Long? {
        raw.toLongOrNull()?.let { return it }
        return wordToNumber[raw.lowercase()]?.toLong()
    }

    fun parse(rawText: String): VoiceIntent {
        val text = rawText.trim()

        searchPattern.find(text)?.let {
            return VoiceIntent.WebSearch(it.groupValues[1].trim())
        }
        searchPatternSimple.find(text)?.let {
            return VoiceIntent.WebSearch(it.groupValues[1].trim())
        }

        timerPattern.find(text)?.let { match ->
            val amount = parseAmount(match.groupValues[1]) ?: return VoiceIntent.Unknown
            val unit = match.groupValues[2].lowercase()
            val seconds = when {
                unit.startsWith("second") -> amount
                unit.startsWith("minut") -> amount * 60
                unit.startsWith("or") -> amount * 3600
                else -> amount * 60
            }
            return VoiceIntent.SetTimer(seconds)
        }

        if (bluetoothOnPattern.containsMatchIn(text)) {
            return VoiceIntent.BluetoothToggle(turnOn = true)
        }
        if (bluetoothOffPattern.containsMatchIn(text)) {
            return VoiceIntent.BluetoothToggle(turnOn = false)
        }

        if (nextTrackPattern.containsMatchIn(text)) {
            return VoiceIntent.NextTrack
        }
        if (previousTrackPattern.containsMatchIn(text)) {
            return VoiceIntent.PreviousTrack
        }
        if (pauseMediaPattern.containsMatchIn(text)) {
            return VoiceIntent.PauseMedia
        }

        // Il volume assoluto va controllato prima di quello relativo: "volume
        // al 70" non deve mai finire nei pattern alza/abbassa.
        volumeSetPattern.find(text)?.let { match ->
            val amount = parseAmount(match.groupValues[1]) ?: return VoiceIntent.Unknown
            return VoiceIntent.SetVolume(amount.toInt().coerceIn(0, 100))
        }
        if (volumeUpPattern.containsMatchIn(text)) {
            return VoiceIntent.AdjustVolume(increase = true)
        }
        if (volumeDownPattern.containsMatchIn(text)) {
            return VoiceIntent.AdjustVolume(increase = false)
        }
        playMusicPattern.find(text)?.let {
            return VoiceIntent.PlayMusic(it.groupValues[1].trim())
        }

        return VoiceIntent.Unknown
    }
}
