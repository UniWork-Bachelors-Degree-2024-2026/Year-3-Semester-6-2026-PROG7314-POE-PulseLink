/*
 * Doctor dashboard activity providing clinician triage overview,
 * critical alert statistics, and quick navigation to patient management workflows.
 *
 * Reference List:
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 * - Google Developers, 2024. ViewModel Overview - Android Developers. [online] Available at: <https://developer.android.com/topic/libraries/architecture/viewmodel> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.activity.viewModels                   // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.prog7314_poepart23.adapters.NotificationListAdapter
import com.example.prog7314_poepart23.data.models.NotificationItem
import com.example.prog7314_poepart23.data.remote.models.DoctorQueueItemDto
import com.example.prog7314_poepart23.data.repository.NotificationRepository
import com.example.prog7314_poepart23.data.repository.PatientPortalResult
import com.example.prog7314_poepart23.messaging.ClinicalNotificationSync
import com.example.prog7314_poepart23.messaging.NotificationHelper
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.DateTimeFormatter
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.applyStoredAvatar
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.openOnClick
import com.example.prog7314_poepart23.viewmodels.DoctorReviewViewModel
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

class DoctorDashboardActivity : AppCompatActivity() { // (Google Developers, 2024)

    private val review: DoctorReviewViewModel by viewModels()
    private lateinit var rvDoctorRecentActivity: RecyclerView
    private lateinit var layoutNoDoctorActivity: LinearLayout
    private lateinit var btnMarkAllDoctorActivityRead: TextView
    private lateinit var activityAdapter: NotificationListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.doctor_dashboard)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)
        NotificationRepository.initialise(this, FirebaseAuth.getInstance().currentUser?.uid)
        BottomNavHelper.setUpDoctorNav(this, BottomNavHelper.DoctorTab.DASHBOARD)
        bindRecentActivity()
        observeMetrics()
        NotificationHelper.requestNotificationPermissionIfNeeded(this)
    }

    private fun bindRecentActivity() {
        rvDoctorRecentActivity = findViewById(R.id.rvDoctorRecentActivity)
        layoutNoDoctorActivity = findViewById(R.id.layoutNoDoctorActivity)
        btnMarkAllDoctorActivityRead = findViewById(R.id.btnMarkAllDoctorActivityRead)

        activityAdapter = NotificationListAdapter { item ->
            HapticFeedbackManager.lightTick()
            NotificationRepository.markAsRead(item.notificationId)
            startActivity(
                Intent(this, NotificationDetailsActivity::class.java)
                    .putExtra(NotificationsActivity.EXTRA_NOTIFICATION_ID, item.notificationId)
            )
        }
        rvDoctorRecentActivity.layoutManager = LinearLayoutManager(this)
        rvDoctorRecentActivity.adapter = activityAdapter

        btnMarkAllDoctorActivityRead.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            NotificationRepository.markAllAsRead()
            Toast.makeText(this, getString(R.string.action_marked_all_read), Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NotificationRepository.notifications.collectLatest { items ->
                    rvDoctorRecentActivity.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                    layoutNoDoctorActivity.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    activityAdapter.submitList(items)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        NotificationRepository.initialise(this, uid)
        BottomNavHelper.setUpDoctorNav(this, BottomNavHelper.DoctorTab.DASHBOARD)
        applyStoredAvatar(R.id.btnProfileAvatar, uid)
        ClinicalNotificationSync.enqueue(this, replace = true)
        lifecycleScope.launch {
            ClinicalNotificationSync.syncInboxNow(this@DoctorDashboardActivity)
        }
        review.loadDashboard()
    }

    private fun observeMetrics() {
        val rows = listOf(R.id.layoutPriorityPatient1, R.id.layoutPriorityPatient2, R.id.layoutPriorityPatient3)
        val names = listOf(R.id.tvPriorityPatient1Name, R.id.tvPriorityPatient2Name, R.id.tvPriorityPatient3Name)
        val info = listOf(R.id.tvPriorityPatient1Info, R.id.tvPriorityPatient2Info, R.id.tvPriorityPatient3Info)
        val states = listOf(R.id.tvPriorityPatient1Alert, R.id.tvPriorityPatient2Alert, R.id.tvPriorityPatient3Alert)
        val dates = listOf(R.id.tvPriorityPatient1Time, R.id.tvPriorityPatient2Time, R.id.tvPriorityPatient3Time)
        rows.forEach { findViewById<View>(it).visibility = View.GONE }
        review.dashboard.observe(this) { result ->
            val data = (result as? PatientPortalResult.Ready)?.value
            findViewById<TextView>(R.id.tvActivePatientsCount).text = data?.assignedPatientsCount?.toString() ?: getString(R.string.value_placeholder)
            findViewById<TextView>(R.id.tvPendingReportsCount).text = data?.awaitingReviewCount?.toString() ?: getString(R.string.value_placeholder)
            findViewById<TextView>(R.id.tvCriticalAlertsCount).text = data?.criticalAlertsCount?.toString() ?: getString(R.string.value_placeholder)
            val queue = data?.completedSessionQueue.orEmpty()
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null && queue.isNotEmpty()) {
                reconcileDoctorQueueActivity(uid, queue)
            }
            rows.forEachIndexed { index, id ->
                val row = findViewById<View>(id)
                val session = queue.getOrNull(index)
                row.visibility = if (session != null || index == 0) View.VISIBLE else View.GONE
                findViewById<TextView>(names[index]).text = session?.patientName ?: getString(if (data == null) R.string.doctor_load_failed else R.string.session_status_none_title)
                findViewById<TextView>(info[index]).text = session?.siteName.orEmpty()
                findViewById<TextView>(states[index]).apply {
                    text = if (session == null) "" else getString(if (session.resultsReleased) R.string.doctor_released else R.string.ui_review_required)
                    setTextColor(getColor(R.color.pulse_text_secondary))
                    setBackgroundResource(R.drawable.bg_pill_neutral)
                }
                findViewById<TextView>(dates[index]).text = session?.let { DateTimeFormatter.formatIsoTimestamp(it.startTimeUtc) }.orEmpty()
                row.isFocusable = true
                row.setOnClickListener {
                    if (session == null) review.loadDashboard()
                    else startActivity(Intent(this, DoctorEcgSessionDetailsActivity::class.java)
                        .putExtra(PatientEcgHistoryActivity.EXTRA_SESSION_ID, session.sessionId)
                        .putExtra(PatientListActivity.EXTRA_PATIENT_ID, session.patientId))
                }
            }
        }
    }

    private fun reconcileDoctorQueueActivity(uid: String, queue: List<DoctorQueueItemDto>) {
        for (session in queue) {
            if (session.sessionId.isBlank()) continue
            val patientLabel = session.patientName.ifBlank { session.patientId }
            val siteLabel = session.siteName.ifBlank { session.siteId }
            val timestampSource = session.endTimeUtc ?: session.startTimeUtc
            val timestampLabel = DateTimeFormatter.formatIsoTimestamp(timestampSource)
            val createdMillis = runCatching {
                OffsetDateTime.parse(timestampSource).toInstant().toEpochMilli()
            }.getOrDefault(System.currentTimeMillis())

            when {
                session.resultsReleased -> {
                    val id = "doc-released-${session.sessionId}"
                    NotificationRepository.addNotification(
                        NotificationItem(
                            notificationId = id,
                            title = getString(R.string.clinical_doctor_activity_released_title, patientLabel),
                            messagePreview = getString(R.string.clinical_doctor_activity_released_body, patientLabel),
                            timestampLabel = timestampLabel,
                            isUnread = false,
                            iconRes = R.drawable.ic_check_circle,
                            sessionId = session.sessionId,
                            avgBpm = session.averageBpm.takeIf { it > 0 },
                            durationSeconds = session.durationSeconds.takeIf { it > 0 },
                            startTimeUtc = session.startTimeUtc,
                            signalQuality = session.signalQuality,
                            sessionStatus = "Completed"
                        ),
                        ownerUid = uid,
                        createdAtMillis = createdMillis
                    )
                }
                session.hasDoctorNotes || session.hasUnreleasedDrafts -> {
                    val id = "doc-draft-${session.sessionId}"
                    NotificationRepository.addNotification(
                        NotificationItem(
                            notificationId = id,
                            title = getString(R.string.clinical_doctor_activity_draft_title, patientLabel),
                            messagePreview = getString(R.string.clinical_doctor_activity_draft_body, patientLabel),
                            timestampLabel = timestampLabel,
                            isUnread = true,
                            iconRes = R.drawable.ic_stethoscope,
                            sessionId = session.sessionId,
                            avgBpm = session.averageBpm.takeIf { it > 0 },
                            durationSeconds = session.durationSeconds.takeIf { it > 0 },
                            startTimeUtc = session.startTimeUtc,
                            signalQuality = session.signalQuality,
                            sessionStatus = "Completed"
                        ),
                        ownerUid = uid,
                        createdAtMillis = createdMillis
                    )
                }
                else -> {
                    val alreadyTracked = NotificationRepository.notifications.value.any { it.sessionId == session.sessionId }
                    if (!alreadyTracked) {
                        val id = "doc-awaiting-${session.sessionId}"
                        NotificationRepository.addNotification(
                            NotificationItem(
                                notificationId = id,
                                title = getString(R.string.clinical_doctor_activity_awaiting_title, patientLabel),
                                messagePreview = getString(
                                    R.string.clinical_doctor_activity_awaiting_body,
                                    siteLabel,
                                    session.averageBpm
                                ),
                                timestampLabel = timestampLabel,
                                isUnread = true,
                                iconRes = R.drawable.ic_document,
                                sessionId = session.sessionId,
                                avgBpm = session.averageBpm.takeIf { it > 0 },
                                durationSeconds = session.durationSeconds.takeIf { it > 0 },
                                startTimeUtc = session.startTimeUtc,
                                signalQuality = session.signalQuality,
                                sessionStatus = "Completed"
                            ),
                            ownerUid = uid,
                            createdAtMillis = createdMillis
                        )
                    }
                }
            }
        }
    }

    private var controlsWired = false

    // Header icons, stat cards and inline text actions exposed by this screen's layout.
    override fun onStart() {
        super.onStart()
        if (controlsWired) return
        controlsWired = true
        HapticFeedbackManager.initialise(this)

        openOnClick(R.id.btnNotifications, DoctorAlertsActivity::class.java)
        openOnClick(R.id.btnProfileAvatar, DoctorProfileActivity::class.java)
        openOnClick(R.id.btnViewAllPatients, PatientListActivity::class.java)
        openOnClick(R.id.cardActivePatients, PatientListActivity::class.java)
        openOnClick(R.id.cardCriticalAlerts, DoctorAlertsActivity::class.java)
        openOnClick(R.id.cardPendingReports, DoctorReportsActivity::class.java)
    }
}
