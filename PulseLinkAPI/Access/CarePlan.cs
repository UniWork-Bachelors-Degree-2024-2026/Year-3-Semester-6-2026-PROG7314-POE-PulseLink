using System.ComponentModel.DataAnnotations;

namespace PulseLinkAPI.Access;

public sealed class CarePlan
{
    [StringLength(500)]
    public string PrimaryCondition { get; set; } = "";

    [StringLength(500)]
    public string SecondaryStatus { get; set; } = "";

    [Required, MaxLength(30)]
    public List<MedicationEntry> Medications { get; set; } = [];

    [StringLength(4000)]
    public string ClinicalInstructions { get; set; } = "";
    public string UpdatedAtUtc { get; set; } = "";
    public string UpdatedByDoctor { get; set; } = "";
}
public sealed class MedicationEntry
{
    [Required, StringLength(160)]
    public string Name { get; set; } = "";
    [StringLength(160)]
    public string Dosage { get; set; } = "";

    [StringLength(160)]
    public string Frequency { get; set; } = "";
}
