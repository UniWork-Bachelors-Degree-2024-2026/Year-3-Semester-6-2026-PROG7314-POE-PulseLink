/*
 * Patient session status screen. It is the destination of the former Monitoring tab and shows the
 * most recent session's recording status, quality, doctor note availability and upload state.
 * Recording is started on the PulseLink touchscreen, so this screen has no live ECG or device controls.
 *
 * Reference List:
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 6 November 2026].
 * - Google Developers, 2024. ViewModel Overview - Android Developers. [online] Available at: <https://developer.android.com/topic/libraries/architecture/viewmodel> [Accessed 6 November 2026].
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 6 November 2026].
 */

package com.example.prog7314_poepart23

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.activity.viewModels                   // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import androidx.lifecycle.lifecycleScope              // (Google Developers, 2024)
import com.example.prog7314_poepart23.data.remote.models.EcgSessionSummaryDto
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.DateTimeFormatter
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.LocaleHelper
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.viewmodels.DashboardViewModel
import com.google.firebase.auth.FirebaseAuth         // (Google Cloud, 2024)
import kotlinx.coroutines.Dispatchers                // (JetBrains, 2024)
import kotlinx.coroutines.launch                     // (JetBrains, 2024)
import kotlinx.coroutines.withContext                // (JetBrains, 2024)
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PatientSessionStatusActivity : AppCompatActivity() { // (Google Developers, 2024)

    private val viewModel: DashboardViewModel by viewModels()

    private lateinit var layoutStatusMessage: View
    private lateinit var tvStatusMessageTitle: TextView
    private lateinit var tvStatusMessageBody: TextView
    private lateinit var btnRetry: View
    private lateinit var cardLatestSession: View
    private lateinit var tvSessionStatusPill: TextView
    private lateinit var tvSessionDate: TextView
    private lateinit var tvSessionDuration: TextView
    private lateinit var tvSessionQuality: TextView
    private lateinit var tvSessionNotes: TextView
    private lateinit var tvSessionSync: TextView

    // The session currently shown. Cleared on every empty, loading or error result so the
    // details button can never open a session from an earlier load.
    private var shownSession: EcgSessionSummaryDto? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.patient_session_status)

        HapticFeedbackManager.initialise(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        bindViews()
        setupClickListeners()
        BottomNavHelper.setUpPatientNav(this, BottomNavHelper.PatientTab.SESSION_STATUS)
        viewModel.sessionOverview.observe(this) { resource ->
            when (resource) {
                is Resource.Loading -> showMessage(getString(R.string.session_status_loading_message), null, showRetry = false)
                is Resource.Error -> showMessage(
                    getString(R.string.session_status_error_title),
                    getString(R.string.session_status_error_message),
                    showRetry = true
                )
                is Resource.Success -> {
                    val session = resource.data?.latest
                    if (session == null) {
                        showMessage(
                            getString(R.string.session_status_none_title),
                            getString(R.string.session_status_none_message),
                            showRetry = false
                        )
                    } else {
                        showSession(session)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadStatus()
    }

    private fun bindViews() {
        layoutStatusMessage = findViewById(R.id.layoutStatusMessage)
        tvStatusMessageTitle = findViewById(R.id.tvStatusMessageTitle)
        tvStatusMessageBody = findViewById(R.id.tvStatusMessageBody)
        btnRetry = findViewById(R.id.btnRetry)
        cardLatestSession = findViewById(R.id.cardLatestSession)
        tvSessionStatusPill = findViewById(R.id.tvSessionStatusPill)
        tvSessionDate = findViewById(R.id.tvSessionDate)
        tvSessionDuration = findViewById(R.id.tvSessionDuration)
        tvSessionQuality = findViewById(R.id.tvSessionQuality)
        tvSessionNotes = findViewById(R.id.tvSessionNotes)
        tvSessionSync = findViewById(R.id.tvSessionSync)
    }

    private fun setupClickListeners() {
        btnRetry.setOnClickListener {
            HapticFeedbackManager.lightTick()
            loadStatus()
        }

        findViewById<View>(R.id.btnOpenHistory).setOnClickListener {
            HapticFeedbackManager.lightTick()
            startActivity(Intent(this, PatientEcgHistoryActivity::class.java))
        }

        findViewById<View>(R.id.btnViewSessionDetails).setOnClickListener {
            val session = shownSession ?: return@setOnClickListener
            HapticFeedbackManager.lightTick()
            startActivity(
                Intent(this, SessionDetailsActivity::class.java)
                    .putExtra(PatientEcgHistoryActivity.EXTRA_SESSION_ID, session.sessionId)
                    .putExtra(PatientEcgHistoryActivity.EXTRA_AVERAGE_BPM, session.averageBpm)
                    .putExtra(PatientEcgHistoryActivity.EXTRA_DURATION_SECONDS, session.durationSeconds)
                    .putExtra(PatientEcgHistoryActivity.EXTRA_START_TIME_UTC, session.startTimeUtc)
                    .putExtra(PatientEcgHistoryActivity.EXTRA_SIGNAL_QUALITY, session.signalQuality)
                    .putExtra(PatientEcgHistoryActivity.EXTRA_SESSION_STATUS, session.status)
            )
        }
    }

    private fun loadStatus() {
        val patientId = FirebaseAuth.getInstance().currentUser?.uid
        if (patientId == null) {
            showMessage(
                getString(R.string.session_status_error_title),
                getString(R.string.session_status_error_message),
                showRetry = true
            )
            return
        }
        viewModel.fetchSessionOverview(patientId)
        refreshUploadStatus(patientId)
    }

    private fun showMessage(title: String, body: String?, showRetry: Boolean) {
        shownSession = null
        cardLatestSession.visibility = View.GONE
        layoutStatusMessage.visibility = View.VISIBLE
        tvStatusMessageTitle.text = title
        tvStatusMessageBody.text = body.orEmpty()
        tvStatusMessageBody.visibility = if (body.isNullOrBlank()) View.GONE else View.VISIBLE
        btnRetry.visibility = if (showRetry) View.VISIBLE else View.GONE
    }

    private fun showSession(session: EcgSessionSummaryDto) {
        shownSession = session
        layoutStatusMessage.visibility = View.GONE
        cardLatestSession.visibility = View.VISIBLE

        // Raw recording status and signal quality only. No rhythm or clinical label is derived here.
        tvSessionStatusPill.text = LocaleHelper.statusLabel(this, session.status)
        tvSessionDate.text = formatSessionStart(session.startTimeUtc)
        tvSessionDuration.text = DateTimeFormatter.formatDuration(session.durationSeconds)
        tvSessionQuality.text = LocaleHelper.qualityLabel(this, session.signalQuality)
        tvSessionNotes.text = getString(
            // Notes are only mentioned once the care team has released them to the patient.
            if (session.hasDoctorNotes && session.resultsReleased) R.string.session_status_notes_available else R.string.session_status_notes_none
        )
    }

    // Saved locally is never shown as uploaded: only the count still waiting is reported.
    private fun refreshUploadStatus(patientId: String) {
        tvSessionSync.text = getString(R.string.value_placeholder)
        lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) {
                com.example.prog7314_poepart23.data.local.OfflineEcgDatabase.getInstance(this@PatientSessionStatusActivity).getPendingSyncCount(patientId)
            }
            tvSessionSync.text = if (pending > 0) {
                getString(R.string.session_status_upload_pending_format, pending)
            } else {
                getString(R.string.session_status_upload_none_pending)
            }
        }
    }

    private fun formatSessionStart(isoTimestamp: String?): String {
        if (isoTimestamp.isNullOrBlank()) return getString(R.string.value_placeholder)
        return try {
            val clean = isoTimestamp.trim()
            val baseIso = if (clean.contains('.')) clean.substringBefore('.') else clean.removeSuffix("Z")
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = parser.parse(baseIso)
            if (date != null) DateTimeFormatter.formatSessionDate(date.time) else getString(R.string.value_placeholder)
        } catch (e: Exception) {
            getString(R.string.value_placeholder)
        }
    }
}
