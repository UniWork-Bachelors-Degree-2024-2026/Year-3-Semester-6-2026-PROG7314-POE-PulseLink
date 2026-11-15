/*
 * Activity presenting the full chronological Lead I ECG session history of a selected patient for diagnostic review by a medical practitioner.
 *
 * Reference List:
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import androidx.lifecycle.lifecycleScope              // (Google Developers, 2024)
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.prog7314_poepart23.adapters.SessionListAdapter
import com.example.prog7314_poepart23.data.remote.models.EcgSessionSummaryDto
import com.example.prog7314_poepart23.data.repository.EcgRepository
import com.example.prog7314_poepart23.databinding.DoctorPatientEcgHistoryBinding
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.utils.comingSoonOnClick
import com.example.prog7314_poepart23.utils.openOnClick
import com.example.prog7314_poepart23.utils.wireBack
import kotlinx.coroutines.launch                     // (JetBrains, 2024)

class DoctorPatientEcgHistoryActivity : AppCompatActivity() { // (Google Developers, 2024)

    private lateinit var binding: DoctorPatientEcgHistoryBinding
    private lateinit var sessionListAdapter: SessionListAdapter
    private val ecgRepository = EcgRepository()

    private var patientId: String? = null
    private var patientDisplayName: String = ""
    private var latestSessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = DoctorPatientEcgHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        patientId = intent.getStringExtra(PatientListActivity.EXTRA_PATIENT_ID)
        if (patientId.isNullOrBlank()) {
            android.widget.Toast.makeText(this, getString(R.string.l10n_no_patient_selected), android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        patientDisplayName = intent.getStringExtra(DoctorAlertsActivity.EXTRA_PATIENT_NAME) ?: getString(R.string.l10n_patient_short_format, patientId?.take(8).orEmpty())
        binding.tvPatientHeaderName.text = patientDisplayName

        HapticFeedbackManager.initialise(this)
        setUpSessionList()
        loadSessions()
        BottomNavHelper.setUpDoctorNavDetached(this)
    }

    override fun onResume() {
        super.onResume()
        BottomNavHelper.setUpDoctorNavDetached(this)
    }

    // Sets up the RecyclerView adapter and handles item click routing to session details.
    private fun setUpSessionList() {
        sessionListAdapter = SessionListAdapter { session ->
            HapticFeedbackManager.lightTick()
            val intent = Intent(this, DoctorEcgSessionDetailsActivity::class.java).apply {
                putExtra(PatientEcgHistoryActivity.EXTRA_SESSION_ID, session.sessionId)
                putExtra(PatientListActivity.EXTRA_PATIENT_ID, patientId)
                putExtra(DoctorAlertsActivity.EXTRA_PATIENT_NAME, session.patientName)
                putExtra(PatientEcgHistoryActivity.EXTRA_AVERAGE_BPM, session.averageBpm)
                putExtra(PatientEcgHistoryActivity.EXTRA_DURATION_SECONDS, session.durationSeconds)
                putExtra(PatientEcgHistoryActivity.EXTRA_START_TIME_UTC, session.startTimeUtc)
                putExtra(PatientEcgHistoryActivity.EXTRA_SIGNAL_QUALITY, session.signalQuality)
                putExtra(PatientEcgHistoryActivity.EXTRA_SESSION_STATUS, session.status)
            }
            startActivity(intent)
        }
        binding.rvSessions.layoutManager = LinearLayoutManager(this)
        binding.rvSessions.adapter = sessionListAdapter
        binding.rvSessions.setHasFixedSize(true)
    }

    // Fetches all recorded ECG sessions for the selected patient from the Azure REST backend.
    private fun loadSessions() {
        val id = patientId ?: return
        lifecycleScope.launch {
            val result = ecgRepository.getPatientSessions(id)
            if (result is Resource.Success) {
                val list = result.data ?: emptyList()
                sessionListAdapter.submitList(list)
                if (list.isNotEmpty()) {
                    latestSessionId = list.first().sessionId
                    val firstPatientName = list.first().patientName
                    if (!firstPatientName.isNullOrBlank()) {
                        patientDisplayName = firstPatientName
                        binding.tvPatientHeaderName.text = patientDisplayName
                    }
                }
            } else {
                sessionListAdapter.submitList(emptyList())
                android.widget.Toast.makeText(
                    this@DoctorPatientEcgHistoryActivity,
                    result.message ?: getString(R.string.l10n_unable_load_ecg_data),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
    }

    private var controlsWired = false

    override fun onStart() {
        super.onStart()
        if (controlsWired) return
        controlsWired = true
        HapticFeedbackManager.initialise(this)
        wireBack(R.id.btnBack)
        openOnClick(R.id.btnNotifications, DoctorAlertsActivity::class.java)
        openOnClick(R.id.ivProfile, DoctorProfileActivity::class.java)
        binding.btnViewPatientProfile.setOnClickListener {
            HapticFeedbackManager.lightTick()
            val profileIntent = Intent(this, DoctorPatientProfileActivity::class.java).apply {
                putExtra(PatientListActivity.EXTRA_PATIENT_ID, patientId)
                putExtra(DoctorAlertsActivity.EXTRA_PATIENT_NAME, patientDisplayName)
                latestSessionId?.let { putExtra(PatientEcgHistoryActivity.EXTRA_SESSION_ID, it) }
            }
            startActivity(profileIntent)
        }
        comingSoonOnClick(R.id.btnDateRangeFilter)
    }
}
