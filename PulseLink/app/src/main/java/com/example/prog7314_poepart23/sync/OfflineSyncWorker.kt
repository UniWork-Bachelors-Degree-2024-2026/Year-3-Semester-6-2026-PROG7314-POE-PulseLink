/*
 * Background WorkManager worker and testable coordinator responsible for draining user-scoped
 * offline AD8232 ECG sessions and ordered 250 Hz telemetry batches to the PulseLink Azure REST API.
 * Preserves original recording audit identity, recovers interrupted uploads across process restarts,
 * enforces ordered batch retries, deduplicates committed chunks, retention-flags conflicts, and
 * tracks lifecycle transitions through SavedLocally -> Pending -> Uploaded -> Processing -> ReportReady.
 *
 * Reference List:
 * - Google Developers, 2024. WorkManager: Threading in CoroutineWorker. [online] Available at: <https://developer.android.com/topic/libraries/architecture/workmanager/advanced/coroutineworker> [Accessed 21 September 2026].
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.sync

import android.content.Context
import androidx.work.CoroutineWorker               // (Google Developers, 2024)
import androidx.work.WorkerParameters              // (Google Developers, 2024)
import com.example.prog7314_poepart23.data.local.OfflineEcgDatabase
import com.example.prog7314_poepart23.data.local.OfflineQueueStore
import com.example.prog7314_poepart23.data.local.models.CachedSampleBatch
import com.example.prog7314_poepart23.data.local.models.RecordingSyncStage
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiClient
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiService
import com.example.prog7314_poepart23.data.remote.models.CompleteRecordingRequestDto
import com.example.prog7314_poepart23.data.remote.models.IngestEcgPayloadDto
import com.example.prog7314_poepart23.data.remote.models.StartSessionRequestDto
import com.example.prog7314_poepart23.security.TokenManager
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers              // (JetBrains, 2024)
import kotlinx.coroutines.withContext              // (JetBrains, 2024)
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.utils.LocaleHelper

data class SyncExecutionReport(
    val recoveredCount: Int = 0,
    val sessionsStarted: Int = 0,
    val batchesUploaded: Int = 0,
    val sessionsCompleted: Int = 0,
    val reportsReady: Int = 0,
    val conflictsRetained: Int = 0,
    val transientFailures: Int = 0
) {
    val shouldRetry: Boolean
        get() = transientFailures > 0
}

class OfflineSyncCoordinator(
    private val store: OfflineQueueStore,
    private val apiService: PulseLinkApiService = PulseLinkApiClient.apiService,
    private val refreshToken: suspend () -> String? = { PulseLinkApiClient.getAuthToken() },
    private val currentUid: () -> String? = {
        PulseLinkApiClient.getActiveUid() ?: try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (_: Exception) {
            null
        }
    },
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val gson = Gson()

    suspend fun syncUserQueue(ownerUid: String): SyncExecutionReport {
        val cleanOwner = ownerUid.trim()
        if (cleanOwner.isEmpty()) return SyncExecutionReport()

        val activeBefore = currentUid()
        if (activeBefore != null && activeBefore != cleanOwner) {
            return SyncExecutionReport()
        }
        val token = refreshToken()
        if (token.isNullOrBlank()) {
            return SyncExecutionReport(transientFailures = 1)
        }
        if (currentUid() != null && currentUid() != cleanOwner) {
            return SyncExecutionReport()
        }

        val recovered = store.recoverInterruptedUploads(cleanOwner)
        var sessionsStarted = 0
        var batchesUploaded = 0
        var sessionsCompleted = 0
        var reportsReady = 0
        var conflictsRetained = 0
        var transientFailures = 0

        val recoverableSessions = store.getRecoverableSessions(cleanOwner)
        for (session in recoverableSessions) {
            if (currentUid() != null && currentUid() != cleanOwner) {
                store.recoverInterruptedUploads(cleanOwner)
                break
            }

            try {
                store.markSessionInFlight(session.localId, nowMillis())
                var targetSessionId = session.remoteSessionId

                // Phase A: Initialise or reconcile remote recording session while preserving audit identity
                if (targetSessionId.startsWith("offline_", ignoreCase = true)) {
                    val startResponse = apiService.startSession(
                        StartSessionRequestDto(
                            patientId = session.patientId,
                            deviceId = session.deviceId,
                            patientName = session.patientName,
                            recordingId = session.recordingId
                        )
                    )
                    if (startResponse.isSuccessful && startResponse.body() != null) {
                        val started = startResponse.body()!!
                        if ((started.patientId.isNotBlank() && started.patientId != session.patientId) ||
                            (started.deviceId.isNotBlank() && started.deviceId != session.deviceId)
                        ) {
                            val reason = LocaleHelper.stringOr(R.string.rt_sync_identity_conflict, "Server session identity (%1\$s/%2\$s) conflicts with local audit identity (%3\$s/%4\$s).", started.patientId, started.deviceId, session.patientId, session.deviceId)
                            store.markSessionConflict(session.localId, 409, reason)
                            conflictsRetained++
                            continue
                        }
                        val newRemoteId = started.sessionId
                        store.updateSessionRemoteId(session.localId, newRemoteId)
                        store.updateBatchesSessionId(targetSessionId, newRemoteId)
                        targetSessionId = newRemoteId
                        sessionsStarted++
                    } else if (startResponse.code() == 409 || startResponse.code() == 410) {
                        val err = extractErrorText(startResponse.errorBody()?.string(), LocaleHelper.stringOr(R.string.rt_sync_start_conflict, "Session start conflict (HTTP %1\$s).", startResponse.code()))
                        store.markSessionConflict(session.localId, startResponse.code(), err)
                        conflictsRetained++
                        continue
                    } else {
                        store.recordSessionRetry(
                            session.localId,
                            LocaleHelper.stringOr(R.string.rt_sync_start_failed, "Session start failed (HTTP %1\$s)", startResponse.code()),
                            nowMillis()
                        )
                        transientFailures++
                        continue
                    }
                } else {
                    // Reconcile interrupted upload progress with the server if available
                    val reconciledOk = reconcileServerProgress(session.localId, targetSessionId)
                    if (reconciledOk == ReconcileOutcome.CONFLICT) {
                        conflictsRetained++
                        continue
                    }
                }

                // Phase B: Upload remaining sample batches in strict sequence order
                val pendingBatches = store.getUnsyncedSampleBatchesForSession(targetSessionId)
                var batchHalted = false
                for (batch in pendingBatches) {
                    if (currentUid() != null && currentUid() != cleanOwner) {
                        batchHalted = true
                        break
                    }
                    try {
                        store.markBatchInFlight(batch.localId, nowMillis())
                        val listType = object : TypeToken<List<Int>>() {}.type
                        val samplePoints: List<Int> = gson.fromJson(batch.samplesJson, listType) ?: emptyList()

                        val payload = IngestEcgPayloadDto(
                            deviceId = batch.deviceId,
                            sessionId = targetSessionId,
                            firmwareVer = "1.0.0-poe",
                            timestamp = batch.createdAtMillis,
                            sampleRateHz = batch.sampleRateHz,
                            leadConnected = !batch.leadsOff,
                            estimatedBpm = if (batch.leadsOff) 0 else batch.estimatedBpm,
                            signalQuality = CachedSampleBatch.normaliseSignalQuality(batch.signalQuality, batch.leadsOff),
                            samples = samplePoints,
                            schemaVersion = 2,
                            bootId = batch.bootId,
                            sequence = batch.sequence,
                            firstSampleIndex = batch.firstSampleIndex,
                            capturedAtUtc = batch.timestampIso,
                            patientId = session.patientId,
                            patientName = session.patientName
                        )

                        val response = apiService.ingestTelemetry(payload)
                        if (response.isSuccessful) {
                            store.markSampleBatchSynced(batch.localId)
                            batchesUploaded++
                        } else if (response.code() == 409 || response.code() == 410) {
                            val err = extractErrorText(
                                response.errorBody()?.string(),
                                LocaleHelper.stringOr(R.string.rt_sync_batch_conflict, "Batch sequence %1\$s rejected with conflict (HTTP %2\$s).", batch.sequence, response.code())
                            )
                            store.markBatchConflict(batch.localId, response.code(), err)
                            store.markSessionConflict(session.localId, response.code(), err)
                            conflictsRetained++
                            batchHalted = true
                            break
                        } else {
                            val err = LocaleHelper.stringOr(R.string.rt_sync_batch_failed, "Batch %1\$s upload failed (HTTP %2\$s)", batch.sequence, response.code())
                            store.recordBatchRetry(batch.localId, err, nowMillis())
                            store.recordSessionRetry(session.localId, err, nowMillis())
                            transientFailures++
                            batchHalted = true
                            break
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        val err = e.localizedMessage ?: LocaleHelper.stringOr(R.string.rt_sync_batch_network, "Network error uploading batch %1\$s", batch.sequence)
                        store.recordBatchRetry(batch.localId, err, nowMillis())
                        store.recordSessionRetry(session.localId, err, nowMillis())
                        transientFailures++
                        batchHalted = true
                        break
                    }
                }

                if (batchHalted) {
                    continue
                }

                // Phase C: Transition to UPLOADED once all batches are synced, then finalise recording -> PROCESSING
                val updatedSession = store.getSessionByLocalId(session.localId) ?: continue
                if (updatedSession.hasConflict) continue

                val allSessionBatches = store.getBatchesForSession(targetSessionId)
                val remainingUnsynced = store.getUnsyncedSampleBatchesForSession(targetSessionId)
                if (remainingUnsynced.isNotEmpty()) {
                    transientFailures++
                    continue
                }

                if (updatedSession.stage == RecordingSyncStage.PENDING) {
                    store.markSessionStage(
                        localId = session.localId,
                        stage = RecordingSyncStage.UPLOADED,
                        uploadedBatchCount = allSessionBatches.size,
                        uploadedSampleCount = allSessionBatches.sumOf { it.sampleCount }
                    )
                }

                val stageAfterBatches = store.getSessionByLocalId(session.localId)?.stage ?: RecordingSyncStage.UPLOADED
                if (stageAfterBatches == RecordingSyncStage.UPLOADED) {
                    val expectedBatches = updatedSession.expectedBatchCount.takeIf { it > 0 }
                        ?: allSessionBatches.size.coerceAtLeast(1)
                    val expectedSamples = updatedSession.expectedSampleCount.takeIf { it > 0 }
                        ?: allSessionBatches.sumOf { it.sampleCount }.coerceAtLeast(1)

                    val completeResponse = apiService.completeRecording(
                        targetSessionId,
                        CompleteRecordingRequestDto(
                            expectedBatchCount = expectedBatches,
                            expectedSampleCount = expectedSamples,
                            recordedDurationSeconds = updatedSession.durationSeconds
                        )
                    )
                    if (completeResponse.isSuccessful) {
                        store.markSessionStage(
                            localId = session.localId,
                            stage = RecordingSyncStage.PROCESSING,
                            uploadedBatchCount = allSessionBatches.size,
                            uploadedSampleCount = allSessionBatches.sumOf { it.sampleCount },
                            reportReady = false,
                            lastError = null
                        )
                        sessionsCompleted++
                    } else if (completeResponse.code() == 409 || completeResponse.code() == 410) {
                        val err = extractErrorText(
                            completeResponse.errorBody()?.string(),
                            LocaleHelper.stringOr(R.string.rt_sync_complete_conflict, "Recording finalisation conflict (HTTP %1\$s).", completeResponse.code())
                        )
                        store.markSessionConflict(session.localId, completeResponse.code(), err)
                        conflictsRetained++
                        continue
                    } else {
                        store.recordSessionRetry(
                            session.localId,
                            LocaleHelper.stringOr(R.string.rt_sync_complete_failed, "Recording finalisation failed (HTTP %1\$s)", completeResponse.code()),
                            nowMillis()
                        )
                        transientFailures++
                        continue
                    }
                }

                // Phase D: Check authoritative PDF report status -> REPORT_READY
                val stageForReport = store.getSessionByLocalId(session.localId)?.stage
                if (stageForReport == RecordingSyncStage.PROCESSING) {
                    val reportStatusResp = apiService.getSessionReportStatus(targetSessionId, generateIfMissing = true)
                    if (reportStatusResp.isSuccessful && reportStatusResp.body() != null) {
                        val reportStatus = reportStatusResp.body()!!
                        when {
                            reportStatus.state.equals("Ready", ignoreCase = true) && reportStatus.reportAvailable -> {
                                store.markSessionStage(
                                    localId = session.localId,
                                    stage = RecordingSyncStage.REPORT_READY,
                                    reportReady = true,
                                    lastError = null
                                )
                                reportsReady++
                            }
                            reportStatus.state.equals("Generating", ignoreCase = true) -> {
                                store.markSessionStage(
                                    localId = session.localId,
                                    stage = RecordingSyncStage.PROCESSING,
                                    reportReady = false,
                                    lastError = reportStatus.message
                                )
                                transientFailures++
                            }
                            else -> {
                                store.recordSessionRetry(
                                    session.localId,
                                    reportStatus.message ?: LocaleHelper.stringOr(R.string.rt_sync_report_state, "Report status is %1\$s", reportStatus.state),
                                    nowMillis()
                                )
                                transientFailures++
                            }
                        }
                    } else if (reportStatusResp.code() == 409 || reportStatusResp.code() == 410) {
                        val err = extractErrorText(
                            reportStatusResp.errorBody()?.string(),
                            LocaleHelper.stringOr(R.string.rt_sync_report_conflict, "Report verification conflict (HTTP %1\$s).", reportStatusResp.code())
                        )
                        store.markSessionConflict(session.localId, reportStatusResp.code(), err)
                        conflictsRetained++
                    } else {
                        store.recordSessionRetry(
                            session.localId,
                            LocaleHelper.stringOr(R.string.rt_sync_report_failed, "Report status check failed (HTTP %1\$s)", reportStatusResp.code()),
                            nowMillis()
                        )
                        transientFailures++
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                store.recordSessionRetry(
                    session.localId,
                    e.localizedMessage ?: LocaleHelper.stringOr(R.string.rt_sync_unexpected, "Unexpected sync error"),
                    nowMillis()
                )
                transientFailures++
            }
        }

        // 2. Drain any standalone sample batches whose session already existed on the server
        val remainingBatches = store.getUnsyncedSampleBatches(cleanOwner)
        val haltedSessions = mutableSetOf<String>()
        for (batch in remainingBatches) {
            if (batch.remoteSessionId.startsWith("offline_", ignoreCase = true)) continue
            if (batch.remoteSessionId in haltedSessions) continue
            try {
                store.markBatchInFlight(batch.localId, nowMillis())
                val listType = object : TypeToken<List<Int>>() {}.type
                val samplePoints: List<Int> = gson.fromJson(batch.samplesJson, listType) ?: emptyList()

                val payload = IngestEcgPayloadDto(
                    deviceId = batch.deviceId,
                    sessionId = batch.remoteSessionId,
                    firmwareVer = "1.0.0-poe",
                    timestamp = batch.createdAtMillis,
                    sampleRateHz = batch.sampleRateHz,
                    leadConnected = !batch.leadsOff,
                    estimatedBpm = if (batch.leadsOff) 0 else batch.estimatedBpm,
                    signalQuality = CachedSampleBatch.normaliseSignalQuality(batch.signalQuality, batch.leadsOff),
                    samples = samplePoints,
                    schemaVersion = 2,
                    bootId = batch.bootId,
                    sequence = batch.sequence,
                    firstSampleIndex = batch.firstSampleIndex,
                    capturedAtUtc = batch.timestampIso,
                    patientId = batch.patientId
                )

                val response = apiService.ingestTelemetry(payload)
                if (response.isSuccessful) {
                    store.markSampleBatchSynced(batch.localId)
                    batchesUploaded++
                } else if (response.code() == 409 || response.code() == 410) {
                    val err = extractErrorText(
                        response.errorBody()?.string(),
                        LocaleHelper.stringOr(R.string.rt_sync_standalone_conflict, "Standalone batch %1\$s conflict (HTTP %2\$s).", batch.sequence, response.code())
                    )
                    store.markBatchConflict(batch.localId, response.code(), err)
                    conflictsRetained++
                    haltedSessions.add(batch.remoteSessionId)
                } else {
                    store.recordBatchRetry(batch.localId, LocaleHelper.stringOr(R.string.rt_sync_batch_failed_http, "Batch upload failed (HTTP %1\$s)", response.code()), nowMillis())
                    transientFailures++
                    haltedSessions.add(batch.remoteSessionId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                store.recordBatchRetry(batch.localId, e.localizedMessage ?: LocaleHelper.stringOr(R.string.rt_sync_network_error, "Network error"), nowMillis())
                transientFailures++
                haltedSessions.add(batch.remoteSessionId)
            }
        }

        store.recordSyncTimestamp(nowMillis())
        return SyncExecutionReport(
            recoveredCount = recovered,
            sessionsStarted = sessionsStarted,
            batchesUploaded = batchesUploaded,
            sessionsCompleted = sessionsCompleted,
            reportsReady = reportsReady,
            conflictsRetained = conflictsRetained,
            transientFailures = transientFailures
        )
    }

    private enum class ReconcileOutcome { OK, CONFLICT }

    private suspend fun reconcileServerProgress(sessionLocalId: Long, remoteSessionId: String): ReconcileOutcome {
        return try {
            val progressResp = apiService.getRecordingProgress(remoteSessionId)
            if (progressResp.code() == 410) {
                store.markSessionConflict(sessionLocalId, 410, LocaleHelper.stringOr(R.string.rt_sync_discarded, "Recording was discarded on the server."))
                return ReconcileOutcome.CONFLICT
            }
            if (!progressResp.isSuccessful || progressResp.body() == null) {
                return ReconcileOutcome.OK
            }
            val progress = progressResp.body()!!
            if (progress.status.equals("Discarded", ignoreCase = true)) {
                store.markSessionConflict(sessionLocalId, 410, LocaleHelper.stringOr(R.string.rt_sync_discarded, "Recording was discarded on the server."))
                return ReconcileOutcome.CONFLICT
            }
            val serverBatchesBySeq = progress.batches.associateBy { it.sequence }
            if (serverBatchesBySeq.isNotEmpty()) {
                val localUnsynced = store.getUnsyncedSampleBatchesForSession(remoteSessionId)
                for (localBatch in localUnsynced) {
                    val serverRef = serverBatchesBySeq[localBatch.sequence] ?: continue
                    if (serverRef.sampleCount > 0 && serverRef.sampleCount != localBatch.sampleCount) {
                        val reason = LocaleHelper.stringOr(R.string.rt_sync_count_conflict, "Server sequence %1\$s sample count (%2\$s) conflicts with local batch (%3\$s).", localBatch.sequence, serverRef.sampleCount, localBatch.sampleCount)
                        store.markBatchConflict(localBatch.localId, 409, reason)
                        store.markSessionConflict(sessionLocalId, 409, reason)
                        return ReconcileOutcome.CONFLICT
                    }
                    store.markSampleBatchSynced(localBatch.localId)
                }
            }
            ReconcileOutcome.OK
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReconcileOutcome.OK
        }
    }

    private fun extractErrorText(rawBody: String?, fallback: String): String {
        val cleaned = rawBody?.trim()?.takeIf { it.isNotEmpty() } ?: return fallback
        return cleaned.take(240)
    }
}

class OfflineSyncWorker( // (Google Developers, 2024)
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val localDb = OfflineEcgDatabase.getInstance(appContext)
    private val coordinator = OfflineSyncCoordinator(
        store = localDb,
        apiService = PulseLinkApiClient.apiService,
        refreshToken = {
            PulseLinkApiClient.getAuthToken() ?: TokenManager.refreshToken(appContext, forceRefresh = false)
        },
        currentUid = {
            FirebaseAuth.getInstance().currentUser?.uid ?: PulseLinkApiClient.getActiveUid()
        }
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ownerUid = FirebaseAuth.getInstance().currentUser?.uid
            ?: PulseLinkApiClient.getActiveUid()
            ?: return@withContext Result.success()

        return@withContext try {
            val report = coordinator.syncUserQueue(ownerUid)
            if (report.shouldRetry) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
