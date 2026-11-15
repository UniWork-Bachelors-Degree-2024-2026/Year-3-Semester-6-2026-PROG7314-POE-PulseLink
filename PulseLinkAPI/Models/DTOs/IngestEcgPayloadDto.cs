//Data Transfer Object representing the high-frequency ECG telemetry batch transmitted
//over Wi-Fi from the ESP32-C3 Super Mini microcontroller.

//Sensor: AD8232 Single-Lead Heart Rate Monitor (Lead I Analogue Front-End)
//ADC Resolution: 12-bit (0 - 4095) sampled at 250 Hz

using System.ComponentModel.DataAnnotations;
using System.Text.Json.Serialization;

namespace PulseLinkAPI.Models.DTOs;

// Telemetry ingestion payload transmitted by the ESP32-C3 firmware.
public class IngestEcgPayloadDto
{
    [Range(2, 2)]
    public int SchemaVersion { get; set; } = 2;

    [Required, RegularExpression("^[A-Za-z0-9_-]{1,64}$")]
    public string BootId { get; set; } = "";

    [Required, Range(0, 599)]
    public int? Sequence { get; set; }

    [Required, Range(0, 149999)]
    public int? FirstSampleIndex { get; set; }

    // Optional UTC estimate for the first sample; never inferred from upload time.
    public DateTimeOffset? CapturedAtUtc { get; set; }
    // Unique hardware identifier programmed into the ESP32 microcontroller.
    [Required]
    [JsonPropertyName("deviceId")]
    public string DeviceId { get; set; } = string.Empty;

    // Unique recording session identifier associated with the patient.
    [Required]
    [JsonPropertyName("sessionId")]
    public string SessionId { get; set; } = string.Empty;

    [JsonPropertyName("patientId")]
    public string? PatientId { get; set; }

    [JsonPropertyName("patientName")]
    public string? PatientName { get; set; }

    // Embedded firmware version string (e.g., 1.0.0-poe).
    [JsonPropertyName("firmwareVer")]
    public string FirmwareVer { get; set; } = string.Empty;

    // Milliseconds elapsed since the ESP32 microcontroller booted.
    [JsonPropertyName("timestamp")]
    [Range(0, long.MaxValue)] public long Timestamp { get; set; }

    // Analogue sampling frequency in Hertz (standardised to 250 Hz).
    [JsonPropertyName("sampleRateHz")]
    [Range(250, 250)] public int SampleRateHz { get; set; } = 250;

    // Reports the LO+/LO- indication; does not prove correct placement or all contacts.
    [JsonPropertyName("leadConnected")]
    public bool LeadConnected { get; set; }

    // Real-time calculated heart rate estimate derived from detected R-peaks.
    // Note: This is a calculated estimate and does not constitute a clinical diagnosis.
    [Range(0, 250)]
    [JsonPropertyName("estimatedBpm")]
    public int EstimatedBpm { get; set; }

    // Dynamic adaptive R-peak amplitude threshold tracked by the firmware.
    [JsonPropertyName("adaptiveThreshold")]
    public int AdaptiveThreshold { get; set; } = 2250;

    // Qualitative signal integrity indicator ("Good", "Degraded", or "LeadsDisconnected").
    [JsonPropertyName("signalQuality")]
    public string SignalQuality { get; set; } = "Good";

    // Raw 12-bit ADC samples (0-4095). Derived display filtering must not replace these.
    [JsonPropertyName("samples")]
    [Required, MinLength(1), MaxLength(1000)]
    public List<int> Samples { get; set; } = new();

    [JsonPropertyName("firstSampleTimestampUs"), JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? FirstSampleTimestampUs { get; set; }

    [JsonPropertyName("sampleOffsetsUs"), JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<int>? SampleOffsetsUs { get; set; }

    [JsonPropertyName("sampleFlags"), JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<byte>? SampleFlags { get; set; }
}
