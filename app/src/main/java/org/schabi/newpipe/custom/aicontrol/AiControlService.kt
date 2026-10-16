/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.schabi.newpipe.R

/** Explicit opt-in connectivity only. No exported receiver, boot autostart or offline command queue. */
class AiControlService : Service() {
    private lateinit var runtime: AiControlRuntime
    private var observer: AutoCloseable? = null
    private var foreground = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val visibilityCheck = object : Runnable {
        override fun run() {
            if (!foreground) return
            val manager = getSystemService(NotificationManager::class.java)
            val visible = androidx.core.app.NotificationManagerCompat.from(this@AiControlService).areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < 26 || manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE)
            if (!visible) {
                runtime.setEnabled(false)
                stopSelf()
            } else {
                main.postDelayed(this, 15_000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runtime = AiControlRuntime.get(this)
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.rv_ai_title), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.rv_ai_notification_explanation)
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runtime.setEnabled(false)
            removeNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION,
                notification(runtime.snapshot),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            )
            foreground = true
            main.removeCallbacks(visibilityCheck)
            visibilityCheck.run()
            if (observer == null) {
                observer = runtime.observe { state ->
                    if (foreground) {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
                    }
                }
                runtime.serviceStarted()
            }
        } catch (_: Exception) {
            runtime.serviceStartFailed()
            stopSelf()
        }
        // A user explicitly starts each app-process service session. Force-stop/reboot means offline.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        main.removeCallbacks(visibilityCheck)
        observer?.close()
        observer = null
        runtime.serviceStopped()
        removeNotification()
        super.onDestroy()
    }

    private fun removeNotification() {
        if (foreground) {
            foreground = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private fun notification(state: AiUiSnapshot): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 27101, Intent(this, AiControlActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 27102, Intent(this, AiControlService::class.java).setAction(ACTION_STOP), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(getString(R.string.rv_ai_title))
            .setContentText(getString(R.string.rv_ai_notification_state, state.connection.lowercase().replace('_', ' ')))
            .setContentIntent(open)
            .setDeleteIntent(stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.rv_ai_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "org.schabi.newpipe.custom.aicontrol.START"
        const val ACTION_STOP = "org.schabi.newpipe.custom.aicontrol.STOP"
        private const val CHANNEL = "rv_ai_control"
        private const val NOTIFICATION = 27100
    }
}
