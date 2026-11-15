/*
 * Adaptive clinical BPM anomaly detection engine for AD8232 single-lead ECG telemetry.
 * Evaluates real-time estimated heart rate against physiological thresholds (bradycardia,
 * tachycardia, lead detachment) and triggers clinical haptic warnings.
 *
 * Reference List:
 * - American Heart Association (AHA), 2020. Heart Rate and Electrocardiography Standards. [online] Available at: <https://www.heart.org/> [Accessed 21 September 2026].
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End. [online] Available at: <https://www.analog.com/media/en/technical-documentation/data-sheets/AD8232.pdf> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.utils

import com.example.prog7314_poepart23.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.TimeZone

object BpmAnomalyDetector { // (American Heart Association (AHA), 2020; Analog Devices, 2020)

    // Physiological rhythm states for single-lead Lead I ECG monitoring
    enum class CardiacRhythmState {
        NORMAL_SINUS_RHYTHM,
        BRADYCARDIA_WARNING,
        BRADYCARDIA_CRITICAL,
        TACHYCARDIA_WARNING,
        TACHYCARDIA_CRITICAL,
        IRREGULAR_RHYTHM,
        LEAD_DETACHED,
        ASYSTOLE
    }

    // Severity level for categorising clinical notifications
    enum class AlertSeverity {
        NORMAL,
        WARNING,
        CRITICAL
    }

    // Evaluation result produced for each telemetry sample or calculated BPM reading
    data class EvaluationResult(
        val state: CardiacRhythmState,
        val severity: AlertSeverity,
        val estimatedBpm: Int,
        val message: String,
        val suggestedAction: String,
        val timestampUtc: String = getUtcTimestamp()
    )

    // Sliding window buffer (5 samples) for smoothing estimated BPM and preventing artefact spikes
    private val bpmHistoryBuffer = LinkedList<Int>()
    private const val BUFFER_CAPACITY = 5

    // Clinical thresholds
    const val THRESHOLD_BRADYCARDIA_CRITICAL = 40
    const val THRESHOLD_BRADYCARDIA_WARNING = 50
    const val THRESHOLD_TACHYCARDIA_WARNING = 100
    const val THRESHOLD_TACHYCARDIA_CRITICAL = 140

    // Evaluates a newly calculated BPM reading and electrode connection state.
    fun evaluate(
        instantaneousBpm: Int,
        isLeadConnected: Boolean = true,
        isRhythmIrregular: Boolean = false
    ): EvaluationResult {
        if (!isLeadConnected) {
            HapticFeedbackManager.warningBuzz()
            return EvaluationResult(
                state = CardiacRhythmState.LEAD_DETACHED,
                severity = AlertSeverity.CRITICAL,
                estimatedBpm = 0,
                message = LocaleHelper.string(R.string.l10n_anomaly_lead_msg),
                suggestedAction = LocaleHelper.string(R.string.l10n_anomaly_lead_action)
            )
        }

        // Add to sliding window buffer for smoothed evaluation
        synchronized(bpmHistoryBuffer) {
            bpmHistoryBuffer.addLast(instantaneousBpm)
            if (bpmHistoryBuffer.size > BUFFER_CAPACITY) {
                bpmHistoryBuffer.removeFirst()
            }
        }

        val smoothedBpm = getSmoothedBpm()

        return when {
            smoothedBpm <= 0 -> {
                HapticFeedbackManager.warningBuzz()
                EvaluationResult(
                    state = CardiacRhythmState.ASYSTOLE,
                    severity = AlertSeverity.CRITICAL,
                    estimatedBpm = smoothedBpm,
                    message = LocaleHelper.string(R.string.l10n_alert_asystole),
                    suggestedAction = LocaleHelper.string(R.string.l10n_anomaly_asystole_action)
                )
            }
            smoothedBpm < THRESHOLD_BRADYCARDIA_CRITICAL -> {
                HapticFeedbackManager.warningBuzz()
                EvaluationResult(
                    state = CardiacRhythmState.BRADYCARDIA_CRITICAL,
                    severity = AlertSeverity.CRITICAL,
                    estimatedBpm = smoothedBpm,
                    message = LocaleHelper.string(R.string.l10n_anomaly_brady_crit_msg, smoothedBpm),
                    suggestedAction = LocaleHelper.string(R.string.l10n_anomaly_brady_crit_action)
                )
            }
            smoothedBpm < THRESHOLD_BRADYCARDIA_WARNING -> {
                HapticFeedbackManager.lightTick()
                EvaluationResult(
                    state = CardiacRhythmState.BRADYCARDIA_WARNING,
                    severity = AlertSeverity.WARNING,
                    estimatedBpm = smoothedBpm,
                    message = LocaleHelper.string(R.string.l10n_anomaly_brady_warn_msg, smoothedBpm),
                    suggestedAction = LocaleHelper.string(R.string.l10n_anomaly_brady_warn_action)
                )
            }
            smoothedBpm > THRESHOLD_TACHYCARDIA_CRITICAL -> {
                HapticFeedbackManager.warningBuzz()
                EvaluationResult(
                    state = CardiacRhythmState.TACHYCARDIA_CRITICAL,
                    severity = AlertSeverity.CRITICAL,
                    estimatedBpm = smoothedBpm,
                    message = LocaleHelper.string(R.string.l10n_anomaly_tachy_crit_msg, smoothedBpm),
                    suggestedAction = LocaleHelper.string(R.string.l10n_anomaly_tachy_crit_action)
                )
            }
            smoothedBpm > THRESHOLD_TACHYCARDIA_WARNING -> {
                HapticFeedbackManager.lightTick()
                EvaluationResult(
                    state = CardiacRhythmState.TACHYCARDIA_WARNING,
                    severity = AlertSeverity.WARNING,
                    estimatedBpm = smoothedBpm,
                    message = LocaleHelper.string(R.string.l10n_anomaly_tachy_warn_msg, smoothedBpm),
                    suggestedAction = LocaleHelper.string(R.string.l10n_anomaly_tachy_warn_action)
                )
            }
            isRhythmIrregular -> {
                HapticFeedbackManager.lightTick()
                EvaluationResult(
                    state = CardiacRhythmState.IRREGULAR_RHYTHM,
                    severity = AlertSeverity.WARNING,
                    estimatedBpm = smoothedBpm,
                    message = LocaleHelper.string(R.string.l10n_anomaly_irregular_msg, smoothedBpm),
                    suggestedAction = LocaleHelper.string(R.string.l10n_anomaly_irregular_action)
                )
            }
            else -> {
                EvaluationResult(
                    state = CardiacRhythmState.NORMAL_SINUS_RHYTHM,
                    severity = AlertSeverity.NORMAL,
                    estimatedBpm = smoothedBpm,
                    message = LocaleHelper.string(R.string.l10n_anomaly_normal_msg, smoothedBpm),
                    suggestedAction = LocaleHelper.string(R.string.l10n_anomaly_normal_action)
                )
            }
        }
    }

    // Calculates the rolling arithmetic mean of recent BPM readings.
    fun getSmoothedBpm(): Int {
        synchronized(bpmHistoryBuffer) {
            if (bpmHistoryBuffer.isEmpty()) return 0 // no readings yet: never invent a BPM
            return bpmHistoryBuffer.average().toInt()
        }
    }

    // Clears the history buffer when beginning a new ECG recording session.
    fun resetBuffer() {
        synchronized(bpmHistoryBuffer) {
            bpmHistoryBuffer.clear()
        }
    }

    private fun getUtcTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.UK)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
