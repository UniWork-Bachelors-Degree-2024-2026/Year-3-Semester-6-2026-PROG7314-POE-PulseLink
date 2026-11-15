/*
 * Activity displaying active real-time clinical ECG alerts (e.g. Bradycardia,
 * Tachycardia, and Lead Disconnections) detected by the PulseLink RPM system.
 *
 * Reference List:
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 * - Google Developers, 2024. ViewModel Overview - Android Developers. [online] Available at: <https://developer.android.com/topic/libraries/architecture/viewmodel> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.activity.viewModels                   // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.prog7314_poepart23.adapters.AlertListAdapter
import com.example.prog7314_poepart23.data.remote.models.AlertDto
import com.example.prog7314_poepart23.databinding.DoctorAlertsBinding
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.viewmodels.AlertsViewModel
import com.example.prog7314_poepart23.utils.FilterChipGroup
import com.example.prog7314_poepart23.utils.onClick
import com.example.prog7314_poepart23.utils.toastOnClick
import com.example.prog7314_poepart23.utils.wireBack

import com.example.prog7314_poepart23.security.SecurePreferences
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry

class DoctorAlertsActivity : AppCompatActivity() { // (Google Developers, 2024)

    private lateinit var binding: DoctorAlertsBinding
    private val alertsViewModel: AlertsViewModel by viewModels()
    private lateinit var alertListAdapter: AlertListAdapter

    // Alerts as returned by the API, before the severity chip filter is applied.
    private var loadedAlerts: List<AlertDto> = emptyList()
    private var severityFilter: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = DoctorAlertsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        HapticFeedbackManager.initialise(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setUpAlertList()
        observeAlerts()
        loadAlerts()
        BottomNavHelper.setUpDoctorNavDetached(this)
    }

    override fun onResume() {
        super.onResume()
        BottomNavHelper.setUpDoctorNavDetached(this)
        val dismissed = SecurePreferences.getInstance(this).getDismissedAlertIds()
        loadedAlerts = loadedAlerts.filter { it.alertId !in dismissed }
        updateChipCounts()
        renderAlerts()
        loadAlerts()
    }

    private fun setUpAlertList() {
        alertListAdapter = AlertListAdapter { alert ->
            HapticFeedbackManager.lightTick()
            startActivity(
                Intent(this, DoctorAlertDetailsActivity::class.java)
                    .putExtra(EXTRA_ALERT_ID, alert.alertId)
                    .putExtra(EXTRA_PATIENT_ID, alert.patientId)
                    .putExtra(EXTRA_PATIENT_NAME, alert.patientName)
                    .putExtra(EXTRA_SESSION_ID, alert.sessionId)
                    .putExtra(EXTRA_SEVERITY, alert.severity)
                    .putExtra(EXTRA_ALERT_TYPE, alert.alertType)
                    .putExtra(EXTRA_MESSAGE, alert.message)
                    .putExtra(EXTRA_BPM, alert.estimatedBpm)
                    .putExtra(EXTRA_TIMESTAMP, alert.timestampUtc)
            )
        }
        binding.rvAlerts.layoutManager = LinearLayoutManager(this)
        binding.rvAlerts.adapter = alertListAdapter
        binding.rvAlerts.setHasFixedSize(true)
    }

    private fun observeAlerts() {
        alertsViewModel.alerts.observe(this) { resource ->
            when (resource) {
                is Resource.Success -> {
                    com.example.prog7314_poepart23.utils.ShimmerHelper.stopShimmer(binding.shimmerAlertsLayout.shimmerList, binding.rvAlerts)
                    val dismissed = SecurePreferences.getInstance(this).getDismissedAlertIds()
                    loadedAlerts = (resource.data ?: emptyList()).filter { it.alertId !in dismissed }
                    updateChipCounts()
                    renderAlerts()
                }
                is Resource.Error -> {
                    com.example.prog7314_poepart23.utils.ShimmerHelper.stopShimmer(binding.shimmerAlertsLayout.shimmerList, binding.rvAlerts)
                    loadedAlerts = emptyList()
                    updateChipCounts()
                    renderAlerts()
                    android.widget.Toast.makeText(
                        this,
                        resource.message ?: getString(R.string.l10n_unable_load_alerts),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                is Resource.Loading -> {
                    com.example.prog7314_poepart23.utils.ShimmerHelper.startShimmer(binding.shimmerAlertsLayout.shimmerList, binding.rvAlerts)
                }
            }
        }
    }

    private fun loadAlerts() {
        alertsViewModel.loadActiveAlerts()
    }

    companion object {
        const val EXTRA_ALERT_ID = "extra_alert_id"
        const val EXTRA_PATIENT_ID = "extra_patient_id"
        const val EXTRA_PATIENT_NAME = "extra_patient_name"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_SEVERITY = "extra_severity"
        const val EXTRA_ALERT_TYPE = "extra_alert_type"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_BPM = "extra_bpm"
        const val EXTRA_TIMESTAMP = "extra_timestamp"
    }

    private var controlsWired = false

    // Header icons, the "mark all read" action and the severity filter chips.
    override fun onStart() {
        super.onStart()
        if (controlsWired) return
        controlsWired = true
        HapticFeedbackManager.initialise(this)

        wireBack(R.id.btnBack)
        binding.btnMarkAllRead.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            val alertIds = loadedAlerts.map { it.alertId }
            if (alertIds.isNotEmpty()) {
                SecurePreferences.getInstance(this).dismissAllAlerts(alertIds)
                loadedAlerts = emptyList()
                updateChipCounts()
                renderAlerts()
            }
            android.widget.Toast.makeText(this, R.string.action_marked_all_read, android.widget.Toast.LENGTH_SHORT).show()
        }

        updateChipCounts()
        FilterChipGroup.bind(
            this,
            R.id.chipAllAlerts,
            R.id.chipCriticalAlerts,
            R.id.chipWarningAlerts,
            R.id.chipSystemAlerts
        ) { index ->
            severityFilter = when (index) {
                1 -> "critical"
                2 -> "warning"
                3 -> "system"
                else -> null
            }
            renderAlerts()
        }
    }

    // Applies the currently selected chip to the alerts already loaded, so switching
    // filters does not trigger another network round trip.
    // Chip labels show the real number of alerts per severity, never fixed numbers.
    private fun updateChipCounts() {
        fun count(sev: String) = loadedAlerts.count { it.severity.equals(sev, ignoreCase = true) }
        findViewById<android.widget.TextView?>(R.id.chipAllAlerts)?.text = getString(R.string.alerts_chip_all_format, loadedAlerts.size)
        findViewById<android.widget.TextView?>(R.id.chipCriticalAlerts)?.text = getString(R.string.alerts_chip_critical_format, count("critical"))
        findViewById<android.widget.TextView?>(R.id.chipWarningAlerts)?.text = getString(R.string.alerts_chip_warning_format, count("warning"))
        findViewById<android.widget.TextView?>(R.id.chipSystemAlerts)?.text = getString(R.string.alerts_chip_system_format, count("system"))
    }

    private fun renderAlerts() {
        updateChipCounts()
        val filter = severityFilter
        alertListAdapter.submitList(
            if (filter == null) loadedAlerts
            else loadedAlerts.filter { it.severity.equals(filter, ignoreCase = true) }
        )
    }
}
