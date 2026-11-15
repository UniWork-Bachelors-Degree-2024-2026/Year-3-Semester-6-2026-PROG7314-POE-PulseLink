/*
 * Doctor patient directory activity listing enrolled telemetry patients,
 * active physiological statuses, and quick review actions.
 *
 * Reference List:
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 * - Google Developers, 2024. ViewModel Overview - Android Developers. [online] Available at: <https://developer.android.com/topic/libraries/architecture/viewmodel> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.activity.viewModels                   // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.prog7314_poepart23.adapters.PatientListAdapter
import com.example.prog7314_poepart23.data.models.PatientStatus
import com.example.prog7314_poepart23.data.models.PatientSummary
import com.example.prog7314_poepart23.databinding.DoctorPatientListBinding
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.FilterChipGroup
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.utils.applyStoredAvatar
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.openOnClick
import com.example.prog7314_poepart23.viewmodels.AlertsViewModel
import com.google.firebase.auth.FirebaseAuth

class PatientListActivity : AppCompatActivity() { // (Google Developers, 2024)

    private lateinit var binding: DoctorPatientListBinding
    private lateinit var patientListAdapter: PatientListAdapter
    private val review: com.example.prog7314_poepart23.viewmodels.DoctorReviewViewModel by viewModels()
    private var nextCursor: String? = null
    private var appendPage = false
    private lateinit var rosterStatus: android.widget.TextView
    private lateinit var loadMore: com.google.android.material.button.MaterialButton

    private var allPatients: List<PatientSummary> = emptyList()
    private var searchQuery: String = ""
    private var filterMode: Int = 0 // 0 = all, 1 = critical, 2 = low adherence

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = DoctorPatientListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)
        setUpPatientList()
        setUpSearchAndFilters()
        binding.chipCriticalPatients.text = getString(R.string.doctor_follow_up)
        binding.chipLowAdherence.text = getString(R.string.ui_review_required)
        rosterStatus = android.widget.TextView(this).apply {
            textSize = 16f
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.pulse_text_secondary))
            setOnClickListener { loadPatients() }
        }
        loadMore = com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.doctor_load_more); isAllCaps = false; visibility = android.view.View.GONE
            setOnClickListener { nextCursor?.let { appendPage = true; isEnabled = false; review.loadAssignedPatients(it) } }
        }
        (binding.rvPatients.parent as android.view.ViewGroup).apply {
            addView(rosterStatus, 0)
            addView(loadMore)
        }
        observeTelemetry()

        BottomNavHelper.setUpDoctorNav(this, BottomNavHelper.DoctorTab.PATIENTS)
    }

    override fun onResume() {
        super.onResume()
        BottomNavHelper.setUpDoctorNav(this, BottomNavHelper.DoctorTab.PATIENTS)
        applyStoredAvatar(R.id.btnProfileAvatar, FirebaseAuth.getInstance().currentUser?.uid)
        loadPatients()
    }

    private fun setUpPatientList() {
        patientListAdapter = PatientListAdapter { patient ->
            HapticFeedbackManager.lightTick()
            startActivity(
                Intent(this, DoctorPatientProfileActivity::class.java)
                    .putExtra(EXTRA_PATIENT_ID, patient.patientId)
            )
        }
        binding.rvPatients.layoutManager = LinearLayoutManager(this)
        binding.rvPatients.adapter = patientListAdapter
        binding.rvPatients.setHasFixedSize(true)
    }

    private fun setUpSearchAndFilters() {
        binding.etSearchPatients.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun observeTelemetry() {
        review.assignedPatients.observe(this) { result ->
            if (result is com.example.prog7314_poepart23.data.repository.PatientPortalResult.Ready) {
                val page = result.value
                val rows = page.items.map { patient -> PatientSummary(
                    patientId = patient.patientId, name = patient.fullName,
                    lastBpm = patient.latestAverageBpm ?: 0,
                    lastSessionDateLabel = patient.latestRecordingAtUtc?.let { com.example.prog7314_poepart23.utils.DateTimeFormatter.formatIsoTimestamp(it) } ?: getString(R.string.l10n_unavailable),
                    status = when {
                        patient.followUpRequired -> PatientStatus.FOLLOW_UP
                        patient.awaitingReviewCount > 0 -> PatientStatus.REVIEW_NEEDED
                        else -> PatientStatus.REGISTERED
                    }) }
                allPatients = (if (appendPage) allPatients + rows else rows).distinctBy { it.patientId }
                nextCursor = page.nextCursor
                rosterStatus.visibility = android.view.View.GONE
                loadMore.visibility = if (nextCursor == null) android.view.View.GONE else android.view.View.VISIBLE
                applyFilters()
            } else {
                rosterStatus.text = getString(R.string.doctor_load_failed)
                rosterStatus.visibility = android.view.View.VISIBLE
            }
            loadMore.isEnabled = true
        }
    }

    private fun loadPatients() {
        appendPage = false
        rosterStatus.text = getString(R.string.profile_loading)
        rosterStatus.visibility = android.view.View.VISIBLE
        review.loadAssignedPatients()
    }

    private fun applyFilters() {
        var filtered = allPatients

        if (searchQuery.isNotEmpty()) {
            filtered = filtered.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.patientId.contains(searchQuery, ignoreCase = true)
            }
        }

        filtered = when (filterMode) {
            1 -> filtered.filter { it.status == PatientStatus.FOLLOW_UP }
            2 -> filtered.filter { it.status == PatientStatus.REVIEW_NEEDED }
            else -> filtered
        }

        patientListAdapter.submitList(filtered)
    }

    companion object {
        const val EXTRA_PATIENT_ID = "extra_patient_id"
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
            R.id.chipAllPatients,
            R.id.chipCriticalPatients,
            R.id.chipLowAdherence
        ) { index ->
            filterMode = index
            applyFilters()
        }
    }
}