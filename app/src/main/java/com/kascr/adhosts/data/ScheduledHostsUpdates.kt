package com.kascr.adhosts.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kascr.adhosts.R
import com.kascr.adhosts.ui.activity.MainActivity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.withLock

object ScheduledHostsUpdates {
    private const val PREFERENCES = "scheduled_hosts_updates"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val WORK_NAME = "scheduled_hosts_preview"

    fun enabled(context: Context): Boolean = preferences(context).getBoolean(KEY_ENABLED, false)
    fun wifiOnly(context: Context): Boolean = preferences(context).getBoolean(KEY_WIFI_ONLY, true)

    fun configure(context: Context, enabled: Boolean, wifiOnly: Boolean) {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled)
            .putBoolean(KEY_WIFI_ONLY, wifiOnly).apply()
        schedule(context)
    }

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled(context)) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<HostsPreviewWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}

class HostsPreviewWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!ScheduledHostsUpdates.enabled(applicationContext)) return Result.success()
        if (ScheduledHostsUpdates.wifiOnly(applicationContext)) {
            val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
            val network = connectivity.activeNetwork ?: return Result.retry()
            if (connectivity.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return Result.retry()
        }
        if (HostsSubscriptionManager.getSubscriptions(applicationContext).none { it.enabled }) {
            return Result.success()
        }
        return try {
            HostsOperationLock.mutex.withLock {
                if (ScheduledHostsUpdates.enabled(applicationContext) &&
                    HostsUpdateManager.pendingPreview(applicationContext) == null) {
                    val preview = HostsUpdateManager.prepare(applicationContext)
                    if (ScheduledHostsUpdates.enabled(applicationContext) && preview.hasChanges) {
                        notifyUpdate(preview)
                    } else {
                        HostsUpdateManager.discard(applicationContext)
                    }
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun notifyUpdate(preview: UpdatePreview) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channelId = "hosts_updates"
        manager.createNotificationChannel(NotificationChannel(
            channelId,
            applicationContext.getString(R.string.update_preview_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        ))
        val intent = MainActivity.newIntent(applicationContext, MainActivity.TAB_HOSTS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(applicationContext.getString(R.string.update_preview_notification_title))
            .setContentText(applicationContext.getString(R.string.update_preview_counts,
                preview.added, preview.removed, preview.changed))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(220, notification)
    }
}
