/*
 * Activity presenting comprehensive clinical review details for a patient's AD8232 Lead I ECG session.
 * Displays patient identity, hospital-grade calibrated 25 mm/s paper waveform, telemetry metrics,
 * AI rhythm interpretation, PDF report retrieval, and access to doctor clinical review notes.
 *
 * Reference List:
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End. [online] Available at: <https://www.analog.com/media/en/technical-documentation/data-sheets/AD8232.pdf> [Accessed 21 September 2026].
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 * - Google Developers, 2024. Material Components for Android. [online] Available at: <https://github.com/material-components/material-components-android> [Accessed 21 September 2026].
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import androidx.activity.viewModels
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import androidx.lifecycle.lifecycleScope              // (Google Developers, 2024)
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiClient
import com.example.prog7314_poepart23.data.repository.EcgRepository
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.DateTimeFormatter
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.utils.onClick
import com.example.prog7314_poepart23.utils.wireBack
import com.example.prog7314_poepart23.views.HospitalPaperEcgView
import com.google.android.material.button.MaterialButton // (Google Developers, 2024)
import kotlinx.coroutines.launch                     // (JetBrains, 2024)
import java.text.SimpleDateFormat
import java.util.Locale

import com.example.prog7314_poepart23.utils.enableFadeThroughEntry

class DoctorEcgSessionDetailsActivity : AppCompatActivity() { // (Google Developers, 2024)

    private val ecgRepository = EcgRepository()
    private val review: com.example.prog7314_poepart23.viewmodels.DoctorReviewViewModel by viewModels()

    private lateinit var tvPatientName: TextView
    private lateinit var tvPatientId: TextView
    private lateinit var tvRhythmStatus: TextView
    private lateinit var tvAvgBpm: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvSignalQuality: TextView
    private lateinit var tvReviewedBy: TextView
    private lateinit var tvRhythmInterpretation: TextView
    private lateinit var waveformHospitalPaperDoctor: HospitalPaperEcgView
    private lateinit var btnFlagForReview: MaterialButton
    private lateinit var btnDownloadPdf: MaterialButton
    private lateinit var btnMarkReviewed: MaterialButton

    private var sessionId: String? = null
    private var patientId: String? = null
    private var patientName: String? = null
    private var averageBpm: Int = 0
    private var durationSeconds: Int = 0
    private var startTimeUtc: String? = null
    private var signalQuality: String = "Unknown"
    private var sessionStatus: String = "Completed"
    private var loadedSamples: List<Int>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.doctor_ecg_session_details)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)

        extractIntentData()
        if (isFinishing) return
        bindViews()
        btnDownloadPdf.isEnabled = false; btnMarkReviewed.isEnabled = false; btnFlagForReview.isEnabled = false
        populateSessionMetrics()
        waveformHospitalPaperDoctor.visibility = android.view.View.GONE
        (waveformHospitalPaperDoctor.parent as? android.view.View)?.visibility = android.view.View.GONE
        review.sessionReview.observe(this) { result ->
            if (result is com.example.prog7314_poepart23.data.repository.PatientPortalResult.Ready) {
                val data = result.value
                patientId = data.patientId; patientName = data.patientName
                averageBpm = data.session.averageBpm; durationSeconds = data.session.durationSeconds
                startTimeUtc = data.session.startTimeUtc; signalQuality = data.session.signalQuality; sessionStatus = data.session.status
                populateSessionMetrics()
                tvReviewedBy.text = if (data.releaseState.resultsReleased) getString(R.string.doctor_released) else getString(R.string.ui_review_required)
                btnDownloadPdf.isEnabled = data.staffReportStatus.reportAvailable
                btnMarkReviewed.isEnabled = data.session.status == "Completed"
                tvReviewedBy.setOnClickListener(null)
                btnFlagForReview.isEnabled = data.session.status == "Completed" && data.draftNotes.isNotEmpty() && (!data.releaseState.resultsReleased || data.releaseState.hasUnreleasedDrafts)
                tvRhythmInterpretation.text = data.draftNotes.joinToString("\n\n") { it.clinicalImpression }.ifBlank { getString(R.string.ui_assessment_not_inferred) }
            } else {
                btnDownloadPdf.isEnabled = false; btnMarkReviewed.isEnabled = false; btnFlagForReview.isEnabled = false
                tvReviewedBy.text = getString(R.string.doctor_load_failed)
                tvReviewedBy.setOnClickListener { loadClinicalReviewStatus() }
            }
        }
        review.releaseResult.observe(this) { result ->
            tvReviewedBy.text = if (result is com.example.prog7314_poepart23.data.repository.PatientPortalResult.Ready)
                getString(R.string.doctor_released) else getString(R.string.doctor_load_failed)
        }
        loadClinicalReviewStatus()
        wireControls()

        BottomNavHelper.setUpDoctorNavDetached(this)
    }

    override fun onResume() {
        super.onResume()
        BottomNavHelper.setUpDoctorNavDetached(this)
        loadClinicalReviewStatus()
    }

    private fun extractIntentData() {
        sessionId = intent.getStringExtra(PatientEcgHistoryActivity.EXTRA_SESSION_ID)
        patientId = intent.getStringExtra(PatientListActivity.EXTRA_PATIENT_ID)
        patientName = intent.getStringExtra(DoctorAlertsActivity.EXTRA_PATIENT_NAME)
        // No fake clinical defaults - 0 means "not provided by the real session" and is
        // rendered as an unavailable/empty state below rather than a plausible-looking reading.
        averageBpm = intent.getIntExtra(PatientEcgHistoryActivity.EXTRA_AVERAGE_BPM, 0)
        durationSeconds = intent.getIntExtra(PatientEcgHistoryActivity.EXTRA_DURATION_SECONDS, 0)
        startTimeUtc = intent.getStringExtra(PatientEcgHistoryActivity.EXTRA_START_TIME_UTC)
        signalQuality = intent.getStringExtra(PatientEcgHistoryActivity.EXTRA_SIGNAL_QUALITY) ?: "Unknown"
        sessionStatus = intent.getStringExtra(PatientEcgHistoryActivity.EXTRA_SESSION_STATUS) ?: "Completed"

        if (sessionId.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.l10n_unable_load_session), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun bindViews() {
        tvPatientName = findViewById(R.id.tvPatientName)
        tvPatientId = findViewById(R.id.tvPatientId)
        tvRhythmStatus = findViewById(R.id.tvRhythmStatus)
        tvAvgBpm = findViewById(R.id.tvAvgBpm)
        tvDuration = findViewById(R.id.tvDuration)
        tvSignalQuality = findViewById(R.id.tvSignalQuality)
        tvReviewedBy = findViewById(R.id.tvReviewedBy)
        tvRhythmInterpretation = findViewById(R.id.tvRhythmInterpretation)
        waveformHospitalPaperDoctor = findViewById(R.id.waveformHospitalPaperDoctor)
        btnFlagForReview = findViewById(R.id.btnFlagForReview)
        btnDownloadPdf = findViewById(R.id.btnDownloadPdf)
        btnMarkReviewed = findViewById(R.id.btnMarkReviewed)
    }

    private fun populateSessionMetrics() {
        val displayName = patientName?.ifBlank { null }
            ?: patientId?.let { getString(R.string.l10n_patient_short_format, it.take(8)) }
            ?: getString(R.string.greeting_empty)
        tvPatientName.text = displayName

        val formattedDate = startTimeUtc?.let { iso ->
            try {
                val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.UK).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                parser.parse(iso)?.let { DateTimeFormatter.formatSessionDate(it.time) } ?: iso
            } catch (e: Exception) {
                iso
            }
        } ?: getString(R.string.l10n_recent_session)

        tvPatientId.text = getString(R.string.l10n_id_and_date_format, patientId ?: getString(R.string.l10n_unavailable), formattedDate)
        com.example.prog7314_poepart23.utils.HeartRateStatus.apply(tvAvgBpm, averageBpm, if (averageBpm > 0) getString(R.string.l10n_bpm_value, averageBpm) else getString(R.string.l10n_bpm_none))
        tvDuration.text = if (durationSeconds > 0) {
            String.format(Locale.UK, "%02d:%02d", durationSeconds / 60, durationSeconds % 60)
        } else {
            "--:--"
        }
        tvSignalQuality.text = com.example.prog7314_poepart23.utils.LocaleHelper.qualityLabel(this, signalQuality)

        tvRhythmStatus.text = com.example.prog7314_poepart23.utils.LocaleHelper.statusLabel(this, sessionStatus)
        tvRhythmStatus.setTextColor(ContextCompat.getColor(this, R.color.pulse_text_secondary))
        tvRhythmStatus.setBackgroundResource(R.drawable.bg_pill_neutral)
        tvRhythmInterpretation.text = getString(R.string.ui_assessment_not_inferred)
    }

    private fun loadClinicalReviewStatus() {
        sessionId?.let { review.loadSessionReview(it) }
    }

    private fun wireControls() {
        wireBack(R.id.btnBack)

        onClick(R.id.btnShare) {
            HapticFeedbackManager.lightTick()
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.l10n_share_clinical_subject_format, sessionId.orEmpty()))
                putExtra(
                    Intent.EXTRA_TEXT,
                    getString(R.string.l10n_share_clinical_body_format, tvPatientName.text.toString(), sessionId.orEmpty(), com.example.prog7314_poepart23.utils.BpmText.orDash(averageBpm), tvDuration.text.toString(), tvSignalQuality.text.toString(), tvRhythmStatus.text.toString())
                )
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.l10n_share_clinical_chooser)))
        }

        onClick(R.id.btnDownloadPdf) {
            sessionId?.let { id -> lifecycleScope.launch {
                com.example.prog7314_poepart23.utils.ClinicalPdfReportGenerator.downloadAndOpenAuthoritativeReport(this@DoctorEcgSessionDetailsActivity, id, true)
            } }
        }
        btnFlagForReview.text = getString(R.string.doctor_release)
        onClick(R.id.btnFlagForReview) {
            val id = sessionId ?: return@onClick
            val message = TextView(this).apply { text = getString(R.string.doctor_release_confirm); textSize = 16f }
            com.example.prog7314_poepart23.utils.PulseLinkModal.show(this, getString(R.string.doctor_release), message,
                listOf(getString(R.string.doctor_release) to { dialog ->
                    btnFlagForReview.isEnabled = false
                    review.releaseSessionResults(id)
                    dialog.dismiss()
                }, getString(R.string.cancel) to { it.dismiss() }))
        }

        onClick(R.id.btnMarkReviewed) {
            HapticFeedbackManager.confirmClick()
            val id = sessionId
            val pId = patientId
            if (id.isNullOrBlank() || pId.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.l10n_session_info_incomplete), Toast.LENGTH_SHORT).show()
                return@onClick
            }
            val intent = Intent(this, DoctorSessionNotesActivity::class.java).apply {
                putExtra(PatientEcgHistoryActivity.EXTRA_SESSION_ID, id)
                putExtra(PatientListActivity.EXTRA_PATIENT_ID, pId)
                putExtra(DoctorAlertsActivity.EXTRA_PATIENT_NAME, patientName)
                putExtra(PatientEcgHistoryActivity.EXTRA_AVERAGE_BPM, averageBpm)
            }
            startActivity(intent)
        }

    }
}
