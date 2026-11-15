/**
 * PulseLink UI Presentation Data Transfer Objects
 *
 * Lightweight presentation-layer models backing RecyclerView adapters and DiffUtil
 * item callbacks for patient rosters, generated reports, and in-app notifications (AHA, 2020; Google Developers, 2024).
 *
 * Reference List:
 * - American Heart Association (AHA), 2020. Recommendations for the Standardization and Interpretation of the Electrocardiogram. Circulation, 142(15), pp. 810-830.
 * - Google Developers, 2024. Create dynamic lists with RecyclerView: ListAdapter and DiffUtil. Android Developers. Available at: <https://developer.android.com/develop/ui/views/layout/recyclerview> [Accessed 18 September 2026].
 */

package com.example.prog7314_poepart23.data.models

// Row model for the doctor-facing patient roster (PatientListActivity).
data class PatientSummary(
    val patientId: String,
    val name: String,
    val lastBpm: Int,
    val status: PatientStatus,
    val lastSessionDateLabel: String
)

enum class PatientStatus {
    REGISTERED,
    FOLLOW_UP,
    STABLE,
    REVIEW_NEEDED,
    CRITICAL
}

// Row model for generated clinical reports (DoctorReportsActivity).
data class ReportSummary(
    val reportId: String,
    val patientName: String,
    val reportDateLabel: String,
    val sessionDurationLabel: String,
    val status: ReportStatus
)

enum class ReportStatus {
    PENDING,
    FLAGGED,
    SIGNED_OFF
}

// Row model for in-app notifications (NotificationsActivity).
data class NotificationItem(
    val notificationId: String,
    val title: String,
    val messagePreview: String,
    val timestampLabel: String,
    val isUnread: Boolean,
    val iconRes: Int,
    // Populated only when the push payload actually included a linked ECG session -
    // e.g. clinical alerts and report-ready notifications. Null for plain clinical notes,
    // and callers must treat null as "no session attached", never substitute placeholder values.
    val sessionId: String? = null,
    val sessionTimestampLabel: String? = null,
    val avgBpm: Int? = null,
    val durationSeconds: Int? = null,
    val startTimeUtc: String? = null,
    val signalQuality: String? = null,
    val sessionStatus: String? = null,
    val rhythmStatus: String? = null
)
