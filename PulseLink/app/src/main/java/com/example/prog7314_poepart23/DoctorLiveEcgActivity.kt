/*
 * Activity facilitating real-time remote physician observation of an active patient Lead I ECG telemetry stream from the AD8232 heart monitor.
 *
 * Reference List:
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End. [online] Available at: <https://www.analog.com/media/en/technical-documentation/data-sheets/AD8232.pdf> [Accessed 21 September 2026].
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.comingSoonOnClick
import com.example.prog7314_poepart23.utils.openOnClick
import com.example.prog7314_poepart23.utils.toastOnClick
import com.example.prog7314_poepart23.utils.wireBack
import com.example.prog7314_poepart23.views.EcgWaveformView
import androidx.lifecycle.lifecycleScope
import com.example.prog7314_poepart23.data.repository.EcgRepository
import com.example.prog7314_poepart23.utils.Resource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DoctorLiveEcgActivity : AppCompatActivity() { // (Google Developers, 2024)

    private lateinit var ecgWaveformView: EcgWaveformView
    private lateinit var tvLiveBpm: TextView

    // Real data only: the samples of a real session recorded by the patient's device, polled from the API.
    private val ecgRepository = EcgRepository()
    private var pollJob: Job? = null
    private var patientId: String? = null
    private var activeSessionId: String? = null
    private var renderedSampleCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.doctor_live_ecg)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)
        patientId = intent.getStringExtra(PatientListActivity.EXTRA_PATIENT_ID)
        activeSessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        if (patientId == null && activeSessionId == null) {
            Toast.makeText(this, getString(R.string.doctor_live_no_session), Toast.LENGTH_LONG).show()
        }
        tvLiveBpm = findViewById(R.id.tvLiveBpm)
        ecgWaveformView = findViewById(R.id.ecgWaveformView)
        ecgWaveformView.gridMode = EcgWaveformView.EcgGridMode.DARK_CLINICAL_MONITOR

        ecgWaveformView.onRPeakDetected = { detectedBpm ->
            runOnUiThread {
                com.example.prog7314_poepart23.utils.HeartRateStatus.apply(tvLiveBpm, detectedBpm, if (detectedBpm > 0) getString(R.string.l10n_bpm_value, detectedBpm) else getString(R.string.l10n_bpm_none))
            }
        }

        wireControls()
        BottomNavHelper.setUpDoctorNavDetached(this)
    }

    override fun onResume() {
        super.onResume()
        BottomNavHelper.setUpDoctorNavDetached(this)
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
    }

    // Polls the newest session of the patient (or the given session) and draws only the samples not yet on screen.
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                val sessionId = resolveSessionId()
                if (sessionId != null) {
                    val result = ecgRepository.getSessionSamples(sessionId)
                    if (result is Resource.Success) {
                        val all = result.data?.samples.orEmpty()
                        if (all.size > renderedSampleCount) {
                            ecgWaveformView.addSamples(ArrayList(all.subList(renderedSampleCount, all.size)))
                            renderedSampleCount = all.size
                        }
                    }
                }
                delay(1000L)
            }
        }
    }

    // An explicit session wins; otherwise follow the patient's most recent recorded session.
    private suspend fun resolveSessionId(): String? {
        intent.getStringExtra(EXTRA_SESSION_ID)?.let { return it }
        val pid = patientId ?: return null
        val sessions = ecgRepository.getPatientSessions(pid)
        val latest = (sessions as? Resource.Success)?.data?.maxByOrNull { it.startTimeUtc }?.sessionId
        if (latest != null && latest != activeSessionId) {
            activeSessionId = latest
            renderedSampleCount = 0
        }
        return activeSessionId
    }

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
    }

    // Wires action buttons, clinical note triggers, and emergency escalation protocols.
    private fun wireControls() {
        wireBack(R.id.btnBack)
        comingSoonOnClick(R.id.btnShare)

        // Route directly to clinical notes modal to record timestamped observations
        openOnClick(R.id.btnAddNote, DoctorSessionNotesActivity::class.java)

        toastOnClick(R.id.btnCallPatient, R.string.action_calling_patient)
        toastOnClick(R.id.btnEscalateEmergency, R.string.action_alert_escalated)
    }
}
