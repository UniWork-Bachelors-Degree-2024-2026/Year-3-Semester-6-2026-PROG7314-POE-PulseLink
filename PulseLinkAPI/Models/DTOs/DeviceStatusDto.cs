 //Data Transfer Objects for ESP32-C3 hardware registration and live device status telemetry.

using System.ComponentModel.DataAnnotations;

namespace PulseLinkAPI.Models.DTOs;

// Payload to register and associate an ESP32-C3 hardware monitor with a patient account.
public class DeviceRegistrationDto
{
    [Required]
    public string DeviceId { get; set; } = string.Empty;

    [Required]
    public string PatientId { get; set; } = string.Empty;

    public string DeviceName { get; set; } = "PulseLink AD8232 Monitor";
    public string? MacAddress { get; set; }
}

// Real-time health, connection state, and telemetry of the ESP32-C3 device.
public class DeviceStatusDto
{
    public string DeviceId { get; set; } = string.Empty;
    public string PatientId { get; set; } = string.Empty;
    public string DeviceName { get; set; } = string.Empty;
    public bool IsOnline { get; set; }
    public bool LeadConnected { get; set; }
    public int EstimatedBpm { get; set; }
    public int AdaptiveThreshold { get; set; } = 2250;
    public int BatteryPercentage { get; set; } = 100;
    public string SignalQuality { get; set; } = "Good";
    public string FirmwareVersion { get; set; } = "1.0.0-poe";
    public DateTime LastHeartbeatUtc { get; set; }
}
