/*
 * Primary nurse dashboard hosting shift/touchscreen authorisation, BLE transfer sync,
 * active clinic patient launch into ECG pre-session checks, hardware link telemetry,
 * and Patient-Nurse live support preview.
 */
package com.example.prog7314_poepart23

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.prog7314_poepart23.ble.BleTransferResult
import com.example.prog7314_poepart23.ble.PulseLinkBleManager
import com.example.prog7314_poepart23.data.local.OfflineEcgDatabase
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiClient
import com.example.prog7314_poepart23.data.remote.models.AccessAccountDto
import com.example.prog7314_poepart23.data.repository.NurseScreenApproval
import com.example.prog7314_poepart23.data.repository.NurseScreenApprovalRepository
import com.example.prog7314_poepart23.data.repository.SupportChatRepository
import com.example.prog7314_poepart23.data.repository.UserRepository
import com.example.prog7314_poepart23.security.SecurePreferences
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.DateTimeFormatter
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.SessionManager
import com.example.prog7314_poepart23.utils.applyStoredAvatar
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.openDashboardFor
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NurseDashboardActivity : AppCompatActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val prefs by lazy { SecurePreferences.getInstance(this) }
    private val userRepo = UserRepository()
    private val screenRepo = NurseScreenApprovalRepository()
    private val chatRepo = SupportChatRepository()
    private val bleManager by lazy { PulseLinkBleManager(this) }

    private fun rememberedScreens() = getSharedPreferences("nurse_screen_devices", MODE_PRIVATE)

    private lateinit var tvGreetingName: TextView
    private lateinit var tvSiteBadge: TextView
    private lateinit var tvShiftPill: TextView
    private lateinit var tvApprovedDevice: TextView
    private lateinit var tvShiftExpiry: TextView
    private lateinit var btnAuthoriseTouchscreen: MaterialButton
    private lateinit var btnSyncBleRecordings: MaterialButton
    private lateinit var tvSelectedPatientName: TextView
    private lateinit var tvSelectedPatientMeta: TextView
    private lateinit var btnStartPreCheck: MaterialButton
    private lateinit var btnSelectPatient: MaterialButton
    private lateinit var tvHardwarePill: TextView
    private lateinit var tvHardwareUnit: TextView
    private lateinit var tvBleBondStatus: TextView
    private lateinit var tvOfflineQueueCount: TextView
    private lateinit var tvLatestChatPreview: TextView

    private var nurseAccount: AccessAccountDto? = null
    private var assignedSiteId: String = ""
    private var activeApproval: NurseScreenApproval = NurseScreenApproval.NotActive
    private var pendingBleSyncAfterPermission = false

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.values.all { it }
        if (granted && pendingBleSyncAfterPermission) {
            pendingBleSyncAfterPermission = false
            syncBleRecordings()
        } else if (!granted) {
            pendingBleSyncAfterPermission = false
            Toast.makeText(this, R.string.nurse_ui_ble_permission_missing, Toast.LENGTH_LONG).show()
        }
        renderHardwareCard()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.nurse_dashboard)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val currentUser = auth.currentUser
        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val role = SessionManager.getCachedRole(this, currentUser.uid) ?: prefs.getUserRole()
        if (role != SessionManager.ROLE_NURSE && role != SessionManager.ROLE_ADMINISTRATOR) {
            openDashboardFor(role)
            return
        }

        HapticFeedbackManager.initialise(this)
        bindViews()
        wireClicks()
        BottomNavHelper.setUpNurseNav(this, BottomNavHelper.NurseTab.DASHBOARD)
    }

    override fun onResume() {
        super.onResume()
        BottomNavHelper.setUpNurseNav(this, BottomNavHelper.NurseTab.DASHBOARD)
        val uid = auth.currentUser?.uid
        applyStoredAvatar(R.id.btnNurseAvatar, uid)
        renderGreetingAndPatient()
        renderHardwareCard()
        refreshDashboardData()
    }

    private fun bindViews() {
        tvGreetingName = findViewById(R.id.tvNurseGreetingName)
        tvSiteBadge = findViewById(R.id.tvNurseSiteBadge)
        tvShiftPill = findViewById(R.id.tvNurseShiftPill)
        tvApprovedDevice = findViewById(R.id.tvNurseApprovedDevice)
        tvShiftExpiry = findViewById(R.id.tvNurseShiftExpiry)
        btnAuthoriseTouchscreen = findViewById(R.id.btnAuthoriseTouchscreen)
        btnSyncBleRecordings = findViewById(R.id.btnSyncBleRecordings)
        tvSelectedPatientName = findViewById(R.id.tvNurseSelectedPatientName)
        tvSelectedPatientMeta = findViewById(R.id.tvNurseSelectedPatientMeta)
        btnStartPreCheck = findViewById(R.id.btnNurseStartPreCheck)
        btnSelectPatient = findViewById(R.id.btnNurseSelectPatient)
        tvHardwarePill = findViewById(R.id.tvNurseHardwarePill)
        tvHardwareUnit = findViewById(R.id.tvNurseHardwareUnit)
        tvBleBondStatus = findViewById(R.id.tvNurseBleBondStatus)
        tvOfflineQueueCount = findViewById(R.id.tvNurseOfflineQueueCount)
        tvLatestChatPreview = findViewById(R.id.tvNurseLatestChatPreview)
    }

    private fun wireClicks() {
        findViewById<ImageView>(R.id.btnNurseAvatar).setOnClickListener {
            HapticFeedbackManager.lightTick()
            startActivity(Intent(this, NurseSettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.ivNurseSettings).setOnClickListener {
            HapticFeedbackManager.lightTick()
            startActivity(Intent(this, NurseSettingsActivity::class.java))
        }
        btnAuthoriseTouchscreen.setOnClickListener {
            HapticFeedbackManager.lightTick()
            openAuthoriseTouchscreenModal()
        }
        btnSyncBleRecordings.setOnClickListener {
            HapticFeedbackManager.lightTick()
            syncBleRecordings()
        }
        btnSelectPatient.setOnClickListener {
            HapticFeedbackManager.lightTick()
            startActivity(Intent(this, NursePatientIntakeActivity::class.java))
        }
        btnStartPreCheck.setOnClickListener {
            HapticFeedbackManager.lightTick()
            val patientId = prefs.getSelectedNursePatientId()
            if (patientId.isNullOrBlank()) {
                Toast.makeText(this, R.string.nurse_ui_precheck_no_patient, Toast.LENGTH_LONG).show()
                startActivity(Intent(this, NursePatientIntakeActivity::class.java))
            } else {
                startActivity(Intent(this, EcgPreSessionCheckActivity::class.java))
            }
        }
        findViewById<View>(R.id.cardNurseHardwareStatus).setOnClickListener {
            HapticFeedbackManager.lightTick()
            startActivity(Intent(this, DeviceManagementActivity::class.java))
        }
        findViewById<View>(R.id.cardNurseLiveChatPreview).setOnClickListener {
            HapticFeedbackManager.lightTick()
            startActivity(Intent(this, LiveSupportChatActivity::class.java))
        }
    }

    private fun renderGreetingAndPatient() {
        val user = auth.currentUser
        val displayName = nurseAccount?.displayName?.takeIf { it.isNotBlank() }
            ?: user?.displayName?.takeIf { it.isNotBlank() }
            ?: user?.email?.substringBefore('@')?.takeIf { it.isNotBlank() }
            ?: getString(R.string.l10n_role_nurse)
        tvGreetingName.text = getString(R.string.nurse_ui_greeting_format, displayName)

        val savedSiteName = prefs.getNurseSiteName()
        tvSiteBadge.text = if (!savedSiteName.isNullOrBlank()) {
            getString(R.string.nurse_ui_assigned_site_format, savedSiteName)
        } else {
            getString(R.string.nurse_ui_assigned_site_none)
        }

        val patientId = prefs.getSelectedNursePatientId()
        val patientName = prefs.getSelectedNursePatientName()
        if (!patientId.isNullOrBlank() && !patientName.isNullOrBlank()) {
            tvSelectedPatientName.text = patientName
            val docName = prefs.getSelectedNurseDoctorName()
                ?: prefs.getSelectedNurseDoctorUid()
                ?: getString(R.string.l10n_unavailable)
            tvSelectedPatientMeta.text =
                "$patientId • ${getString(R.string.nurse_ui_attending_doctor_format, docName)}"
        } else {
            tvSelectedPatientName.text = getString(R.string.nurse_ui_active_patient_none_title)
            tvSelectedPatientMeta.text = getString(R.string.nurse_ui_active_patient_none_subtitle)
        }
    }

    private fun renderHardwareCard() {
        val uid = auth.currentUser?.uid.orEmpty()
        val pairedId = prefs.getPairedDeviceId()
            ?: rememberedScreens().getString(uid, null)
        val hasDevice = !pairedId.isNullOrBlank()

        tvHardwareUnit.text = pairedId ?: getString(R.string.l10n_not_registered)
        val bonded = findBondedSafely(pairedId)
        tvBleBondStatus.text = if (bonded != null) {
            getString(R.string.nurse_ui_hardware_ble_bonded, bonded.name ?: bonded.address)
        } else {
            getString(R.string.nurse_ui_hardware_ble_not_bonded)
        }

        val isLinked = hasDevice || bonded != null
        tvHardwarePill.text = getString(
            if (isLinked) R.string.nurse_ui_hardware_online else R.string.nurse_ui_hardware_offline
        )
        styleStatusPill(
            tvHardwarePill,
            bgColorRes = if (isLinked) R.color.pulse_success_bg else R.color.pulse_input_bg,
            textColorRes = if (isLinked) R.color.pulse_success else R.color.pulse_text_secondary
        )

        lifecycleScope.launch(Dispatchers.IO) {
            val summary = OfflineEcgDatabase.getInstance(applicationContext).getDeviceSyncSummary(pairedId)
            val pendingTotal = summary.local + summary.synchronising
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    tvOfflineQueueCount.text =
                        getString(R.string.nurse_ui_hardware_queue_count, pendingTotal)
                }
            }
        }
    }

    private fun refreshDashboardData() {
        val user = auth.currentUser ?: return
        lifecycleScope.launch {
            runCatching { userRepo.refreshAuthToken() }

            val meRes = runCatching {
                withContext(Dispatchers.IO) { PulseLinkApiClient.apiService.getAccessAccount() }
            }.getOrNull()
            if (meRes != null && meRes.isSuccessful && meRes.body() != null) {
                nurseAccount = meRes.body()
                assignedSiteId = nurseAccount?.siteIds?.firstOrNull().orEmpty()
                if (!nurseAccount?.displayName.isNullOrBlank()) {
                    renderGreetingAndPatient()
                }
            }

            val sitesRes = runCatching {
                withContext(Dispatchers.IO) { PulseLinkApiClient.apiService.getAssignedSites() }
            }.getOrNull()
            if (sitesRes != null && sitesRes.isSuccessful && !sitesRes.body().isNullOrEmpty()) {
                val primarySite = sitesRes.body()!!.first()
                assignedSiteId = primarySite.id
                prefs.saveNurseSiteName(primarySite.name)
                tvSiteBadge.text = getString(R.string.nurse_ui_assigned_site_format, primarySite.name)
            }

            val deviceId = rememberedScreens().getString(user.uid, null)
                ?: prefs.getPairedDeviceId()
            activeApproval = if (!deviceId.isNullOrBlank()) {
                screenRepo.current(deviceId, assignedSiteId, user.uid)
            } else {
                NurseScreenApproval.NotActive
            }
            renderShiftStatus()
            renderHardwareCard()

            val threads = runCatching { chatRepo.loadThreads(applicationContext) }.getOrDefault(emptyList())
            val latest = threads.maxByOrNull { it.lastTimestampMillis }
            tvLatestChatPreview.text = if (latest != null && latest.lastMessage.isNotBlank()) {
                "${latest.patientName.ifBlank { getString(R.string.support_chat_sender_patient) }}: ${latest.lastMessage}"
            } else {
                getString(R.string.nurse_ui_chat_card_empty)
            }
        }
    }

    private fun renderShiftStatus() {
        val uid = auth.currentUser?.uid.orEmpty()
        val rememberedDevice = rememberedScreens().getString(uid, null) ?: prefs.getPairedDeviceId()
        val grant = (activeApproval as? NurseScreenApproval.Approved)?.grant
        val expiresMs = grant?.let { NurseApproval.parseUtcMillis(it.expiresAtUtc) }
        val state = NurseApproval.authorisationState(expiresMs, System.currentTimeMillis())

        when (state) {
            AuthorisationState.ACTIVE -> {
                tvShiftPill.text = getString(R.string.nurse_ui_shift_pill_active)
                styleStatusPill(tvShiftPill, R.color.pulse_success_bg, R.color.pulse_success)
                tvApprovedDevice.text = grant?.deviceId ?: rememberedDevice ?: getString(R.string.l10n_not_registered)
                tvShiftExpiry.text = DateTimeFormatter.formatIsoTimestamp(grant?.expiresAtUtc)
                btnAuthoriseTouchscreen.text = getString(R.string.nurse_ui_btn_authorise_another)
            }
            AuthorisationState.EXPIRED -> {
                tvShiftPill.text = getString(R.string.nurse_ui_shift_pill_expired)
                styleStatusPill(tvShiftPill, R.color.pulse_warning_bg, R.color.pulse_warning)
                tvApprovedDevice.text = grant?.deviceId ?: rememberedDevice ?: getString(R.string.l10n_not_registered)
                tvShiftExpiry.text = DateTimeFormatter.formatIsoTimestamp(grant?.expiresAtUtc)
                btnAuthoriseTouchscreen.text = getString(R.string.nurse_ui_btn_authorise_screen)
            }
            AuthorisationState.NONE -> {
                tvShiftPill.text = getString(R.string.nurse_ui_shift_pill_none)
                styleStatusPill(tvShiftPill, R.color.pulse_blue_bg, R.color.pulse_picker_accent)
                tvApprovedDevice.text = rememberedDevice ?: getString(R.string.l10n_not_registered)
                tvShiftExpiry.text = getString(R.string.l10n_unavailable)
                btnAuthoriseTouchscreen.text = getString(R.string.nurse_ui_btn_authorise_screen)
            }
        }
    }

    private fun openAuthoriseTouchscreenModal() {
        val uid = auth.currentUser?.uid ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_authorise_touchscreen, null)
        val etDeviceId = dialogView.findViewById<EditText>(R.id.etDialogTouchscreenId)
        val etCode = dialogView.findViewById<EditText>(R.id.etDialogTouchscreenCode)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelTouchscreenDialog)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirmTouchscreenDialog)

        val defaultId = rememberedScreens().getString(uid, null)
            ?: prefs.getPairedDeviceId()
            ?: ""
        if (defaultId.isNotBlank()) etDeviceId.setText(defaultId)

        val dialog = createCardDialog(dialogView)
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val deviceId = etDeviceId.text.toString().trim()
            val code = etCode.text.toString().trim()
            if (deviceId.isBlank()) {
                etDeviceId.error = getString(R.string.access_required)
                return@setOnClickListener
            }
            if (code.length < 4) {
                etCode.error = getString(R.string.screen_code_required)
                return@setOnClickListener
            }
            btnConfirm.isEnabled = false
            lifecycleScope.launch {
                runCatching { userRepo.refreshAuthToken() }
                val res = screenRepo.approve(deviceId, code, assignedSiteId, uid)
                btnConfirm.isEnabled = true
                when (res) {
                    is NurseScreenApproval.Approved -> {
                        rememberedScreens().edit().putString(uid, res.grant.deviceId).apply()
                        prefs.savePairedDeviceId(res.grant.deviceId)
                        activeApproval = res
                        renderShiftStatus()
                        renderHardwareCard()
                        Toast.makeText(
                            this@NurseDashboardActivity,
                            getString(
                                R.string.nurse_feedback_success,
                                res.grant.deviceId,
                                DateTimeFormatter.formatIsoTimestamp(res.grant.expiresAtUtc)
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                        dialog.dismiss()
                    }
                    is NurseScreenApproval.Failed -> {
                        Toast.makeText(this@NurseDashboardActivity, submitError(res.reason), Toast.LENGTH_LONG).show()
                    }
                    NurseScreenApproval.NotActive -> {
                        Toast.makeText(this@NurseDashboardActivity, R.string.nurse_feedback_unavailable, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun syncBleRecordings() {
        val uid = auth.currentUser?.uid ?: return
        val grant = (activeApproval as? NurseScreenApproval.Approved)?.grant
        val expiresMs = grant?.let { NurseApproval.parseUtcMillis(it.expiresAtUtc) }
        if (grant == null || NurseApproval.authorisationState(expiresMs, System.currentTimeMillis()) != AuthorisationState.ACTIVE) {
            Toast.makeText(this, R.string.nurse_ui_ble_sync_requires_shift, Toast.LENGTH_LONG).show()
            openAuthoriseTouchscreenModal()
            return
        }

        if (!hasBluetoothPermissions()) {
            pendingBleSyncAfterPermission = true
            requestBluetoothPermissions()
            return
        }

        val bonded = findBondedSafely(grant.deviceId)
        if (bonded == null) {
            Toast.makeText(
                this,
                getString(R.string.ui_ble_pair_first, grant.deviceId),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        btnSyncBleRecordings.isEnabled = false
        lifecycleScope.launch {
            val result = bleManager.connectAndSyncAuthorisedRecordings(bonded, grant.id, uid)
            btnSyncBleRecordings.isEnabled = true
            renderHardwareCard()
            when (result) {
                is BleTransferResult.Success -> {
                    Toast.makeText(
                        this@NurseDashboardActivity,
                        resources.getQuantityString(
                            R.plurals.ui_ble_transferred,
                            result.transferredSessions,
                            result.transferredSessions
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
                is BleTransferResult.Failure -> {
                    Toast.makeText(
                        this@NurseDashboardActivity,
                        R.string.ui_ble_transfer_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun findBondedSafely(deviceId: String?): android.bluetooth.BluetoothDevice? {
        if (!hasBluetoothPermissions()) return null
        return try {
            bleManager.findBondedTouchscreen(deviceId)
        } catch (_: SecurityException) {
            null
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissionLauncher.launch(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            )
        }
    }

    private fun createCardDialog(content: View): AppCompatDialog {
        return AppCompatDialog(
            this,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
        ).apply {
            supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(content)
            window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                val dm = resources.displayMetrics
                val maxWidth = (500 * dm.density).toInt()
                setLayout(minOf(dm.widthPixels - (24 * dm.density).toInt(), maxWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun styleStatusPill(pill: TextView, bgColorRes: Int, textColorRes: Int) {
        val density = resources.displayMetrics.density
        pill.background = GradientDrawable().apply {
            cornerRadius = 20f * density
            setColor(ContextCompat.getColor(this@NurseDashboardActivity, bgColorRes))
        }
        pill.setTextColor(ContextCompat.getColor(this, textColorRes))
    }

    private fun submitError(reason: NurseScreenApproval.Reason): Int = when (reason) {
        NurseScreenApproval.Reason.INVALID_DEVICE -> R.string.nurse_feedback_device_not_found
        NurseScreenApproval.Reason.INVALID_CODE -> R.string.nurse_feedback_wrong_code
        NurseScreenApproval.Reason.SIGN_IN_REQUIRED -> R.string.nurse_feedback_sign_in
        NurseScreenApproval.Reason.DENIED -> R.string.nurse_feedback_denied
        NurseScreenApproval.Reason.EXPIRED -> R.string.nurse_feedback_expired
        NurseScreenApproval.Reason.CONFLICT -> R.string.screen_approval_conflict
        NurseScreenApproval.Reason.INVALID_RECEIPT -> R.string.screen_approval_invalid_receipt
        NurseScreenApproval.Reason.UNAVAILABLE -> R.string.nurse_feedback_unavailable
    }
}
