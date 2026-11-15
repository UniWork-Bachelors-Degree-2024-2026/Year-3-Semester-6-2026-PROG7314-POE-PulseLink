/*
 * Pre-session hardware validation controller verifying
 * AD8232 single-lead hardware readiness, USB 5V power state, Wi-Fi telemetry link,
 * and Lead I electrode placement before recording.
 *
 * 1. Initialises centralised device haptic feedback.
 * 2. Asynchronously polls the PulseLink REST API (GET /api/devices/{deviceId}/status)
 *    to retrieve dynamic Online/Offline state and lead attachment state.
 * 3. Updates interactive checklist indicators with vector assets (SVG icons) and clear text.
 * 4. Enforces strict 3-point gating (Device Online + Skin Cleansed + Leads Attached)
 *    before enabling the ECG recording session launcher.
 * 5. Connects to the Einthoven Lead I anatomical guide for patient education.
 *
 * Reference List:
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End. [online] Available at: <https://www.analog.com/media/en/technical-documentation/data-sheets/AD8232.pdf> [Accessed 21 September 2026].
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 * - Google Developers, 2024. Material Components for Android. [online] Available at: <https://github.com/material-components/material-components-android> [Accessed 21 September 2026].
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import androidx.lifecycle.lifecycleScope              // (Google Developers, 2024)
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiClient
import com.example.prog7314_poepart23.security.SecurePreferences
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.google.android.material.button.MaterialButton // (Google Developers, 2024)
import kotlinx.coroutines.launch                     // (JetBrains, 2024)

class EcgPreSessionCheckActivity : AppCompatActivity() { // (Google Developers, 2024)

    // View references
    private lateinit var btnCloseCheck: View
    private lateinit var layoutDeviceCheck: View
    private lateinit var ivDeviceCheckIcon: ImageView
    private lateinit var tvDeviceCheckStatus: TextView
    private lateinit var layoutSkinCheck: View
    private lateinit var ivSkinCheckIcon: ImageView
    private lateinit var tvSkinCheckStatus: TextView
    private lateinit var layoutElectrodeCheck: View
    private lateinit var ivElectrodeCheckIcon: ImageView
    private lateinit var tvElectrodeCheckStatus: TextView
    private lateinit var layoutPlacementGuide: View
    private lateinit var btnStartEcgRecording: MaterialButton

    // Gating state variables
    private var isDeviceOnline: Boolean = false
    private var isSkinPrepConfirmed: Boolean = false
    private var isLeadConnected: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.patient_ecg_pre_session_check)

        // Initialise centralised device haptics
        HapticFeedbackManager.initialise(this)

        // Apply edge-to-edge window insets padding
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        bindViews()
        setupListeners()
        updateStartButtonState()
        performPreFlightCheck()
    }

    // Binds XML layout view references.
    private fun bindViews() {
        btnCloseCheck = findViewById(R.id.btnCloseCheck)
        layoutDeviceCheck = findViewById(R.id.layoutDeviceCheck)
        ivDeviceCheckIcon = findViewById(R.id.ivDeviceCheckIcon)
        tvDeviceCheckStatus = findViewById(R.id.tvDeviceCheckStatus)

        layoutSkinCheck = findViewById(R.id.layoutSkinCheck)
        ivSkinCheckIcon = findViewById(R.id.ivSkinCheckIcon)
        tvSkinCheckStatus = findViewById(R.id.tvSkinCheckStatus)

        layoutElectrodeCheck = findViewById(R.id.layoutElectrodeCheck)
        ivElectrodeCheckIcon = findViewById(R.id.ivElectrodeCheckIcon)
        tvElectrodeCheckStatus = findViewById(R.id.tvElectrodeCheckStatus)

        layoutPlacementGuide = findViewById(R.id.layoutPlacementGuide)
        btnStartEcgRecording = findViewById(R.id.btnStartEcgRecording)
    }

    // Attaches click handlers for dismissing the check, toggles, retries, and launching recording.
    private fun setupListeners() {
        btnCloseCheck.setOnClickListener {
            HapticFeedbackManager.lightTick()
            finish()
        }

        // Tap device row to retry hardware connectivity probe
        layoutDeviceCheck.setOnClickListener {
            HapticFeedbackManager.lightTick()
            tvDeviceCheckStatus.text = getString(R.string.patient_ecg_pre_session_check_text_hardware_checking)
            ivDeviceCheckIcon.setImageResource(R.drawable.ic_circle_outline)
            ivDeviceCheckIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.pulse_text_secondary))
            performPreFlightCheck()
        }

        // Patient confirms skin cleansing and preparation
        layoutSkinCheck.setOnClickListener {
            HapticFeedbackManager.lightTick()
            isSkinPrepConfirmed = !isSkinPrepConfirmed
            if (isSkinPrepConfirmed) {
                ivSkinCheckIcon.setImageResource(R.drawable.ic_check_circle)
                ivSkinCheckIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.pulse_success))
                tvSkinCheckStatus.text = getString(R.string.patient_ecg_pre_session_check_text_skin_cleansed)
            } else {
                ivSkinCheckIcon.setImageResource(R.drawable.ic_circle_outline)
                ivSkinCheckIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.pulse_text_secondary))
                tvSkinCheckStatus.text = getString(R.string.patient_ecg_pre_session_check_text_tap_skin)
            }
            updateStartButtonState()
        }

        // Tap electrode row to retry lead attachment probe
        layoutElectrodeCheck.setOnClickListener {
            HapticFeedbackManager.lightTick()
            tvElectrodeCheckStatus.text = getString(R.string.patient_ecg_pre_session_check_text_lead_checking)
            ivElectrodeCheckIcon.setImageResource(R.drawable.ic_circle_outline)
            ivElectrodeCheckIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.pulse_text_secondary))
            performPreFlightCheck()
        }

        // Tap placement guide to view full anatomical electrode guide
        layoutPlacementGuide.setOnClickListener {
            HapticFeedbackManager.lightTick()
            startActivity(Intent(this, ElectrodePlacementGuideActivity::class.java))
        }

        // Start recording only when all 3 checks are satisfied
        btnStartEcgRecording.setOnClickListener {
            if (isDeviceOnline && isSkinPrepConfirmed && isLeadConnected) {
                HapticFeedbackManager.confirmClick()
                startActivity(Intent(this, LiveEcgMonitoringActivity::class.java))
                finish()
            }
        }
    }

    // Updates the launch button enabled state and visual appearance based on all 3 conditions
    private fun updateStartButtonState() {
        val allChecksPassed = isDeviceOnline && isSkinPrepConfirmed && isLeadConnected
        btnStartEcgRecording.isEnabled = allChecksPassed
        if (allChecksPassed) {
            btnStartEcgRecording.alpha = 1.0f
            btnStartEcgRecording.text = getString(R.string.patient_ecg_pre_session_check_text_start_ecg_recording)
        } else {
            btnStartEcgRecording.alpha = 0.5f
            var missingCount = 0
            if (!isDeviceOnline) missingCount++
            if (!isSkinPrepConfirmed) missingCount++
            if (!isLeadConnected) missingCount++
            btnStartEcgRecording.text = getString(R.string.patient_ecg_pre_session_check_text_complete_all_dynamic, missingCount)
        }
    }

    // Executes asynchronous pre-flight device health query against the Azure backend
    // for the patient's real paired device. Unpaired or unreachable devices are reported honestly.
    private fun performPreFlightCheck() {
        val deviceId = SecurePreferences.getInstance(this).getPairedDeviceId()
        if (deviceId.isNullOrBlank()) {
            isDeviceOnline = false
            isLeadConnected = false
            tvDeviceCheckStatus.text = getString(R.string.patient_ecg_pre_session_check_text_no_device_paired)
            ivDeviceCheckIcon.setImageResource(R.drawable.ic_warning_triangle)
            ivDeviceCheckIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.pulse_danger))

            tvElectrodeCheckStatus.text = getString(R.string.patient_ecg_pre_session_check_text_pair_device_lead1)
            ivElectrodeCheckIcon.setImageResource(R.drawable.ic_circle_outline)
            ivElectrodeCheckIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.pulse_text_secondary))

            updateStartButtonState()
            return
        }

        lifecycleScope.launch {
            try {
                val response = PulseLinkApiClient.apiService.getDeviceStatus(deviceId)
                if (response.isSuccessful && response.body() != null) {
                    val status = response.body()!!
                    val isOnline = status.isActuallyOnline()
                    isDeviceOnline = isOnline
                    isLeadConnected = if (isOnline) status.leadConnected else false
                    updateDeviceStatusUI(isOnline, isLeadConnected)
                } else {
                    isDeviceOnline = false
                    isLeadConnected = false
                    updateDeviceStatusUI(isOnline = false, leadConnected = false)
                }
            } catch (e: Exception) {
                isDeviceOnline = false
                isLeadConnected = false
                updateDeviceStatusUI(isOnline = false, leadConnected = false)
            }
            updateStartButtonState()
        }
    }

    // Updates checklist UI indicators dynamically based on online telemetry
    private fun updateDeviceStatusUI(isOnline: Boolean, leadConnected: Boolean) {
        if (isOnline) {
            tvDeviceCheckStatus.text = getString(R.string.l10n_esp_online_wifi)
            ivDeviceCheckIcon.setImageResource(R.drawable.ic_check_circle)
            ivDeviceCheckIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.pulse_success))
        } else {
            tvDeviceCheckStatus.text = getString(R.string.l10n_esp_offline_retry)
            ivDeviceCheckIcon.setImageResource(R.drawable.ic_warning_triangle)
            ivDeviceCheckIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.pulse_danger))
        }

        if (leadConnected) {
            tvElectrodeCheckStatus.text = getString(R.string.l10n_electrodes_attached)
            ivElectrodeCheckIcon.setImageResource(R.drawable.ic_check_circle)
            ivElectrodeCheckIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.pulse_success))
        } else {
            tvElectrodeCheckStatus.text = getString(R.string.l10n_electrodes_detached)
            ivElectrodeCheckIcon.setImageResource(R.drawable.ic_warning_triangle)
            ivElectrodeCheckIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.pulse_danger))
        }
    }
}

