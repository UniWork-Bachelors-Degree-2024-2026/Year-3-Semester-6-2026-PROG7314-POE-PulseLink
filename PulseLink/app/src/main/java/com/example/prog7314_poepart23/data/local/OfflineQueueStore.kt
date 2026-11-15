/*
 * Durable Queue & Notification Inbox Store Contract.
 * Defines user-scoped ECG recording queue persistence, ordered batch retrieval,
 * duplicate protection, interrupted-upload recovery, conflict retention, and
 * persistent notification inbox operations.
 *
 * Reference List:
 * - Google Developers, 2024. Guide to app architecture. [online] Available at: <https://developer.android.com/topic/architecture> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.data.local

import com.example.prog7314_poepart23.NurseSyncSummary
import com.example.prog7314_poepart23.data.local.models.CachedSampleBatch
import com.example.prog7314_poepart23.data.local.models.CachedSession
import com.example.prog7314_poepart23.data.local.models.QueueStatusBreakdown
import com.example.prog7314_poepart23.data.local.models.RecordingSyncStage
import com.example.prog7314_poepart23.data.models.NotificationItem
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.utils.LocaleHelper

interface OfflineQueueStore {
    // Session lifecycle & recovery
    fun insertSession(session: CachedSession): Long
    fun getUnsyncedSessions(patientId: String): List<CachedSession>
    fun getRecoverableSessions(ownerOrPatientId: String): List<CachedSession>
    fun getConflictedSessions(ownerOrPatientId: String? = null): List<CachedSession>
    fun getSessionsByStage(ownerOrPatientId: String, stage: RecordingSyncStage): List<CachedSession>
    fun getAllUserSessions(ownerOrPatientId: String): List<CachedSession>
    fun getSessionByLocalId(localId: Long): CachedSession?
    fun getSessionByRemoteOrRecordingId(id: String): CachedSession?
    fun updateSessionRemoteId(localId: Long, remoteSessionId: String)
    fun updateSessionMetrics(remoteSessionId: String, avgBpm: Int, durationSeconds: Int, endTimeIso: String)
    fun queueSessionForUpload(
        remoteSessionId: String,
        avgBpm: Int,
        durationSeconds: Int,
        endTimeIso: String,
        expectedBatchCount: Int? = null,
        expectedSampleCount: Int? = null
    )
    fun markSessionInFlight(localId: Long, inFlightAtMillis: Long)
    fun markSessionStage(
        localId: Long,
        stage: RecordingSyncStage,
        uploadedBatchCount: Int? = null,
        uploadedSampleCount: Int? = null,
        reportReady: Boolean = stage == RecordingSyncStage.REPORT_READY,
        lastError: String? = null
    )
    fun recordSessionRetry(localId: Long, errorMessage: String, attemptedAtMillis: Long = System.currentTimeMillis())
    fun markSessionConflict(localId: Long, conflictCode: Int, conflictReason: String)
    fun markSessionSynced(localId: Long)
    fun deleteSession(sessionId: String)

    // Sample batch operations
    fun insertSampleBatch(batch: CachedSampleBatch): Long
    fun updateBatchesSessionId(oldSessionId: String, newSessionId: String)
    fun getUnsyncedSampleBatches(patientId: String): List<CachedSampleBatch>
    fun getUnsyncedSampleBatchesForSession(sessionId: String): List<CachedSampleBatch>
    fun getBatchesForSession(sessionId: String): List<CachedSampleBatch>
    fun markBatchInFlight(localId: Long, inFlightAtMillis: Long)
    fun markSampleBatchSynced(localId: Long)
    fun recordBatchRetry(localId: Long, errorMessage: String, attemptedAtMillis: Long = System.currentTimeMillis())
    fun markBatchConflict(localId: Long, conflictCode: Int, conflictReason: String)

    // Process-restart recovery & status summaries
    fun recoverInterruptedUploads(ownerOrPatientId: String? = null): Int
    fun getPendingSyncCount(patientId: String): Int
    fun getQueueStatusBreakdown(ownerOrPatientId: String?, deviceId: String? = null): QueueStatusBreakdown
    fun getDeviceSyncSummary(deviceId: String?): NurseSyncSummary
    fun recordSyncTimestamp(millis: Long = System.currentTimeMillis())
    fun getLastSyncTimestamp(): Long
    fun onUserSignedOut()

    // User-scoped durable notification inbox operations
    fun upsertNotification(ownerUid: String, item: NotificationItem, createdAtMillis: Long = System.currentTimeMillis()): Boolean
    fun getNotificationsForUser(ownerUid: String): List<NotificationItem>
    fun getNotificationById(ownerUid: String, notificationId: String): NotificationItem?
    fun markNotificationRead(ownerUid: String, notificationId: String)
    fun markAllNotificationsRead(ownerUid: String)
    fun deleteNotification(ownerUid: String, notificationId: String)
}

/**
 * Thread-safe in-memory implementation of [OfflineQueueStore] enforcing the exact same
 * duplicate protection, audit-identity immutability, ordered batch retrieval, conflict retention,
 * and user-scoped notification durability rules as [OfflineEcgDatabase].
 */
class InMemoryOfflineQueueStore : OfflineQueueStore {

    private data class StoredNotification(
        val ownerUid: String,
        val item: NotificationItem,
        val isDeleted: Boolean = false,
        val createdAtMillis: Long
    )

    private var nextSessionId = 1L
    private var nextBatchId = 1L
    private var lastSyncTimestamp = 0L
    private val sessions = mutableListOf<CachedSession>()
    private val batches = mutableListOf<CachedSampleBatch>()
    private val notifications = mutableListOf<StoredNotification>()

    @Synchronized
    override fun insertSession(session: CachedSession): Long {
        val existing = sessions.firstOrNull {
            it.recordingId.equals(session.recordingId, ignoreCase = true) ||
                it.remoteSessionId.equals(session.remoteSessionId, ignoreCase = false)
        }
        if (existing != null) {
            if (existing.matchesAuditIdentity(session)) {
                return existing.localId
            }
            val conflictReason = LocaleHelper.stringOr(R.string.rt_db_audit_conflict, "Audit identity conflict for recording %1\$s: existing (%2\$s/%3\$s@%4\$s) vs incoming (%5\$s/%6\$s@%7\$s).", session.recordingId, existing.patientId, existing.deviceId, existing.startTimeIso, session.patientId, session.deviceId, session.startTimeIso)
            val conflictCopy = session.copy(
                localId = nextSessionId++,
                status = "Conflict",
                isSynced = false,
                syncStage = RecordingSyncStage.CONFLICT.wireValue,
                conflictCode = 409,
                conflictReason = conflictReason,
                inFlightAtMillis = 0L
            )
            sessions.add(conflictCopy)
            return conflictCopy.localId
        }

        val initialStage = if (session.syncStage.isNotBlank()) {
            session.syncStage
        } else {
            CachedSession.defaultSyncStage(session.status, session.isSynced)
        }
        val stored = session.copy(
            localId = nextSessionId++,
            syncStage = initialStage,
            reportReady = initialStage.equals(RecordingSyncStage.REPORT_READY.wireValue, ignoreCase = true)
        )
        sessions.add(stored)
        return stored.localId
    }

    private fun belongsToOwnerOrPatient(session: CachedSession, id: String): Boolean {
        return session.ownerUid == id || session.patientId == id
    }

    @Synchronized
    override fun getUnsyncedSessions(patientId: String): List<CachedSession> {
        return sessions
            .filter {
                belongsToOwnerOrPatient(it, patientId) &&
                    !it.isSynced &&
                    it.stage != RecordingSyncStage.SAVED_LOCALLY &&
                    it.stage != RecordingSyncStage.CONFLICT &&
                    it.stage != RecordingSyncStage.REPORT_READY
            }
            .sortedWith(compareBy<CachedSession> { it.createdAtMillis }.thenBy { it.localId })
    }

    @Synchronized
    override fun getRecoverableSessions(ownerOrPatientId: String): List<CachedSession> {
        return sessions
            .filter {
                belongsToOwnerOrPatient(it, ownerOrPatientId) &&
                    it.stage in setOf(
                        RecordingSyncStage.PENDING,
                        RecordingSyncStage.UPLOADED,
                        RecordingSyncStage.PROCESSING
                    )
            }
            .sortedWith(compareBy<CachedSession> { it.createdAtMillis }.thenBy { it.localId })
    }

    @Synchronized
    override fun getConflictedSessions(ownerOrPatientId: String?): List<CachedSession> {
        return sessions
            .filter {
                (ownerOrPatientId.isNullOrBlank() || belongsToOwnerOrPatient(it, ownerOrPatientId)) &&
                    it.hasConflict
            }
            .sortedWith(compareBy<CachedSession> { it.createdAtMillis }.thenBy { it.localId })
    }

    @Synchronized
    override fun getSessionsByStage(ownerOrPatientId: String, stage: RecordingSyncStage): List<CachedSession> {
        return sessions
            .filter { belongsToOwnerOrPatient(it, ownerOrPatientId) && it.stage == stage }
            .sortedWith(compareBy<CachedSession> { it.createdAtMillis }.thenBy { it.localId })
    }

    @Synchronized
    override fun getAllUserSessions(ownerOrPatientId: String): List<CachedSession> {
        return sessions
            .filter { belongsToOwnerOrPatient(it, ownerOrPatientId) }
            .sortedWith(compareByDescending<CachedSession> { it.createdAtMillis }.thenByDescending { it.localId })
    }

    @Synchronized
    override fun getSessionByLocalId(localId: Long): CachedSession? {
        return sessions.firstOrNull { it.localId == localId }
    }

    @Synchronized
    override fun getSessionByRemoteOrRecordingId(id: String): CachedSession? {
        return sessions.firstOrNull {
            it.remoteSessionId == id || it.recordingId.equals(id, ignoreCase = true)
        }
    }

    @Synchronized
    override fun updateSessionRemoteId(localId: Long, remoteSessionId: String) {
        val index = sessions.indexOfFirst { it.localId == localId }
        if (index >= 0) {
            val current = sessions[index]
            sessions[index] = current.copy(remoteSessionId = remoteSessionId)
        }
    }

    @Synchronized
    override fun updateSessionMetrics(
        remoteSessionId: String,
        avgBpm: Int,
        durationSeconds: Int,
        endTimeIso: String
    ) {
        queueSessionForUpload(
            remoteSessionId = remoteSessionId,
            avgBpm = avgBpm,
            durationSeconds = durationSeconds,
            endTimeIso = endTimeIso
        )
    }

    @Synchronized
    override fun queueSessionForUpload(
        remoteSessionId: String,
        avgBpm: Int,
        durationSeconds: Int,
        endTimeIso: String,
        expectedBatchCount: Int?,
        expectedSampleCount: Int?
    ) {
        val index = sessions.indexOfFirst { it.remoteSessionId == remoteSessionId }
        if (index < 0) return
        val current = sessions[index]
        if (current.hasConflict) return

        val sessionBatches = getBatchesForSession(remoteSessionId)
        val computedBatches = expectedBatchCount ?: sessionBatches.size
        val computedSamples = expectedSampleCount ?: sessionBatches.sumOf { it.sampleCount }
        val uploadedBatches = sessionBatches.count { it.isSynced }
        val uploadedSamples = sessionBatches.filter { it.isSynced }.sumOf { it.sampleCount }

        sessions[index] = current.copy(
            status = "Completed",
            avgBpm = avgBpm,
            durationSeconds = durationSeconds,
            endTimeIso = endTimeIso,
            expectedBatchCount = computedBatches,
            expectedSampleCount = computedSamples,
            uploadedBatchCount = uploadedBatches,
            uploadedSampleCount = uploadedSamples,
            syncStage = RecordingSyncStage.PENDING.wireValue,
            inFlightAtMillis = 0L
        )
    }

    @Synchronized
    override fun markSessionInFlight(localId: Long, inFlightAtMillis: Long) {
        val index = sessions.indexOfFirst { it.localId == localId }
        if (index >= 0) {
            sessions[index] = sessions[index].copy(inFlightAtMillis = inFlightAtMillis)
        }
    }

    @Synchronized
    override fun markSessionStage(
        localId: Long,
        stage: RecordingSyncStage,
        uploadedBatchCount: Int?,
        uploadedSampleCount: Int?,
        reportReady: Boolean,
        lastError: String?
    ) {
        val index = sessions.indexOfFirst { it.localId == localId }
        if (index < 0) return
        val current = sessions[index]
        val synced = stage == RecordingSyncStage.PROCESSING || stage == RecordingSyncStage.REPORT_READY
        sessions[index] = current.copy(
            syncStage = stage.wireValue,
            isSynced = synced || current.isSynced,
            uploadedBatchCount = uploadedBatchCount ?: current.uploadedBatchCount,
            uploadedSampleCount = uploadedSampleCount ?: current.uploadedSampleCount,
            reportReady = reportReady,
            lastError = lastError,
            inFlightAtMillis = 0L
        )
    }

    @Synchronized
    override fun recordSessionRetry(localId: Long, errorMessage: String, attemptedAtMillis: Long) {
        val index = sessions.indexOfFirst { it.localId == localId }
        if (index < 0) return
        val current = sessions[index]
        sessions[index] = current.copy(
            retryCount = current.retryCount + 1,
            lastAttemptAtMillis = attemptedAtMillis,
            lastError = errorMessage,
            inFlightAtMillis = 0L
        )
    }

    @Synchronized
    override fun markSessionConflict(localId: Long, conflictCode: Int, conflictReason: String) {
        val index = sessions.indexOfFirst { it.localId == localId }
        if (index < 0) return
        val current = sessions[index]
        sessions[index] = current.copy(
            status = "Conflict",
            isSynced = false,
            syncStage = RecordingSyncStage.CONFLICT.wireValue,
            conflictCode = conflictCode,
            conflictReason = conflictReason,
            lastError = conflictReason,
            inFlightAtMillis = 0L
        )
    }

    @Synchronized
    override fun markSessionSynced(localId: Long) {
        val index = sessions.indexOfFirst { it.localId == localId }
        if (index < 0) return
        val current = sessions[index]
        val nextStage = if (current.stage == RecordingSyncStage.REPORT_READY) {
            RecordingSyncStage.REPORT_READY
        } else {
            RecordingSyncStage.PROCESSING
        }
        sessions[index] = current.copy(
            isSynced = true,
            syncStage = nextStage.wireValue,
            inFlightAtMillis = 0L
        )
    }

    @Synchronized
    override fun deleteSession(sessionId: String) {
        batches.removeAll { it.remoteSessionId == sessionId }
        sessions.removeAll { it.remoteSessionId == sessionId }
    }

    @Synchronized
    override fun insertSampleBatch(batch: CachedSampleBatch): Long {
        val computedSha = batch.sha256.ifBlank {
            CachedSampleBatch.computeBatchSha256(
                deviceId = batch.deviceId,
                sequence = batch.sequence,
                firstSampleIndex = batch.firstSampleIndex,
                sampleRateHz = batch.sampleRateHz,
                bootId = batch.bootId,
                leadsOff = batch.leadsOff,
                samplesJson = batch.samplesJson
            )
        }
        val existing = batches.firstOrNull {
            (it.remoteSessionId == batch.remoteSessionId || it.recordingId.equals(batch.recordingId, ignoreCase = true)) &&
                it.sequence == batch.sequence &&
                it.stage != RecordingSyncStage.CONFLICT
        }
        if (existing != null) {
            if (existing.sha256.equals(computedSha, ignoreCase = true)) {
                return existing.localId
            }
            val reason = LocaleHelper.stringOr(R.string.rt_db_checksum_conflict, "Batch sequence %1\$s for session %2\$s already exists with a different SHA-256 checksum.", batch.sequence, batch.remoteSessionId)
            val conflictBatch = batch.copy(
                localId = nextBatchId++,
                sha256 = computedSha,
                isSynced = false,
                syncStage = RecordingSyncStage.CONFLICT.wireValue,
                conflictCode = 409,
                conflictReason = reason,
                lastError = reason,
                inFlightAtMillis = 0L
            )
            batches.add(conflictBatch)
            val parentSession = sessions.firstOrNull {
                it.remoteSessionId == batch.remoteSessionId || it.recordingId.equals(batch.recordingId, ignoreCase = true)
            }
            if (parentSession != null) {
                markSessionConflict(parentSession.localId, 409, reason)
            }
            return conflictBatch.localId
        }

        val stored = batch.copy(
            localId = nextBatchId++,
            sha256 = computedSha,
            signalQuality = CachedSampleBatch.normaliseSignalQuality(batch.signalQuality, batch.leadsOff),
            sampleCount = if (batch.sampleCount > 0) batch.sampleCount else CachedSampleBatch.countSamples(batch.samplesJson)
        )
        batches.add(stored)
        return stored.localId
    }

    @Synchronized
    override fun updateBatchesSessionId(oldSessionId: String, newSessionId: String) {
        for (i in batches.indices) {
            if (batches[i].remoteSessionId == oldSessionId) {
                batches[i] = batches[i].copy(remoteSessionId = newSessionId)
            }
        }
    }

    @Synchronized
    override fun getUnsyncedSampleBatches(patientId: String): List<CachedSampleBatch> {
        return batches
            .filter {
                (it.ownerUid == patientId || it.patientId == patientId) &&
                    !it.isSynced &&
                    it.stage != RecordingSyncStage.CONFLICT
            }
            .sortedWith(
                compareBy<CachedSampleBatch> { it.remoteSessionId }
                    .thenBy { it.sequence }
                    .thenBy { it.firstSampleIndex }
                    .thenBy { it.createdAtMillis }
                    .thenBy { it.localId }
            )
    }

    @Synchronized
    override fun getUnsyncedSampleBatchesForSession(sessionId: String): List<CachedSampleBatch> {
        return batches
            .filter {
                it.remoteSessionId == sessionId &&
                    !it.isSynced &&
                    it.stage != RecordingSyncStage.CONFLICT
            }
            .sortedWith(
                compareBy<CachedSampleBatch> { it.sequence }
                    .thenBy { it.firstSampleIndex }
                    .thenBy { it.createdAtMillis }
                    .thenBy { it.localId }
            )
    }

    @Synchronized
    override fun getBatchesForSession(sessionId: String): List<CachedSampleBatch> {
        return batches
            .filter { it.remoteSessionId == sessionId && it.stage != RecordingSyncStage.CONFLICT }
            .sortedWith(
                compareBy<CachedSampleBatch> { it.sequence }
                    .thenBy { it.firstSampleIndex }
                    .thenBy { it.createdAtMillis }
                    .thenBy { it.localId }
            )
    }

    @Synchronized
    override fun markBatchInFlight(localId: Long, inFlightAtMillis: Long) {
        val index = batches.indexOfFirst { it.localId == localId }
        if (index >= 0) {
            batches[index] = batches[index].copy(inFlightAtMillis = inFlightAtMillis)
        }
    }

    @Synchronized
    override fun markSampleBatchSynced(localId: Long) {
        val index = batches.indexOfFirst { it.localId == localId }
        if (index < 0) return
        val batch = batches[index]
        batches[index] = batch.copy(
            isSynced = true,
            syncStage = RecordingSyncStage.UPLOADED.wireValue,
            lastError = null,
            inFlightAtMillis = 0L
        )
        recomputeSessionProgress(batch.remoteSessionId)
    }

    @Synchronized
    override fun recordBatchRetry(localId: Long, errorMessage: String, attemptedAtMillis: Long) {
        val index = batches.indexOfFirst { it.localId == localId }
        if (index < 0) return
        val current = batches[index]
        batches[index] = current.copy(
            retryCount = current.retryCount + 1,
            lastAttemptAtMillis = attemptedAtMillis,
            lastError = errorMessage,
            inFlightAtMillis = 0L
        )
    }

    @Synchronized
    override fun markBatchConflict(localId: Long, conflictCode: Int, conflictReason: String) {
        val index = batches.indexOfFirst { it.localId == localId }
        if (index < 0) return
        val current = batches[index]
        batches[index] = current.copy(
            isSynced = false,
            syncStage = RecordingSyncStage.CONFLICT.wireValue,
            conflictCode = conflictCode,
            conflictReason = conflictReason,
            lastError = conflictReason,
            inFlightAtMillis = 0L
        )
    }

    @Synchronized
    private fun recomputeSessionProgress(sessionId: String) {
        val sIndex = sessions.indexOfFirst { it.remoteSessionId == sessionId }
        if (sIndex < 0) return
        val session = sessions[sIndex]
        if (session.hasConflict) return
        val sessionBatches = getBatchesForSession(sessionId)
        val uploadedBatches = sessionBatches.count { it.isSynced }
        val uploadedSamples = sessionBatches.filter { it.isSynced }.sumOf { it.sampleCount }
        val expectedBatches = if (session.expectedBatchCount > 0) session.expectedBatchCount else sessionBatches.size
        val expectedSamples = if (session.expectedSampleCount > 0) session.expectedSampleCount else sessionBatches.sumOf { it.sampleCount }
        val nextStage = when {
            session.stage == RecordingSyncStage.REPORT_READY -> RecordingSyncStage.REPORT_READY
            session.stage == RecordingSyncStage.PROCESSING -> RecordingSyncStage.PROCESSING
            session.stage == RecordingSyncStage.SAVED_LOCALLY -> RecordingSyncStage.SAVED_LOCALLY
            expectedBatches > 0 && uploadedBatches >= expectedBatches && sessionBatches.all { it.isSynced } ->
                RecordingSyncStage.UPLOADED
            else -> RecordingSyncStage.PENDING
        }
        sessions[sIndex] = session.copy(
            expectedBatchCount = expectedBatches,
            expectedSampleCount = expectedSamples,
            uploadedBatchCount = uploadedBatches,
            uploadedSampleCount = uploadedSamples,
            syncStage = nextStage.wireValue
        )
    }

    @Synchronized
    override fun recoverInterruptedUploads(ownerOrPatientId: String?): Int {
        var recoveredCount = 0
        for (i in batches.indices) {
            val b = batches[i]
            val matchesUser = ownerOrPatientId.isNullOrBlank() || b.ownerUid == ownerOrPatientId || b.patientId == ownerOrPatientId
            if (matchesUser && b.inFlightAtMillis > 0L) {
                batches[i] = b.copy(inFlightAtMillis = 0L)
                recoveredCount++
            }
        }
        for (i in sessions.indices) {
            val s = sessions[i]
            val matchesUser = ownerOrPatientId.isNullOrBlank() || belongsToOwnerOrPatient(s, ownerOrPatientId)
            if (!matchesUser) continue
            val hadInFlight = s.inFlightAtMillis > 0L
            if (hadInFlight) {
                sessions[i] = s.copy(inFlightAtMillis = 0L)
                recoveredCount++
            }
            if (!s.hasConflict && s.stage != RecordingSyncStage.SAVED_LOCALLY && s.stage != RecordingSyncStage.REPORT_READY) {
                recomputeSessionProgress(s.remoteSessionId)
            }
        }
        return recoveredCount
    }

    @Synchronized
    override fun getPendingSyncCount(patientId: String): Int {
        val pendingSessions = sessions.count {
            belongsToOwnerOrPatient(it, patientId) &&
                !it.isSynced &&
                it.stage != RecordingSyncStage.SAVED_LOCALLY &&
                it.stage != RecordingSyncStage.CONFLICT &&
                it.stage != RecordingSyncStage.REPORT_READY
        }
        val pendingBatches = batches.count {
            (it.ownerUid == patientId || it.patientId == patientId) &&
                !it.isSynced &&
                it.stage != RecordingSyncStage.CONFLICT
        }
        return pendingSessions + pendingBatches
    }

    @Synchronized
    override fun getQueueStatusBreakdown(ownerOrPatientId: String?, deviceId: String?): QueueStatusBreakdown {
        val cleanDevice = deviceId?.trim()?.ifBlank { null }
        val filteredSessions = sessions.filter { s ->
            val userOk = ownerOrPatientId.isNullOrBlank() || belongsToOwnerOrPatient(s, ownerOrPatientId)
            val deviceOk = cleanDevice == null || s.deviceId == cleanDevice
            userOk && deviceOk
        }
        val filteredBatches = batches.filter { b ->
            val userOk = ownerOrPatientId.isNullOrBlank() || b.ownerUid == ownerOrPatientId || b.patientId == ownerOrPatientId
            val deviceOk = cleanDevice == null || b.deviceId == cleanDevice
            userOk && deviceOk && !b.isSynced && b.stage != RecordingSyncStage.CONFLICT
        }
        return QueueStatusBreakdown(
            savedLocallyCount = filteredSessions.count { it.stage == RecordingSyncStage.SAVED_LOCALLY },
            pendingCount = filteredSessions.count { it.stage == RecordingSyncStage.PENDING },
            uploadedCount = filteredSessions.count { it.stage == RecordingSyncStage.UPLOADED },
            processingCount = filteredSessions.count { it.stage == RecordingSyncStage.PROCESSING },
            reportReadyCount = filteredSessions.count { it.stage == RecordingSyncStage.REPORT_READY },
            conflictCount = filteredSessions.count { it.stage == RecordingSyncStage.CONFLICT },
            failedCount = filteredSessions.count { it.stage == RecordingSyncStage.FAILED },
            pendingBatchCount = filteredBatches.size,
            lastSyncedAtMillis = lastSyncTimestamp.takeIf { it > 0L }
        )
    }

    @Synchronized
    override fun getDeviceSyncSummary(deviceId: String?): NurseSyncSummary {
        val breakdown = getQueueStatusBreakdown(ownerOrPatientId = null, deviceId = deviceId)
        return NurseSyncSummary(
            local = breakdown.savedLocallyCount + breakdown.conflictCount + breakdown.failedCount,
            synchronising = breakdown.pendingCount + breakdown.uploadedCount + breakdown.processingCount,
            uploaded = breakdown.reportReadyCount,
            updatedAtMillis = breakdown.lastSyncedAtMillis
        )
    }

    @Synchronized
    override fun recordSyncTimestamp(millis: Long) {
        lastSyncTimestamp = millis
    }

    @Synchronized
    override fun getLastSyncTimestamp(): Long = lastSyncTimestamp

    @Synchronized
    override fun onUserSignedOut() {
        recoverInterruptedUploads(null)
    }

    @Synchronized
    override fun upsertNotification(ownerUid: String, item: NotificationItem, createdAtMillis: Long): Boolean {
        val cleanOwner = ownerUid.trim()
        if (cleanOwner.isEmpty() || item.notificationId.isBlank()) return false
        val index = notifications.indexOfFirst {
            it.ownerUid == cleanOwner && it.item.notificationId == item.notificationId
        }
        if (index >= 0) {
            val existing = notifications[index]
            if (existing.isDeleted) return false
            // Preserve read state if the user has already marked this notification as read
            val preservedUnread = existing.item.isUnread && item.isUnread
            notifications[index] = existing.copy(
                item = item.copy(isUnread = preservedUnread)
            )
            return false
        }
        notifications.add(
            StoredNotification(
                ownerUid = cleanOwner,
                item = item,
                isDeleted = false,
                createdAtMillis = createdAtMillis
            )
        )
        return true
    }

    @Synchronized
    override fun getNotificationsForUser(ownerUid: String): List<NotificationItem> {
        val cleanOwner = ownerUid.trim()
        if (cleanOwner.isEmpty()) return emptyList()
        return notifications
            .filter { it.ownerUid == cleanOwner && !it.isDeleted }
            .sortedByDescending { it.createdAtMillis }
            .map { it.item }
    }

    @Synchronized
    override fun getNotificationById(ownerUid: String, notificationId: String): NotificationItem? {
        val cleanOwner = ownerUid.trim()
        if (cleanOwner.isEmpty()) return null
        return notifications.firstOrNull {
            it.ownerUid == cleanOwner && !it.isDeleted && it.item.notificationId == notificationId
        }?.item
    }

    @Synchronized
    override fun markNotificationRead(ownerUid: String, notificationId: String) {
        val cleanOwner = ownerUid.trim()
        for (i in notifications.indices) {
            val entry = notifications[i]
            if (entry.ownerUid == cleanOwner && entry.item.notificationId == notificationId) {
                notifications[i] = entry.copy(item = entry.item.copy(isUnread = false))
            }
        }
    }

    @Synchronized
    override fun markAllNotificationsRead(ownerUid: String) {
        val cleanOwner = ownerUid.trim()
        for (i in notifications.indices) {
            val entry = notifications[i]
            if (entry.ownerUid == cleanOwner && !entry.isDeleted && entry.item.isUnread) {
                notifications[i] = entry.copy(item = entry.item.copy(isUnread = false))
            }
        }
    }

    @Synchronized
    override fun deleteNotification(ownerUid: String, notificationId: String) {
        val cleanOwner = ownerUid.trim()
        for (i in notifications.indices) {
            val entry = notifications[i]
            if (entry.ownerUid == cleanOwner && entry.item.notificationId == notificationId) {
                notifications[i] = entry.copy(
                    item = entry.item.copy(isUnread = false),
                    isDeleted = true
                )
            }
        }
    }
}
