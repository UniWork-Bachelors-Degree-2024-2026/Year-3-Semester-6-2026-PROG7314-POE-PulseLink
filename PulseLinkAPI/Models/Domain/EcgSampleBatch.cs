 //Domain model representing a discrete telemetry batch of 250 Hz analogue samples received from ESP32.

namespace PulseLinkAPI.Models.Domain;

// Domain model for an ingested batch of ECG waveform samples.
public class EcgSampleBatch
{
    public string BatchId { get; set; } = Guid.NewGuid().ToString();
    public string SessionId { get; set; } = string.Empty;
    public string DeviceId { get; set; } = string.Empty;
    public long HardwareTimestampMs { get; set; }
    public DateTime ReceivedAtUtc { get; set; } = DateTime.UtcNow;
    public int SampleRateHz { get; set; } = 250;
    public bool LeadConnected { get; set; }
    public int EstimatedBpm { get; set; }
    public string SignalQuality { get; set; } = "Good";
    public List<int> Samples { get; set; } = new();
}
