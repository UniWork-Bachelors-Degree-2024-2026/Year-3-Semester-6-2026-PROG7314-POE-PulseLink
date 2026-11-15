 //Domain entity representing an ESP32-C3 hardware device registered to a patient account.

namespace PulseLinkAPI.Models.Domain;

// Domain model representing a registered ESP32-C3 physical monitor.
public class DeviceRecord
{
    public string DeviceId { get; set; } = string.Empty;
    public string PatientId { get; set; } = string.Empty;
    public string DeviceName { get; set; } = "PulseLink AD8232 Monitor";
    public string? MacAddress { get; set; }
    public string FirmwareVersion { get; set; } = "1.0.0-poe";
    public bool IsOnline { get; set; }
    public bool LeadConnected { get; set; }
    public int EstimatedBpm { get; set; }
    public int AdaptiveThreshold { get; set; } = 2250;
    public int BatteryPercentage { get; set; } = 100;
    public string SignalQuality { get; set; } = "Good";
    public DateTime RegisteredAtUtc { get; set; } = DateTime.UtcNow;
    public DateTime LastHeartbeatUtc { get; set; } = DateTime.UtcNow;
}
