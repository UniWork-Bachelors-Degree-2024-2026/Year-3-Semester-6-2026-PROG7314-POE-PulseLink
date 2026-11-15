using System.Net;
using System.Net.Http.Json;
using PulseLinkAPI.Access;
using Xunit;

namespace PulseLinkAPI.Tests;

public sealed class FacilityDirectoryTests
{
    [Fact]
    public async Task Public_facilities_directory_supports_patient_and_nurse_search_with_verified_coordinates_and_clean_maps_urls()
    {
        using var app = new AccessTestApplication();

        using var patientClient = app.As("pat1");
        var allResponse = await patientClient.GetFromJsonAsync<PublicFacilityDirectoryResponse>("/api/facilities");
        Assert.NotNull(allResponse);
        Assert.Equal(8, allResponse!.TotalCount);
        Assert.Equal(8, allResponse.Facilities.Count);
        Assert.Contains("Gqeberha", allResponse.Municipality);
        Assert.Equal("2026-11-06", allResponse.LastReviewedDate);
        Assert.Contains("Does not indicate fastest or nearest clinical suitability", allResponse.ClinicalDisclaimer);

        foreach (var facility in allResponse.Facilities)
        {
            Assert.False(string.IsNullOrWhiteSpace(facility.FacilityId));
            Assert.False(string.IsNullOrWhiteSpace(facility.Name));
            Assert.False(string.IsNullOrWhiteSpace(facility.FacilityType));
            Assert.False(string.IsNullOrWhiteSpace(facility.Area));
            Assert.False(string.IsNullOrWhiteSpace(facility.Address));
            Assert.False(string.IsNullOrWhiteSpace(facility.Source));
            Assert.Equal("2026-11-06", facility.LastCheckedDate);
            Assert.True(PublicFacilityDirectory.IsValidDestinationCoordinate(facility.EntranceLatitude, facility.EntranceLongitude));
            Assert.StartsWith("https://www.google.com/maps/dir/?api=1&destination=", facility.ValidatedGoogleMapsUrl);
            Assert.DoesNotContain("PAT-", facility.ValidatedGoogleMapsUrl, StringComparison.OrdinalIgnoreCase);
            Assert.DoesNotContain("token", facility.ValidatedGoogleMapsUrl, StringComparison.OrdinalIgnoreCase);
            Assert.DoesNotContain("origin=", facility.ValidatedGoogleMapsUrl, StringComparison.OrdinalIgnoreCase);
            Assert.DoesNotContain("travelmode=", facility.ValidatedGoogleMapsUrl, StringComparison.OrdinalIgnoreCase);
        }

        // Nurse search by area/suburb and type
        using var nurseClient = app.As("nurse1");
        var korstenResults = await nurseClient.GetFromJsonAsync<PublicFacilityDirectoryResponse>("/api/facilities?query=Korsten");
        Assert.NotNull(korstenResults);
        Assert.Single(korstenResults!.Facilities);
        Assert.Equal("Livingstone Tertiary Hospital", korstenResults.Facilities[0].Name);

        var clinicResults = await nurseClient.GetFromJsonAsync<PublicFacilityDirectoryResponse>("/api/facilities?type=Clinic");
        Assert.NotNull(clinicResults);
        Assert.Equal(4, clinicResults!.TotalCount);

        // Single facility lookup and invalid destination check
        var dora = await patientClient.GetFromJsonAsync<PublicFacilityRecord>("/api/facilities/facility-ec-nmb-dora-nginza");
        Assert.NotNull(dora);
        Assert.Equal("Dora Nginza Provincial Hospital", dora!.Name);

        var missing = await patientClient.GetAsync("/api/facilities/facility-invalid-id");
        Assert.Equal(HttpStatusCode.NotFound, missing.StatusCode);
    }

    [Fact]
    public void Rejects_out_of_bounds_or_invalid_destination_coordinates()
    {
        Assert.False(PublicFacilityDirectory.IsValidDestinationCoordinate(0.0, 0.0));
        Assert.False(PublicFacilityDirectory.IsValidDestinationCoordinate(-26.2041, 28.0473)); // Johannesburg
        Assert.False(PublicFacilityDirectory.IsValidDestinationCoordinate(double.NaN, 25.56976));
        Assert.Throws<ArgumentOutOfRangeException>(() =>
            PublicFacilityDirectory.BuildValidatedGoogleMapsDirectionsUrl(0.0, 0.0));
    }
}
