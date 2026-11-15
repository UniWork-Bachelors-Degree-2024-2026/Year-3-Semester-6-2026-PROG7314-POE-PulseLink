 //Data Transfer Objects for system telemetry alerts (e.g. Lead detachment or
 //elevated estimated heart rate threshold detections).

namespace PulseLinkAPI.Models.DTOs;

// Telemetry alert record generated from incoming ECG stream analysis.
public class AlertDto
{
    public string AlertId { get; set; } = string.Empty;
    public string PatientId { get; set; } = string.Empty;
    public string? PatientName { get; set; }
    public string DeviceId { get; set; } = string.Empty;
    public string SessionId { get; set; } = string.Empty;
    public string AlertType { get; set; } = "ElevatedHeartRate"; // "ElevatedHeartRate", "LowHeartRate", "LeadsDetached"
    public string Severity { get; set; } = "Warning"; // "Info", "Warning", "Critical"
    public string Message { get; set; } = string.Empty;
    public int EstimatedBpm { get; set; }
    public DateTime TimestampUtc { get; set; }
    public bool IsAcknowledged { get; set; }
}
