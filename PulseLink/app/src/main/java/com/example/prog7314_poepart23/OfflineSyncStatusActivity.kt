/*
 * Activity presenting local offline persistence status, unsynced AD8232 ECG sample counts,
 * cloud connectivity diagnostics, and on-demand background sync execution.
 *
 * Reference List:
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End. [online] Available at: <https://www.analog.com/media/en/technical-documentation/data-sheets/AD8232.pdf> [Accessed 21 September 2026].
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 * - Google Developers, 2024. Material Components for Android. [online] Available at: <https://github.com/material-components/material-components-android> [Accessed 21 September 2026].
 * - Google Developers, 2024. Schedule tasks with WorkManager. [online] Available at: <https://developer.android.com/topic/libraries/architecture/workmanager> [Accessed 21 September 2026].
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import androidx.lifecycle.lifecycleScope              // (Google Developers, 2024)
import androidx.work.WorkInfo                         // (Google Developers, 2024)
import com.example.prog7314_poepart23.data.local.OfflineEcgDatabase
import com.example.prog7314_poepart23.sync.SyncScheduler
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.google.android.material.button.MaterialButton // (Google Developers, 2024)
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers              // (JetBrains, 2024)
import kotlinx.coroutines.launch                     // (JetBrains, 2024)
import kotlinx.coroutines.withContext              // (JetBrains, 2024)
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OfflineSyncStatusActivity : AppCompatActivity() { // (Google Developers, 2024)

    private lateinit var ivBack: ImageView
    private lateinit var layoutStatusBanner: LinearLayout
    private lateinit var ivStatusIcon: ImageView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusSubtitle: TextView
    private lateinit var tvLastSyncedTime: TextView
    private lateinit var tvPendingCount: TextView
    private lateinit var containerPendingItems: LinearLayout
    private lateinit var tvEmptyPendingState: TextView
    private lateinit var btnRetrySync: MaterialButton
    private lateinit var tvBleSyncStage: TextView
    private lateinit var btnBleSync: MaterialButton

    private val bleManager by lazy { com.example.prog7314_poepart23.ble.PulseLinkBleManager(this) }
    private lateinit var localDb: OfflineEcgDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.patient_offline_sync_status)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        localDb = OfflineEcgDatabase.getInstance(this)
        initViews()
        setupListeners()
        observeWorkManagerSync()
        refreshSyncStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshSyncStatus()
    }

    private fun initViews() {
        ivBack = findViewById(R.id.ivBack)
        layoutStatusBanner = findViewById(R.id.layoutStatusBanner)
        ivStatusIcon = findViewById(R.id.ivStatusIcon)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusSubtitle = findViewById(R.id.tvStatusSubtitle)
        tvLastSyncedTime = findViewById(R.id.tvLastSyncedTime)
        tvPendingCount = findViewById(R.id.tvPendingCount)
        containerPendingItems = findViewById(R.id.containerPendingItems)
        tvEmptyPendingState = findViewById(R.id.tvEmptyPendingState)
        btnRetrySync = findViewById(R.id.btnRetrySync)
        tvBleSyncStage = findViewById(R.id.tvBleSyncStage)
        btnBleSync = findViewById(R.id.btnBleSync)
    }

    private fun setupListeners() {
        ivBack.setOnClickListener {
            HapticFeedbackManager.lightTick()
            finish()
        }

        btnRetrySync.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            if (!isNetworkConnected()) {
                HapticFeedbackManager.warningBuzz()
                Toast.makeText(this, getString(R.string.l10n_no_internet_saved_locally), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnRetrySync.isEnabled = false
            btnRetrySync.text = getString(R.string.l10n_syncing_cloud)
            SyncScheduler.triggerImmediateSync(this)
            Toast.makeText(this, getString(R.string.l10n_sync_starting), Toast.LENGTH_SHORT).show()
        }

        btnBleSync.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            performBleTouchscreenSync()
        }
    }

    private fun performBleTouchscreenSync() {
        val target = bleManager.findBondedTouchscreen()
        if (target == null) {
            HapticFeedbackManager.warningBuzz()
            tvBleSyncStage.text = getString(R.string.sync_ble_not_found)
            Toast.makeText(this, getString(R.string.sync_ble_not_found), Toast.LENGTH_LONG).show()
            return
        }

        btnBleSync.isEnabled = false
        tvBleSyncStage.text = getString(R.string.sync_ble_stage_connecting)

        val activeUid = FirebaseAuth.getInstance().currentUser?.uid ?: "nurse-shift"
        val activeGrant = "grant-active"

        lifecycleScope.launch {
            when (val result = bleManager.connectAndSyncAuthorisedRecordings(target, activeGrant, activeUid)) {
                is com.example.prog7314_poepart23.ble.BleTransferResult.Success -> {
                    HapticFeedbackManager.confirmClick()
                    tvBleSyncStage.text = getString(R.string.sync_ble_success_format, result.transferredSessions, result.transferredBatches)
                    btnBleSync.isEnabled = true
                    refreshSyncStatus()
                }
                is com.example.prog7314_poepart23.ble.BleTransferResult.Failure -> {
                    HapticFeedbackManager.warningBuzz()
                    tvBleSyncStage.text = result.reason
                    btnBleSync.isEnabled = true
                    refreshSyncStatus()
                }
            }
        }
    }

    private fun observeWorkManagerSync() {
        SyncScheduler.getImmediateSyncWorkInfo(this).observe(this) { workInfoList ->
            val activeWork = workInfoList?.firstOrNull()
            if (activeWork != null) {
                when (activeWork.state) {
                    WorkInfo.State.RUNNING -> {
                        btnRetrySync.isEnabled = false
                        btnRetrySync.text = getString(R.string.l10n_synchronising)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        btnRetrySync.isEnabled = true
                        btnRetrySync.text = getString(R.string.patient_offline_sync_status_text_retry_sync_now)
                        refreshSyncStatus()
                    }
                    WorkInfo.State.FAILED -> {
                        btnRetrySync.isEnabled = true
                        btnRetrySync.text = getString(R.string.patient_offline_sync_status_text_retry_sync_now)
                        Toast.makeText(this, getString(R.string.l10n_sync_failed_retry), Toast.LENGTH_SHORT).show()
                        refreshSyncStatus()
                    }
                    else -> {
                        btnRetrySync.isEnabled = true
                        btnRetrySync.text = getString(R.string.patient_offline_sync_status_text_retry_sync_now)
                    }
                }
            }
        }
    }

    private fun refreshSyncStatus() {
        val patientId = FirebaseAuth.getInstance().currentUser?.uid
        if (patientId == null) {
            lifecycleScope.launch(Dispatchers.Main) {
                tvPendingCount.text = getString(R.string.l10n_pending_upload_format, 0)
                tvEmptyPendingState.visibility = View.VISIBLE
                containerPendingItems.visibility = View.GONE
                containerPendingItems.removeAllViews()
            }
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val isOnline = isNetworkConnected()
            val breakdown = localDb.getQueueStatusBreakdown(patientId)
            val pendingCount = localDb.getPendingSyncCount(patientId) + breakdown.processingCount + breakdown.conflictCount
            val lastSyncMillis = localDb.getLastSyncTimestamp()
            val userSessions = localDb.getAllUserSessions(patientId).filter {
                it.stage != com.example.prog7314_poepart23.data.local.models.RecordingSyncStage.REPORT_READY
            }
            val unsyncedBatches = localDb.getUnsyncedSampleBatches(patientId)

            withContext(Dispatchers.Main) {
                // Update connectivity banner
                if (isOnline) {
                    layoutStatusBanner.setBackgroundResource(R.drawable.bg_pill_success)
                    ivStatusIcon.setImageResource(R.drawable.ic_check_circle)
                    tvStatusTitle.text = getString(R.string.l10n_cloud_connected)
                    tvStatusTitle.setTextColor(getColor(R.color.pulse_success))
                    tvStatusSubtitle.text = getString(R.string.l10n_cloud_connected_sub)
                } else {
                    layoutStatusBanner.setBackgroundResource(R.drawable.bg_card_danger_light)
                    ivStatusIcon.setImageResource(R.drawable.ic_cloud_off)
                    tvStatusTitle.text = getString(R.string.patient_offline_sync_status_text_re_offline)
                    tvStatusTitle.setTextColor(getColor(R.color.pulse_text_primary))
                    tvStatusSubtitle.text = getString(R.string.patient_offline_sync_status_text_data_being_saved_locally)
                }

                // Format last sync time
                if (lastSyncMillis > 0L) {
                    val sdf = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
                    tvLastSyncedTime.text = sdf.format(Date(lastSyncMillis))
                } else {
                    tvLastSyncedTime.text = getString(R.string.l10n_never_initial)
                }

                // Update pending count label
                tvPendingCount.text = getString(R.string.l10n_pending_upload_format, pendingCount)

                // Update pending items list
                containerPendingItems.removeAllViews()
                if (userSessions.isEmpty() && unsyncedBatches.isEmpty()) {
                    tvEmptyPendingState.visibility = View.VISIBLE
                    containerPendingItems.visibility = View.GONE
                } else {
                    tvEmptyPendingState.visibility = View.GONE
                    containerPendingItems.visibility = View.VISIBLE

                    val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())

                    // Render user sessions with explicit lifecycle stage labels
                    for (session in userSessions) {
                        val stageLabel = when (session.stage) {
                            com.example.prog7314_poepart23.data.local.models.RecordingSyncStage.SAVED_LOCALLY -> getString(R.string.rt_stage_saved_locally)
                            com.example.prog7314_poepart23.data.local.models.RecordingSyncStage.PENDING ->
                                if (session.expectedBatchCount > 0) getString(R.string.rt_stage_pending_progress, session.uploadedBatchCount, session.expectedBatchCount) else getString(R.string.l10n_waiting)
                            com.example.prog7314_poepart23.data.local.models.RecordingSyncStage.UPLOADED -> getString(R.string.rt_stage_uploaded)
                            com.example.prog7314_poepart23.data.local.models.RecordingSyncStage.PROCESSING -> getString(R.string.rt_stage_processing)
                            com.example.prog7314_poepart23.data.local.models.RecordingSyncStage.REPORT_READY -> getString(R.string.rt_stage_report_ready)
                            com.example.prog7314_poepart23.data.local.models.RecordingSyncStage.CONFLICT -> getString(R.string.rt_stage_conflict)
                            com.example.prog7314_poepart23.data.local.models.RecordingSyncStage.FAILED -> getString(R.string.rt_stage_failed)
                        }
                        val baseSubtitle = getString(
                            R.string.l10n_recorded_at_format,
                            sdfTime.format(Date(session.createdAtMillis)),
                            com.example.prog7314_poepart23.utils.BpmText.orDash(session.avgBpm)
                        )
                        val detailSubtitle = if (!session.conflictReason.isNullOrBlank()) {
                            "$baseSubtitle | ${session.conflictReason}"
                        } else {
                            baseSubtitle
                        }
                        val itemView = createPendingItemView(
                            title = getString(R.string.l10n_ecg_session_title_format, session.durationSeconds),
                            subtitle = detailSubtitle,
                            status = stageLabel
                        )
                        containerPendingItems.addView(itemView)
                    }

                    // Render unsynced sample batches in sequence order
                    for (batch in unsyncedBatches) {
                        val itemView = createPendingItemView(
                            title = "${getString(R.string.l10n_waveform_batch_title)} #${batch.sequence + 1}",
                            subtitle = getString(R.string.l10n_captured_at_format, sdfTime.format(Date(batch.createdAtMillis)), com.example.prog7314_poepart23.utils.BpmText.orDash(batch.estimatedBpm)),
                            status = getString(R.string.l10n_waiting)
                        )
                        containerPendingItems.addView(itemView)
                    }
                }
            }
        }
    }

    private fun createPendingItemView(title: String, subtitle: String, status: String): View {
        val layout = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (10 * resources.displayMetrics.density).toInt()
            }
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_card_stroke)
            val pad = (14 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val icon = ImageView(this).apply {
            val size = (34 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            setBackgroundResource(R.drawable.circle_warning_bg)
            val iconPad = (8 * resources.displayMetrics.density).toInt()
            setPadding(iconPad, iconPad, iconPad, iconPad)
            setImageResource(R.drawable.ic_upload)
        }

        val textLayout = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * resources.displayMetrics.density).toInt()
            }
            orientation = LinearLayout.VERTICAL
        }

        val titleTv = TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.pulse_text_primary))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val subtitleTv = TextView(this).apply {
            text = subtitle
            setTextColor(getColor(R.color.pulse_text_secondary))
            textSize = 11f
        }

        textLayout.addView(titleTv)
        textLayout.addView(subtitleTv)

        val statusTv = TextView(this).apply {
            text = status
            setTextColor(getColor(R.color.pulse_warning))
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        layout.addView(icon)
        layout.addView(textLayout)
        layout.addView(statusTv)

        return layout
    }

    private fun isNetworkConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
