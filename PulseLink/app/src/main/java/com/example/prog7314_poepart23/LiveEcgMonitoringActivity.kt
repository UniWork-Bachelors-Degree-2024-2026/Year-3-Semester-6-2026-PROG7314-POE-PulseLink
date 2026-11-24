// Real-time patient ECG monitoring controller managing
// the 90-second clinical telemetry session, 60 FPS waveform rendering, R-peak haptic
// feedback, live BPM tracking, and session lifecycle controls.

// Key Architecture & Clinical Flow:
// 1. 90-Second Protocol: Executes a standard 1 minute 30 second recording interval using CountDownTimer.
// 2. Real-Time Telemetry: Feeds 250 Hz single-lead AD8232 Lead I analogue data into EcgWaveformView.
// 3. Haptic Synchronisation: On each detected R-peak, triggers a gentle cardiac haptic tick and UI pulse.
// 4. Lifecycle Management: Handles pause, resume, early termination, and emergency call intents safely.
// 5. Visual Standards: Uses pure Android vector drawables (SVG equivalent) and zero emojis.

package com.example.prog7314_poepart23

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope               // (Google Developers, 2024)
import com.example.prog7314_poepart23.security.SecurePreferences
import com.example.prog7314_poepart23.utils.ArrhythmiaAlertHandler
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.BpmAnomalyDetector
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.SessionManager
import com.example.prog7314_poepart23.utils.applyStoredAvatar
import com.example.prog7314_poepart23.views.EcgWaveformView
import com.example.prog7314_poepart23.viewmodels.EcgSessionViewModel
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AlertDialog
import com.example.prog7314_poepart23.utils.Resource
import kotlinx.coroutines.launch                      // (JetBrains, 2024)
import java.util.Locale
import com.example.prog7314_poepart23.utils.openOnClick

class LiveEcgMonitoringActivity : AppCompatActivity() {

    // ViewModel managing remote API telemetry and recording session state
    private val ecgSessionViewModel: EcgSessionViewModel by viewModels()

    // Interactive user interface elements
    private lateinit var ecgWaveformView: EcgWaveformView
    private lateinit var tvBpmValue: TextView
    private lateinit var ivHeartPulse: ImageView
    private lateinit var tvSessionDuration: TextView
    private lateinit var btnPauseSession: TextView
    private lateinit var btnEndSession: TextView
    private lateinit var btnCallEmergency: TextView
    private lateinit var tvSensorStatus: TextView
    private lateinit var tvLeadStatus: TextView
    private lateinit var toggleGridMode: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var layoutOfflineLiveBanner: android.widget.LinearLayout

    private val localDb by lazy { com.example.prog7314_poepart23.data.local.OfflineEcgDatabase.getInstance(this) }
    private val sampleBuffer = mutableListOf<Int>()

    private val pendingCacheWrites = mutableListOf<Job>()
    private var leadDisconnectDialog: AlertDialog? = null
    private var sessionActionInProgress = false
    private var isSessionPaused = false
    private var isSessionActive = true

    // Samples shown on screen come only from the ESP32-C3 / AD8232 hardware: the device posts them
    // to the backend and the view model polls them for this session. Nothing is generated locally.
    // The API returns the session's samples so far, so only the part not yet drawn is rendered.
    private var renderedSampleCount = 0
    private var receivedDeviceData = false

    // Current 15-second BPM average, used for the display and offline batch cache
    private var latestSmoothedBpm = 0
    private var isLeadConnected = true

    private var patientId: String? = null
    private var deviceId: String? = null
    private var activePatientName: String? = null
    private var isNurseMode: Boolean = false

    // Real-time arrhythmia alerting (dialog + notification + redirect to SOS)
    private lateinit var arrhythmiaAlerts: ArrhythmiaAlertHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.patient_ecg_live_monitoring)

        // Initialise centralised device haptics
        HapticFeedbackManager.initialise(this)
        arrhythmiaAlerts = ArrhythmiaAlertHandler(this)

        // Apply edge-to-edge window insets padding
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        val prefs = SecurePreferences.getInstance(this)
        val uid = currentUser?.uid.orEmpty()
        val role = (if (uid.isNotBlank()) SessionManager.getCachedRole(this, uid) else null)
            ?: prefs.getUserRole()?.lowercase(Locale.ROOT)
        isNurseMode = role == SessionManager.ROLE_NURSE || role == SessionManager.ROLE_ADMINISTRATOR

        val rememberedScreen = if (uid.isNotBlank()) {
            getSharedPreferences("nurse_screen_devices", MODE_PRIVATE).getString(uid, null)
        } else null
        deviceId = prefs.getPairedDeviceId()?.takeIf { it.isNotBlank() } ?: rememberedScreen

        if (isNurseMode) {
            patientId = prefs.getSelectedNursePatientId()?.takeIf { it.isNotBlank() }
            activePatientName = prefs.getSelectedNursePatientName()?.takeIf { it.isNotBlank() }
            if (patientId == null) {
                Toast.makeText(this, getString(R.string.nurse_ui_precheck_no_patient), Toast.LENGTH_LONG).show()
                startActivity(Intent(this, NursePatientIntakeActivity::class.java))
                finish()
                return
            }
        } else {
            patientId = currentUser?.uid
            activePatientName = currentUser?.displayName
        }

        bindViews()
        BottomNavHelper.setUpNurseNav(this, BottomNavHelper.NurseTab.MONITOR)
        setupWaveformListener()
        setupClickListeners()
        observeSessionViewModel()
        observeDeviceSamples()
        ecgSessionViewModel.leadConnected.observe(this) { connected ->
            if (!connected && isLeadConnected) flushSampleBufferToLocalDb()
            isLeadConnected = connected
            ecgWaveformView.isLeadsOff = !connected
            if (!connected) {
                latestSmoothedBpm = 0
                ecgWaveformView.clearBuffer()
                tvBpmValue.text = getString(R.string.l10n_bpm_none)
                tvLeadStatus.text = getString(R.string.l10n_lead_fault_banner)
                HapticFeedbackManager.warningBuzz()
                showLeadDisconnectedDialog()
            } else {
                if (receivedDeviceData) tvLeadStatus.text = getString(R.string.l10n_lead_active)
                updateLeadDialog()
            }
        }

        if (patientId == null || deviceId == null) {
            Toast.makeText(this, getString(R.string.l10n_pair_before_recording), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (!isFinishing && ecgSessionViewModel.currentSessionId == null) startSession()
    }

    // Binds view layout identifiers to local activity references.
    private fun bindViews() {
        ecgWaveformView = findViewById(R.id.ecgWaveformView)
        tvBpmValue = findViewById(R.id.tvBpmValue)
        ivHeartPulse = findViewById(R.id.ivHeartPulse)
        tvSessionDuration = findViewById(R.id.tvSessionDuration)
        btnPauseSession = findViewById(R.id.btnPauseSession)
        btnEndSession = findViewById(R.id.btnEndSession)
        btnCallEmergency = findViewById(R.id.btnCallEmergency)
        tvSensorStatus = findViewById(R.id.tvSensorStatus)
        tvLeadStatus = findViewById(R.id.tvLeadStatus)
        toggleGridMode = findViewById(R.id.toggleGridMode)
        layoutOfflineLiveBanner = findViewById(R.id.layoutOfflineLiveBanner)

        // Nothing is claimed about the sensor until real samples arrive from the device.
        tvSensorStatus.text = getString(R.string.live_waiting_for_device)
        tvLeadStatus.text = getString(R.string.live_waiting_for_device)
    }

    // Subscribes to Pan-Tompkins QRS R-peak detection events from the 60 FPS waveform surface.
    private fun setupWaveformListener() {
        ecgWaveformView.onRPeakDetected = { smoothedBpm ->
            if (!isSessionPaused && isSessionActive && isLeadConnected) {
                // Deliver synchronised cardiac haptic feedback and pulse animation
                HapticFeedbackManager.rPeakPulse()
                animateHeartPulse()

                // Real QRS estimates feed the rolling display average; alerts keep their existing beat-based checks.
                if (smoothedBpm > 0) {
                    ecgSessionViewModel.updateEstimatedBpm(smoothedBpm)
                    val evaluation = BpmAnomalyDetector.evaluate(
                        smoothedBpm,
                        isLeadConnected = true,
                        isRhythmIrregular = ecgWaveformView.isRhythmIrregular
                    )
                    arrhythmiaAlerts.onEvaluation(evaluation)
                }
            }
        }
    }

    // Sets up click event listeners for pause/resume, session termination, grid mode toggle, and emergency call.
    private fun setupClickListeners() {
        toggleGridMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                HapticFeedbackManager.lightTick()
                when (checkedId) {
                    R.id.btnModeMonitor -> {
                        ecgWaveformView.gridMode = EcgWaveformView.EcgGridMode.DARK_CLINICAL_MONITOR
                    }
                    R.id.btnModePaper -> {
                        ecgWaveformView.gridMode = EcgWaveformView.EcgGridMode.HOSPITAL_PAPER
                    }
                }
            }
        }

        btnPauseSession.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            togglePauseResume()
        }

        btnEndSession.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            finishSession()
        }

        btnCallEmergency.setOnClickListener {
            HapticFeedbackManager.warningBuzz()
            // Launch emergency telephony dialer with the South African standard emergency number (112)
            val emergencyIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:" + getString(R.string.emergency_number_general))
            }
            startActivity(emergencyIntent)
        }

        // Opens the pre-session checklist
        findViewById<View>(R.id.btnPreSessionCheck).setOnClickListener {
            HapticFeedbackManager.confirmClick()
            startActivity(Intent(this, EcgPreSessionCheckActivity::class.java))
        }
    }

    // Commences the 90-second countdown, starts the real backend recording session,
    // and begins local waveform rendering with offline SQLite resilience.
    private fun startSession() {
        isSessionActive = true
        isSessionPaused = false
        tvBpmValue.text = getString(R.string.l10n_bpm_none)
        BpmAnomalyDetector.resetBuffer()
        ecgSessionViewModel.startRecording(
            patientId = patientId!!,
            deviceId = deviceId,
            patientName = activePatientName ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName,
            context = this
        )
    }

    // Observes the real session lifecycle (start failure / final backend-computed summary)
    // reported by the view model. No BPM or session data displayed here is invented locally.
    private fun observeSessionViewModel() {
        // Observe real-time network connectivity to toggle the offline status indicator
        lifecycleScope.launch {
            com.example.prog7314_poepart23.utils.NetworkMonitor.isOnline.collect { isOnline ->
                layoutOfflineLiveBanner.visibility = if (isOnline) View.GONE else View.VISIBLE
            }
        }

        ecgSessionViewModel.controlError.observe(this) { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        ecgSessionViewModel.formattedTime.observe(this) { tvSessionDuration.text = it }
        ecgSessionViewModel.isRecording.observe(this) { isSessionActive = it }
        ecgSessionViewModel.isPaused.observe(this) {
            isSessionPaused = it
            findViewById<TextView>(R.id.tvRecordingStatus).setText(if (it) R.string.live_recording_paused else R.string.live_ecg_status_recording)
            findViewById<View>(R.id.ivRecordingDot).visibility = if (it) View.INVISIBLE else View.VISIBLE
            updateSessionControls()
        }
        ecgSessionViewModel.isReady.observe(this) { updateSessionControls() }
        ecgSessionViewModel.currentBpm.observe(this) {
            latestSmoothedBpm = it
            com.example.prog7314_poepart23.utils.HeartRateStatus.apply(tvBpmValue, it, if (it > 0) getString(R.string.l10n_bpm_value, it) else getString(R.string.l10n_bpm_none))
        }
        ecgSessionViewModel.timeExpired.observe(this) { expired -> if (expired) finishSession() }

        ecgSessionViewModel.sessionSummary.observe(this) { resource ->
            when (resource) {
                is com.example.prog7314_poepart23.utils.Resource.Success -> {
                    val summary = resource.data ?: return@observe
                    val previewIntent = Intent(this, SessionReportPreviewActivity::class.java).apply {
                        putExtra(PatientEcgHistoryActivity.EXTRA_SESSION_ID, summary.sessionId)
                        putExtra(PatientEcgHistoryActivity.EXTRA_AVERAGE_BPM, summary.averageBpm)
                        putExtra(PatientEcgHistoryActivity.EXTRA_DURATION_SECONDS, summary.durationSeconds)
                        putExtra(PatientEcgHistoryActivity.EXTRA_START_TIME_UTC, summary.startTimeUtc)
                        putExtra(PatientEcgHistoryActivity.EXTRA_SIGNAL_QUALITY, summary.signalQuality)
                        putExtra(PatientEcgHistoryActivity.EXTRA_SESSION_STATUS, summary.status)
                    }
                    startActivity(previewIntent)
                    finish()
                }
                is com.example.prog7314_poepart23.utils.Resource.Error -> {
                    if (!isSessionActive) {
                        // Failed while wrapping up the session.
                        Toast.makeText(this, resource.message ?: getString(R.string.l10n_unable_save_recording), Toast.LENGTH_LONG).show()
                        finish()
                    } else if (ecgSessionViewModel.currentSessionId == null) {
                        // Failed to even start the session.
                        Toast.makeText(this, resource.message ?: getString(R.string.l10n_unable_start_recording), Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
                is com.example.prog7314_poepart23.utils.Resource.Loading -> Unit
            }
        }
    }

    private fun togglePauseResume() {
        if (ecgSessionViewModel.isPaused.value == true) {
            if (!ecgSessionViewModel.resumeRecording()) showLeadDisconnectedDialog()
            else startTelemetryIngestion()
        } else {
            flushSampleBufferToLocalDb()
            ecgSessionViewModel.pauseRecording()
        }
    }

    private fun showLeadDisconnectedDialog() {
        if (isFinishing || isDestroyed || leadDisconnectDialog?.isShowing == true) return
        arrhythmiaAlerts.dismiss()
        leadDisconnectDialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_PulseLink_Dialog)
            .setTitle(R.string.live_lead_disconnected_title)
            .setMessage(R.string.live_lead_disconnected_message)
            .setCancelable(false)
            .setPositiveButton(R.string.live_resume_recording, null)
            .setNeutralButton(R.string.live_restart_recording, null)
            .setNegativeButton(R.string.live_discard_recording, null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (ecgSessionViewModel.resumeRecording()) {
                            dismiss()
                            startTelemetryIngestion()
                        }
                    }
                    getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { discardSession(restart = true) }
                    getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { discardSession(restart = false) }
                    updateLeadDialog()
                }
                show()
            }
        updateLeadDialog()
    }

    private fun updateLeadDialog() {
        leadDisconnectDialog?.takeIf { it.isShowing }?.apply {
            setMessage(getString(if (isLeadConnected) R.string.live_lead_reconnected_message else R.string.live_lead_disconnected_message))
            val ready = ecgSessionViewModel.isReady.value == true && !sessionActionInProgress
            getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = ready && isLeadConnected
            getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = ready && isLeadConnected
            getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = ready
        }
    }

    private fun discardSession(restart: Boolean) {
        if (sessionActionInProgress || (restart && !isLeadConnected)) return
        sessionActionInProgress = true
        flushSampleBufferToLocalDb()
        ecgSessionViewModel.pauseRecording()
        updateSessionControls()
        lifecycleScope.launch {
            pendingCacheWrites.toList().forEach { it.join() }
            when (val result = ecgSessionViewModel.discardRecording(this@LiveEcgMonitoringActivity)) {
                is Resource.Success -> {
                    leadDisconnectDialog?.dismiss()
                    leadDisconnectDialog = null
                    if (restart) {
                        renderedSampleCount = 0
                        receivedDeviceData = false
                        ecgWaveformView.clearBuffer()
                        startSession()
                    } else finish()
                }
                is Resource.Error -> Toast.makeText(this@LiveEcgMonitoringActivity, result.message, Toast.LENGTH_LONG).show()
                is Resource.Loading -> Unit
            }
            sessionActionInProgress = false
            updateSessionControls()
        }
    }

    private fun updateSessionControls() {
        val ready = ecgSessionViewModel.isReady.value == true && !sessionActionInProgress
        btnPauseSession.isEnabled = ready
        btnEndSession.isEnabled = ready
        btnPauseSession.setText(if (isSessionPaused) R.string.l10n_resume_session else R.string.patient_ecg_live_monitoring_text_pause_session)
        (btnPauseSession as? MaterialButton)?.setIconResource(if (isSessionPaused) R.drawable.ic_play else R.drawable.ic_pause)
        updateLeadDialog()
    }

    // Concludes the ECG recording session, halts rendering, flushes all sample points to SQLite,
    // and requests the backend-computed or locally reconciled summary.
    private fun finishSession() {
        if (sessionActionInProgress || ecgSessionViewModel.isReady.value != true) return
        if (!isLeadConnected) {
            showLeadDisconnectedDialog()
            return
        }
        sessionActionInProgress = true
        flushSampleBufferToLocalDb()
        ecgSessionViewModel.pauseRecording()
        ecgWaveformView.stopRendering()
        updateSessionControls()
        lifecycleScope.launch {
            pendingCacheWrites.toList().forEach { it.join() }
            ecgSessionViewModel.stopRecording(this@LiveEcgMonitoringActivity)
        }
    }

    // Flushes buffered 250 Hz Lead I samples directly to local SQLite cache for offline persistence.
    private fun flushSampleBufferToLocalDb() {
        if (sampleBuffer.isEmpty()) return
        val currentSessionId = ecgSessionViewModel.currentSessionId ?: return
        val pId = patientId ?: return
        val dId = deviceId ?: return
        val leadsOff = !isLeadConnected
        val batchBpm = latestSmoothedBpm
        val pointsToSave = ArrayList(sampleBuffer)
        sampleBuffer.clear()

        pendingCacheWrites.removeAll { it.isCompleted }
        pendingCacheWrites += lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val samplesJson = com.google.gson.Gson().toJson(pointsToSave)
            val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.UK).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
            val cachedBatch = com.example.prog7314_poepart23.data.local.models.CachedSampleBatch(
                remoteSessionId = currentSessionId,
                deviceId = dId,
                patientId = pId,
                samplesJson = samplesJson,
                sampleRateHz = 250,
                leadsOff = leadsOff,
                estimatedBpm = batchBpm, // 0 when no BPM was detected from the real signal
                timestampIso = nowIso,
                isSynced = false
            )
            localDb.insertSampleBatch(cachedBatch)
        }
    }

    // Draws the samples the device has sent that are not on screen yet, and buffers them for the offline cache.
    private fun observeDeviceSamples() {
        ecgSessionViewModel.incomingSamples.observe(this) { samples -> consumeDeviceSamples(samples) }
    }

    private fun consumeDeviceSamples(samples: List<Int>?) {
        if (samples.isNullOrEmpty()) return
        if (isSessionPaused || !isSessionActive || !isLeadConnected) {
            renderedSampleCount = samples.size
            return
        }
        if (samples.size <= renderedSampleCount) return
        val fresh = ArrayList(samples.subList(renderedSampleCount, samples.size))
        renderedSampleCount = samples.size

        if (!receivedDeviceData) {
            receivedDeviceData = true
            tvSensorStatus.text = getString(R.string.patient_ecg_live_monitoring_text_ad8232_sensor_connected)
            tvLeadStatus.text = getString(R.string.l10n_lead_active)
        }

        sampleBuffer.addAll(fresh)
        ecgWaveformView.addSamples(fresh)

        // Flush to SQLite every 250 samples (1 second of recording)
        if (sampleBuffer.size >= 250) flushSampleBufferToLocalDb()
    }

    // Resuming (or returning to the screen) catches up on anything received meanwhile.
    private fun startTelemetryIngestion() {
        consumeDeviceSamples(ecgSessionViewModel.incomingSamples.value)
    }

    // Nothing to stop: samples are pushed by the view model and ignored while paused.
    private fun stopTelemetryIngestion() = Unit

    // Executes a cardiac heartbeat scale animation on the heart icon when an R-peak occurs.
    private fun animateHeartPulse() {
        val scaleX = ObjectAnimator.ofFloat(ivHeartPulse, "scaleX", 1f, 1.4f, 1f)
        val scaleY = ObjectAnimator.ofFloat(ivHeartPulse, "scaleY", 1f, 1.4f, 1f)
        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 240
            interpolator = OvershootInterpolator()
            start()
        }
    }

    override fun onResume() {
        super.onResume()
        BottomNavHelper.setUpNurseNav(this, BottomNavHelper.NurseTab.MONITOR)
        val avatarUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: patientId
        applyStoredAvatar(R.id.btnProfileAvatar, avatarUid)
        ecgWaveformView.startRendering()
        if (isSessionActive && !isSessionPaused) {
            startTelemetryIngestion()
        }
    }

    override fun onPause() {
        super.onPause()
        stopTelemetryIngestion()
        ecgWaveformView.stopRendering()
    }

    override fun onDestroy() {
        super.onDestroy()
        arrhythmiaAlerts.dismiss()
        leadDisconnectDialog?.dismiss()
        leadDisconnectDialog = null
        stopTelemetryIngestion()
    }

    private var controlsWired = false

    // Header icons, filter chips and inline text actions exposed by this screen's layout.
    override fun onStart() {
        super.onStart()
        if (controlsWired) return
        controlsWired = true
        HapticFeedbackManager.initialise(this)
        val settingsTarget = if (isNurseMode) NurseSettingsActivity::class.java else Patient_Settings::class.java
        openOnClick(R.id.btnSettings, settingsTarget)
        openOnClick(R.id.btnProfileAvatar, if (isNurseMode) NurseSettingsActivity::class.java else PatientProfileActivity::class.java)
    }

}
