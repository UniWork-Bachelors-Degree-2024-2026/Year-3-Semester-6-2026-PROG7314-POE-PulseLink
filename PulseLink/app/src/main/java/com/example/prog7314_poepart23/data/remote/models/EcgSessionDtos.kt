/*
 * Data Transfer Objects for ECG recording session lifecycle management,
 * summary telemetry, and high-resolution sample retrieval for mobile visualisation.
 *
 * Reference List:
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End. [online] Available at: <https://www.analog.com/media/en/technical-documentation/data-sheets/AD8232.pdf> [Accessed 21 September 2026].
 * - Google Developers, 2024. JSON in Android and Gson Library Guide. [online] Available at: <https://developer.android.com/> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.data.remote.models

import com.google.gson.annotations.SerializedName // (Google Developers, 2024)

// Request payload to initialise a new remote ECG recording session.
data class StartSessionRequestDto(
    @SerializedName("patientId") // (Google Developers, 2024)
    val patientId: String,

    @SerializedName("deviceId") // (Google Developers, 2024)
    val deviceId: String,

    @SerializedName("patientName") // (Google Developers, 2024)
    val patientName: String? = null,

    @SerializedName("recordingId")
    val recordingId: String? = null
)

// Request payload to finalise a recording session with expected batch and sample counts.
data class CompleteRecordingRequestDto(
    @SerializedName("expectedBatchCount")
    val expectedBatchCount: Int,

    @SerializedName("expectedSampleCount")
    val expectedSampleCount: Int,

    @SerializedName("recordedDurationSeconds")
    val recordedDurationSeconds: Int? = null
)

data class RecordingBatchReferenceDto(
    @SerializedName("sequence")
    val sequence: Int = 0,

    @SerializedName("firstSampleIndex")
    val firstSampleIndex: Int = 0,

    @SerializedName("sampleCount")
    val sampleCount: Int = 0,

    @SerializedName("sha256")
    val sha256: String = "",

    @SerializedName("bootId")
    val bootId: String = ""
)

data class RecordingProgressDto(
    @SerializedName("sessionId")
    val sessionId: String = "",

    @SerializedName("status")
    val status: String = "",

    @SerializedName("revision")
    val revision: Long = 0L,

    @SerializedName("receivedBatchCount")
    val receivedBatchCount: Int = 0,

    @SerializedName("receivedSampleCount")
    val receivedSampleCount: Int = 0,

    @SerializedName("expectedBatchCount")
    val expectedBatchCount: Int? = null,

    @SerializedName("expectedSampleCount")
    val expectedSampleCount: Int? = null,

    @SerializedName("missingSequences")
    val missingSequences: List<Int> = emptyList(),

    @SerializedName("hasSampleGaps")
    val hasSampleGaps: Boolean = false,

    @SerializedName("batches")
    val batches: List<RecordingBatchReferenceDto> = emptyList()
)

// Response payload returned when an ECG session is successfully initialised.
data class StartSessionResponseDto(
    @SerializedName("sessionId") // (Google Developers, 2024)
    val sessionId: String = "",

    @SerializedName("patientId") // (Google Developers, 2024)
    val patientId: String = "",

    @SerializedName("deviceId") // (Google Developers, 2024)
    val deviceId: String = "",

    @SerializedName("startTimeUtc") // (Google Developers, 2024)
    val startTimeUtc: String = "",

    @SerializedName("status") // (Google Developers, 2024)
    val status: String = "Active"
)

// Request payload to conclude an active ECG recording session.
data class StopSessionRequestDto(
    @SerializedName("sessionId") // (Google Developers, 2024)
    val sessionId: String
)

// Summary metrics for an ECG recording session utilised by history lists and doctor reviews.
data class EcgSessionSummaryDto(
    @SerializedName("sessionId") // (Google Developers, 2024)
    val sessionId: String = "",

    @SerializedName("patientId") // (Google Developers, 2024)
    val patientId: String = "",

    @SerializedName("patientName") // (Google Developers, 2024)
    val patientName: String? = null,

    @SerializedName("deviceId") // (Google Developers, 2024)
    val deviceId: String = "",

    @SerializedName("startTimeUtc") // (Google Developers, 2024)
    val startTimeUtc: String = "",

    @SerializedName("endTimeUtc") // (Google Developers, 2024)
    val endTimeUtc: String? = null,

    @SerializedName("durationSeconds") // (Google Developers, 2024)
    val durationSeconds: Int = 0,

    @SerializedName("averageBpm") // (Google Developers, 2024)
    val averageBpm: Int = 0,

    @SerializedName("minBpm") // (Google Developers, 2024)
    val minBpm: Int = 0,

    @SerializedName("maxBpm") // (Google Developers, 2024)
    val maxBpm: Int = 0,

    @SerializedName("totalSamplesCount") // (Google Developers, 2024)
    val totalSamplesCount: Int = 0,

    @SerializedName("signalQuality") // (Google Developers, 2024)
    val signalQuality: String = "Unknown",

    @SerializedName("status") // (Google Developers, 2024)
    val status: String = "Completed",

    @SerializedName("hasDoctorNotes") // (Google Developers, 2024)
    val hasDoctorNotes: Boolean = false,
    val resultsReleased: Boolean = false,
    val hasUnreleasedDrafts: Boolean = false,
    val reportAvailable: Boolean = false
)

/**
 * Full sample waveform response for rendering the oscilloscope strip chart on the mobile canvas.
 */
data class EcgSessionSamplesResponseDto(
    @SerializedName("sessionId") // (Google Developers, 2024)
    val sessionId: String = "",

    @SerializedName("sampleRateHz") // (Google Developers, 2024; Analog Devices, 2020)
    val sampleRateHz: Int = 250,

    @SerializedName("totalSamples") // (Google Developers, 2024)
    val totalSamples: Int = 0,

    @SerializedName("samples") // (Google Developers, 2024)
    val samples: List<Int> = emptyList(),

    @SerializedName("retrievedAtUtc") // (Google Developers, 2024)
    val retrievedAtUtc: String = ""
)
