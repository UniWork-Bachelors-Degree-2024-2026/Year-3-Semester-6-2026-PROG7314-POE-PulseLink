/*
 * Raises real-time arrhythmia alerts during live ECG monitoring: confirms an abnormal reading over
 * several consecutive beats, then shows a localised dialog, a high-priority notification and an
 * in-app notification, with a one-tap redirect to the Emergency SOS screen.
 *
 * Reference List:
 * - American Heart Association (AHA), 2020. Target Heart Rates and Arrhythmia Overview. [online] Available at: <https://www.heart.org> [Accessed 21 September 2026].
 * - Google Developers, 2024. Notifications Overview - Android Developers. [online] Available at: <https://developer.android.com/develop/ui/views/notifications> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.utils

import android.content.Intent
import android.os.SystemClock
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.prog7314_poepart23.EmergencySosActivity
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.data.models.NotificationItem
import com.example.prog7314_poepart23.data.repository.NotificationRepository
import com.example.prog7314_poepart23.messaging.NotificationHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ArrhythmiaAlertHandler(private val activity: AppCompatActivity) {

    private var consecutiveAbnormal = 0
    private var lastAlertAt = 0L
    private var lastAlertSeverity = BpmAnomalyDetector.AlertSeverity.NORMAL
    private var dialog: AlertDialog? = null

    // Feed every evaluation here. Warnings need 3 abnormal readings in a row, critical needs 2,
    // so a single noisy beat never triggers an alert. Repeats are rate limited by a cool-down.
    fun onEvaluation(result: BpmAnomalyDetector.EvaluationResult) {
        if (result.severity == BpmAnomalyDetector.AlertSeverity.NORMAL) {
            consecutiveAbnormal = 0
            return
        }
        consecutiveAbnormal++
        val critical = result.severity == BpmAnomalyDetector.AlertSeverity.CRITICAL
        if (consecutiveAbnormal < (if (critical) CONFIRM_CRITICAL else CONFIRM_WARNING)) return

        val now = SystemClock.elapsedRealtime()
        val escalated = critical && lastAlertSeverity != BpmAnomalyDetector.AlertSeverity.CRITICAL
        if (!escalated && lastAlertAt != 0L && now - lastAlertAt < COOLDOWN_MS) return
        if (activity.isFinishing || activity.isDestroyed) return

        lastAlertAt = now
        lastAlertSeverity = result.severity
        raise(result, critical)
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun sosIntent(result: BpmAnomalyDetector.EvaluationResult, critical: Boolean) =
        Intent(activity, EmergencySosActivity::class.java).apply {
            putExtra(EmergencySosActivity.EXTRA_ALERT_MESSAGE, result.message)
            putExtra(EmergencySosActivity.EXTRA_ALERT_ACTION, result.suggestedAction)
            putExtra(EmergencySosActivity.EXTRA_ALERT_BPM, result.estimatedBpm)
            putExtra(EmergencySosActivity.EXTRA_ALERT_CRITICAL, critical)
        }

    private fun raise(result: BpmAnomalyDetector.EvaluationResult, critical: Boolean) {
        HapticFeedbackManager.warningBuzz()

        val title = activity.getString(
            if (critical) R.string.l10n_arrhythmia_title_critical else R.string.l10n_arrhythmia_title_warning
        )
        val body = activity.getString(R.string.l10n_arrhythmia_body_format, result.message, result.suggestedAction)

        // In-app inbox entry so the alert can be reviewed later from Notifications.
        val timeLabel = activity.getString(
            R.string.l10n_today_time_format,
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        )
        NotificationRepository.addNotification(
            NotificationItem(
                notificationId = "ARRHYTHMIA_${System.currentTimeMillis()}",
                title = title,
                messagePreview = result.message,
                timestampLabel = timeLabel,
                isUnread = true,
                iconRes = R.drawable.ic_warning_triangle,
                avgBpm = result.estimatedBpm.takeIf { it > 0 },
                rhythmStatus = result.message
            )
        )

        // System notification (tapping it opens SOS even if the app is in the background).
        NotificationHelper.showNotification(
            context = activity.applicationContext,
            title = title,
            body = body,
            channelId = NotificationHelper.CHANNEL_CRITICAL_ALERTS,
            targetIntent = sosIntent(result, critical).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            notificationId = ALERT_NOTIFICATION_ID
        )

        dialog?.dismiss()
        dialog = MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_PulseLink_Dialog)
            .setTitle(title)
            .setMessage(body)
            .setCancelable(!critical)
            .setPositiveButton(
                if (critical) R.string.l10n_arrhythmia_action_sos_now else R.string.l10n_arrhythmia_action_sos
            ) { _, _ ->
                HapticFeedbackManager.confirmClick()
                activity.startActivity(sosIntent(result, critical))
            }
            .setNegativeButton(
                if (critical) R.string.l10n_arrhythmia_action_im_ok else R.string.l10n_arrhythmia_action_dismiss,
                null
            )
            .show()

        // Voice assistance: read the alert aloud so it is heard as well as seen.
        VoiceAssistant.announce(activity, "$title. $body")
    }

    private companion object {
        const val CONFIRM_WARNING = 3
        const val CONFIRM_CRITICAL = 2
        const val COOLDOWN_MS = 30_000L
        const val ALERT_NOTIFICATION_ID = 4001
    }
}
