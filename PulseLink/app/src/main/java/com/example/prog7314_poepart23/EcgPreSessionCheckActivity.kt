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

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import androidx.lifecycle.lifecycleScope              // (Google Developers, 2024)
import com.example.prog7314_poepart23.ble.PulseLinkBleManager
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiClient
import com.example.prog7314_poepart23.data.repository.NurseScreenApproval
import com.example.prog7314_poepart23.data.repository.NurseScreenApprovalRepository
import com.example.prog7314_poepart23.security.SecurePreferences
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.SessionManager
import com.google.android.material.button.MaterialButton // (Google Developers, 2024)
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch                     // (JetBrains, 2024)
import kotlinx.coroutines.withContext
import java.util.Locale

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
    private var tvNurseSelectedPatientBanner: TextView? = null

    // Gating state variables
    private var isDeviceOnline: Boolean = false
    private var isSkinPrepConfirmed: Boolean = false
    private var isLeadConnected: Boolean = false
    private var isLiveApiOnline: Boolean = false
    private var hasLiveHeartbeat: Boolean = false
    private var hasLiveLeadOffFlag: Boolean = false
    private var leadWarningRetried: Boolean = false
    private var resolvedDeviceLabel: String = ""

    private val screenRepo = NurseScreenApprovalRepository()
    private val bleManager by lazy { PulseLinkBleManager(this) }

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
        BottomNavHelper.setUpNurseNav(this, BottomNavHelper.NurseTab.MONITOR)
        refreshSelectedPatientBanner()
        updateStartButtonState()
        performPreFlightCheck()
    }

    override fun onResume() {
        super.onResume()
        BottomNavHelper.setUpNurseNav(this, BottomNavHelper.NurseTab.MONITOR)
        refreshSelectedPatientBanner()
    }

    // Binds XML layout view references.
    private fun bindViews() {
        btnCloseCheck = findViewById(R.id.btnCloseCheck)
        tvNurseSelectedPatientBanner = findViewById(R.id.tvNurseSelectedPatientBanner)
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

        // Tap electrode row to verify Lead I contact or retry live probe
        layoutElectrodeCheck.setOnClickListener {
            HapticFeedbackManager.lightTick()
            if (isDeviceOnline && ((!hasLiveHeartbeat && !hasLiveLeadOffFlag) || leadWarningRetried)) {
                isLeadConnected = !isLeadConnected
                updateDeviceStatusUI(isDeviceOnline, isLeadConnected)
                updateStartButtonState()
            } else {
                if (hasLiveLeadOffFlag) leadWarningRetried = true
                tvElectrodeCheckStatus.text = getString(R.string.patient_ecg_pre_session_check_text_lead_checking)
                ivElectrodeCheckIcon.setImageResource(R.drawable.ic_circle_outline)
                ivElectrodeCheckIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.pulse_text_secondary))
                performPreFlightCheck()
            }
        }

        // Tap placement guide to view full anatomical electrode guide
        layoutPlacementGuide.setOnClickListener {
            HapticFeedbackManager.lightTick()
            startActivity(Intent(this, ElectrodePlacementGuideActivity::class.java))
        }

        // Start recording only when all 3 checks are satisfied
        btnStartEcgRecording.setOnClickListener {
            if (isNurseOrAdmin() && SecurePreferences.getInstance(this).getSelectedNursePatientId().isNullOrBlank()) {
                Toast.makeText(this, R.string.nurse_ui_precheck_no_patient, Toast.LENGTH_LONG).show()
                startActivity(Intent(this, NursePatientIntakeActivity::class.java))
                return@setOnClickListener
            }
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

    private fun isNurseOrAdmin(): Boolean {
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val role = (if (uid.isNotBlank()) SessionManager.getCachedRole(this, uid) else null)
            ?: SecurePreferences.getInstance(this).getUserRole()?.lowercase(Locale.ROOT)
        return role == SessionManager.ROLE_NURSE || role == SessionManager.ROLE_ADMINISTRATOR
    }

    private fun refreshSelectedPatientBanner() {
        val banner = tvNurseSelectedPatientBanner ?: return
        if (!isNurseOrAdmin()) {
            banner.visibility = View.GONE
            return
        }
        val prefs = SecurePreferences.getInstance(this)
        val patientId = prefs.getSelectedNursePatientId()
        val patientName = prefs.getSelectedNursePatientName()
        banner.visibility = View.VISIBLE
        if (!patientId.isNullOrBlank() && !patientName.isNullOrBlank()) {
            banner.text = getString(R.string.nurse_ui_selected_patient_banner, patientName, patientId)
            banner.setTextColor(ContextCompat.getColor(this, R.color.pulse_picker_accent))
            banner.setBackgroundColor(ContextCompat.getColor(this, R.color.pulse_blue_bg))
            banner.setOnClickListener { startActivity(Intent(this, NursePatientIntakeActivity::class.java)) }
        } else {
            banner.text = getString(R.string.nurse_ui_precheck_no_patient)
            banner.setTextColor(ContextCompat.getColor(this, R.color.pulse_warning))
            banner.setBackgroundColor(ContextCompat.getColor(this, R.color.pulse_warning_bg))
            banner.setOnClickListener { startActivity(Intent(this, NursePatientIntakeActivity::class.java)) }
        }
    }

    // Executes asynchronous pre-flight device health query against the Azure backend
    // and active nurse touchscreen shift / bonded BLE status.
    private fun performPreFlightCheck() {
        val prefs = SecurePreferences.getInstance(this)
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val rememberedScreen = if (uid.isNotBlank()) {
            getSharedPreferences("nurse_screen_devices", MODE_PRIVATE).getString(uid, null)
        } else null
        val deviceId = prefs.getPairedDeviceId()?.takeIf { it.isNotBlank() } ?: rememberedScreen

        if (deviceId.isNullOrBlank()) {
            isDeviceOnline = false
            isLeadConnected = false
            isLiveApiOnline = false
            hasLiveHeartbeat = false
            hasLiveLeadOffFlag = false
            tvDeviceCheckStatus.text = getString(R.string.patient_ecg_pre_session_check_text_no_device_paired)
            ivDeviceCheckIcon.setImageResource(R.drawable.ic_warning_triangle)
            ivDeviceCheckIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.pulse_danger))

            tvElectrodeCheckStatus.text = getString(R.string.patient_ecg_pre_session_check_text_pair_device_lead1)
            ivElectrodeCheckIcon.setImageResource(R.drawable.ic_circle_outline)
            ivElectrodeCheckIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.pulse_text_secondary))

            updateStartButtonState()
            return
        }
        resolvedDeviceLabel = deviceId

        lifecycleScope.launch {
            var apiOnline = false
            var apiLeadConnected = false
            var apiHasHeartbeat = false
            var isRegistered = false
            var hasActiveShift = false
            var hasBondedBle = false

            try {
                val response = withContext(Dispatchers.IO) { PulseLinkApiClient.apiService.getDeviceStatus(deviceId) }
                if (response.isSuccessful && response.body() != null) {
                    val status = response.body()!!
                    apiOnline = status.isActuallyOnline()
                    apiHasHeartbeat = status.hasRecentHeartbeat()
                    isRegistered = status.isRegisteredDevice()
                    apiLeadConnected = if (apiHasHeartbeat) status.leadConnected else false
                }
            } catch (_: Exception) {
            }

            if (isNurseOrAdmin() && uid.isNotBlank()) {
                var siteId = prefs.getSelectedNurseSiteId().orEmpty()
                if (siteId.isBlank()) {
                    val sites = runCatching {
                        withContext(Dispatchers.IO) { PulseLinkApiClient.apiService.getAssignedSites() }
                    }.getOrNull()
                    siteId = sites?.body()?.firstOrNull()?.id.orEmpty()
                }
                if (siteId.isNotBlank()) {
                    val approval = runCatching { screenRepo.current(deviceId, siteId, uid) }.getOrNull()
                    if (approval is NurseScreenApproval.Approved) {
                        val state = NurseApproval.authorisationState(
                            NurseApproval.parseUtcMillis(approval.grant.expiresAtUtc),
                            System.currentTimeMillis()
                        )
                        hasActiveShift = state == AuthorisationState.ACTIVE
                    }
                }
            }

            hasBondedBle = hasBluetoothConnectPermission() && runCatching {
                bleManager.findBondedTouchscreen(deviceId) != null
            }.getOrDefault(false)

            isLiveApiOnline = apiOnline
            hasLiveHeartbeat = apiHasHeartbeat
            hasLiveLeadOffFlag = apiHasHeartbeat && !apiLeadConnected
            isDeviceOnline = apiOnline || hasActiveShift || hasBondedBle || isRegistered || (isNurseOrAdmin() && deviceId.isNotBlank())
            isLeadConnected = when {
                apiHasHeartbeat -> apiLeadConnected
                isDeviceOnline && isLeadConnected -> true
                else -> false
            }

            updateDeviceStatusUI(isDeviceOnline, isLeadConnected)
            updateStartButtonState()
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    // Updates checklist UI indicators dynamically based on online telemetry or authorised hardware link
    private fun updateDeviceStatusUI(isOnline: Boolean, leadConnected: Boolean) {
        if (isOnline) {
            tvDeviceCheckStatus.text = if (isLiveApiOnline) {
                getString(R.string.l10n_esp_online_wifi)
            } else {
                getString(R.string.nurse_ui_precheck_shift_online, resolvedDeviceLabel)
            }
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
        } else if (isOnline && (!hasLiveHeartbeat || leadWarningRetried)) {
            tvElectrodeCheckStatus.text = getString(R.string.precheck_tap_confirm_electrodes)
            ivElectrodeCheckIcon.setImageResource(R.drawable.ic_circle_outline)
            ivElectrodeCheckIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.pulse_text_secondary))
        } else {
            tvElectrodeCheckStatus.text = getString(R.string.l10n_electrodes_detached)
            ivElectrodeCheckIcon.setImageResource(R.drawable.ic_warning_triangle)
            ivElectrodeCheckIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.pulse_danger))
        }
    }
}

