package com.example.localvoice.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Esito reale dell'operazione — usato da ActionExecutor per dare feedback
 * vocale accurato, mai un "Timer impostato" a prescindere.
 */
sealed class TimerResult {
    data object ScheduledExact : TimerResult()
    data object ScheduledInexact : TimerResult()
    data class MissingNotificationPermission(val underlying: TimerResult) : TimerResult()
}

/**
 * NOTA: la versione precedente tentava prima di aprire un'app Orologio di
 * sistema tramite l'intent implicito ACTION_SET_TIMER. L'ho rimossa: non è
 * verificabile da qui se l'app risolta la gestisca correttamente (dipende
 * dal dispositivo/ROM), e sul tuo GrapheneOS sembra non funzionare in modo
 * affidabile. Usiamo SOLO il nostro AlarmManager interno con receiver
 * esplicito: meno "magico", ma ogni passaggio è verificabile in Logcat.
 */
class TimerAction(private val context: Context) {

    fun set(seconds: Long, label: String?): TimerResult {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + seconds * 1000

        val receiverIntent = Intent(context, TimerExpiredReceiver::class.java).apply {
            putExtra(TimerExpiredReceiver.EXTRA_LABEL, label)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, receiverIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        Log.d(TAG, "set(): seconds=$seconds label=$label canScheduleExact=$canScheduleExact triggerAtElapsed=$triggerAt")

        val schedulingResult = if (canScheduleExact) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent
            )
            Log.d(TAG, "Alarm ESATTO programmato con successo")
            TimerResult.ScheduledExact
        } else {
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            Log.w(TAG, "Permesso sveglie esatte assente: alarm NON esatto (può ritardare)")
            TimerResult.ScheduledInexact
        }

        val hasNotificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasNotificationPermission) {
            Log.w(TAG, "Permesso notifiche assente: alla scadenza NON comparirà nulla")
        }

        return if (hasNotificationPermission) schedulingResult
        else TimerResult.MissingNotificationPermission(schedulingResult)
    }

    companion object {
        private const val TAG = "LocalVoiceTimer"
    }
}
