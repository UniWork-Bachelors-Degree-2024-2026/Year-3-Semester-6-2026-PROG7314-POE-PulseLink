using System.Globalization;

namespace PulseLinkAPI.Access;

public sealed record PublicFacilityRecord(
    string FacilityId,
    string Name,
    string FacilityType,
    string Area,
    string Address,
    double EntranceLatitude,
    double EntranceLongitude,
    string Source,
    string LastCheckedDate,
    string ValidatedGoogleMapsUrl
);

public sealed record PublicFacilityDirectoryResponse(
    IReadOnlyList<PublicFacilityRecord> Facilities,
    int TotalCount,
    string Municipality,
    string LastReviewedDate,
    string ClinicalDisclaimer
);

/// <summary>
/// Provides a reviewed directory of public clinics and hospitals in Gqeberha (Port Elizabeth),
/// Nelson Mandela Bay Metropolitan Municipality, Eastern Cape.
/// Does not claim fastest/nearest suitability, live opening hours, capacity, emergency dispatch
/// or offline turn-by-turn routing. Keeps all clinical information, credentials and patient
/// identifiers out of navigation URLs.
/// </summary>
public sealed class PublicFacilityDirectory
{
    public const string MunicipalityName = "Nelson Mandela Bay Metropolitan Municipality (Gqeberha / Port Elizabeth), Eastern Cape";
    public const string ReviewedDateIso = "2026-11-06";
    public const string DirectoryDisclaimer =
        "Reviewed public healthcare directory for Gqeberha / Port Elizabeth. Does not indicate fastest or nearest clinical suitability, live opening hours, bed or staff capacity, or emergency ambulance dispatch.";

    // Valid geographic bounding box for Nelson Mandela Bay / Eastern Cape destination entrance verification
    private const double MinLatitude = -35.0;
    private const double MaxLatitude = -32.0;
    private const double MinLongitude = 24.0;
    private const double MaxLongitude = 27.0;

    private static readonly IReadOnlyList<PublicFacilityRecord> SeededFacilities =
    [
        CreateSeeded(
            facilityId: "facility-ec-nmb-livingstone",
            name: "Livingstone Tertiary Hospital",
            facilityType: "Tertiary Hospital",
            area: "Korsten, Gqeberha",
            address: "Stanford Road, Korsten, Gqeberha, 6020",
            latitude: -33.925240,
            longitude: 25.569760,
            source: "Eastern Cape Department of Health / National Health Facility Directory",
            lastCheckedDate: ReviewedDateIso),
        CreateSeeded(
            facilityId: "facility-ec-nmb-dora-nginza",
            name: "Dora Nginza Provincial Hospital",
            facilityType: "Provincial Hospital",
            area: "Zwide (Ibhayi), Gqeberha",
            address: "Spondo Street, Zwide, Gqeberha, 6201",
            latitude: -33.877300,
            longitude: 25.560000,
            source: "Eastern Cape Department of Health / National Health Facility Directory",
            lastCheckedDate: ReviewedDateIso),
        CreateSeeded(
            facilityId: "facility-ec-nmb-pe-provincial",
            name: "Port Elizabeth Provincial Hospital",
            facilityType: "Regional Hospital",
            area: "Mount Croix, Gqeberha",
            address: "Gipson Street & Buckingham Road, Mount Croix, Gqeberha, 6001",
            latitude: -33.958300,
            longitude: 25.599190,
            source: "Eastern Cape Department of Health / National Health Facility Directory",
            lastCheckedDate: ReviewedDateIso),
        CreateSeeded(
            facilityId: "facility-ec-nmb-motherwell-chc",
            name: "Motherwell Community Health Centre (CHC)",
            facilityType: "Community Health Centre",
            area: "Motherwell (NU 8), Gqeberha",
            address: "81 Tynira Street, Motherwell, Gqeberha, 6211",
            latitude: -33.798700,
            longitude: 25.585900,
            source: "Eastern Cape Department of Health / National Health Facility Directory",
            lastCheckedDate: ReviewedDateIso),
        CreateSeeded(
            facilityId: "facility-ec-nmb-central-clinic",
            name: "Central Clinic (Gqeberha Central)",
            facilityType: "Public Primary Healthcare Clinic",
            area: "Gqeberha Central",
            address: "6 Rose Street, Gqeberha Central, Gqeberha, 6001",
            latitude: -33.964300,
            longitude: 25.613300,
            source: "Eastern Cape Department of Health / Nelson Mandela Bay Health District",
            lastCheckedDate: ReviewedDateIso),
        CreateSeeded(
            facilityId: "facility-ec-nmb-walmer-14th",
            name: "Walmer 14th Avenue Clinic",
            facilityType: "Public Primary Healthcare Clinic",
            area: "Walmer, Gqeberha",
            address: "14th Avenue, Walmer, Gqeberha, 6070",
            latitude: -33.975830,
            longitude: 25.585830,
            source: "Eastern Cape Department of Health / Nelson Mandela Bay Health District",
            lastCheckedDate: ReviewedDateIso),
        CreateSeeded(
            facilityId: "facility-ec-nmb-schauderville",
            name: "Schauderville Clinic",
            facilityType: "Public Primary Healthcare Clinic",
            area: "Schauderville, Gqeberha",
            address: "Corner of Highfield Street & Jan Hofmeyr Road, Schauderville, Gqeberha, 6060",
            latitude: -33.934800,
            longitude: 25.579400,
            source: "Eastern Cape Department of Health / Nelson Mandela Bay Health District",
            lastCheckedDate: ReviewedDateIso),
        CreateSeeded(
            facilityId: "facility-ec-nmb-new-brighton",
            name: "New Brighton Clinic",
            facilityType: "Public Primary Healthcare Clinic",
            area: "New Brighton, Gqeberha",
            address: "Mendi Road, New Brighton, Gqeberha, 6200",
            latitude: -33.904500,
            longitude: 25.592800,
            source: "Eastern Cape Department of Health / Nelson Mandela Bay Health District",
            lastCheckedDate: ReviewedDateIso)
    ];

    public PublicFacilityDirectoryResponse Search(string? query = null, string? facilityType = null)
    {
        IEnumerable<PublicFacilityRecord> results = SeededFacilities;

        if (!string.IsNullOrWhiteSpace(facilityType))
        {
            var cleanedType = facilityType.Trim();
            results = results.Where(f =>
                f.FacilityType.Contains(cleanedType, StringComparison.OrdinalIgnoreCase));
        }

        if (!string.IsNullOrWhiteSpace(query))
        {
            var cleanedQuery = query.Trim();
            results = results.Where(f =>
                f.Name.Contains(cleanedQuery, StringComparison.OrdinalIgnoreCase) ||
                f.Area.Contains(cleanedQuery, StringComparison.OrdinalIgnoreCase) ||
                f.Address.Contains(cleanedQuery, StringComparison.OrdinalIgnoreCase) ||
                f.FacilityType.Contains(cleanedQuery, StringComparison.OrdinalIgnoreCase));
        }

        var list = results.ToList();
        return new PublicFacilityDirectoryResponse(
            Facilities: list,
            TotalCount: list.Count,
            Municipality: MunicipalityName,
            LastReviewedDate: ReviewedDateIso,
            ClinicalDisclaimer: DirectoryDisclaimer);
    }

    public PublicFacilityRecord? GetById(string? facilityId)
    {
        if (string.IsNullOrWhiteSpace(facilityId))
        {
            return null;
        }

        var cleaned = facilityId.Trim();
        return SeededFacilities.FirstOrDefault(f =>
            string.Equals(f.FacilityId, cleaned, StringComparison.OrdinalIgnoreCase));
    }

    public static bool IsValidDestinationCoordinate(double latitude, double longitude)
    {
        if (double.IsNaN(latitude) || double.IsInfinity(latitude) ||
            double.IsNaN(longitude) || double.IsInfinity(longitude))
        {
            return false;
        }

        return latitude >= MinLatitude && latitude <= MaxLatitude &&
               longitude >= MinLongitude && longitude <= MaxLongitude;
    }

    /// <summary>
    /// Builds a validated Google Maps directions URL containing only the verified destination
    /// entrance coordinates, leaving Google Maps to handle origin and travel mode and ensuring
    /// zero clinical information, credentials or patient identifiers appear in the URL.
    /// </summary>
    public static string BuildValidatedGoogleMapsDirectionsUrl(double latitude, double longitude)
    {
        if (!IsValidDestinationCoordinate(latitude, longitude))
        {
            throw new ArgumentOutOfRangeException(
                nameof(latitude),
                "Destination coordinates are outside the verified Eastern Cape bounds.");
        }

        var latStr = latitude.ToString("F6", CultureInfo.InvariantCulture);
        var lngStr = longitude.ToString("F6", CultureInfo.InvariantCulture);
        return $"https://www.google.com/maps/dir/?api=1&destination={latStr},{lngStr}";
    }

    private static PublicFacilityRecord CreateSeeded(
        string facilityId,
        string name,
        string facilityType,
        string area,
        string address,
        double latitude,
        double longitude,
        string source,
        string lastCheckedDate)
    {
        var mapsUrl = BuildValidatedGoogleMapsDirectionsUrl(latitude, longitude);
        return new PublicFacilityRecord(
            FacilityId: facilityId,
            Name: name,
            FacilityType: facilityType,
            Area: area,
            Address: address,
            EntranceLatitude: latitude,
            EntranceLongitude: longitude,
            Source: source,
            LastCheckedDate: lastCheckedDate,
            ValidatedGoogleMapsUrl: mapsUrl);
    }
}
