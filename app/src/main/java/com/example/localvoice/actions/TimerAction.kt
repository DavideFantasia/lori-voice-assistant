package com.example.localvoice.actions

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.localvoice.MainActivity

sealed class TimerResult {
    data object ScheduledExact : TimerResult()
    data object ScheduledInexact : TimerResult()
    data class MissingNotificationPermission(val underlying: TimerResult) : TimerResult()
}

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
            TimerResult.ScheduledExact
        } else {
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            TimerResult.ScheduledInexact
        }

        val hasNotificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasNotificationPermission) {
            Log.w(TAG, "Permesso notifiche assente: alla scadenza NON comparirà nulla")
        } else {
            // Mostra il conto alla rovescia se abbiamo i permessi
            showRunningNotification(seconds, label)
        }

        return if (hasNotificationPermission) schedulingResult
        else TimerResult.MissingNotificationPermission(schedulingResult)
    }

    private fun showRunningNotification(seconds: Long, label: String?) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channelId = "localvoice_timer_running"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Timer in corso", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mostra il conto alla rovescia dei timer attivi"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val targetTimeMillis = System.currentTimeMillis() + (seconds * 1000)

        val openAppIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = Intent(context, TimerExpiredReceiver::class.java).apply {
            action = TimerExpiredReceiver.ACTION_CANCEL_TIMER
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, 2, cancelIntent, // requestCode 2 per non sovrascrivere altri intent
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // builder nativo
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label ?: "Timer")
            .setOngoing(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(targetTimeMillis)
            .setContentIntent(openAppIntent)
            .setGroup("standalone_timer_group")
            // Aggiungiamo il pulsante alla notifica
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Annulla", cancelPendingIntent)

        notificationManager.notify(TimerExpiredReceiver.RUNNING_NOTIFICATION_ID, builder.build())
    }

    companion object {
        private const val TAG = "LocalVoiceTimer"
    }
}