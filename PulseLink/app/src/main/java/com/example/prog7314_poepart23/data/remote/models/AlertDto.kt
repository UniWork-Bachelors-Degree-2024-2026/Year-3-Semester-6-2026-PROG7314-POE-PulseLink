/*
 * Data Transfer Objects for system telemetry alerts (e.g. Lead detachment or
 * elevated estimated heart rate threshold detections).
 *
 * Reference List:
 * - Google Developers, 2024. JSON in Android and Gson Library Guide. [online] Available at: <https://developer.android.com/> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.data.remote.models

import com.google.gson.annotations.SerializedName // (Google Developers, 2024)

// Telemetry alert record generated from incoming ECG stream analysis.
data class AlertDto(
    @SerializedName("alertId") // (Google Developers, 2024)
    val alertId: String = "",

    @SerializedName("patientId") // (Google Developers, 2024)
    val patientId: String = "",

    @SerializedName("patientName") // (Google Developers, 2024)
    val patientName: String? = null,

    @SerializedName("deviceId") // (Google Developers, 2024)
    val deviceId: String = "",

    @SerializedName("sessionId") // (Google Developers, 2024)
    val sessionId: String = "",

    @SerializedName("alertType") // (Google Developers, 2024)
    val alertType: String = "ElevatedHeartRate",

    @SerializedName("severity") // (Google Developers, 2024)
    val severity: String = "Warning",

    @SerializedName("message") // (Google Developers, 2024)
    val message: String = "",

    @SerializedName("estimatedBpm") // (Google Developers, 2024)
    val estimatedBpm: Int = 0,

    @SerializedName("timestampUtc") // (Google Developers, 2024)
    val timestampUtc: String = "",

    @SerializedName("isAcknowledged") // (Google Developers, 2024)
    val isAcknowledged: Boolean = false
)
