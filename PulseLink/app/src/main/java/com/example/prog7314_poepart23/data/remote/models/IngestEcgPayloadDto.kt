/*
 * Data Transfer Object representing the high-frequency ECG telemetry payload
 * transmitted over Wi-Fi from the ESP32-C3 Super Mini microcontroller or Android sync worker.
 *
 * Sensor: AD8232 Single-Lead Heart Rate Monitor (Lead I Analogue Front-End)
 * ADC Resolution: 12-bit (0 - 4095) sampled at a cadence of 250 Hz.
 *
 * Reference List:
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End. [online] Available at: <https://www.analog.com/media/en/technical-documentation/data-sheets/AD8232.pdf> [Accessed 21 September 2026].
 * - Espressif Systems, 2023. ESP32-C3 Series Datasheet. [online] Available at: <https://www.espressif.com/sites/default/files/documentation/esp32-c3_datasheet_en.pdf> [Accessed 21 September 2026].
 * - Google Developers, 2024. JSON in Android and Gson Library Guide. [online] Available at: <https://developer.android.com/> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.data.remote.models

import com.google.gson.annotations.SerializedName // (Google Developers, 2024)

// Telemetry ingestion payload transmitted by the ESP32-C3 hardware or offline sync engine. // (Espressif Systems, 2023; Analog Devices, 2020)
data class IngestEcgPayloadDto(
    @SerializedName("deviceId") // (Google Developers, 2024)
    val deviceId: String = "",

    @SerializedName("sessionId") // (Google Developers, 2024)
    val sessionId: String = "",

    @SerializedName("firmwareVer") // (Google Developers, 2024)
    val firmwareVer: String = "1.0.0-poe",

    @SerializedName("timestamp") // (Google Developers, 2024)
    val timestamp: Long = 0L,

    @SerializedName("sampleRateHz") // (Google Developers, 2024; Analog Devices, 2020)
    val sampleRateHz: Int = 250,

    @SerializedName("leadConnected") // (Google Developers, 2024; Analog Devices, 2020)
    val leadConnected: Boolean = true,

    @SerializedName("estimatedBpm") // (Google Developers, 2024)
    val estimatedBpm: Int = 0,

    @SerializedName("signalQuality") // (Google Developers, 2024)
    val signalQuality: String = "Good",

    @SerializedName("samples") // (Google Developers, 2024)
    val samples: List<Int> = emptyList(),

    @SerializedName("schemaVersion")
    val schemaVersion: Int = 2,

    @SerializedName("bootId")
    val bootId: String = "boot_android_local",

    @SerializedName("sequence")
    val sequence: Int = 0,

    @SerializedName("firstSampleIndex")
    val firstSampleIndex: Int = 0,

    @SerializedName("capturedAtUtc")
    val capturedAtUtc: String? = null,

    @SerializedName("patientId")
    val patientId: String? = null,

    @SerializedName("patientName")
    val patientName: String? = null,

    @SerializedName("adaptiveThreshold")
    val adaptiveThreshold: Int = 2250,

    @SerializedName("firstSampleTimestampUs")
    val firstSampleTimestampUs: Long? = null,

    @SerializedName("sampleOffsetsUs")
    val sampleOffsetsUs: List<Int>? = null,

    @SerializedName("sampleFlags")
    val sampleFlags: List<Int>? = null
)
