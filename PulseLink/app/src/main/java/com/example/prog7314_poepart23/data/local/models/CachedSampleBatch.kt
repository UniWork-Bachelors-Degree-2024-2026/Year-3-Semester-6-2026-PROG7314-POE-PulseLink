/*
 * Model representing high-frequency 250 Hz AD8232 analogue ECG sample batches
 * captured during an active recording session while in offline mode or via BLE transfer.
 *
 * Reference List:
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End. [online] Available at: <https://www.analog.com/media/en/technical-documentation/data-sheets/AD8232.pdf> [Accessed 21 September 2026].
 * - Google Developers, 2024. Save data in a local database using SQLite. [online] Available at: <https://developer.android.com/training/data-storage/sqlite> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.data.local.models

import java.security.MessageDigest
import java.util.Locale

data class CachedSampleBatch( // (Google Developers, 2024; Analog Devices, 2020)
    val localId: Long = 0,
    val remoteSessionId: String,
    val deviceId: String,
    val patientId: String,
    val samplesJson: String,
    val sampleRateHz: Int = 250,
    val leadsOff: Boolean = false,
    val estimatedBpm: Int = 72,
    val timestampIso: String,
    val isSynced: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    // Ordered retry, content-addressed duplicate protection, and conflict tracking fields
    val recordingId: String = CachedSession.extractOrCreateRecordingId(remoteSessionId),
    val ownerUid: String = patientId,
    val sequence: Int = 0,
    val firstSampleIndex: Int = 0,
    val sampleCount: Int = countSamples(samplesJson),
    val bootId: String = "boot_android_local",
    val sampleOffsetsUsJson: String? = null,
    val sampleFlagsJson: String? = null,
    val firstSampleTimestampUs: Long? = null,
    val signalQuality: String = normaliseSignalQuality(null, leadsOff),
    val sha256: String = computeBatchSha256(
        deviceId = deviceId,
        sequence = sequence,
        firstSampleIndex = firstSampleIndex,
        sampleRateHz = sampleRateHz,
        bootId = bootId,
        leadsOff = leadsOff,
        samplesJson = samplesJson
    ),
    val syncStage: String = if (isSynced) RecordingSyncStage.UPLOADED.wireValue else RecordingSyncStage.PENDING.wireValue,
    val retryCount: Int = 0,
    val lastAttemptAtMillis: Long = 0L,
    val lastError: String? = null,
    val conflictCode: Int? = null,
    val conflictReason: String? = null,
    val inFlightAtMillis: Long = 0L
) {
    val stage: RecordingSyncStage
        get() = RecordingSyncStage.fromWire(syncStage)

    companion object {
        fun normaliseSignalQuality(rawQuality: String?, leadsOff: Boolean): String {
            if (leadsOff) return "LeadsDisconnected"
            return when (rawQuality?.trim()) {
                "Good" -> "Good"
                "Degraded", "Poor" -> "Degraded"
                "LeadsDisconnected" -> "LeadsDisconnected"
                "Unknown" -> "Unknown"
                else -> "Good"
            }
        }

        fun countSamples(samplesJson: String): Int {
            val trimmed = samplesJson.trim().removePrefix("[").removeSuffix("]").trim()
            if (trimmed.isEmpty()) return 0
            return trimmed.split(',').count { it.trim().isNotEmpty() }
        }

        fun computeBatchSha256(
            deviceId: String,
            sequence: Int,
            firstSampleIndex: Int,
            sampleRateHz: Int,
            bootId: String,
            leadsOff: Boolean,
            samplesJson: String
        ): String {
            val canonicalSamples = samplesJson.filterNot { it.isWhitespace() }
            val input = "$deviceId|$sequence|$firstSampleIndex|$sampleRateHz|$bootId|$leadsOff|$canonicalSamples"
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
        }
    }
}
