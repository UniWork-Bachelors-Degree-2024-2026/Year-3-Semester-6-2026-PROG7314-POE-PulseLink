/*
 * Activity displaying detailed clinical metrics, Lead I rhythm strip,
 * hardware configuration parameters, and PDF report export actions for an ECG recording session.
 *
 * Reference List:
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End. [online] Available at: <https://www.analog.com/media/en/technical-documentation/data-sheets/AD8232.pdf> [Accessed 21 September 2026].
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 * - Google Developers, 2024. ViewModel Overview - Android Developers. [online] Available at: <https://developer.android.com/topic/libraries/architecture/viewmodel> [Accessed 21 September 2026].
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.activity.viewModels                   // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import androidx.lifecycle.lifecycleScope              // (Google Developers, 2024)
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.viewmodels.SessionHistoryViewModel
import kotlinx.coroutines.launch                     // (JetBrains, 2024)
import java.text.SimpleDateFormat
import java.util.Locale

class SessionDetailsActivity : AppCompatActivity() { // (Google Developers, 2024)

    private val sessionHistoryViewModel: SessionHistoryViewModel by viewModels()

    private lateinit var ivBack: ImageView
    private lateinit var tvSessionTitle: TextView
    private lateinit var ivRhythmStatusIcon: ImageView
    private lateinit var tvRhythmStatus: TextView
    private lateinit var tvAvgBpm: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvDoctorNotes: TextView
    private lateinit var tvSignalQuality: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvPowerSource: TextView
    private lateinit var tvLeadConfiguration: TextView
    private lateinit var tvSamplingRate: TextView
    private lateinit var btnDownloadPdf: TextView

    private var sessionId: String? = null
    private var averageBpm: Int = 0
    private var durationSeconds: Int = 0
    private var startTimeUtc: String? = null
    private var signalQuality: String = "Unknown"
    private var sessionStatus: String = "Completed"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.patient_ecg_session_details)

        HapticFeedbackManager.initialise(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        extractIntentData()
        bindViews()
        populateSessionDetails()
        setupClickListeners()
        loadSessionSamples()
        loadDoctorNotes()
    }

    private fun extractIntentData() {
        sessionId = intent.getStringExtra(PatientEcgHistoryActivity.EXTRA_SESSION_ID)
        averageBpm = intent.getIntExtra(PatientEcgHistoryActivity.EXTRA_AVERAGE_BPM, 0)
        durationSeconds = intent.getIntExtra(PatientEcgHistoryActivity.EXTRA_DURATION_SECONDS, 0)
        startTimeUtc = intent.getStringExtra(PatientEcgHistoryActivity.EXTRA_START_TIME_UTC)
        signalQuality = intent.getStringExtra(PatientEcgHistoryActivity.EXTRA_SIGNAL_QUALITY) ?: "Unknown"
        sessionStatus = intent.getStringExtra(PatientEcgHistoryActivity.EXTRA_SESSION_STATUS) ?: "Completed"

        if (sessionId.isNullOrBlank()) {
            android.widget.Toast.makeText(this, getString(R.string.l10n_unable_load_session), android.widget.Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun bindViews() {
        ivBack = findViewById(R.id.ivBack)
        tvSessionTitle = findViewById(R.id.tvSessionTitle)
        ivRhythmStatusIcon = findViewById(R.id.ivRhythmStatusIcon)
        tvRhythmStatus = findViewById(R.id.tvRhythmStatus)
        tvAvgBpm = findViewById(R.id.tvAvgBpm)
        tvDuration = findViewById(R.id.tvDuration)
        tvDoctorNotes = findViewById(R.id.tvDoctorNotes)
        tvSignalQuality = findViewById(R.id.tvSignalQuality)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        tvPowerSource = findViewById(R.id.tvPowerSource)
        tvLeadConfiguration = findViewById(R.id.tvLeadConfiguration)
        tvSamplingRate = findViewById(R.id.tvSamplingRate)
        btnDownloadPdf = findViewById(R.id.btnDownloadPdf)
    }

    private fun populateSessionDetails() {
        tvSessionTitle.text = formatSessionTitle(startTimeUtc)
        com.example.prog7314_poepart23.utils.HeartRateStatus.apply(tvAvgBpm, averageBpm, if (averageBpm > 0) getString(R.string.l10n_bpm_value, averageBpm) else getString(R.string.l10n_bpm_none))
        tvDuration.text = getString(R.string.l10n_duration_min_format, durationSeconds / 60, durationSeconds % 60)

        tvRhythmStatus.text = com.example.prog7314_poepart23.utils.LocaleHelper.statusLabel(this, sessionStatus)
        tvRhythmStatus.setTextColor(ContextCompat.getColor(this, R.color.pulse_text_secondary))
        ivRhythmStatusIcon.setImageResource(R.drawable.status_dot_grey)
        tvDoctorNotes.text = getString(R.string.l10n_loading_notes)

        tvSignalQuality.text = com.example.prog7314_poepart23.utils.LocaleHelper.qualityLabel(this, signalQuality)
        tvDeviceInfo.text = getString(R.string.device_model_esp32_ad8232)
        tvPowerSource.text = getString(R.string.l10n_usb_powered)
        tvLeadConfiguration.text = getString(R.string.patient_ecg_session_details_text_lead_i)
        tvSamplingRate.text = getString(R.string.doctor_session_notes_text_250_hz)
    }

    // Loads the real clinical note(s) recorded by a doctor for this session from the
    // backend. No clinical interpretation is ever invented locally.
    private fun loadDoctorNotes() {
        val id = sessionId ?: return
        lifecycleScope.launch {
            when (val result = com.example.prog7314_poepart23.data.repository.EcgRepository().getDoctorNotes(id)) {
                is Resource.Success -> {
                    val notes = result.data
                    tvDoctorNotes.text = if (notes.isNullOrEmpty()) {
                        getString(R.string.l10n_no_clinical_notes_yet)
                    } else {
                        notes.joinToString("\n\n") { it.clinicalImpression }
                    }
                }
                is Resource.Error -> {
                    tvDoctorNotes.text = getString(R.string.l10n_unable_load_clinical_notes)
                }
                is Resource.Loading -> Unit
            }
        }
    }

    private fun setupClickListeners() {
        ivBack.setOnClickListener {
            HapticFeedbackManager.lightTick()
            finish()
        }

        btnDownloadPdf.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            val intent = Intent(this, SessionReportPreviewActivity::class.java).apply {
                putExtra(PatientEcgHistoryActivity.EXTRA_SESSION_ID, sessionId)
                putExtra(PatientEcgHistoryActivity.EXTRA_AVERAGE_BPM, averageBpm)
                putExtra(PatientEcgHistoryActivity.EXTRA_DURATION_SECONDS, durationSeconds)
                putExtra(PatientEcgHistoryActivity.EXTRA_START_TIME_UTC, startTimeUtc)
                putExtra(PatientEcgHistoryActivity.EXTRA_SIGNAL_QUALITY, signalQuality)
                putExtra(PatientEcgHistoryActivity.EXTRA_SESSION_STATUS, sessionStatus)
            }
            startActivity(intent)
        }

        // Live or reconstructed waveform strips on client Android screens are retired
        // in favour of authoritative server-generated landscape PDF reports.
        findViewById<android.view.View>(R.id.cardWaveformPreview)?.visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.waveformHospitalPaper)?.visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.scrollEcgWaveform)?.visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.ivZoomIn)?.visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.ivZoomOut)?.visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.ivFullscreen)?.visibility = android.view.View.GONE
    }

    private fun loadSessionSamples() {
        // Telemetry samples are preserved on the server and rendered into authoritative
        // A4/A3 landscape PDF reports. Client-side reconstructed canvas is retired.
    }

    private fun formatSessionTitle(isoTimestamp: String?): String {
        if (isoTimestamp.isNullOrBlank()) return getString(R.string.l10n_session_report)
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.UK).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val date = parser.parse(isoTimestamp)
            if (date != null) {
                val formatter = SimpleDateFormat("d MMM yyyy", resources.configuration.locales[0])
                getString(R.string.l10n_session_report_dated_format, formatter.format(date))
            } else {
                getString(R.string.l10n_session_report)
            }
        } catch (e: Exception) {
            getString(R.string.l10n_session_report)
        }
    }
}
