package com.example.bookings.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.bookings.App
import com.example.bookings.MainActivity
import com.example.bookings.R
import com.example.bookings.data.RoomUpdateStore
import com.example.bookings.telegram.MonitorStatus
import com.example.bookings.telegram.PollingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service hosting the polling loop. Android may still stop it (battery saver,
 * OEM task killers, force-stop, reboot) - there is no guarantee of 24/7 operation.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as App
        if (intent?.action == ACTION_STOP) {
            app.prefs.monitoring = false
            stopSelf()
            return START_NOT_STICKY
        }
        // intent == null means the system restarted us after killing the process.
        if (intent?.action != ACTION_START && !app.prefs.monitoring) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startAsForeground()
        } catch (e: Exception) {          // e.g. background start not allowed on Android 12+
            stopSelf()
            return START_NOT_STICKY
        }
        app.prefs.monitoring = true
        if (job?.isActive != true) {
            job = scope.launch {
                PollingEngine(
                    gateway = app.api,
                    store = RoomUpdateStore(app.db) { n -> notifyNewRequests(n) },
                    tokenProvider = { app.tokenStore.get() },
                    status = app.status,
                ).run()
                // run() only returns on a fatal state (invalid token / webhook / no token).
                app.prefs.monitoring = false
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        val app = application as App
        when (app.status.value) {
            is MonitorStatus.Connected, is MonitorStatus.Reconnecting, MonitorStatus.Starting ->
                app.status.value = MonitorStatus.Stopped
            else -> {}
        }
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel_monitor), NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.notif_monitor_title))
            .setContentText(getString(R.string.notif_monitor_text))
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notifyNewRequests(count: Int) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_REQ, getString(R.string.notif_channel_requests), NotificationManager.IMPORTANCE_DEFAULT)
            )
            val n = NotificationCompat.Builder(this, CHANNEL_REQ)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(getString(R.string.notif_new_requests_title))
                .setContentText(getString(R.string.notif_new_requests_text, count))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
            nm.notify(NOTIF_REQ_ID, n)
        } catch (e: Exception) {
            // notifications are a convenience; never let them stop message processing
        }
    }

    companion object {
        const val ACTION_START = "com.example.bookings.START"
        const val ACTION_STOP = "com.example.bookings.STOP"
        private const val CHANNEL = "monitor"
        private const val CHANNEL_REQ = "requests"
        private const val NOTIF_ID = 1001
        private const val NOTIF_REQ_ID = 1002
    }
}
