//Service interface defining operations for processing and storing ECG telemetry batches
//captured by the AD8232 sensor and ESP32-C3 microcontroller.

using PulseLinkAPI.Models.DTOs;

namespace PulseLinkAPI.Services.Interfaces;

// Contract for managing real-time ECG telemetry and recording sessions.
public interface IEcgDataService
{
    Task<PulseLinkAPI.Access.SessionAccessContext?> GetSessionAccessContextAsync(string sessionId);
    Task<RecordingReceipt> IngestRecordingBatchAsync(IngestEcgPayloadDto payload);
    Task<RecordingProgress?> GetRecordingProgressAsync(string sessionId);
    Task<RecordingProgress?> CompleteRecordingAsync(string sessionId, CompleteRecordingRequest request);
    Task<RecordingBatchPage?> GetRecordingBatchesAsync(string sessionId, int afterSequence = -1, int limit = 20);
    Task<PulseLinkAPI.Access.AccessPage<EcgSessionSummaryDto>> GetPatientSessionPageAsync(string patientId, string? after = null, int limit = 20);
    // Processes an incoming batch of 250 Hz analogue ECG samples and estimated BPM values.
    Task<bool> IngestBatchAsync(IngestEcgPayloadDto payload);

    // Initialises a new remote ECG monitoring session for a patient.
    Task<StartSessionResponseDto> StartSessionAsync(StartSessionRequestDto request);

    // Finalises an active ECG recording session and computes summary metrics.
    Task<EcgSessionSummaryDto?> StopSessionAsync(string sessionId, int? recordedDurationSeconds = null);

    Task<bool> DiscardSessionAsync(string sessionId);

    Task<bool> SetSessionPausedAsync(string sessionId, bool paused);

    // Retrieves all historical ECG monitoring sessions for a specific patient.
    Task<List<EcgSessionSummaryDto>> GetPatientSessionsAsync(string patientId);

    // Retrieves high-frequency 250 Hz ECG sample points for oscilloscope waveform rendering.
    Task<EcgSessionSamplesResponseDto?> GetSessionSamplesAsync(string sessionId);

    // Registers and binds an ESP32-C3 hardware monitor to a patient account.
    Task<bool> RegisterDeviceAsync(DeviceRegistrationDto registration);

    // Retrieves live connection, lead detachment, and health telemetry for an ESP32-C3 device.
    Task<DeviceStatusDto?> GetDeviceStatusAsync(string deviceId);

    // Attaches an authorised doctor's clinical review notes to a recorded ECG session.
    Task<DoctorNoteDto> AddDoctorNoteAsync(CreateDoctorNoteDto request);

    // Retrieves all clinical assessment notes associated with an ECG recording session.
    Task<List<DoctorNoteDto>> GetDoctorNotesAsync(string sessionId);

    // Retrieves all active telemetry alerts (e.g. Lead detachment or elevated estimated BPM).
    Task<List<AlertDto>> GetActiveAlertsAsync();

    // Dismisses a specific active alert.
    Task<bool> DismissAlertAsync(string alertId);

    // Dismisses all active alerts.
    Task<bool> DismissAllAlertsAsync();

    // Compiles an authoritative clinical PDF session summary and stores it privately in Blob Storage.
    Task<byte[]?> GenerateSessionReportPdfAsync(string sessionId, bool forceRegenerate = false);

    // Retrieves or triggers the staff PDF report status for a session.
    Task<ReportStatusDto?> GetStaffReportStatusAsync(string sessionId, bool generateIfMissing = true);

    // Compiles and persists an isolated patient-released PDF snapshot from explicitly released notes and care instructions.
    Task<byte[]> GenerateReleasedPatientPdfAsync(string sessionId, IReadOnlyList<DoctorNoteDto> releasedNotes, string? releasedCareInstructions, DateTimeOffset releasedAtUtc);
}
