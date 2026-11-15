package com.example.prog7314_poepart23

import com.example.prog7314_poepart23.data.local.InMemoryOfflineQueueStore
import com.example.prog7314_poepart23.data.local.models.CachedSampleBatch
import com.example.prog7314_poepart23.data.local.models.CachedSession
import com.example.prog7314_poepart23.data.local.models.RecordingSyncStage
import com.example.prog7314_poepart23.data.models.NotificationItem
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiService
import com.example.prog7314_poepart23.data.remote.models.AccessPageDto
import com.example.prog7314_poepart23.data.remote.models.CompleteRecordingRequestDto
import com.example.prog7314_poepart23.data.remote.models.IngestEcgPayloadDto
import com.example.prog7314_poepart23.data.remote.models.RecordingBatchReferenceDto
import com.example.prog7314_poepart23.data.remote.models.RecordingProgressDto
import com.example.prog7314_poepart23.data.remote.models.ReportStatusDto
import com.example.prog7314_poepart23.data.remote.models.StartSessionRequestDto
import com.example.prog7314_poepart23.data.remote.models.StartSessionResponseDto
import com.example.prog7314_poepart23.data.repository.NotificationRepository
import com.example.prog7314_poepart23.messaging.ClinicalNotificationDto
import com.example.prog7314_poepart23.messaging.ClinicalNotificationSync
import com.example.prog7314_poepart23.sync.OfflineSyncCoordinator
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.lang.reflect.Proxy

class SqliteAndNotificationRecoveryTest {

    private fun fakeApi(handler: (methodName: String, args: Array<out Any?>?) -> Any?): PulseLinkApiService {
        return Proxy.newProxyInstance(
            PulseLinkApiService::class.java.classLoader,
            arrayOf(PulseLinkApiService::class.java)
        ) { _, method, args ->
            handler(method.name, args)
        } as PulseLinkApiService
    }

    @Test
    fun `recording lifecycle distinguishes SavedLocally Pending Uploaded Processing and ReportReady while preserving audit identity`() = runBlocking {
        val store = InMemoryOfflineQueueStore()
        val recordingUuid = "550e8400-e29b-41d4-a716-446655440000"
        val offlineId = "offline_$recordingUuid"

        // 1. Insert during active capture -> SavedLocally
        val localId = store.insertSession(
            CachedSession(
                remoteSessionId = offlineId,
                recordingId = recordingUuid,
                ownerUid = "uid_nurse_1",
                patientId = "PAT-1001",
                patientName = "Thabo Molefe",
                deviceId = "DEV-CYD-01",
                operatorUid = "uid_nurse_1",
                siteId = "SITE-GQE-01",
                authorisationGrantId = "GRANT-8899",
                startTimeIso = "2026-11-06T09:00:00Z",
                status = "Recording"
            )
        )

        var breakdown = store.getQueueStatusBreakdown("uid_nurse_1", "DEV-CYD-01")
        assertEquals(1, breakdown.savedLocallyCount)
        assertEquals(0, breakdown.pendingCount)
        assertTrue(store.getUnsyncedSessions("uid_nurse_1").isEmpty())

        // Insert two sample batches (out of order: sequence 1 then sequence 0)
        store.insertSampleBatch(
            CachedSampleBatch(
                remoteSessionId = offlineId,
                recordingId = recordingUuid,
                ownerUid = "uid_nurse_1",
                deviceId = "DEV-CYD-01",
                patientId = "PAT-1001",
                sequence = 1,
                firstSampleIndex = 4,
                samplesJson = "[2050,2060,2070,2080]",
                timestampIso = "2026-11-06T09:00:01Z"
            )
        )
        store.insertSampleBatch(
            CachedSampleBatch(
                remoteSessionId = offlineId,
                recordingId = recordingUuid,
                ownerUid = "uid_nurse_1",
                deviceId = "DEV-CYD-01",
                patientId = "PAT-1001",
                sequence = 0,
                firstSampleIndex = 0,
                samplesJson = "[2010,2020,2030,2040]",
                timestampIso = "2026-11-06T09:00:00Z"
            )
        )

        // 2. Complete capture locally -> transitions to Pending
        store.queueSessionForUpload(
            remoteSessionId = offlineId,
            avgBpm = 74,
            durationSeconds = 90,
            endTimeIso = "2026-11-06T09:01:30Z"
        )
        val pendingSession = store.getSessionByLocalId(localId)!!
        assertEquals(RecordingSyncStage.PENDING, pendingSession.stage)
        assertEquals(2, pendingSession.expectedBatchCount)
        assertEquals(8, pendingSession.expectedSampleCount)

        val uploadedSequences = mutableListOf<Int>()
        var capturedStartReq: StartSessionRequestDto? = null
        var capturedCompleteReq: CompleteRecordingRequestDto? = null
        var reportState = "Generating"

        val api = fakeApi { name, args ->
            when (name) {
                "startSession" -> {
                    capturedStartReq = args?.get(0) as StartSessionRequestDto
                    Response.success(
                        StartSessionResponseDto(
                            sessionId = "SESSION-550e8400e29b41d4a716446655440000",
                            patientId = capturedStartReq!!.patientId,
                            deviceId = capturedStartReq!!.deviceId,
                            startTimeUtc = "2026-11-06T09:00:00Z",
                            status = "Active"
                        )
                    )
                }
                "ingestTelemetry" -> {
                    val payload = args?.get(0) as IngestEcgPayloadDto
                    uploadedSequences.add(payload.sequence)
                    Response.success(mapOf("persisted" to true))
                }
                "completeRecording" -> {
                    capturedCompleteReq = args?.get(1) as CompleteRecordingRequestDto
                    Response.success(
                        RecordingProgressDto(
                            sessionId = "SESSION-550e8400e29b41d4a716446655440000",
                            status = "Completed",
                            receivedBatchCount = 2,
                            receivedSampleCount = 8
                        )
                    )
                }
                "getSessionReportStatus" -> {
                    Response.success(
                        ReportStatusDto(
                            sessionId = "SESSION-550e8400e29b41d4a716446655440000",
                            patientId = "PAT-1001",
                            audience = "Staff",
                            state = reportState,
                            reportAvailable = reportState == "Ready",
                            fileName = "PulseLink_Report_SESSION-550e8400e29b41d4a716446655440000.pdf",
                            downloadPath = "/api/reports/session/SESSION-550e8400e29b41d4a716446655440000/pdf",
                            streamPath = "/api/reports/session/SESSION-550e8400e29b41d4a716446655440000/pdf",
                            authenticatedEmailLinkPath = "/api/reports/session/SESSION-550e8400e29b41d4a716446655440000/pdf"
                        )
                    )
                }
                else -> error("Unexpected API call: $name")
            }
        }

        val coordinator = OfflineSyncCoordinator(
            store = store,
            apiService = api,
            refreshToken = { "valid-jwt-token" },
            currentUid = { "uid_nurse_1" }
        )

        // 3. First sync pass: uploads batches in strict 0, 1 sequence order, completes session, report is still Generating -> Processing
        val firstReport = coordinator.syncUserQueue("uid_nurse_1")
        assertEquals(listOf(0, 1), uploadedSequences)
        assertEquals(recordingUuid, capturedStartReq?.recordingId)
        assertEquals(2, capturedCompleteReq?.expectedBatchCount)
        assertEquals(8, capturedCompleteReq?.expectedSampleCount)
        assertTrue(firstReport.shouldRetry) // because reportState is still Generating

        val processingSession = store.getSessionByLocalId(localId)!!
        assertEquals(RecordingSyncStage.PROCESSING, processingSession.stage)
        assertFalse(processingSession.reportReady)
        // Original audit identity preserved
        assertEquals(recordingUuid, processingSession.recordingId)
        assertEquals("PAT-1001", processingSession.patientId)
        assertEquals("DEV-CYD-01", processingSession.deviceId)
        assertEquals("uid_nurse_1", processingSession.operatorUid)
        assertEquals("SITE-GQE-01", processingSession.siteId)
        assertEquals("GRANT-8899", processingSession.authorisationGrantId)
        assertEquals("2026-11-06T09:00:00Z", processingSession.startTimeIso)

        // 4. Second sync pass: report is now Ready -> transitions to ReportReady without re-uploading batches
        reportState = "Ready"
        val secondReport = coordinator.syncUserQueue("uid_nurse_1")
        assertFalse(secondReport.shouldRetry)
        assertEquals(1, secondReport.reportsReady)
        assertEquals(listOf(0, 1), uploadedSequences) // no duplicate batch uploads

        val readySession = store.getSessionByLocalId(localId)!!
        assertEquals(RecordingSyncStage.REPORT_READY, readySession.stage)
        assertTrue(readySession.reportReady)

        breakdown = store.getQueueStatusBreakdown("uid_nurse_1", "DEV-CYD-01")
        assertEquals(1, breakdown.reportReadyCount)
        assertEquals(0, breakdown.pendingCount)
        assertEquals(0, breakdown.processingCount)
    }

    @Test
    fun `interrupted upload and transient batch failure recover cleanly and retry in strict sequence order`() = runBlocking {
        val store = InMemoryOfflineQueueStore()
        val recordingUuid = "660e8400-e29b-41d4-a716-446655440111"
        val remoteId = "SESSION-660e8400e29b41d4a716446655440111"

        val localId = store.insertSession(
            CachedSession(
                remoteSessionId = remoteId,
                recordingId = recordingUuid,
                ownerUid = "uid_nurse_1",
                patientId = "PAT-2002",
                deviceId = "DEV-CYD-02",
                startTimeIso = "2026-11-06T10:00:00Z",
                status = "Completed",
                expectedBatchCount = 3,
                expectedSampleCount = 6,
                inFlightAtMillis = 123456789L // Simulate process killed mid-flight
            )
        )

        store.insertSampleBatch(
            CachedSampleBatch(
                remoteSessionId = remoteId,
                recordingId = recordingUuid,
                ownerUid = "uid_nurse_1",
                deviceId = "DEV-CYD-02",
                patientId = "PAT-2002",
                sequence = 0,
                firstSampleIndex = 0,
                samplesJson = "[2000,2001]",
                timestampIso = "2026-11-06T10:00:00Z",
                inFlightAtMillis = 123456789L // Interrupted mid-upload, but server actually received it
            )
        )
        store.insertSampleBatch(
            CachedSampleBatch(
                remoteSessionId = remoteId,
                recordingId = recordingUuid,
                ownerUid = "uid_nurse_1",
                deviceId = "DEV-CYD-02",
                patientId = "PAT-2002",
                sequence = 1,
                firstSampleIndex = 2,
                samplesJson = "[2002,2003]",
                timestampIso = "2026-11-06T10:00:01Z"
            )
        )
        store.insertSampleBatch(
            CachedSampleBatch(
                remoteSessionId = remoteId,
                recordingId = recordingUuid,
                ownerUid = "uid_nurse_1",
                deviceId = "DEV-CYD-02",
                patientId = "PAT-2002",
                sequence = 2,
                firstSampleIndex = 4,
                samplesJson = "[2004,2005]",
                timestampIso = "2026-11-06T10:00:02Z"
            )
        )

        var failSequenceOne = true
        val ingestedSequences = mutableListOf<Int>()
        val batch0Sha = store.getBatchesForSession(remoteId).first { it.sequence == 0 }.sha256

        val api = fakeApi { name, args ->
            when (name) {
                "getRecordingProgress" -> {
                    // Server already committed sequence 0 before the process restart
                    Response.success(
                        RecordingProgressDto(
                            sessionId = remoteId,
                            status = "Active",
                            receivedBatchCount = 1,
                            receivedSampleCount = 2,
                            batches = listOf(
                                RecordingBatchReferenceDto(
                                    sequence = 0,
                                    firstSampleIndex = 0,
                                    sampleCount = 2,
                                    sha256 = batch0Sha
                                )
                            )
                        )
                    )
                }
                "ingestTelemetry" -> {
                    val payload = args?.get(0) as IngestEcgPayloadDto
                    if (payload.sequence == 1 && failSequenceOne) {
                        Response.error<Map<String, Any>>(503, "Service Unavailable".toResponseBody("text/plain".toMediaType()))
                    } else {
                        ingestedSequences.add(payload.sequence)
                        Response.success(mapOf("persisted" to true))
                    }
                }
                "completeRecording" -> {
                    Response.success(
                        RecordingProgressDto(
                            sessionId = remoteId,
                            status = "Completed",
                            receivedBatchCount = 3,
                            receivedSampleCount = 6
                        )
                    )
                }
                "getSessionReportStatus" -> {
                    Response.success(
                        ReportStatusDto(
                            sessionId = remoteId,
                            patientId = "PAT-2002",
                            audience = "Staff",
                            state = "Ready",
                            reportAvailable = true,
                            fileName = "PulseLink_Report_$remoteId.pdf",
                            downloadPath = "/api/reports/session/$remoteId/pdf",
                            streamPath = "/api/reports/session/$remoteId/pdf",
                            authenticatedEmailLinkPath = "/api/reports/session/$remoteId/pdf"
                        )
                    )
                }
                else -> error("Unexpected API call: $name")
            }
        }

        val coordinator = OfflineSyncCoordinator(
            store = store,
            apiService = api,
            refreshToken = { "valid-jwt-token" },
            currentUid = { "uid_nurse_1" }
        )

        // First pass: recovers interrupted in-flight markers, reconciles sequence 0 from server progress,
        // attempts sequence 1 (which fails with 503), and halts before sequence 2 so sequence 2 is never uploaded out of order!
        val pass1 = coordinator.syncUserQueue("uid_nurse_1")
        assertEquals(2, pass1.recoveredCount)
        assertTrue(ingestedSequences.isEmpty())
        assertTrue(pass1.shouldRetry)
        assertTrue(store.getBatchesForSession(remoteId).first { it.sequence == 0 }.isSynced)
        assertFalse(store.getBatchesForSession(remoteId).first { it.sequence == 1 }.isSynced)
        assertFalse(store.getBatchesForSession(remoteId).first { it.sequence == 2 }.isSynced)

        // Second pass: sequence 1 and sequence 2 succeed in order, session completes and reaches ReportReady
        failSequenceOne = false
        val pass2 = coordinator.syncUserQueue("uid_nurse_1")
        assertFalse(pass2.shouldRetry)
        assertEquals(listOf(1, 2), ingestedSequences)
        assertEquals(RecordingSyncStage.REPORT_READY, store.getSessionByLocalId(localId)?.stage)
    }

    @Test
    fun `duplicate protection and conflict retention preserve original records and flag conflicts for review`() = runBlocking {
        val store = InMemoryOfflineQueueStore()
        val recordingUuid = "770e8400-e29b-41d4-a716-446655440222"
        val sessionId = "offline_$recordingUuid"

        val originalSession = CachedSession(
            remoteSessionId = sessionId,
            recordingId = recordingUuid,
            ownerUid = "uid_nurse_1",
            patientId = "PAT-3001",
            deviceId = "DEV-CYD-01",
            startTimeIso = "2026-11-06T11:00:00Z",
            status = "Completed"
        )
        val firstId = store.insertSession(originalSession)
        // Exact duplicate returns the same localId
        val duplicateId = store.insertSession(originalSession)
        assertEquals(firstId, duplicateId)

        // Conflicting session with same recordingId but different patientId is retained as Conflict
        val conflictSessionId = store.insertSession(
            originalSession.copy(patientId = "PAT-9999")
        )
        assertTrue(conflictSessionId != firstId)
        val conflicts = store.getConflictedSessions("uid_nurse_1")
        assertEquals(1, conflicts.size)
        assertEquals(409, conflicts.first().conflictCode)
        // Original session audit identity is untouched
        assertEquals("PAT-3001", store.getSessionByLocalId(firstId)?.patientId)

        // Batch duplicate protection and checksum conflict detection
        val batch0 = CachedSampleBatch(
            remoteSessionId = sessionId,
            recordingId = recordingUuid,
            ownerUid = "uid_nurse_1",
            deviceId = "DEV-CYD-01",
            patientId = "PAT-3001",
            sequence = 0,
            firstSampleIndex = 0,
            samplesJson = "[2048,2050,2052]",
            timestampIso = "2026-11-06T11:00:00Z"
        )
        val bId1 = store.insertSampleBatch(batch0)
        val bIdDup = store.insertSampleBatch(batch0)
        assertEquals(bId1, bIdDup)

        // Conflicting batch at sequence 0 with different samples marks conflict rather than overwriting
        store.insertSampleBatch(batch0.copy(samplesJson = "[3000,3001,3002]", sha256 = ""))
        assertEquals(RecordingSyncStage.CONFLICT, store.getSessionByLocalId(firstId)?.stage)
    }

    @Test
    fun `durable user-scoped notification inbox preserves read and deleted states across restarts and duplicate pushes`() {
        val store = InMemoryOfflineQueueStore()
        var currentUid: String? = "uid_alice"
        NotificationRepository.configureForTesting(store) { currentUid }

        val alertNotif = NotificationItem(
            notificationId = "NOTIF-101",
            title = "Clinical Report Ready",
            messagePreview = "Your assigned doctor released your ECG report.",
            timestampLabel = "Today, 12:30",
            isUnread = true,
            iconRes = 1,
            sessionId = "SESSION-101"
        )
        NotificationRepository.addNotification(alertNotif)
        assertEquals(1, NotificationRepository.notifications.value.size)
        assertTrue(NotificationRepository.notifications.value.first().isUnread)

        // Mark as read
        NotificationRepository.markAsRead("NOTIF-101")
        assertFalse(NotificationRepository.notifications.value.first().isUnread)

        // Duplicate push delivery with isUnread = true must NOT reset read state to unread
        NotificationRepository.addNotification(alertNotif.copy(isUnread = true))
        assertEquals(1, NotificationRepository.notifications.value.size)
        assertFalse(NotificationRepository.notifications.value.first().isUnread)

        // Sign out and switch to another user (uid_bob) -> Alice's inbox is not visible to Bob
        NotificationRepository.clear()
        assertTrue(NotificationRepository.notifications.value.isEmpty())
        currentUid = "uid_bob"
        NotificationRepository.bindUser("uid_bob")
        assertTrue(NotificationRepository.notifications.value.isEmpty())

        // Simulate process restart / re-login as Alice -> Alice's inbox and read state are restored
        currentUid = "uid_alice"
        NotificationRepository.bindUser("uid_alice")
        assertEquals(1, NotificationRepository.notifications.value.size)
        assertFalse(NotificationRepository.notifications.value.first().isUnread)

        // Delete notification -> tombstone prevents resurrection on duplicate push
        NotificationRepository.deleteNotification("NOTIF-101")
        assertTrue(NotificationRepository.notifications.value.isEmpty())
        NotificationRepository.addNotification(alertNotif)
        assertTrue(NotificationRepository.notifications.value.isEmpty())
        assertNull(NotificationRepository.getNotificationById("NOTIF-101"))
    }

    @Test
    fun `clinical notification sync populates doctor and patient recycler feeds with assessment prescription and advice alerts`() = runBlocking {
        val store = InMemoryOfflineQueueStore()
        var activeUid: String? = "uid_doctor_1"
        NotificationRepository.configureForTesting(store) { activeUid }

        val doctorApi = fakeApi { name, _ ->
            when (name) {
                "getClinicalNotifications" -> Response.success(
                    AccessPageDto(
                        items = listOf(
                            ClinicalNotificationDto(
                                notificationId = "N-DOC-1",
                                recipientUid = "uid_doctor_1",
                                type = "REPORT_READY",
                                patientId = "PAT-1001",
                                sessionId = "SESSION-900",
                                createdAtUtc = "2026-11-07T01:00:00Z",
                                patientName = "Sipho Dlamini"
                            )
                        ),
                        nextCursor = null
                    )
                )
                else -> error("Unexpected API call: $name")
            }
        }

        val pushedIds = mutableListOf<String>()
        val doctorFetched = ClinicalNotificationSync.fetchAllForRecipient(doctorApi, "uid_doctor_1") { activeUid }
        assertNotNull(doctorFetched)
        ClinicalNotificationSync.ingestFetchedItems(
            uid = "uid_doctor_1",
            items = doctorFetched!!,
            resolveText = { ClinicalNotificationSync.resolvePresentationWithStrings(it) },
            currentUidProvider = { activeUid },
            onNewUnreadNotification = { dto, _ -> pushedIds.add(dto.notificationId) }
        )

        assertEquals(listOf("N-DOC-1"), pushedIds)
        val doctorFeed = NotificationRepository.notifications.value
        assertEquals(1, doctorFeed.size)
        assertEquals("Report ready", doctorFeed.first().title)
        assertTrue(doctorFeed.first().messagePreview.contains("Sipho Dlamini"))

        // Switch to linked patient account and ingest released assessment, prescription, and follow-up advice
        activeUid = "uid_patient_1"
        NotificationRepository.bindUser("uid_patient_1")
        pushedIds.clear()

        val patientApi = fakeApi { name, _ ->
            when (name) {
                "getClinicalNotifications" -> Response.success(
                    AccessPageDto(
                        items = listOf(
                            ClinicalNotificationDto(
                                notificationId = "N-PAT-1",
                                recipientUid = "uid_patient_1",
                                type = "RESULTS_RELEASED",
                                patientId = "PAT-1001",
                                sessionId = "SESSION-900",
                                createdAtUtc = "2026-11-07T01:05:00Z",
                                doctorName = "Dr N. Khumalo"
                            ),
                            ClinicalNotificationDto(
                                notificationId = "N-PAT-2",
                                recipientUid = "uid_patient_1",
                                type = "PRESCRIPTION_ISSUED",
                                patientId = "PAT-1001",
                                sessionId = "SESSION-900",
                                createdAtUtc = "2026-11-07T01:05:01Z",
                                doctorName = "Dr N. Khumalo",
                                prescriptionSummary = "Bisoprolol 5 mg Once daily"
                            ),
                            ClinicalNotificationDto(
                                notificationId = "N-PAT-3",
                                recipientUid = "uid_patient_1",
                                type = "CLINICAL_ADVICE",
                                patientId = "PAT-1001",
                                sessionId = "SESSION-900",
                                createdAtUtc = "2026-11-07T01:05:02Z",
                                doctorName = "Dr N. Khumalo",
                                clinicalAdvice = "Return to clinic in 14 days for follow-up review."
                            )
                        ),
                        nextCursor = null
                    )
                )
                else -> error("Unexpected API call: $name")
            }
        }

        val patientFetched = ClinicalNotificationSync.fetchAllForRecipient(patientApi, "uid_patient_1") { activeUid }
        assertNotNull(patientFetched)
        ClinicalNotificationSync.ingestFetchedItems(
            uid = "uid_patient_1",
            items = patientFetched!!,
            resolveText = { ClinicalNotificationSync.resolvePresentationWithStrings(it) },
            currentUidProvider = { activeUid },
            onNewUnreadNotification = { dto, _ -> pushedIds.add(dto.notificationId) }
        )

        assertEquals(listOf("N-PAT-1", "N-PAT-2", "N-PAT-3"), pushedIds)
        val patientFeed = NotificationRepository.notifications.value
        assertEquals(3, patientFeed.size)
        assertTrue(patientFeed.any { it.title == "ECG assessment released" && it.messagePreview.contains("Dr N. Khumalo") })
        assertTrue(patientFeed.any { it.title == "Prescription updated" && it.messagePreview.contains("Bisoprolol 5 mg Once daily") })
        assertTrue(patientFeed.any { it.title == "Clinical follow-up guidance" && it.messagePreview.contains("Return to clinic in 14 days") })

        // Re-ingesting identical notifications does not re-fire OS push alerts
        pushedIds.clear()
        ClinicalNotificationSync.ingestFetchedItems(
            uid = "uid_patient_1",
            items = patientFetched,
            resolveText = { ClinicalNotificationSync.resolvePresentationWithStrings(it) },
            currentUidProvider = { activeUid },
            onNewUnreadNotification = { dto, _ -> pushedIds.add(dto.notificationId) }
        )
        assertTrue(pushedIds.isEmpty())
    }
}
