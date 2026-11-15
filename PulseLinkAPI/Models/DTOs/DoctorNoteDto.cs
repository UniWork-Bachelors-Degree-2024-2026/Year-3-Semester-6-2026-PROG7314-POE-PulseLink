//Data Transfer Objects for clinical session assessment notes authored by authorised doctors.

using System.ComponentModel.DataAnnotations;
using PulseLinkAPI.Access;

namespace PulseLinkAPI.Models.DTOs;

// Payload submitted by an authorised doctor when evaluating a patient's recorded ECG session.
// DoctorId and DoctorName are always overwritten server-side from the authenticated doctor's access record.
public class CreateDoctorNoteDto
{
    [Required]
    public string SessionId { get; set; } = string.Empty;

    [Required]
    public string PatientId { get; set; } = string.Empty;

    public string DoctorId { get; set; } = string.Empty;

    public string DoctorName { get; set; } = string.Empty;

    [Required]
    [MaxLength(2000)]
    public string ClinicalImpression { get; set; } = string.Empty;

    [MaxLength(2000)]
    public string? Recommendations { get; set; }

    public bool FollowUpRequired { get; set; }

    [MaxLength(1000)]
    public string? FollowUpInstructions { get; set; }

    [Range(0, 365)]
    public int? FollowUpInDays { get; set; }

    [MaxLength(30)]
    public List<MedicationEntry> Prescriptions { get; set; } = [];
}

// Clinical note details returned to patients and healthcare practitioners.
public class DoctorNoteDto
{
    public string NoteId { get; set; } = string.Empty;
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
    public DateTime CreatedAtUtc { get; set; }
}
