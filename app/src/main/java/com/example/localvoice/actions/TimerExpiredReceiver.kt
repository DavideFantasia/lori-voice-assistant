package com.example.localvoice.actions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.util.Log
import com.example.localvoice.MainActivity

/**
 * Riceve la sveglia programmata da TimerAction e mostra la notifica di
 * scadenza. Senza questo receiver, un AlarmManager programmato con successo
 * non produce MAI nulla di visibile quando scatta — è il pezzo che mancava
 * del tutto nella prima versione (era un TODO, non un placeholder).
 */
class TimerExpiredReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: timer scaduto, mostro notifica")
        val label = intent.getStringExtra(EXTRA_LABEL)
        showNotification(context, label)
    }

    private fun showNotification(context: Context, label: String?) {
        ensureChannel(context)

        val openAppIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Timer scaduto")
            .setContentText(label ?: "Il tempo è finito")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(alarmSound)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .build()

        // Se manca il permesso POST_NOTIFICATIONS (Android 13+), questa
        // chiamata fallisce silenziosamente — nessun crash, nessuna notifica.
        // Va richiesto esplicitamente all'utente (vedi MainActivity).
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "Notifica inviata a NotificationManagerCompat (non garantisce che sia VISIBILE se manca POST_NOTIFICATIONS)")
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Timer scaduti", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifica quando un timer impostato via voce scade"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val EXTRA_LABEL = "extra_label"
        private const val CHANNEL_ID = "localvoice_timer"
        private const val NOTIFICATION_ID = 43
        private const val TAG = "LocalVoiceTimer"
    }
}
