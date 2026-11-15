/*
 * Deep diagnostic view for clinical alert telemetry inspection,
 * displaying patient biometric context and triage action triggers.
 *
 * Reference List:
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.prog7314_poepart23.security.SecurePreferences
import com.example.prog7314_poepart23.utils.DateTimeFormatter
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.onClick
import com.example.prog7314_poepart23.utils.toastOnClick
import com.example.prog7314_poepart23.utils.wireBack

class DoctorAlertDetailsActivity : AppCompatActivity() { // (Google Developers, 2024)

    private var alertId: String? = null
    private var patientId: String? = null
    private var patientName: String? = null
    private var sessionId: String? = null
    private var severity: String = "Warning"
    private var alertType: String = ""
    private var message: String = ""
    private var estimatedBpm: Int = 0
    private var timestampUtc: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.doctor_alert_details)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)
        alertId = intent.getStringExtra(DoctorAlertsActivity.EXTRA_ALERT_ID)
        patientId = intent.getStringExtra(DoctorAlertsActivity.EXTRA_PATIENT_ID)
        patientName = intent.getStringExtra(DoctorAlertsActivity.EXTRA_PATIENT_NAME)
        sessionId = intent.getStringExtra(DoctorAlertsActivity.EXTRA_SESSION_ID)
        severity = intent.getStringExtra(DoctorAlertsActivity.EXTRA_SEVERITY) ?: "Warning"
        alertType = intent.getStringExtra(DoctorAlertsActivity.EXTRA_ALERT_TYPE) ?: ""
        message = intent.getStringExtra(DoctorAlertsActivity.EXTRA_MESSAGE) ?: ""
        estimatedBpm = intent.getIntExtra(DoctorAlertsActivity.EXTRA_BPM, 0)
        timestampUtc = intent.getStringExtra(DoctorAlertsActivity.EXTRA_TIMESTAMP)

        if (alertId.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.l10n_unable_load_alert_details), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        bindAlertData()
        loadWaveformSnapshot()
        wireControls()
    }

    private fun loadWaveformSnapshot() {
        val ecgView = findViewById<com.example.prog7314_poepart23.views.HospitalPaperEcgView?>(R.id.hospitalPaperEcgView) ?: return

        lifecycleScope.launch {
            val samples = sessionId?.let { id ->
                try {
                    val repo = com.example.prog7314_poepart23.data.repository.EcgRepository()
                    val res = repo.getSessionSamples(id)
                    if (res is com.example.prog7314_poepart23.utils.Resource.Success && !res.data?.samples.isNullOrEmpty()) {
                        res.data?.samples
                    } else {
                        val local = withContext(Dispatchers.IO) {
                            com.example.prog7314_poepart23.data.local.OfflineEcgDatabase.getInstance(this@DoctorAlertDetailsActivity).getSamplesForSession(id)
                        }
                        if (local.isNotEmpty()) local else null
                    }
                } catch (_: Exception) { null }
            } ?: emptyList()

            ecgView.setSamples(samples)
        }
    }

    private fun bindAlertData() {
        val tvSeverity = findViewById<TextView?>(R.id.tvAlertSeverity)
        val tvTimestamp = findViewById<TextView?>(R.id.tvAlertTimestamp)
        val tvTitle = findViewById<TextView?>(R.id.tvAlertTitle)
        val tvMessage = findViewById<TextView?>(R.id.tvAlertMessage)
        val tvPatientName = findViewById<TextView?>(R.id.tvPatientName)
        val tvPatientId = findViewById<TextView?>(R.id.tvPatientId)
        val tvBpm = findViewById<TextView?>(R.id.tvAlertBpm)

        tvSeverity?.text = when (severity.trim().lowercase()) {
            "critical" -> getString(R.string.severity_critical)
            "warning" -> getString(R.string.severity_warning)
            "system" -> getString(R.string.severity_system)
            else -> severity
        }
        tvTimestamp?.text = timestampUtc?.let { iso ->
            try {
                val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.UK).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                parser.parse(iso)?.let { DateTimeFormatter.formatSessionDate(it.time) } ?: iso
            } catch (e: Exception) {
                iso
            }
        } ?: "--"
        tvTitle?.text = com.example.prog7314_poepart23.utils.LocaleHelper.alertTypeLabel(this, alertType).ifBlank { getString(R.string.doctor_alert_details_text_tachycardia_detected) }
        tvMessage?.text = message
        tvPatientName?.text = patientName ?: getString(R.string.greeting_empty)
        tvPatientId?.text = patientId?.let { getString(R.string.l10n_id_format, it) } ?: ""
        tvBpm?.text = if (estimatedBpm > 0) "$estimatedBpm" else "--"
    }

    // Header navigation, session review, plus the triage actions.
    private fun wireControls() {
        wireBack(R.id.btnBack)

        onClick(R.id.btnViewEcgSession) {
            HapticFeedbackManager.lightTick()
            val id = sessionId
            if (id.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.l10n_no_session_linked_alert), Toast.LENGTH_SHORT).show()
                return@onClick
            }
            val intent = Intent(this, DoctorEcgSessionDetailsActivity::class.java).apply {
                putExtra(PatientEcgHistoryActivity.EXTRA_SESSION_ID, id)
                putExtra(PatientListActivity.EXTRA_PATIENT_ID, patientId)
                putExtra(DoctorAlertsActivity.EXTRA_PATIENT_NAME, patientName)
                putExtra(PatientEcgHistoryActivity.EXTRA_AVERAGE_BPM, estimatedBpm)
                putExtra(PatientEcgHistoryActivity.EXTRA_START_TIME_UTC, timestampUtc)
            }
            startActivity(intent)
        }

        onClick(R.id.btnDismissAlert) {
            HapticFeedbackManager.confirmClick()
            alertId?.let { id ->
                SecurePreferences.getInstance(this).dismissAlert(id)
            }
            Toast.makeText(this, R.string.action_alert_dismissed, Toast.LENGTH_SHORT).show()
            finish()
        }

        toastOnClick(R.id.btnEscalateAlert, R.string.action_alert_escalated)
    }
}
