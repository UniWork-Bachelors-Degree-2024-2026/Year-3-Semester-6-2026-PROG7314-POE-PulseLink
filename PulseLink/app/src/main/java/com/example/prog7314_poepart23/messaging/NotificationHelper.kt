/*
 * Helper utility for managing Android notification channels, builders,
 * and dispatching clinical telemetry notifications for PulseLink.
 *
 * Reference List:
 * - Google Developers, 2024. Create and Manage Notification Channels. [online] Available at: <https://developer.android.com/develop/ui/views/notifications/channels> [Accessed 21 September 2026].
 * - Google Developers, 2024. Notifications Overview - Android Developers. [online] Available at: <https://developer.android.com/develop/ui/views/notifications> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.messaging

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel            // (Google Developers, 2024)
import android.app.NotificationManager            // (Google Developers, 2024)
import android.app.PendingIntent                  // (Google Developers, 2024)
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat      // (Google Developers, 2024)
import androidx.core.content.ContextCompat
import com.example.prog7314_poepart23.NotificationsActivity
import com.example.prog7314_poepart23.R

object NotificationHelper { // (Google Developers, 2024)

    const val CHANNEL_CRITICAL_ALERTS = "pulselink_critical_alerts"
    const val CHANNEL_CLINICAL_UPDATES = "pulselink_clinical_updates"

    fun requestNotificationPermissionIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Critical ECG Alerts Channel (High Importance, Sound & Vibration)
            val alertsChannel = NotificationChannel(
                CHANNEL_CRITICAL_ALERTS,
                com.example.prog7314_poepart23.utils.LocaleHelper.string(R.string.l10n_channel_critical_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = com.example.prog7314_poepart23.utils.LocaleHelper.string(R.string.l10n_channel_critical_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
                setShowBadge(true)
            }

            // Clinical Updates & Reports Channel (Default Importance)
            val updatesChannel = NotificationChannel(
                CHANNEL_CLINICAL_UPDATES,
                com.example.prog7314_poepart23.utils.LocaleHelper.string(R.string.l10n_channel_updates_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = com.example.prog7314_poepart23.utils.LocaleHelper.string(R.string.l10n_channel_updates_desc)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(alertsChannel)
            notificationManager.createNotificationChannel(updatesChannel)
        }
    }

    fun showNotification(
        context: Context,
        title: String,
        body: String,
        channelId: String = CHANNEL_CLINICAL_UPDATES,
        targetIntent: Intent? = null,
        notificationId: Int = System.currentTimeMillis().toInt()
    ) {
        createNotificationChannels(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val intent = targetIntent ?: Intent(context, NotificationsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_heart_pulse)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(
                if (channelId == CHANNEL_CRITICAL_ALERTS)
                    NotificationCompat.PRIORITY_HIGH
                else
                    NotificationCompat.PRIORITY_DEFAULT
            )
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, channelId).setSmallIcon(R.drawable.ic_heart_pulse).setContentTitle(context.getString(R.string.app_name)).build())
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, builder.build())
    }
}
