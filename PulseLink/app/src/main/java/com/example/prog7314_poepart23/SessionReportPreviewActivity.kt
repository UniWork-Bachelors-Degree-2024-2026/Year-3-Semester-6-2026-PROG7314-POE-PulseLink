/*
 * Activity presenting a clinical ECG PDF report preview for an AD8232 recording session,
 * providing options to download the formal PDF report or dispatch it directly to the assigned doctor.
 *
 * Reference List:
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End. [online] Available at: <https://www.analog.com/media/en/technical-documentation/data-sheets/AD8232.pdf> [Accessed 21 September 2026].
 * - Google Cloud, 2024. Firebase Authentication Documentation. [online] Available at: <https://firebase.google.com/docs/auth> [Accessed 21 September 2026].
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.prog7314_poepart23.utils.ClinicalPdfReportGenerator
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.viewmodels.SessionHistoryViewModel
import com.example.prog7314_poepart23.views.HospitalPaperEcgView
import com.google.firebase.auth.FirebaseAuth         // (Google Cloud, 2024)
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SessionReportPreviewActivity : AppCompatActivity() { // (Google Developers, 2024)

    companion object {
        const val EXTRA_SESSION_SAMPLES = "extra_session_samples"
        const val EXTRA_IS_DOCTOR = "extra_is_doctor"
        const val EXTRA_PATIENT_ID = "extra_patient_id"
        const val EXTRA_PATIENT_NAME = "extra_patient_name"
    }

    private val sessionHistoryViewModel: SessionHistoryViewModel by viewModels()

    private lateinit var ivClose: ImageView
    private lateinit var ivShare: ImageView
    private lateinit var tvReportId: TextView
    private lateinit var tvPatientName: TextView
    private lateinit var tvPatientDetails: TextView
    private lateinit var tvHeartRate: TextView
    private lateinit var tvClinicalAnalysis: TextView
    private lateinit var tvAvgHeartRate: TextView
    private lateinit var tvDuration: TextView
    private lateinit var btnSendToDoctor: TextView
    private lateinit var btnDownloadPdf: TextView

    // Interactive waveform strip & review controls
    private lateinit var waveformView: HospitalPaperEcgView
    private lateinit var btnZoomIn: ImageView
    private lateinit var btnZoomOut: ImageView
    private lateinit var btnToggleEnlarge: ImageView

    private var sessionId: String? = null
    private var averageBpm: Int = 0
    private var durationSeconds: Int = 0
    private var startTimeUtc: String? = null
    private var signalQuality: String = "Unknown"
    private var sessionStatus: String = "Completed"
    private var sessionSamplesList: List<Int>? = null
    private var isDoctorView: Boolean = false
    private var passedPatientId: String? = null
    private var passedPatientName: String? = null

    private val auth = FirebaseAuth.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.patient_session_report_preview)

        HapticFeedbackManager.initialise(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        extractIntentData()
        if (isFinishing) return
        bindViews()
        populateReportData()
        setupClickListeners()
        loadSessionSamples()
    }

    private fun loadSessionSamples() {
        if (!sessionSamplesList.isNullOrEmpty()) {
            waveformView.setSamples(sessionSamplesList!!)
            return
        }
        val id = sessionId ?: return
        sessionHistoryViewModel.sessionSamples.observe(this) { resource ->
            when (resource) {
                is Resource.Success -> {
                    val samples = resource.data?.samples.orEmpty()
                    if (samples.isNotEmpty()) {
                        sessionSamplesList = samples
                        waveformView.setSamples(samples)
                    } else loadCachedSamples(id)
                }
                is Resource.Error -> loadCachedSamples(id)
                is Resource.Loading -> Unit
            }
        }
        sessionHistoryViewModel.loadSessionSamples(id)
    }

    private fun loadCachedSamples(id: String) {
        lifecycleScope.launch {
            val samples = withContext(Dispatchers.IO) {
                com.example.prog7314_poepart23.data.local.OfflineEcgDatabase.getInstance(this@SessionReportPreviewActivity).getSamplesForSession(id)
            }
            sessionSamplesList = samples
            waveformView.setSamples(samples)
        }
    }

    private fun extractIntentData() {
        sessionId = intent.getStringExtra(PatientEcgHistoryActivity.EXTRA_SESSION_ID)
        averageBpm = intent.getIntExtra(PatientEcgHistoryActivity.EXTRA_AVERAGE_BPM, 0)
        durationSeconds = intent.getIntExtra(PatientEcgHistoryActivity.EXTRA_DURATION_SECONDS, 0)
        startTimeUtc = intent.getStringExtra(PatientEcgHistoryActivity.EXTRA_START_TIME_UTC)
        signalQuality = intent.getStringExtra(PatientEcgHistoryActivity.EXTRA_SIGNAL_QUALITY) ?: "Unknown"
        sessionStatus = intent.getStringExtra(PatientEcgHistoryActivity.EXTRA_SESSION_STATUS) ?: "Completed"

        isDoctorView = intent.getBooleanExtra(EXTRA_IS_DOCTOR, false) ||
                com.example.prog7314_poepart23.security.SecurePreferences.getInstance(this).getUserRole().equals("Doctor", ignoreCase = true)
        passedPatientId = intent.getStringExtra(EXTRA_PATIENT_ID)

        passedPatientName = intent.getStringExtra(EXTRA_PATIENT_NAME)

        val passedSamples = intent.getIntegerArrayListExtra(EXTRA_SESSION_SAMPLES)
        if (!passedSamples.isNullOrEmpty()) {
            sessionSamplesList = passedSamples
        }

        if (sessionId.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.l10n_report_unable_load), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun bindViews() {
        ivClose = findViewById(R.id.ivClose)
        ivShare = findViewById(R.id.ivShare)
        tvReportId = findViewById(R.id.tvReportId)
        tvPatientName = findViewById(R.id.tvPatientName)
        tvPatientDetails = findViewById(R.id.tvPatientDetails)
        tvHeartRate = findViewById(R.id.tvHeartRate)
        tvClinicalAnalysis = findViewById(R.id.tvClinicalAnalysis)
        tvAvgHeartRate = findViewById(R.id.tvAvgHeartRate)
        tvDuration = findViewById(R.id.tvDuration)
        btnSendToDoctor = findViewById(R.id.btnSendToDoctor)
        btnDownloadPdf = findViewById(R.id.btnDownloadPdf)

        waveformView = findViewById(R.id.waveformHospitalPaper)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        btnToggleEnlarge = findViewById(R.id.btnToggleEnlarge)
    }

    private fun populateReportData() {
        val user = auth.currentUser
        val displayName = if (isDoctorView) {
            passedPatientName
        } else {
            user?.displayName?.ifBlank { null } ?: user?.email?.substringBefore("@")
        }
        val patientUid = if (isDoctorView) {
            passedPatientId?.take(6)?.uppercase(Locale.UK)
        } else {
            user?.uid?.take(6)?.uppercase(Locale.UK)
        }

        tvReportId.text = getString(R.string.report_id_format, sessionId.orEmpty().takeLast(6).uppercase(Locale.UK))
        tvPatientName.text = displayName ?: getString(R.string.greeting_empty)
        tvPatientDetails.text = if (patientUid != null) getString(R.string.l10n_patient_details_mrn_format, patientUid) else getString(R.string.l10n_lead_only)
        com.example.prog7314_poepart23.utils.HeartRateStatus.apply(tvHeartRate, averageBpm, if (averageBpm > 0) getString(R.string.l10n_bpm_value, averageBpm) else getString(R.string.l10n_bpm_none))
        com.example.prog7314_poepart23.utils.HeartRateStatus.apply(tvAvgHeartRate, averageBpm, if (averageBpm > 0) getString(R.string.l10n_bpm_value, averageBpm) else getString(R.string.l10n_bpm_none))
        tvDuration.text = String.format(Locale.UK, "%02d:%02d", durationSeconds / 60, durationSeconds % 60)

        if (isDoctorView) {
            btnSendToDoctor.text = getString(R.string.ui_provide_doctor_notes)
            btnSendToDoctor.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_edit, 0, 0, 0)
            btnSendToDoctor.compoundDrawablePadding = 8
        }

        tvClinicalAnalysis.text = getString(R.string.ui_review_required)
        tvClinicalAnalysis.setTextColor(ContextCompat.getColor(this, R.color.pulse_text_secondary))
        tvClinicalAnalysis.setBackgroundResource(R.drawable.bg_pill_neutral)
    }

    private fun setupClickListeners() {
        ivClose.setOnClickListener {
            HapticFeedbackManager.lightTick()
            finish()
        }

        ivShare.setOnClickListener {
            HapticFeedbackManager.lightTick()
            shareReport()
        }

        btnSendToDoctor.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            if (isDoctorView && !passedPatientId.isNullOrBlank()) {
                startActivity(Intent(this, DoctorSessionNotesActivity::class.java).apply {
                    putExtra(PatientEcgHistoryActivity.EXTRA_SESSION_ID, sessionId)
                    putExtra(PatientListActivity.EXTRA_PATIENT_ID, passedPatientId)
                    putExtra(DoctorAlertsActivity.EXTRA_PATIENT_NAME, passedPatientName)
                    putExtra(PatientEcgHistoryActivity.EXTRA_AVERAGE_BPM, averageBpm)
                })
            } else {
                Toast.makeText(this, getString(R.string.l10n_already_visible_care_team), Toast.LENGTH_SHORT).show()
            }
        }

        btnDownloadPdf.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            downloadPdfReport()
        }

        btnZoomIn.setOnClickListener {
            HapticFeedbackManager.lightTick()
            waveformView.zoomIn()
        }

        btnZoomOut.setOnClickListener {
            HapticFeedbackManager.lightTick()
            waveformView.zoomOut()
        }

        btnToggleEnlarge.setOnClickListener {
            HapticFeedbackManager.lightTick()
            showEnlargedWaveformDialog()
        }

        waveformView.setOnClickListener {
            HapticFeedbackManager.lightTick()
            showEnlargedWaveformDialog()
        }
    }

    // Displays full-screen high-resolution modal for clinical review of the entire 90s recording
    private fun showEnlargedWaveformDialog() {
        val dialog = android.app.Dialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_enlarged_ecg_strip, null)
        dialog.setContentView(view)

        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.95).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val tvTitle = view.findViewById<TextView>(R.id.tvEnlargedTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvEnlargedSubtitle)
        val dialogWaveformView = view.findViewById<HospitalPaperEcgView>(R.id.waveformEnlargedPaper)
        val btnClose = view.findViewById<android.widget.ImageButton>(R.id.btnDialogClose)
        val btnDialogZoomIn = view.findViewById<android.widget.ImageButton>(R.id.btnDialogZoomIn)
        val btnDialogZoomOut = view.findViewById<android.widget.ImageButton>(R.id.btnDialogZoomOut)
        val btnDialogFitToggle = view.findViewById<android.widget.ImageButton>(R.id.btnDialogFitToggle)

        tvTitle.text = getString(R.string.ui_ecg_review_title, getString(R.string.l10n_bpm_value, averageBpm))
        tvSubtitle.text = getString(R.string.rt_ecg_strip_subtitle, String.format(Locale.UK, "%02d:%02d", durationSeconds / 60, durationSeconds % 60))

        val samples = sessionSamplesList.orEmpty()
        dialogWaveformView.setSamples(samples)

        btnDialogZoomIn.setOnClickListener {
            HapticFeedbackManager.lightTick()
            dialogWaveformView.zoomIn()
        }
        btnDialogZoomOut.setOnClickListener {
            HapticFeedbackManager.lightTick()
            dialogWaveformView.zoomOut()
        }
        btnDialogFitToggle.setOnClickListener {
            HapticFeedbackManager.lightTick()
            dialogWaveformView.toggleFitToScreen()
        }
        btnClose.setOnClickListener {
            HapticFeedbackManager.lightTick()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun shareReport() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_SUBJECT,
                getString(R.string.l10n_share_report_subject_format, sessionId.orEmpty())
            )
            putExtra(
                Intent.EXTRA_TEXT,
                getString(R.string.l10n_share_report_body_format, sessionId.orEmpty(), com.example.prog7314_poepart23.utils.BpmText.orDash(averageBpm), durationSeconds, com.example.prog7314_poepart23.utils.LocaleHelper.qualityLabel(this@SessionReportPreviewActivity, signalQuality))
            )
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.l10n_share_report_chooser)))
    }

    private fun downloadPdfReport() {
        val id = sessionId ?: return
        val user = auth.currentUser
        val pId = if (isDoctorView) passedPatientId else user?.uid
        if (pId.isNullOrBlank()) {
            Toast.makeText(this, R.string.l10n_no_patient_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val pName = if (isDoctorView) passedPatientName else user?.displayName?.ifBlank { null } ?: user?.email
        val metadata = ClinicalPdfReportGenerator.ReportMetadata(
            sessionId = id,
            patientName = pName.orEmpty(),
            patientId = pId,
            averageBpm = averageBpm,
            durationSeconds = durationSeconds,
            startTimeUtc = startTimeUtc,
            signalQuality = signalQuality,
            samples = sessionSamplesList
        )
        ClinicalPdfReportGenerator.generateAndOpenReport(this, metadata)
    }
}
