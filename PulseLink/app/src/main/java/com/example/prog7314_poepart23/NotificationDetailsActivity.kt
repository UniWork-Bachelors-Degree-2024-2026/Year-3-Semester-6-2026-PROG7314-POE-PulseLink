/**
 * PulseLink Notification & Telemetry Alert Details Activity
 *
 * Presents comprehensive details of a clinical notification or rhythm anomaly alert,
 * linking to ECG telemetry reports and urgent triage actions (AHA, 2020; Google Developers, 2024).
 *
 * Reference List:
 * - American Heart Association (AHA), 2020. Recommendations for the Standardization and Interpretation of the Electrocardiogram. Circulation, 142(15), pp. 810-830.
 * - Google Developers, 2024. View binding and Material Design components in Android. Android Developers. Available at: <https://developer.android.com/guide/topics/ui> [Accessed 18 September 2026].
 */

package com.example.prog7314_poepart23

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.prog7314_poepart23.data.repository.NotificationRepository
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.google.android.material.button.MaterialButton

class NotificationDetailsActivity : AppCompatActivity() {

    private lateinit var ivBack: ImageView
    private lateinit var ivTrash: ImageView
    private lateinit var ivNotificationIcon: ImageView
    private lateinit var tvNotificationCategory: TextView
    private lateinit var tvNotificationTimestamp: TextView
    private lateinit var tvNotificationTitle: TextView
    private lateinit var tvNotificationBody: TextView
    private lateinit var cardRelatedSession: CardView
    private lateinit var tvSessionTimestamp: TextView
    private lateinit var tvSessionAvgBpm: TextView
    private lateinit var tvSessionRhythmPill: TextView
    private lateinit var btnViewReport: MaterialButton
    private lateinit var btnSendToDoctor: MaterialButton

    private var notificationOwner: String? = null
    private var notificationId: String? = null
    private var currentNotification: com.example.prog7314_poepart23.data.models.NotificationItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.patient_notification_details)

        // Adjust window insets (Google Developers, 2024)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        notificationOwner = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        runCatching { NotificationRepository.initialise(this) }
        notificationId = intent.getStringExtra(NotificationsActivity.EXTRA_NOTIFICATION_ID)
        notificationId?.let { NotificationRepository.markAsRead(it) }

        initViews()
        populateNotificationDetails()
        setupListeners()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationId = intent.getStringExtra(NotificationsActivity.EXTRA_NOTIFICATION_ID)
        notificationId?.let { NotificationRepository.markAsRead(it) }
        populateNotificationDetails()
    }

    override fun onResume() {
        super.onResume()
        if (notificationOwner == null || com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid != notificationOwner) finish()
    }

    private fun initViews() {
        ivBack = findViewById(R.id.ivBack)
        ivTrash = findViewById(R.id.ivTrash)
        ivNotificationIcon = findViewById(R.id.ivNotificationIcon)
        tvNotificationCategory = findViewById(R.id.tvNotificationCategory)
        tvNotificationTimestamp = findViewById(R.id.tvNotificationTimestamp)
        tvNotificationTitle = findViewById(R.id.tvNotificationTitle)
        tvNotificationBody = findViewById(R.id.tvNotificationBody)
        cardRelatedSession = findViewById(R.id.cardRelatedSession)
        tvSessionTimestamp = findViewById(R.id.tvSessionTimestamp)
        tvSessionAvgBpm = findViewById(R.id.tvSessionAvgBpm)
        tvSessionRhythmPill = findViewById(R.id.tvSessionRhythmPill)
        btnViewReport = findViewById(R.id.btnViewReport)
        btnSendToDoctor = findViewById(R.id.btnSendToDoctor)
    }

    private fun populateNotificationDetails() {
        val notif = notificationId?.let { NotificationRepository.getNotificationById(it) }
        currentNotification = notif
        if (notif == null) {
            Toast.makeText(this, getString(R.string.l10n_report_unable_load), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tvNotificationTitle.text = notif.title
        tvNotificationBody.text = notif.messagePreview
        tvNotificationTimestamp.text = notif.timestampLabel
        ivNotificationIcon.setImageResource(notif.iconRes)

        val isAlert = notif.title.contains("Alert", ignoreCase = true) || notif.title.contains("Tachycardia", ignoreCase = true)
        val isReport = !notif.sessionId.isNullOrBlank()

        when {
            isAlert -> {
                tvNotificationCategory.text = getString(R.string.l10n_cat_health_alert)
                tvNotificationCategory.setBackgroundResource(R.drawable.bg_pill_danger)
                tvNotificationCategory.setTextColor(getColor(R.color.pulse_danger))
            }
            isReport -> {
                tvNotificationCategory.text = getString(R.string.l10n_cat_report)
                tvNotificationCategory.setBackgroundResource(R.drawable.bg_pill_success)
                tvNotificationCategory.setTextColor(getColor(R.color.pulse_success))
            }
            else -> {
                tvNotificationCategory.text = getString(R.string.l10n_cat_clinical_note)
                tvNotificationCategory.setBackgroundResource(R.drawable.bg_pill_info)
                tvNotificationCategory.setTextColor(getColor(R.color.pulse_primary))
            }
        }

        // Only render the related-session card (and enable "View Report") when the push payload
        // actually carried a linked session. No sessionId means no report to show - showing a
        // fabricated "Normal Sinus / 72 BPM" card here would be clinically misleading.
        val hasSessionData = (isAlert || isReport) && !notif.sessionId.isNullOrBlank()
        if (hasSessionData) {
            cardRelatedSession.visibility = View.VISIBLE
            btnViewReport.visibility = View.VISIBLE

            tvSessionTimestamp.text = notif.sessionTimestampLabel ?: notif.timestampLabel
            com.example.prog7314_poepart23.utils.HeartRateStatus.apply(tvSessionAvgBpm, notif.avgBpm, notif.avgBpm?.let { getString(R.string.l10n_bpm_value, it) } ?: getString(R.string.l10n_bpm_none))

            tvSessionRhythmPill.text = com.example.prog7314_poepart23.utils.LocaleHelper.statusLabel(this, notif.sessionStatus)
            tvSessionRhythmPill.setBackgroundResource(R.drawable.bg_pill_neutral)
            tvSessionRhythmPill.setTextColor(getColor(R.color.pulse_text_secondary))
        } else {
            cardRelatedSession.visibility = View.GONE
            btnViewReport.visibility = View.GONE
        }
    }

    private fun setupListeners() {
        ivBack.setOnClickListener {
            HapticFeedbackManager.lightTick()
            finish()
        }

        ivTrash.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            notificationId?.let { id ->
                NotificationRepository.deleteNotification(id)
                Toast.makeText(this, getString(R.string.l10n_notification_deleted), Toast.LENGTH_SHORT).show()
            }
            finish()
        }

        btnViewReport.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            val notif = currentNotification
            val sessionId = notif?.sessionId
            if (notif == null || sessionId.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.l10n_no_session_report_attached), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val doctor = com.example.prog7314_poepart23.security.SecurePreferences.getInstance(this).getUserRole().equals("doctor", true)
            val reportIntent = Intent(this, if (doctor) DoctorEcgSessionDetailsActivity::class.java else SessionReportPreviewActivity::class.java).apply {
                putExtra(PatientEcgHistoryActivity.EXTRA_SESSION_ID, sessionId)
                notif.avgBpm?.let { putExtra(PatientEcgHistoryActivity.EXTRA_AVERAGE_BPM, it) }
                notif.durationSeconds?.let { putExtra(PatientEcgHistoryActivity.EXTRA_DURATION_SECONDS, it) }
                notif.startTimeUtc?.let { putExtra(PatientEcgHistoryActivity.EXTRA_START_TIME_UTC, it) }
                notif.signalQuality?.let { putExtra(PatientEcgHistoryActivity.EXTRA_SIGNAL_QUALITY, it) }
                notif.sessionStatus?.let { putExtra(PatientEcgHistoryActivity.EXTRA_SESSION_STATUS, it) }
            }
            startActivity(reportIntent)
        }

        btnSendToDoctor.visibility = View.GONE
        btnSendToDoctor.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            Toast.makeText(this, getString(R.string.l10n_report_forwarded), Toast.LENGTH_SHORT).show()
        }
    }
}
