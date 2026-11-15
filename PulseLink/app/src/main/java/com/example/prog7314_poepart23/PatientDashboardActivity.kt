/*
 * Patient dashboard activity showing the latest session status, completed report and released note
 * counts, a history link, the profile, settings and SOS shortcuts, recent updates, and bottom navigation tabs.
 * Recording is started on the PulseLink touchscreen, so this screen has no live ECG or device controls.
 *
 * Reference List:
 * - Google Cloud, 2024. Firebase Authentication Documentation. [online] Available at: <https://firebase.google.com/docs/auth> [Accessed 21 September 2026].
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 * - Google Developers, 2024. ViewModel Overview - Android Developers. [online] Available at: <https://developer.android.com/topic/libraries/architecture/viewmodel> [Accessed 21 September 2026].
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.activity.viewModels                   // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope              // (Google Developers, 2024)
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.prog7314_poepart23.adapters.NotificationListAdapter
import com.example.prog7314_poepart23.data.local.OfflineEcgDatabase
import com.example.prog7314_poepart23.data.models.NotificationItem
import com.example.prog7314_poepart23.data.remote.models.EcgSessionSummaryDto
import com.example.prog7314_poepart23.data.repository.NotificationRepository
import com.example.prog7314_poepart23.data.repository.UserRepository
import com.example.prog7314_poepart23.messaging.ClinicalNotificationSync
import com.example.prog7314_poepart23.messaging.NotificationHelper
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.DateTimeFormatter
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.LocaleHelper
import com.example.prog7314_poepart23.utils.NetworkMonitor
import com.example.prog7314_poepart23.utils.PulseLinkModal
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.utils.applyStoredAvatar
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.viewmodels.DashboardViewModel
import com.example.prog7314_poepart23.viewmodels.SessionOverview
import com.google.firebase.auth.FirebaseAuth         // (Google Cloud, 2024)
import kotlinx.coroutines.Dispatchers                // (JetBrains, 2024)
import kotlinx.coroutines.Job                        // (JetBrains, 2024)
import kotlinx.coroutines.flow.collectLatest          // (JetBrains, 2024)
import kotlinx.coroutines.launch                     // (JetBrains, 2024)
import kotlinx.coroutines.withContext                // (JetBrains, 2024)
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Locale
import java.util.TimeZone

class PatientDashboardActivity : AppCompatActivity() { // (Google Developers, 2024)

    private val dashboardViewModel: DashboardViewModel by viewModels()

    // Views
    private lateinit var tvGreetingName: TextView
    private lateinit var tvGreetingSubtext: TextView
    private lateinit var ivSettings: ImageView

    // Session status card
    private lateinit var cardSessionStatus: CardView
    private lateinit var tvSessionStatusPill: TextView
    private lateinit var tvSessionLastDate: TextView
    private lateinit var tvSessionQuality: TextView
    private lateinit var tvSessionSync: TextView

    // Completed reports, released notes and history
    private lateinit var cardCompletedReports: CardView
    private lateinit var tvCompletedReportsCount: TextView
    private lateinit var cardReleasedNotes: CardView
    private lateinit var tvReleasedNotesCount: TextView
    private lateinit var cardHistory: CardView

    // Emergency SOS
    private lateinit var cardEmergencySos: CardView

    // Offline Banner
    private lateinit var layoutOfflineBanner: LinearLayout
    private lateinit var tvOfflineBannerSubtitle: TextView

    // Recent Updates / Notifications
    private lateinit var rvRecentUpdates: RecyclerView
    private lateinit var layoutNoUpdates: LinearLayout
    private lateinit var btnMarkAllRead: TextView
    private lateinit var notificationAdapter: NotificationListAdapter

    private val auth = FirebaseAuth.getInstance()
    private val userRepository = UserRepository()
    private var currentPatientId: String? = null
    private var updatesReconcileJob: Job? = null

    // True while the last load failed, so tapping the status card retries instead of opening details.
    private var loadFailed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.patient_dashboard)

        // Initialise centralised haptic feedback
        HapticFeedbackManager.initialise(this)

        // Apply edge-to-edge window insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        currentPatientId = auth.currentUser?.uid
        NotificationRepository.initialise(this, currentPatientId)

        bindViews()
        loadGreetingProfile()
        setupClickListeners()
        setupBottomNav()
        setupObservers()
        // loadDashboard() runs from onResume(), which always follows onCreate().
        NotificationHelper.requestNotificationPermissionIfNeeded(this)
    }

    private fun bindViews() {
        tvGreetingName = findViewById(R.id.tvGreetingName)
        tvGreetingSubtext = findViewById(R.id.tvGreetingSubtext)
        ivSettings = findViewById(R.id.ivSettings)

        layoutOfflineBanner = findViewById(R.id.layoutOfflineBanner)
        tvOfflineBannerSubtitle = findViewById(R.id.tvOfflineBannerSubtitle)

        cardSessionStatus = findViewById(R.id.cardSessionStatus)
        tvSessionStatusPill = findViewById(R.id.tvSessionStatusPill)
        tvSessionLastDate = findViewById(R.id.tvSessionLastDate)
        tvSessionQuality = findViewById(R.id.tvSessionQuality)
        tvSessionSync = findViewById(R.id.tvSessionSync)

        cardCompletedReports = findViewById(R.id.cardCompletedReports)
        tvCompletedReportsCount = findViewById(R.id.tvCompletedReportsCount)
        cardReleasedNotes = findViewById(R.id.cardReleasedNotes)
        tvReleasedNotesCount = findViewById(R.id.tvReleasedNotesCount)
        cardHistory = findViewById(R.id.cardHistory)

        cardEmergencySos = findViewById(R.id.cardEmergencySos)

        rvRecentUpdates = findViewById(R.id.rvRecentUpdates)
        layoutNoUpdates = findViewById(R.id.layoutNoUpdates)
        btnMarkAllRead = findViewById(R.id.btnMarkAllRead)
    }

    // Loads the authenticated user's real name from Firebase, keyed by their UID.
    // No hard-coded name is ever substituted; Loading/Empty/Error states are shown instead,
    // and the greeting is tappable to retry after a failure.
    private fun loadGreetingProfile() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            tvGreetingName.text = getString(R.string.greeting_error)
            return
        }

        tvGreetingName.text = getString(R.string.greeting_loading)
        tvGreetingName.setOnClickListener(null)

        lifecycleScope.launch {
            when (val result = userRepository.getUserProfile(uid)) {
                is Resource.Success -> {
                    val name = result.data?.displayName?.ifBlank { null }
                    if (name != null) {
                        tvGreetingName.text = getString(R.string.patient_dashboard_greeting_format, name)
                    } else {
                        // Empty state: profile exists but has no name on record yet.
                        tvGreetingName.text = getString(R.string.greeting_empty)
                    }
                }
                is Resource.Error -> {
                    tvGreetingName.text = getString(R.string.greeting_error)
                    tvGreetingName.setOnClickListener {
                        HapticFeedbackManager.lightTick()
                        loadGreetingProfile()
                    }
                    Toast.makeText(this@PatientDashboardActivity, getString(R.string.greeting_error_toast), Toast.LENGTH_SHORT).show()
                }
                is Resource.Loading -> {
                    tvGreetingName.text = getString(R.string.greeting_loading)
                }
            }
        }
    }

    private fun setupClickListeners() {
        findViewById<View?>(R.id.btnPatientAvatar)?.setOnClickListener {
            HapticFeedbackManager.lightTick()
            startActivity(Intent(this, PatientProfileActivity::class.java))
        }

        ivSettings.setOnClickListener {
            HapticFeedbackManager.lightTick()
            startActivity(Intent(this, Patient_Settings::class.java))
        }

        layoutOfflineBanner.setOnClickListener {
            HapticFeedbackManager.lightTick()
            startActivity(Intent(this, OfflineSyncStatusActivity::class.java))
        }

        cardSessionStatus.setOnClickListener {
            HapticFeedbackManager.lightTick()
            if (loadFailed) {
                loadDashboard()
            } else {
                startActivity(Intent(this, PatientSessionStatusActivity::class.java))
            }
        }

        val openHistory = View.OnClickListener {
            HapticFeedbackManager.lightTick()
            startActivity(Intent(this, PatientEcgHistoryActivity::class.java))
        }
        cardCompletedReports.setOnClickListener(openHistory)
        cardReleasedNotes.setOnClickListener(openHistory)
        cardHistory.setOnClickListener(openHistory)

        cardEmergencySos.setOnClickListener {
            HapticFeedbackManager.warningBuzz()
            val description = TextView(this).apply {
                text = getString(R.string.patient_dashboard_text_emergency_dispatch_message)
                textSize = 16f
                setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.pulse_text_secondary))
            }
            PulseLinkModal.show(
                this,
                getString(R.string.patient_dashboard_text_emergency_dispatch_title),
                description,
                listOf(
                    getString(R.string.patient_dashboard_text_dial_112_national) to { sheet ->
                        startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + getString(R.string.emergency_number_general))))
                        sheet.dismiss()
                    },
                    getString(R.string.patient_dashboard_text_dial_10177_ambulance) to { sheet ->
                        startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + getString(R.string.emergency_number_ambulance))))
                        sheet.dismiss()
                    },
                    getString(R.string.facility_finder_title) to { sheet ->
                        startActivity(Intent(this, FacilityFinderActivity::class.java))
                        sheet.dismiss()
                    },
                    getString(R.string.cancel) to { it.dismiss() }
                )
            )
        }

        btnMarkAllRead.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            NotificationRepository.markAllAsRead()
            Toast.makeText(this, getString(R.string.action_marked_all_read), Toast.LENGTH_SHORT).show()
        }

        notificationAdapter = NotificationListAdapter { notification ->
            HapticFeedbackManager.lightTick()
            NotificationRepository.markAsRead(notification.notificationId)
            startActivity(
                Intent(this, NotificationDetailsActivity::class.java)
                    .putExtra(NotificationsActivity.EXTRA_NOTIFICATION_ID, notification.notificationId)
            )
        }
        rvRecentUpdates.layoutManager = LinearLayoutManager(this)
        rvRecentUpdates.adapter = notificationAdapter
    }

    private fun setupBottomNav() {
        BottomNavHelper.setUpPatientNav(this, BottomNavHelper.PatientTab.DASHBOARD)
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NotificationRepository.notifications.collectLatest { items ->
                    rvRecentUpdates.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                    layoutNoUpdates.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    notificationAdapter.submitList(items)
                }
            }
        }

        // Observe network connectivity for offline banner
        lifecycleScope.launch {
            NetworkMonitor.isOnline.collect { isOnline ->
                if (isOnline) {
                    layoutOfflineBanner.visibility = View.GONE
                } else {
                    val pendingCount = currentPatientId?.let { pid ->
                        OfflineEcgDatabase.getInstance(this@PatientDashboardActivity).getPendingSyncCount(pid)
                    } ?: 0
                    tvOfflineBannerSubtitle.text = if (pendingCount > 0) {
                        getString(R.string.l10n_items_pending_format, pendingCount)
                    } else {
                        getString(R.string.patient_dashboard_offline_mode_subtitle)
                    }
                    layoutOfflineBanner.visibility = View.VISIBLE
                }
            }
        }

        // Observe the session overview. Every branch rewrites every field, so nothing from an
        // earlier load can remain visible after an empty or failed response.
        dashboardViewModel.sessionOverview.observe(this) { resource ->
            when (resource) {
                is Resource.Success -> {
                    loadFailed = false
                    val overview = resource.data
                    if (overview == null) {
                        showError()
                    } else {
                        showOverview(overview)
                        reconcileReleasedUpdates(overview.sessions)
                    }
                }
                is Resource.Error -> showError()
                is Resource.Loading -> showLoading()
            }
        }
    }

    private fun reconcileReleasedUpdates(sessions: List<EcgSessionSummaryDto>) {
        val uid = currentPatientId ?: return
        updatesReconcileJob?.cancel()
        updatesReconcileJob = lifecycleScope.launch {
            ClinicalNotificationSync.syncInboxNow(this@PatientDashboardActivity)
            val untrackedIds = sessions
                .filter { it.resultsReleased && it.sessionId.isNotBlank() }
                .sortedByDescending { it.startTimeUtc }
                .map { it.sessionId }
                .filter { sid -> NotificationRepository.notifications.value.none { it.sessionId == sid } }
            if (untrackedIds.isEmpty()) return@launch

            val sessionLookup = sessions.associateBy { it.sessionId }
            val releases = dashboardViewModel.loadReleasedSessionDetails(untrackedIds)
            for (release in releases) {
                if (auth.currentUser?.uid != uid) return@launch
                if (NotificationRepository.notifications.value.any { it.sessionId == release.sessionId }) continue
                val summary = sessionLookup[release.sessionId]
                val latestNote = release.notes.maxByOrNull { it.createdAtUtc }
                val doctorName = latestNote?.doctorName?.takeIf { it.isNotBlank() }
                val timestampLabel = DateTimeFormatter.formatIsoTimestamp(release.releasedAtUtc)
                val createdMillis = runCatching {
                    OffsetDateTime.parse(release.releasedAtUtc).toInstant().toEpochMilli()
                }.getOrDefault(System.currentTimeMillis())

                val assessmentBody = if (doctorName != null) {
                    getString(R.string.clinical_results_released_doctor_body, doctorName)
                } else {
                    getString(R.string.clinical_results_released_body)
                }
                NotificationRepository.addNotification(
                    NotificationItem(
                        notificationId = "rel-summary-${release.sessionId}",
                        title = getString(R.string.clinical_results_released_title),
                        messagePreview = assessmentBody,
                        timestampLabel = timestampLabel,
                        isUnread = true,
                        iconRes = R.drawable.ic_check_circle,
                        sessionId = release.sessionId,
                        avgBpm = summary?.averageBpm?.takeIf { it > 0 },
                        durationSeconds = summary?.durationSeconds?.takeIf { it > 0 },
                        startTimeUtc = summary?.startTimeUtc,
                        signalQuality = summary?.signalQuality,
                        sessionStatus = "Completed"
                    ),
                    ownerUid = uid,
                    createdAtMillis = createdMillis
                )

                val rxSummary = release.carePlan.medications
                    .mapNotNull { med ->
                        listOf(med.name, med.dosage, med.frequency)
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .joinToString(" ")
                            .takeIf { it.isNotEmpty() }
                    }
                    .joinToString(", ")
                if (rxSummary.isNotEmpty()) {
                    val rxBody = if (doctorName != null) {
                        getString(R.string.clinical_prescription_issued_body, doctorName, rxSummary)
                    } else {
                        getString(R.string.clinical_prescription_issued_generic_body, rxSummary)
                    }
                    NotificationRepository.addNotification(
                        NotificationItem(
                            notificationId = "rel-rx-${release.sessionId}",
                            title = getString(R.string.clinical_prescription_issued_title),
                            messagePreview = rxBody,
                            timestampLabel = timestampLabel,
                            isUnread = true,
                            iconRes = R.drawable.ic_medical_badge,
                            sessionId = release.sessionId,
                            avgBpm = summary?.averageBpm?.takeIf { it > 0 },
                            durationSeconds = summary?.durationSeconds?.takeIf { it > 0 },
                            startTimeUtc = summary?.startTimeUtc,
                            signalQuality = summary?.signalQuality,
                            sessionStatus = "Completed"
                        ),
                        ownerUid = uid,
                        createdAtMillis = createdMillis + 1L
                    )
                }

                val adviceText = release.notes
                    .flatMap { listOf(it.recommendations, it.followUpInstructions) }
                    .plus(release.carePlan.clinicalInstructions)
                    .mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
                    .distinct()
                    .joinToString(" ")
                if (adviceText.isNotEmpty()) {
                    val adviceBody = if (doctorName != null) {
                        getString(R.string.clinical_advice_issued_body, doctorName, adviceText)
                    } else {
                        getString(R.string.clinical_advice_issued_generic_body, adviceText)
                    }
                    NotificationRepository.addNotification(
                        NotificationItem(
                            notificationId = "rel-advice-${release.sessionId}",
                            title = getString(R.string.clinical_advice_issued_title),
                            messagePreview = adviceBody,
                            timestampLabel = timestampLabel,
                            isUnread = true,
                            iconRes = R.drawable.ic_stethoscope,
                            sessionId = release.sessionId,
                            avgBpm = summary?.averageBpm?.takeIf { it > 0 },
                            durationSeconds = summary?.durationSeconds?.takeIf { it > 0 },
                            startTimeUtc = summary?.startTimeUtc,
                            signalQuality = summary?.signalQuality,
                            sessionStatus = "Completed"
                        ),
                        ownerUid = uid,
                        createdAtMillis = createdMillis + 2L
                    )
                }
            }
        }
    }

    private fun showLoading() {
        loadFailed = false
        tvSessionStatusPill.text = getString(R.string.session_status_loading)
        tvSessionLastDate.text = getString(R.string.value_placeholder)
        tvSessionQuality.text = getString(R.string.value_placeholder)
        setCounts(null, null)
    }

    private fun showError() {
        loadFailed = true
        tvSessionStatusPill.text = getString(R.string.l10n_unable_to_load)
        tvSessionLastDate.text = getString(R.string.l10n_tap_to_retry)
        tvSessionQuality.text = getString(R.string.value_placeholder)
        setCounts(null, null)
    }

    private fun showOverview(overview: SessionOverview) {
        val session = overview.latest
        if (session == null) {
            tvSessionStatusPill.text = getString(R.string.session_status_pill_none)
            tvSessionLastDate.text = getString(R.string.patient_dashboard_text_no_sessions_yet)
            tvSessionQuality.text = getString(R.string.value_placeholder)
        } else {
            // Raw recording status and signal quality only. No rhythm or clinical label is derived here.
            tvSessionStatusPill.text = LocaleHelper.statusLabel(this, session.status)
            tvSessionLastDate.text = formatSessionStart(session.startTimeUtc)
            tvSessionQuality.text = LocaleHelper.qualityLabel(this, session.signalQuality)
        }
        setCounts(overview.completedReports, overview.releasedNotes)
    }

    // Null means "not available" (loading or failed) and shows the placeholder, never a zero.
    private fun setCounts(completedReports: Int?, releasedNotes: Int?) {
        val reportsText = completedReports?.toString() ?: getString(R.string.value_placeholder)
        val notesText = releasedNotes?.toString() ?: getString(R.string.value_placeholder)
        tvCompletedReportsCount.text = reportsText
        tvReleasedNotesCount.text = notesText
        cardCompletedReports.contentDescription = getString(R.string.dashboard_stat_cd_format, getString(R.string.dashboard_completed_reports), reportsText)
        cardReleasedNotes.contentDescription = getString(R.string.dashboard_stat_cd_format, getString(R.string.dashboard_released_notes), notesText)
    }

    // Shows how many saved sessions are still waiting to upload. Saved locally is never shown as uploaded.
    private fun refreshUploadStatus() {
        val patientId = currentPatientId
        if (patientId == null) {
            tvSessionSync.text = getString(R.string.value_placeholder)
            return
        }
        lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) {
                OfflineEcgDatabase.getInstance(this@PatientDashboardActivity).getPendingSyncCount(patientId)
            }
            tvSessionSync.text = if (pending > 0) {
                getString(R.string.session_status_upload_pending_format, pending)
            } else {
                getString(R.string.session_status_upload_none_pending)
            }
        }
    }

    private fun loadDashboard() {
        val patientId = currentPatientId
        if (patientId == null) {
            showError()
            tvSessionSync.text = getString(R.string.value_placeholder)
            return
        }
        dashboardViewModel.fetchSessionOverview(patientId)
        refreshUploadStatus()
    }

    // Formats an API start time in the device time zone. An unreadable value shows the placeholder
    // rather than a made-up "Today".
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

    override fun onResume() {
        super.onResume()
        currentPatientId = auth.currentUser?.uid
        NotificationRepository.initialise(this, currentPatientId)
        ClinicalNotificationSync.enqueue(this, replace = true)
        applyStoredAvatar(R.id.btnPatientAvatar, currentPatientId)
        loadDashboard()
    }

    private var controlsWired = false

    // Header icons, filter chips and inline text actions exposed by this screen's layout.
    override fun onStart() {
        super.onStart()
        if (controlsWired) return
        controlsWired = true
        HapticFeedbackManager.initialise(this)
    }
}
