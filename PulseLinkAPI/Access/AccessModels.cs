using System.ComponentModel.DataAnnotations;

namespace PulseLinkAPI.Access;

public static class AccessRoles
{
    public const string Patient = "patient", Nurse = "nurse", Doctor = "doctor", Administrator = "administrator";
    public static bool IsStaff(string role) => role is Nurse or Doctor;
}

public sealed record VerifiedUser(string Uid, string Email, bool EmailVerified, string DisplayName);
public sealed record CurrentAccess(VerifiedUser User, AccessAccount Account);
public sealed record StoredValue<T>(T? Value, string ETag);
public sealed record AccessPage<T>(IReadOnlyList<T> Items, string? NextCursor);

public sealed class AccessAccount
{
    public bool EmailVerified { get; set; }
    public string Uid { get; set; } = "";
    public string Email { get; set; } = "";
    public string DisplayName { get; set; } = "";
    public string Role { get; set; } = AccessRoles.Patient;
    public string Status { get; set; } = "approved";
    public string? RequestedRole { get; set; }
    public List<string> SiteIds { get; set; } = [];
    public int Version { get; set; }
    public string? ApprovedByUid { get; set; }
}

public sealed class ClinicSite
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string? LocationDescription { get; set; }
    public double? Latitude { get; set; }
    public double? Longitude { get; set; }
    public bool Active { get; set; } = true;
}

public sealed class ClinicalPatient
{
    public string? LinkedAccountUid { get; set; }
    public string Id { get; set; } = "";
    public string FullName { get; set; } = "";
    public DateOnly? DateOfBirth { get; set; }
    public string SiteId { get; set; } = "";
    public string PrimaryDoctorUid { get; set; } = "";
    public List<string> AdditionalDoctorUids { get; set; } = [];
    public string RegisteredByUid { get; set; } = "";
    public DateTimeOffset RegisteredAtUtc { get; set; }
    public int Version { get; set; }
}

public sealed class SiteDevice
{
    public string DeviceId { get; set; } = "";
    public string SiteId { get; set; } = "";
    public bool Active { get; set; } = true;
}

public sealed class SiteRequest
{
    [Required, StringLength(120, MinimumLength = 2)]
    public string Name { get; set; } = "";

    [StringLength(250)]
    public string? LocationDescription { get; set; }

    [Range(-90, 90)]
    public double? Latitude { get; set; }

    [Range(-180, 180)]
    public double? Longitude { get; set; }
}

public sealed class ApprovalRequest
{
    [Required, RegularExpression("^(patient|nurse|doctor)$")]
    public string Role { get; set; } = "";

    [Required, RegularExpression("^(approved|suspended)$")]
    public string Status { get; set; } = "approved";

    [Required, MaxLength(50)]
    public List<string> SiteIds { get; set; } = [];

    [Range(0, int.MaxValue)]
    public int ExpectedVersion { get; set; }
}

public sealed class PatientRegistrationRequest
{
    public Guid? RegistrationId { get; set; }

    [Required, StringLength(160, MinimumLength = 2)] 
    public string FullName { get; set; } = "";
    public DateOnly? DateOfBirth { get; set; }

    [Required, StringLength(128)] 
    public string SiteId { get; set; } = "";

    [Required, StringLength(128)] 
    public string PrimaryDoctorUid { get; set; } = "";
}

public sealed class PatientAssignmentRequest
{
    [Required, StringLength(128)] 
    public string PrimaryDoctorUid { get; set; } = "";

    [Required, MaxLength(20)] 
    public List<string> AdditionalDoctorUids { get; set; } = [];
    
    [Range(1, int.MaxValue)] 
    public int ExpectedVersion { get; set; }
}

public sealed class RoleRequest
{
    [Required, RegularExpression("^(patient|nurse|doctor)$")] 
    public string RequestedRole { get; set; } = "";
}

public sealed class DeviceSiteRequest
{
    [Required, StringLength(128)] 
    public string SiteId { get; set; } = "";
    public bool Active { get; set; } = true;
}

public sealed class AccessProblem(int status, string message) : Exception(message)
{
    public int Status { get; } = status;
}
