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

    // Verbi accettati per avviare un timer: imposta / avvia / metti / fai partire.
    // L'articolo "un" prima di "timer" è opzionale in tutti i casi.
    // Le forme plurali italiane di queste parole non aggiungono una lettera
    // (non è "secondo" + "i" opzionale) ma cambiano la vocale finale:
    // secondo→secondi, minuto→minuti, ora→ore. Vanno quindi elencate come
    // alternative letterali, non con un "?" su un singolo carattere finale.
    private val timerPattern = Regex(
        """(?:imposta|avvia|metti|fai partire)(?:\s+un)?\s+timer\s+di\s+(\d+|[a-zà]+)\s*(secondi|secondo|minuti|minuto|ore|ora)""",
        RegexOption.IGNORE_CASE
    )

    private val bluetoothOnPattern = Regex(
        """accendi(?:\s+il)?\s+bluetooth""", RegexOption.IGNORE_CASE
    )
    private val bluetoothOffPattern = Regex(
        """spegni(?:\s+il)?\s+bluetooth""", RegexOption.IGNORE_CASE
    )

    // Media: skip traccia. Diverse formulazioni comuni in italiano, incluse
    // le forme brevi "prossima"/"successiva"/"precedente" da sole — NOTA: a
    // differenza della versione precedente, qui "precedente"/"successiva"
    // da sole possono dare falsi positivi su frasi non legate a canzoni
    // (es. "il giorno precedente"). È un tradeoff esplicito richiesto per
    // comandi più brevi da pronunciare — se in pratica causa problemi,
    // restringi di nuovo richiedendo la parola "canzone" accanto.
    private val nextTrackPattern = Regex(
        """\b(?:prossima|successiva)\b|canzone\s+(?:successiva|dopo)|salta(?:\s+la)?\s+canzone|cambia\s+canzone""",
        RegexOption.IGNORE_CASE
    )
    private val previousTrackPattern = Regex(
        """\bprecedente\b|canzone\s+(?:di\s+)?prima|torna(?:\s+alla)?\s+canzone\s+precedente""",
        RegexOption.IGNORE_CASE
    )
    private val pauseMediaPattern = Regex(
        """\bpausa\b|ferma\s+tutto|metti(?:\s+in)?\s+pausa|\bsilenzio\b""",
        RegexOption.IGNORE_CASE
    )

    // Volume: sia forma assoluta ("volume al 70") sia relativa ("alza/abbassa il volume").
    // L'assoluta va controllata PRIMA della relativa, perché altrimenti
    // "volume" da solo potrebbe essere ambiguo — tenerle come pattern
    // separati con verbi diversi evita il problema alla radice.
    private val volumeSetPattern = Regex(
        """volume\s+(?:a|al)?\s*(\d{1,3}|[a-zà]+)\s*%?""",
        RegexOption.IGNORE_CASE
    )
    private val volumeUpPattern = Regex(
        """alza(?:\s+il)?\s+volume""", RegexOption.IGNORE_CASE
    )
    private val volumeDownPattern = Regex(
        """abbassa(?:\s+il)?\s+volume""", RegexOption.IGNORE_CASE
    )

    // Spotify: "metti %s" — usa lo stesso verbo di timer/pausa, quindi va
    // controllato DOPO quei due pattern in parse(), altrimenti "metti in
    // pausa" o "metti un timer di 5 minuti" verrebbero interpretati come
    // ricerche musicali letterali invece che come i comandi giusti.
    private val playMusicPattern = Regex(
        """metti\s+(.+)""", RegexOption.IGNORE_CASE
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
