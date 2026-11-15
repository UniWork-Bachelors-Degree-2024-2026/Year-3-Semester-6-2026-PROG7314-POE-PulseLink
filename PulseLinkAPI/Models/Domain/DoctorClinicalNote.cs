// Domain entity representing clinical assessment notes attached to a completed ECG recording session.

using PulseLinkAPI.Access;

namespace PulseLinkAPI.Models.Domain;

// Domain model for doctor review notes and clinical recommendations.
public class DoctorClinicalNote
{
    public string NoteId { get; set; } = Guid.NewGuid().ToString();
    public string SessionId { get; set; } = string.Empty;
    public string PatientId { get; set; } = string.Empty;
    public string DoctorId { get; set; } = string.Empty;
    public string DoctorName { get; set; } = string.Empty;
    public string ClinicalImpression { get; set; } = string.Empty;
    public string? Recommendations { get; set; }
    public bool FollowUpRequired { get; set; }
    public string? FollowUpInstructions { get; set; }
    public int? FollowUpInDays { get; set; }
    public List<MedicationEntry> Prescriptions { get; set; } = [];
    public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;
}
