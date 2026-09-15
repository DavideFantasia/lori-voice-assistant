package com.example.localvoice.actions

import android.app.AlarmManager
import android.app.Notification
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

class TimerExpiredReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val notificationManager = NotificationManagerCompat.from(context)

        // 1. NUOVO: Se l'utente preme "Annulla" sul timer IN CORSO
        if (intent.action == ACTION_CANCEL_TIMER) {
            Log.d(TAG, "Timer in corso annullato dall'utente")

            // Dobbiamo ricreare un PendingIntent identico a quello che ha generato l'allarme
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val receiverIntent = Intent(context, TimerExpiredReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, receiverIntent, // requestCode 0 era quello usato in TimerAction
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Cancella l'allarme di sistema e la notifica in corso
            alarmManager.cancel(pendingIntent)
            notificationManager.cancel(RUNNING_NOTIFICATION_ID)
            return
        }

        // Se l'utente ha premuto "Ferma", eliminiamo l'allarme SCADUTO e usciamo
        if (intent.action == ACTION_STOP_TIMER) {
            Log.d(TAG, "Timer fermato dall'utente")
            notificationManager.cancel(EXPIRED_NOTIFICATION_ID)
            return
        }

        Log.d(TAG, "onReceive: timer scaduto, mostro notifica insistente")
        val label = intent.getStringExtra(EXTRA_LABEL)

        // Rimuoviamo la notifica silenziosa del conto alla rovescia
        notificationManager.cancel(RUNNING_NOTIFICATION_ID)

        // Mostriamo la notifica della sveglia
        showNotification(context, label)
    }

    private fun showNotification(context: Context, label: String?) {
        ensureChannel(context)

        val openAppIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent specifico per il pulsante "Ferma"
        val stopIntent = Intent(context, TimerExpiredReceiver::class.java).apply {
            action = ACTION_STOP_TIMER
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Timer scaduto")
            .setContentText(label ?: "Il tempo è finito")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(alarmSound)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Ferma", stopPendingIntent)

        val notification = notificationBuilder.build()

        // Suona in loop insistente e si comporta come una vera sveglia
        notification.flags = notification.flags or Notification.FLAG_INSISTENT or Notification.FLAG_SHOW_LIGHTS

        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(EXPIRED_NOTIFICATION_ID, notification)
            Log.d(TAG, "Notifica inviata in loop insistente")
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            // Diciamo ad Android che questo suono è una SVEGLIA (così suona anche in silenzioso)
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID, "Timer scaduti", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifica quando un timer impostato via voce scade"
                setSound(alarmSound, audioAttributes) // FONDAMENTALE: impostato sul canale!
                enableVibration(true)
                setBypassDnd(true) // Ignora il Non Disturbare
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val EXTRA_LABEL = "extra_label"
        const val ACTION_STOP_TIMER = "com.example.localvoice.ACTION_STOP_TIMER" // cancella il timer scaduto
        const val ACTION_CANCEL_TIMER = "com.example.localvoice.ACTION_CANCEL_TIMER" // annulla il timer avviato
        const val RUNNING_NOTIFICATION_ID = 44
        const val EXPIRED_NOTIFICATION_ID = 43
        private const val CHANNEL_ID = "localvoice_timer_alarm_v1"
        private const val TAG = "LocalVoiceTimer"
    }
}