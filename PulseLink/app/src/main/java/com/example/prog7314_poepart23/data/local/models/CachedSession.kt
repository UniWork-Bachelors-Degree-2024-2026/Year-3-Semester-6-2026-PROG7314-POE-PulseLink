/*
 * Model representing an ECG recording session captured locally in offline mode.
 * Stores AD8232 single-lead session metadata, original audit identity, lifecycle stage,
 * and conflict diagnostics awaiting synchronisation with the PulseLink API.
 *
 * Reference List:
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End. [online] Available at: <https://www.analog.com/media/en/technical-documentation/data-sheets/AD8232.pdf> [Accessed 21 September 2026].
 * - Google Developers, 2024. Save data in a local database using SQLite. [online] Available at: <https://developer.android.com/training/data-storage/sqlite> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.data.local.models

import java.util.Locale
import java.util.UUID

/**
 * Explicit local and remote synchronisation stages for an offline or BLE-transferred ECG recording.
 * Clearly distinguishes recordings saved locally during capture, pending upload, uploaded batches,
 * server processing (finalising/PDF generation), report ready, and retained conflicts.
 */
enum class RecordingSyncStage(val wireValue: String) {
    SAVED_LOCALLY("SavedLocally"),
    PENDING("Pending"),
    UPLOADED("Uploaded"),
    PROCESSING("Processing"),
    REPORT_READY("ReportReady"),
    CONFLICT("Conflict"),
    FAILED("Failed");

    companion object {
        fun fromWire(value: String?): RecordingSyncStage {
            val cleaned = value?.trim().orEmpty()
            return entries.firstOrNull { it.wireValue.equals(cleaned, ignoreCase = true) }
                ?: PENDING
        }
    }
}

/**
 * Aggregated counts across every lifecycle stage for a user or device queue.
 */
data class QueueStatusBreakdown(
    val savedLocallyCount: Int = 0,
    val pendingCount: Int = 0,
    val uploadedCount: Int = 0,
    val processingCount: Int = 0,
    val reportReadyCount: Int = 0,
    val conflictCount: Int = 0,
    val failedCount: Int = 0,
    val pendingBatchCount: Int = 0,
    val lastSyncedAtMillis: Long? = null
) {
    val activeSyncQueueCount: Int
        get() = pendingCount + uploadedCount + processingCount
}

data class CachedSession( // (Google Developers, 2024; Analog Devices, 2020)
    val localId: Long = 0,
    val remoteSessionId: String,
    val patientId: String,
    val deviceId: String,
    val startTimeIso: String,
    val endTimeIso: String? = null,
    val avgBpm: Int = 0,
    val durationSeconds: Int = 0,
    val status: String = "Normal",
    val isSynced: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    // Durable audit identity and recovery state fields
    val recordingId: String = extractOrCreateRecordingId(remoteSessionId),
    val ownerUid: String = patientId,
    val patientName: String? = null,
    val operatorUid: String? = null,
    val siteId: String? = null,
    val authorisationGrantId: String? = null,
    val bootId: String = "boot_android_local",
    val expectedBatchCount: Int = 0,
    val expectedSampleCount: Int = 0,
    val uploadedBatchCount: Int = 0,
    val uploadedSampleCount: Int = 0,
    val syncStage: String = defaultSyncStage(status, isSynced),
    val reportReady: Boolean = syncStage.equals(RecordingSyncStage.REPORT_READY.wireValue, ignoreCase = true),
    val retryCount: Int = 0,
    val lastAttemptAtMillis: Long = 0L,
    val lastError: String? = null,
    val conflictCode: Int? = null,
    val conflictReason: String? = null,
    val inFlightAtMillis: Long = 0L
) {
    val stage: RecordingSyncStage
        get() = RecordingSyncStage.fromWire(syncStage)

    val hasConflict: Boolean
        get() = stage == RecordingSyncStage.CONFLICT || conflictCode != null || !conflictReason.isNullOrBlank()

    /**
     * Verifies whether another session candidate preserves the same immutable recording audit identity.
     */
    fun matchesAuditIdentity(other: CachedSession): Boolean {
        if (!patientId.equals(other.patientId, ignoreCase = false)) return false
        if (!deviceId.equals(other.deviceId, ignoreCase = false)) return false
        if (startTimeIso.isNotBlank() && other.startTimeIso.isNotBlank() && startTimeIso != other.startTimeIso) return false
        if (!operatorUid.isNullOrBlank() && !other.operatorUid.isNullOrBlank() && operatorUid != other.operatorUid) return false
        if (!siteId.isNullOrBlank() && !other.siteId.isNullOrBlank() && siteId != other.siteId) return false
        if (!authorisationGrantId.isNullOrBlank() && !other.authorisationGrantId.isNullOrBlank() &&
            authorisationGrantId != other.authorisationGrantId
        ) return false
        return true
    }

    companion object {
        fun defaultSyncStage(status: String, isSynced: Boolean): String {
            return when {
                status.equals("Recording", ignoreCase = true) -> RecordingSyncStage.SAVED_LOCALLY.wireValue
                status.equals("Conflict", ignoreCase = true) -> RecordingSyncStage.CONFLICT.wireValue
                isSynced -> RecordingSyncStage.UPLOADED.wireValue
                else -> RecordingSyncStage.PENDING.wireValue
            }
        }

        /**
         * Extracts a deterministic UUID string from an `offline_<uuid>` or `SESSION-<32hex>` identifier,
         * or generates a stable UUID hash from the supplied session identifier.
         */
        fun extractOrCreateRecordingId(remoteSessionId: String): String {
            val trimmed = remoteSessionId.trim()
            if (trimmed.isEmpty()) return UUID.randomUUID().toString()
            val candidate = when {
                trimmed.startsWith("offline_", ignoreCase = true) -> trimmed.substringAfter("_")
                trimmed.startsWith("SESSION-", ignoreCase = true) -> trimmed.substringAfter("-")
                else -> trimmed
            }
            try {
                return UUID.fromString(candidate).toString().lowercase(Locale.ROOT)
            } catch (_: IllegalArgumentException) {
                val hex = candidate.replace("-", "").lowercase(Locale.ROOT)
                if (hex.length == 32 && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
                    val formatted = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
                    return formatted
                }
                return UUID.nameUUIDFromBytes(trimmed.toByteArray(Charsets.UTF_8)).toString().lowercase(Locale.ROOT)
            }
        }
    }
}
