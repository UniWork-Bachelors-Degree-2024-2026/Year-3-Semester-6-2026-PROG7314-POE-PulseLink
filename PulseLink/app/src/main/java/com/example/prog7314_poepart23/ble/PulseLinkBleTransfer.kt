package com.example.prog7314_poepart23.ble

import com.example.prog7314_poepart23.data.local.OfflineQueueStore
import com.example.prog7314_poepart23.data.local.models.CachedSampleBatch
import com.example.prog7314_poepart23.data.local.models.CachedSession
import com.example.prog7314_poepart23.data.local.models.RecordingSyncStage
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.utils.LocaleHelper

/**
 * BLE and connectivity protocol models and background recording transfer coordinator.
 *
 * Architectural guardrails:
 * 1. BLE pairs the ESP32-2432S028R touchscreen with Android, never the C3 with the touchscreen (which uses wired UART).
 * 2. Android never streams live ECG waveforms or live BPM over BLE.
 * 3. Touchscreen Wi-Fi, API reachability, C3 UART, microSD, and BLE security states are represented separately.
 */
object PulseLinkBleContract {
    const val SERVICE_UUID = "b6f0a100-5664-4fb0-9f96-6f60754c4e4b"
    const val STATUS_CHAR_UUID = "b6f0a101-5664-4fb0-9f96-6f60754c4e4b"
    const val CONTROL_CHAR_UUID = "b6f0a102-5664-4fb0-9f96-6f60754c4e4b"
    const val CHUNK_CHAR_UUID = "b6f0a103-5664-4fb0-9f96-6f60754c4e4b"

    private val DEVICE_NAME_REGEX = Regex("^PulseLink-[0-9A-Fa-f]{6}$")
    private val PAIRING_CODE_REGEX = Regex("^[0-9]{6}$")

    fun isValidTouchscreenName(name: String?): Boolean =
        !name.isNullOrBlank() && DEVICE_NAME_REGEX.matches(name.trim())

    fun isValidPairingCode(code: String?): Boolean =
        !code.isNullOrBlank() && PAIRING_CODE_REGEX.matches(code.trim())
}

enum class TouchscreenWifiState { NOT_CONFIGURED, CONNECTING, CONNECTED, DISCONNECTED, UNKNOWN }
enum class TouchscreenApiState { NOT_CONFIGURED, WAITING_FOR_WIFI, WAITING_FOR_CLOCK, READY, UNAUTHORISED, UNAVAILABLE, UNKNOWN }
enum class BleConnectionStage { OFF, DISCONNECTED, SCANNING, PAIRING, BONDED_RECONNECTING, SECURED_LOCKED, SECURED_AUTHORISED, TRANSFERRING, REVOKED, TIMEOUT, FAILED }

data class TouchscreenBleStatus(
    val deviceId: String,
    val c3UartOk: Boolean,
    val sdReady: Boolean,
    val wifiState: TouchscreenWifiState,
    val apiState: TouchscreenApiState,
    val staffTransferUnlocked: Boolean,
    val queuedRecordings: Int
) {
    companion object {
        fun parse(raw: String?): TouchscreenBleStatus? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.trim().split(";")
            if (parts.firstOrNull() != "PulseLink") return null
            val fields = mutableMapOf<String, String>()
            for (i in 1 until parts.size) {
                val kv = parts[i].split("=", limit = 2)
                if (kv.size == 2) fields[kv[0].trim()] = kv[1].trim()
            }
            val wifiCode = fields["wifi"]?.toIntOrNull()
            val apiCode = fields["api"]?.toIntOrNull()
            return TouchscreenBleStatus(
                deviceId = fields["device"].orEmpty(),
                c3UartOk = fields["c3_uart"] == "ok",
                sdReady = fields["sd"] == "ready",
                wifiState = when (wifiCode) {
                    0 -> TouchscreenWifiState.NOT_CONFIGURED
                    1 -> TouchscreenWifiState.CONNECTING
                    2 -> TouchscreenWifiState.CONNECTED
                    3 -> TouchscreenWifiState.DISCONNECTED
                    else -> TouchscreenWifiState.UNKNOWN
                },
                apiState = when (apiCode) {
                    0 -> TouchscreenApiState.NOT_CONFIGURED
                    1 -> TouchscreenApiState.WAITING_FOR_WIFI
                    2 -> TouchscreenApiState.WAITING_FOR_CLOCK
                    3 -> TouchscreenApiState.READY
                    4 -> TouchscreenApiState.UNAUTHORISED
                    5 -> TouchscreenApiState.UNAVAILABLE
                    else -> TouchscreenApiState.UNKNOWN
                },
                staffTransferUnlocked = fields["staff_transfer"] == "unlocked",
                queuedRecordings = fields["queued"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            )
        }
    }
}

/**
 * Interface abstracting the encrypted/authenticated GATT characteristic operations on the touchscreen,
 * allowing deterministic unit testing alongside Android BluetoothGatt execution.
 */
interface BleGattTransport {
    suspend fun readStatus(): String?
    suspend fun writeControlAndReadChunk(commandJson: String): String?
}

sealed class BleTransferResult {
    data class Success(val transferredSessions: Int, val transferredBatches: Int) : BleTransferResult()
    data class Failure(val stage: BleConnectionStage, val reason: String) : BleTransferResult()
}

/**
 * Coordinates authorised background recording transfer from the touchscreen over BLE into the local
 * SQLite offline queue store without ever streaming live ECG or live BPM to Android.
 */
class BleBackgroundTransferCoordinator(
    private val queueStore: OfflineQueueStore,
    private val onSyncReady: () -> Unit = {}
) {
    private fun JsonObject.str(key: String, default: String = ""): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: default

    private fun JsonObject.int(key: String, default: Int = 0): Int =
        runCatching { get(key)?.takeIf { !it.isJsonNull }?.asInt ?: default }.getOrDefault(default)

    private fun JsonObject.long(key: String, default: Long = 0L): Long =
        runCatching { get(key)?.takeIf { !it.isJsonNull }?.asLong ?: default }.getOrDefault(default)

    private val gson = Gson()

    private fun parseJson(raw: String?): JsonObject? =
        runCatching { gson.fromJson(raw ?: "", JsonObject::class.java) }.getOrNull()

    suspend fun syncCompletedRecordingsOverBle(
        transport: BleGattTransport,
        grantId: String,
        nurseUid: String
    ): BleTransferResult {
        if (grantId.isBlank() || nurseUid.isBlank()) {
            return BleTransferResult.Failure(BleConnectionStage.REVOKED, LocaleHelper.stringOr(R.string.rt_ble_shift_required, "Nurse shift authorisation is required."))
        }

        // 1. Authorise staff transfer against the touchscreen's active shift grant.
        val authCmd = JsonObject().apply {
            addProperty("op", "auth")
            addProperty("grantId", grantId.trim())
            addProperty("nurseUid", nurseUid.trim())
        }.toString()
        val authReplyRaw = transport.writeControlAndReadChunk(authCmd)
            ?: return BleTransferResult.Failure(BleConnectionStage.TIMEOUT, LocaleHelper.stringOr(R.string.rt_ble_auth_timeout, "Touchscreen did not respond to BLE authorisation."))
        val authReply = parseJson(authReplyRaw)
            ?: return BleTransferResult.Failure(BleConnectionStage.FAILED, LocaleHelper.stringOr(R.string.rt_ble_auth_invalid, "Invalid BLE authorisation reply."))
        val authStatus = authReply.str("status")
        if (authStatus == "busy") {
            return BleTransferResult.Failure(BleConnectionStage.SECURED_LOCKED, LocaleHelper.stringOr(R.string.rt_ble_recording_busy, "Touchscreen is recording. Wait for capture to finish."))
        }
        if (authStatus != "authorised" || authReply.str("staffTransfer") != "unlocked") {
            return BleTransferResult.Failure(BleConnectionStage.REVOKED, LocaleHelper.stringOr(R.string.rt_ble_shift_mismatch, "Nurse shift does not match this touchscreen."))
        }

        // 2. Request list of completed (.ready) clinical sessions awaiting transfer.
        val listCmd = JsonObject().apply { addProperty("op", "list") }.toString()
        val listReplyRaw = transport.writeControlAndReadChunk(listCmd)
            ?: return BleTransferResult.Failure(BleConnectionStage.TIMEOUT, LocaleHelper.stringOr(R.string.rt_ble_list_timeout, "Timed out reading queued recordings from touchscreen."))
        val listReply = parseJson(listReplyRaw)
            ?: return BleTransferResult.Failure(BleConnectionStage.FAILED, LocaleHelper.stringOr(R.string.rt_ble_list_invalid, "Invalid recording list from touchscreen."))
        if (listReply.str("status") != "ok") {
            return BleTransferResult.Failure(BleConnectionStage.FAILED, listReply.str("reason", LocaleHelper.stringOr(R.string.rt_ble_list_failed, "Could not list recordings.")))
        }

        val sessionsJson = listReply.getAsJsonArray("sessions") ?: JsonArray()
        var completedSessions = 0
        var totalTransferredBatches = 0

        for (i in 0 until sessionsJson.size()) {
            val sessionId = sessionsJson.get(i)?.asString.orEmpty()
            if (!sessionId.startsWith("SESSION-")) continue

            // Check if we already have partial batches for this session in SQLite so we resume cleanly.
            val existingBatches = queueStore.getBatchesForSession(sessionId)
            val resumeSequence = existingBatches.size

            val manifestCmd = JsonObject().apply {
                addProperty("op", "manifest")
                addProperty("sessionId", sessionId)
                addProperty("resumeSequence", resumeSequence)
            }.toString()
            val manifestRaw = transport.writeControlAndReadChunk(manifestCmd)
                ?: return BleTransferResult.Failure(BleConnectionStage.TIMEOUT, LocaleHelper.stringOr(R.string.rt_ble_manifest_interrupted, "Interrupted while requesting recording manifest."))
            val manifest = parseJson(manifestRaw)
                ?: return BleTransferResult.Failure(BleConnectionStage.FAILED, LocaleHelper.stringOr(R.string.rt_ble_manifest_corrupt, "Corrupt recording manifest."))
            if (manifest.str("status") != "manifest") {
                return BleTransferResult.Failure(BleConnectionStage.FAILED, manifest.str("reason", LocaleHelper.stringOr(R.string.rt_ble_manifest_rejected, "Manifest rejected.")))
            }

            val recordingId = manifest.str("recordingId")
            val patientId = manifest.str("patientId")
            val patientName = manifest.str("patientName")
            val deviceId = manifest.str("deviceId")
            val operatorUid = manifest.str("operatorUid", nurseUid)
            val siteId = manifest.str("siteId")
            val manifestGrantId = manifest.str("grantId", grantId)
            val bootId = manifest.str("bootId")
            val startedAtUtc = manifest.str("startedAtUtc")
            val durationSeconds = manifest.int("durationSeconds", 90)
            val expectedBatches = manifest.int("expectedBatchCount", 0)
            val expectedSamples = manifest.int("expectedSampleCount", 0)

            if (patientId.isBlank() || expectedBatches <= 0 || expectedSamples <= 0) {
                return BleTransferResult.Failure(BleConnectionStage.FAILED, LocaleHelper.stringOr(R.string.rt_ble_manifest_incomplete, "Incomplete clinical metadata in recording manifest."))
            }

            // Persist or update the session in SQLite with its immutable recording identity.
            queueStore.insertSession(
                CachedSession(
                    remoteSessionId = sessionId,
                    recordingId = recordingId.ifBlank { CachedSession.extractOrCreateRecordingId(sessionId) },
                    ownerUid = operatorUid,
                    patientId = patientId,
                    patientName = patientName,
                    deviceId = deviceId,
                    operatorUid = operatorUid,
                    siteId = siteId,
                    authorisationGrantId = manifestGrantId,
                    bootId = bootId,
                    startTimeIso = startedAtUtc,
                    endTimeIso = null,
                    avgBpm = 0,
                    durationSeconds = durationSeconds,
                    expectedBatchCount = expectedBatches,
                    expectedSampleCount = expectedSamples,
                    status = "Recording",
                    isSynced = false,
                    syncStage = RecordingSyncStage.SAVED_LOCALLY.wireValue
                )
            )

            var totalBpmSum = 0
            var bpmBatchCount = 0

            for (seq in resumeSequence until expectedBatches) {
                val batchCmd = JsonObject().apply {
                    addProperty("op", "batch")
                    addProperty("sessionId", sessionId)
                    addProperty("sequence", seq)
                }.toString()
                val batchRaw = transport.writeControlAndReadChunk(batchCmd)
                    ?: return BleTransferResult.Failure(BleConnectionStage.TIMEOUT, LocaleHelper.stringOr(R.string.rt_ble_transfer_interrupted, "BLE transfer interrupted at batch %1\$s. Progress retained.", seq))
                val batchJson = parseJson(batchRaw)
                    ?: return BleTransferResult.Failure(BleConnectionStage.FAILED, LocaleHelper.stringOr(R.string.rt_ble_batch_corrupt, "Corrupt batch payload at sequence %1\$s.", seq))
                if (batchJson.str("status") != "batch" || batchJson.int("sequence", -1) != seq) {
                    return BleTransferResult.Failure(BleConnectionStage.FAILED, LocaleHelper.stringOr(R.string.rt_ble_batch_mismatch, "Batch sequence mismatch at %1\$s.", seq))
                }

                val samplesArray = batchJson.getAsJsonArray("samples") ?: JsonArray()
                val rawSamples = ArrayList<Int>(samplesArray.size())
                for (s in 0 until samplesArray.size()) {
                    rawSamples.add(samplesArray.get(s).asInt)
                }
                if (rawSamples.isEmpty()) {
                    return BleTransferResult.Failure(BleConnectionStage.FAILED, LocaleHelper.stringOr(R.string.rt_ble_batch_empty, "Empty sample batch at sequence %1\$s.", seq))
                }
                val samplesJson = rawSamples.joinToString(",", prefix = "[", postfix = "]")
                val firstSampleIndex = batchJson.int("firstSampleIndex", seq * 250)
                val firstTimestampUs = batchJson.long("firstSampleTimestampUs", 0L)
                val estimatedBpm = batchJson.int("estimatedBpm", 0)
                if (estimatedBpm > 0) {
                    totalBpmSum += estimatedBpm
                    bpmBatchCount++
                }
                val batchBootId = batchJson.str("bootId", bootId)
                val digest = CachedSampleBatch.computeBatchSha256(
                    deviceId = deviceId,
                    sequence = seq,
                    firstSampleIndex = firstSampleIndex,
                    sampleRateHz = 250,
                    bootId = batchBootId,
                    leadsOff = false,
                    samplesJson = samplesJson
                )
                queueStore.insertSampleBatch(
                    CachedSampleBatch(
                        remoteSessionId = sessionId,
                        recordingId = recordingId.ifBlank { CachedSession.extractOrCreateRecordingId(sessionId) },
                        ownerUid = operatorUid,
                        deviceId = deviceId,
                        patientId = patientId,
                        bootId = batchBootId,
                        sequence = seq,
                        firstSampleIndex = firstSampleIndex,
                        sampleCount = rawSamples.size,
                        firstSampleTimestampUs = firstTimestampUs,
                        sampleRateHz = 250,
                        samplesJson = samplesJson,
                        sha256 = digest,
                        estimatedBpm = estimatedBpm,
                        signalQuality = "Good",
                        leadsOff = false,
                        timestampIso = startedAtUtc,
                        isSynced = false,
                        syncStage = RecordingSyncStage.SAVED_LOCALLY.wireValue
                    )
                )

                val ackCmd = JsonObject().apply {
                    addProperty("op", "ack")
                    addProperty("sessionId", sessionId)
                    addProperty("sequence", seq)
                }.toString()
                val ackRaw = transport.writeControlAndReadChunk(ackCmd)
                    ?: return BleTransferResult.Failure(BleConnectionStage.TIMEOUT, LocaleHelper.stringOr(R.string.rt_ble_ack_lost, "Lost BLE acknowledgement at batch %1\$s.", seq))
                val ackJson = parseJson(ackRaw)
                if (ackJson?.str("status") != "ack_ok") {
                    return BleTransferResult.Failure(BleConnectionStage.FAILED, LocaleHelper.stringOr(R.string.rt_ble_ack_rejected, "Touchscreen rejected batch acknowledgement at %1\$s.", seq))
                }
                totalTransferredBatches++
            }

            // Finalise BLE session transfer on the touchscreen and promote local SQLite rows to Pending upload.
            val completeCmd = JsonObject().apply {
                addProperty("op", "complete")
                addProperty("sessionId", sessionId)
                addProperty("expectedBatchCount", expectedBatches)
                addProperty("expectedSampleCount", expectedSamples)
            }.toString()
            val completeRaw = transport.writeControlAndReadChunk(completeCmd)
                ?: return BleTransferResult.Failure(BleConnectionStage.TIMEOUT, LocaleHelper.stringOr(R.string.rt_ble_receipt_timeout, "Timed out completing BLE recording receipt."))
            val completeJson = parseJson(completeRaw)
            if (completeJson?.str("status") != "completed") {
                return BleTransferResult.Failure(BleConnectionStage.FAILED, LocaleHelper.stringOr(R.string.rt_ble_receipt_failed, "Touchscreen could not finalise BLE transfer receipt."))
            }

            val avgBpm = if (bpmBatchCount > 0) totalBpmSum / bpmBatchCount else 0
            queueStore.queueSessionForUpload(
                remoteSessionId = sessionId,
                avgBpm = avgBpm,
                durationSeconds = durationSeconds,
                endTimeIso = startedAtUtc,
                expectedBatchCount = expectedBatches,
                expectedSampleCount = expectedSamples
            )
            completedSessions++
        }

        if (completedSessions > 0) {
            onSyncReady()
        }
        return BleTransferResult.Success(completedSessions, totalTransferredBatches)
    }
}
