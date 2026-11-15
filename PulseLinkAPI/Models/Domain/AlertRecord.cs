 //Domain entity representing a physiological threshold warning or hardware detachment alert.

namespace PulseLinkAPI.Models.Domain;

// Domain model for system alerts.
public class AlertRecord
{
    public string AlertId { get; set; } = Guid.NewGuid().ToString();
    public string PatientId { get; set; } = string.Empty;
    public string? PatientName { get; set; }
    public string DeviceId { get; set; } = string.Empty;
    public string SessionId { get; set; } = string.Empty;
    public string AlertType { get; set; } = "ElevatedHeartRate"; // "ElevatedHeartRate", "LowHeartRate", "LeadsDetached"
    public string Severity { get; set; } = "Warning"; // "Info", "Warning", "Critical"
    public string Message { get; set; } = string.Empty;
    public int EstimatedBpm { get; set; }
    public DateTime TimestampUtc { get; set; } = DateTime.UtcNow;
    public bool IsAcknowledged { get; set; }
}
