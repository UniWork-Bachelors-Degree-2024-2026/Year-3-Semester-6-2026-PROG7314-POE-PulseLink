/*
 * Data Transfer Objects for ESP32-C3 hardware registration and live device status telemetry.
 *
 * Reference List:
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End. [online] Available at: <https://www.analog.com/media/en/technical-documentation/data-sheets/AD8232.pdf> [Accessed 21 September 2026].
 * - Espressif Systems, 2023. ESP32-C3 Series Datasheet. [online] Available at: <https://www.espressif.com/sites/default/files/documentation/esp32-c3_datasheet_en.pdf> [Accessed 21 September 2026].
 * - Google Developers, 2024. JSON in Android and Gson Library Guide. [online] Available at: <https://developer.android.com/> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.data.remote.models

import com.google.gson.annotations.SerializedName // (Google Developers, 2024)

// Payload to register and associate an ESP32-C3 hardware monitor with a patient account. // (Espressif Systems, 2023)
data class DeviceRegistrationDto(
    @SerializedName("deviceId") // (Google Developers, 2024)
    val deviceId: String,

    @SerializedName("patientId") // (Google Developers, 2024)
    val patientId: String,

    @SerializedName("deviceName") // (Google Developers, 2024)
    val deviceName: String = "PulseLink AD8232 Monitor",

    @SerializedName("macAddress") // (Google Developers, 2024)
    val macAddress: String? = null
)

// Real-time health, connection state, and telemetry of the ESP32-C3 device. // (Analog Devices, 2020)
data class DeviceStatusDto(
    @SerializedName("deviceId") // (Google Developers, 2024)
    val deviceId: String = "",

    @SerializedName("patientId") // (Google Developers, 2024)
    val patientId: String = "",

    @SerializedName("deviceName") // (Google Developers, 2024)
    val deviceName: String = "",

    @SerializedName("isOnline") // (Google Developers, 2024)
    val isOnline: Boolean = false,

    @SerializedName("leadConnected") // (Google Developers, 2024; Analog Devices, 2020)
    val leadConnected: Boolean = true,

    @SerializedName("batteryPercentage") // (Google Developers, 2024)
    val batteryPercentage: Int = 100,

    @SerializedName("signalQuality") // (Google Developers, 2024)
    val signalQuality: String = "Good",

    @SerializedName("estimatedBpm") // (Google Developers, 2024)
    val estimatedBpm: Int = 0,

    @SerializedName("adaptiveThreshold") // (Google Developers, 2024)
    val adaptiveThreshold: Int = 2250,

    @SerializedName("firmwareVersion") // (Google Developers, 2024)
    val firmwareVersion: String = "1.0.0-poe",

    @SerializedName("lastHeartbeatUtc") // (Google Developers, 2024)
    val lastHeartbeatUtc: String = ""
) {
    // Evaluates telemetry recency from lastHeartbeatUtc (within 5 minutes) since backend historical
    // reads intentionally leave isOnline = false when querying cached state.
    fun hasRecentHeartbeat(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (lastHeartbeatUtc.isBlank() || lastHeartbeatUtc.startsWith("0001-")) {
            return false
        }
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val date = sdf.parse(lastHeartbeatUtc.take(19))
            if (date != null) {
                val ageMillis = nowMs - date.time
                ageMillis in -60_000L..300_000L
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isActuallyOnline(): Boolean = hasRecentHeartbeat() || isOnline

    fun isRegisteredDevice(): Boolean = deviceId.isNotBlank() && patientId.isNotBlank()
}
