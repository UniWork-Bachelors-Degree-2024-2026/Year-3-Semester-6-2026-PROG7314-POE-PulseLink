//Service interface defining operations for persisting and retrieving private binary objects
//(e.g., compiled PDF ECG session reports) in Azure Blob Storage or local storage.

namespace PulseLinkAPI.Services.Interfaces;

// Contract for managing private Blob Storage persistence for ECG clinical reports.
public interface IBlobStorageService
{
    // Uploads a binary report file to private storage and returns its internal storage key.
    Task<string> UploadReportBlobAsync(string fileName, byte[] fileData, string contentType);

    // Retrieves a binary report file from private storage by its file name.
    Task<byte[]?> DownloadReportBlobAsync(string fileName);

    // Checks whether a persisted report blob is available in private storage.
    Task<bool> ReportBlobExistsAsync(string fileName) =>
        DownloadReportBlobAsync(fileName).ContinueWith(t => t.Result is { Length: > 0 }, TaskContinuationOptions.ExecuteSynchronously);
}
