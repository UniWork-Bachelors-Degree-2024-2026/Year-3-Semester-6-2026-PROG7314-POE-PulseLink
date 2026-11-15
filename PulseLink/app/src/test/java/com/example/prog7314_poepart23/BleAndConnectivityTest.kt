package com.example.prog7314_poepart23

import com.example.prog7314_poepart23.ble.BleBackgroundTransferCoordinator
import com.example.prog7314_poepart23.ble.BleConnectionStage
import com.example.prog7314_poepart23.ble.BleGattTransport
import com.example.prog7314_poepart23.ble.BleTransferResult
import com.example.prog7314_poepart23.ble.PulseLinkBleContract
import com.example.prog7314_poepart23.ble.TouchscreenApiState
import com.example.prog7314_poepart23.ble.TouchscreenBleStatus
import com.example.prog7314_poepart23.ble.TouchscreenWifiState
import com.example.prog7314_poepart23.data.local.InMemoryOfflineQueueStore
import com.example.prog7314_poepart23.data.local.models.RecordingSyncStage
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleAndConnectivityTest {

    @Test
    fun validatesTouchscreenNameAndPairingCode() {
        assertTrue(PulseLinkBleContract.isValidTouchscreenName("PulseLink-A1B2C3"))
        assertTrue(PulseLinkBleContract.isValidTouchscreenName("PulseLink-00ff9a"))
        assertFalse(PulseLinkBleContract.isValidTouchscreenName("ESP32-C3"))
        assertFalse(PulseLinkBleContract.isValidTouchscreenName("PulseLink-12345"))
        assertFalse(PulseLinkBleContract.isValidTouchscreenName(null))

        assertTrue(PulseLinkBleContract.isValidPairingCode("004219"))
        assertFalse(PulseLinkBleContract.isValidPairingCode("4219"))
        assertFalse(PulseLinkBleContract.isValidPairingCode("12345a"))
    }

    @Test
    fun parsesTouchscreenStatusMaintainingSeparateWifiApiAndUartStates() {
        val status = TouchscreenBleStatus.parse(
            "PulseLink;device=PulseLink-A1B2C3;c3_uart=ok;sd=ready;wifi=2;api=5;staff_transfer=unlocked;queued=3"
        )
        assertNotNull(status)
        assertEquals("PulseLink-A1B2C3", status!!.deviceId)
        assertTrue(status.c3UartOk)
        assertTrue(status.sdReady)
        // Wi-Fi associated (CONNECTED) does not imply API is READY (here API is UNAVAILABLE = 5)
        assertEquals(TouchscreenWifiState.CONNECTED, status.wifiState)
        assertEquals(TouchscreenApiState.UNAVAILABLE, status.apiState)
        assertTrue(status.staffTransferUnlocked)
        assertEquals(3, status.queuedRecordings)

        assertNull(TouchscreenBleStatus.parse("InvalidHeader;c3_uart=ok"))
    }

    @Test
    fun rejectsUnauthorisedOrRecordingBusyTransferAndResumesInterruptedBleTransferWithoutDuplicates() = runBlocking {
        val store = InMemoryOfflineQueueStore()
        var syncTriggered = 0
        val coordinator = BleBackgroundTransferCoordinator(store) { syncTriggered++ }

        val sessionId = "SESSION-0102030405060708090a0b0c0d0e0f10"
        var failSecondBatch = true

        val transport = object : BleGattTransport {
            override suspend fun readStatus(): String =
                "PulseLink;device=PulseLink-A1B2C3;c3_uart=ok;sd=ready;wifi=3;api=1;staff_transfer=unlocked;queued=1"

            override suspend fun writeControlAndReadChunk(commandJson: String): String? {
                val cmd = Gson().fromJson(commandJson, JsonObject::class.java)
                return when (cmd.get("op")?.asString) {
                    "auth" -> {
                        val reply = JsonObject()
                        if (cmd.get("grantId")?.asString == "grant-active" && cmd.get("nurseUid")?.asString == "nurse-1") {
                            reply.addProperty("status", "authorised")
                            reply.addProperty("staffTransfer", "unlocked")
                        } else {
                            reply.addProperty("status", "denied")
                            reply.addProperty("staffTransfer", "locked")
                        }
                        reply.toString()
                    }
                    "list" -> JsonObject().apply {
                        addProperty("status", "ok")
                        add("sessions", JsonArray().apply { add(sessionId) })
                    }.toString()
                    "manifest" -> JsonObject().apply {
                        addProperty("status", "manifest")
                        addProperty("sessionId", sessionId)
                        addProperty("recordingId", "01020304-0506-0708-090a-0b0c0d0e0f10")
                        addProperty("patientId", "PAT-400")
                        addProperty("patientName", "Nomsa Dlamini")
                        addProperty("deviceId", "PulseLink-A1B2C3")
                        addProperty("operatorUid", "nurse-1")
                        addProperty("siteId", "site-rural-1")
                        addProperty("grantId", "grant-active")
                        addProperty("bootId", "0000000000000065")
                        addProperty("startedAtUtc", "2026-11-06T12:00:00.000Z")
                        addProperty("durationSeconds", 90)
                        addProperty("expectedBatchCount", 2)
                        addProperty("expectedSampleCount", 6)
                    }.toString()
                    "batch" -> {
                        val seq = cmd.get("sequence").asInt
                        if (seq == 1 && failSecondBatch) return null // Simulate BLE link drop mid-transfer
                        val samples = if (seq == 0) listOf(2040, 2048, 2052) else listOf(2044, 2050, 2046)
                        JsonObject().apply {
                            addProperty("status", "batch")
                            addProperty("sessionId", sessionId)
                            addProperty("bootId", "0000000000000065")
                            addProperty("sequence", seq)
                            addProperty("firstSampleIndex", seq * 3)
                            addProperty("firstSampleTimestampUs", 1_000_000L + seq * 12_000L)
                            addProperty("sampleCount", 3)
                            addProperty("estimatedBpm", 72)
                            add("samples", JsonArray().apply { samples.forEach { add(it) } })
                        }.toString()
                    }
                    "ack" -> JsonObject().apply { addProperty("status", "ack_ok") }.toString()
                    "complete" -> JsonObject().apply { addProperty("status", "completed") }.toString()
                    else -> null
                }
            }
        }

        // 1. Wrong nurse grant is rejected before any recording list or data can be accessed.
        val denied = coordinator.syncCompletedRecordingsOverBle(transport, "wrong-grant", "nurse-1")
        assertTrue(denied is BleTransferResult.Failure)
        assertEquals(BleConnectionStage.REVOKED, (denied as BleTransferResult.Failure).stage)
        assertNull(store.getSessionByRemoteOrRecordingId(sessionId))

        // 2. Authorised nurse starts transfer, batch 0 succeeds, batch 1 drops connection.
        val interrupted = coordinator.syncCompletedRecordingsOverBle(transport, "grant-active", "nurse-1")
        assertTrue(interrupted is BleTransferResult.Failure)
        assertEquals(BleConnectionStage.TIMEOUT, (interrupted as BleTransferResult.Failure).stage)
        assertEquals(1, store.getBatchesForSession(sessionId).size)
        assertEquals(RecordingSyncStage.SAVED_LOCALLY, store.getSessionByRemoteOrRecordingId(sessionId)?.stage)
        assertEquals(0, syncTriggered)

        // 3. Reconnect and resume: batch 0 is not duplicated, batch 1 completes, session promotes to Pending and triggers sync.
        failSecondBatch = false
        val resumed = coordinator.syncCompletedRecordingsOverBle(transport, "grant-active", "nurse-1")
        assertTrue(resumed is BleTransferResult.Success)
        assertEquals(1, (resumed as BleTransferResult.Success).transferredSessions)
        assertEquals(1, resumed.transferredBatches) // Only the remaining batch 1 was transferred
        assertEquals(2, store.getBatchesForSession(sessionId).size)
        val completedSession = store.getSessionByRemoteOrRecordingId(sessionId)!!
        assertEquals(RecordingSyncStage.PENDING, completedSession.stage)
        assertEquals("PAT-400", completedSession.patientId)
        assertEquals("nurse-1", completedSession.operatorUid)
        assertEquals("site-rural-1", completedSession.siteId)
        assertEquals("grant-active", completedSession.authorisationGrantId)
        assertEquals(72, completedSession.avgBpm)
        assertEquals(1, syncTriggered)
    }
}
