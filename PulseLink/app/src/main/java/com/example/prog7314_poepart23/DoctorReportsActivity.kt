/*
 * Activity managing clinical Lead I diagnostic reports and PDF summaries for healthcare practitioners across assigned patients.
 *
 * Reference List:
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import androidx.core.widget.doAfterTextChanged
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.prog7314_poepart23.adapters.ReportListAdapter
import com.example.prog7314_poepart23.data.models.ReportStatus
import com.example.prog7314_poepart23.data.models.ReportSummary
import com.example.prog7314_poepart23.databinding.DoctorReportsBinding
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.FilterChipGroup
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.applyStoredAvatar
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.openOnClick
import com.google.firebase.auth.FirebaseAuth

class DoctorReportsActivity : AppCompatActivity() { // (Google Developers, 2024)

    private lateinit var binding: DoctorReportsBinding
    private lateinit var reportListAdapter: ReportListAdapter

    private var searchQuery = ""
    private var allReports: List<ReportSummary> = emptyList()
    private var filterMode: Int = 0 // 0 = all, 1 = pending, 2 = flagged, 3 = signed off

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = DoctorReportsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)
        setUpReportList()
        binding.etSearchReports.doAfterTextChanged { text ->
            searchQuery = text?.toString()?.trim().orEmpty()
            applyFilters()
        }
        loadReports()

        BottomNavHelper.setUpDoctorNav(this, BottomNavHelper.DoctorTab.REPORTS)
    }

    override fun onResume() {
        super.onResume()
        BottomNavHelper.setUpDoctorNav(this, BottomNavHelper.DoctorTab.REPORTS)
        applyStoredAvatar(R.id.btnProfileAvatar, FirebaseAuth.getInstance().currentUser?.uid)
        loadReports()
    }

    /**
     * Initialises the RecyclerView adapter for diagnostic report summaries.
     */
    private fun setUpReportList() {
        reportListAdapter = ReportListAdapter(
            onReportClick = { report ->
                HapticFeedbackManager.lightTick()
                startActivity(
                    Intent(this, SessionReportPreviewActivity::class.java)
                        .putExtra(PatientEcgHistoryActivity.EXTRA_SESSION_ID, report.reportId)
                )
            },
            onDownloadClick = { report ->
                HapticFeedbackManager.confirmClick()
                Toast.makeText(this, getString(R.string.l10n_downloading_pdf_for_format, report.patientName), Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(this, SessionReportPreviewActivity::class.java)
                        .putExtra(PatientEcgHistoryActivity.EXTRA_SESSION_ID, report.reportId)
                )
            }
        )
        binding.rvReports.layoutManager = LinearLayoutManager(this)
        binding.rvReports.adapter = reportListAdapter
        binding.rvReports.setHasFixedSize(true)
    }

    // NOTE: PulseLinkApiService exposes no endpoint to list clinical reports across
    // patients - only api/reports/session/{sessionId}/pdf for a single known session.
    // Per the "do not invent backend endpoints" requirement, this screen shows an
    // honest empty state rather than fabricated report rows until that endpoint exists.
    private fun loadReports() {
        allReports = emptyList()
        applyFilters()
    }

    // Filters the clinical report feed based on physician sign-off status.
    private fun applyFilters() {
        val filtered = when (filterMode) {
            1 -> allReports.filter { it.status == ReportStatus.PENDING }
            2 -> allReports.filter { it.status == ReportStatus.FLAGGED }
            3 -> allReports.filter { it.status == ReportStatus.SIGNED_OFF }
            else -> allReports
        }
        reportListAdapter.submitList(filtered.filter {
            it.patientName.contains(searchQuery, ignoreCase = true) || it.reportId.contains(searchQuery, ignoreCase = true)
        })
    }

    companion object {
        const val EXTRA_REPORT_ID = "extra_report_id"
    }

    private var controlsWired = false

    override fun onStart() {
        super.onStart()
        if (controlsWired) return
        controlsWired = true
        HapticFeedbackManager.initialise(this)
        openOnClick(R.id.btnNotifications, DoctorAlertsActivity::class.java)
        openOnClick(R.id.btnProfileAvatar, DoctorProfileActivity::class.java)

        FilterChipGroup.bind(
            this,
            R.id.chipAllReports,
            R.id.chipPendingSignOff,
            R.id.chipFlaggedReports,
            R.id.chipSignedOff
        ) { index ->
            filterMode = index
            applyFilters()
        }
    }
}
