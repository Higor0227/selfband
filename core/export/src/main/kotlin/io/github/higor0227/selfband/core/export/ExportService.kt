// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export

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
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps an export alive in the background (SPEC 2.7): a foreground service with a progress
 * notification and a Cancel action. It mirrors [ExportManager.state] and stops itself when the
 * export ends, leaving a final notification.
 */
@AndroidEntryPoint
class ExportService : Service() {
    @Inject lateinit var manager: ExportManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            manager.cancel()
            return START_NOT_STICKY
        }
        createChannel()
        val state = manager.state.value as? ExportState.Running
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        // Called directly (minSdk 29): ServiceCompat masks the type to the ones known on API 34,
        // which drops mediaProcessing (API 35) and makes Android refuse a typeless service.
        startForeground(NOTIFICATION_ID, progressNotification(state), type)
        if (watcher == null) watcher = scope.launch {
            manager.state.collect { s ->
                when (s) {
                    is ExportState.Running -> notify(NOTIFICATION_ID, progressNotification(s))
                    ExportState.Idle -> finish(null)
                    else -> finish(s)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun finish(state: ExportState?) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (state != null) notify(RESULT_NOTIFICATION_ID, resultNotification(state))
        stopSelf()
    }

    private fun progressNotification(state: ExportState.Running?): Notification {
        val cancel = PendingIntent.getService(
            this, 0, Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when {
            state == null -> null
            state.step == ExportStep.STEMS -> getString(R.string.export_step_stems, state.stemIndex, state.stemCount)
            else -> getString(R.string.export_step_mix)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_export_notification)
            .setContentTitle(getString(R.string.export_running_title, state?.projectName.orEmpty()))
            .setContentText(text)
            .setProgress(1000, ((state?.progress ?: 0f) * 1000).toInt(), state == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.export_cancel), cancel)
            .build()
    }

    private fun resultNotification(state: ExportState): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_export_notification)
            .setContentIntent(openApp())
            .setAutoCancel(true)
        when (state) {
            is ExportState.Done -> builder.setContentTitle(getString(R.string.export_done_title))
                .setContentText(getString(R.string.export_done_text, state.projectName, state.outputs.size))
            is ExportState.Failed -> builder.setContentTitle(getString(R.string.export_failed_title))
            else -> builder.setContentTitle(getString(R.string.export_cancelled_title))
        }
        return builder.build()
    }

    private fun openApp(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        return PendingIntent.getActivity(this, 1, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notify(id: Int, notification: Notification) {
        val nm = NotificationManagerCompat.from(this)
        // Without POST_NOTIFICATIONS the export still runs; only the notification is hidden.
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.export_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private companion object {
        const val CHANNEL_ID = "export"
        const val NOTIFICATION_ID = 41
        const val RESULT_NOTIFICATION_ID = 42
        const val ACTION_CANCEL = "io.github.higor0227.selfband.export.CANCEL"
    }
}
