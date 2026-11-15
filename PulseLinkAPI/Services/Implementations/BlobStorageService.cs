//Service implementing IBlobStorageService for persisting and retrieving compiled PDF reports
//privately in Azure Blob Storage (with local private disk storage when unconfigured).
//Never exposes public blob URLs or permanent SAS token URLs.

using Azure.Storage.Blobs;         // (Microsoft, 2024)
using Azure.Storage.Blobs.Models;  // (Microsoft, 2024)
using PulseLinkAPI.Access;
using PulseLinkAPI.Services.Interfaces;

namespace PulseLinkAPI.Services.Implementations;

// Stores and retrieves report PDF binary blobs privately in Azure Blob Storage. (Microsoft, 2024)
public class BlobStorageService : IBlobStorageService
{
    private readonly BlobContainerClient? _containerClient;
    private readonly string _storageDirectory;
    private readonly ILogger<BlobStorageService> _logger;

    public BlobStorageService(
        IConfiguration configuration,
        IWebHostEnvironment environment,
        ILogger<BlobStorageService> logger)
    {
        _logger = logger;
        _storageDirectory = Path.Combine(environment.ContentRootPath, "App_Data", "Reports");

        if (!Directory.Exists(_storageDirectory))
        {
            Directory.CreateDirectory(_storageDirectory);
        }

        var connectionString = configuration.GetConnectionString("AzureBlobStorage");
        var containerName = configuration["BlobStorage:ContainerName"] ?? "ecg-reports";

        if (!string.IsNullOrWhiteSpace(connectionString))
        {
            try
            {
                var blobServiceClient = new BlobServiceClient(connectionString);
                _containerClient = blobServiceClient.GetBlobContainerClient(containerName);
                _containerClient.CreateIfNotExists(PublicAccessType.None);

                _logger.LogInformation("[AZURE BLOB] Connected to private container: {ContainerName}", containerName);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "[AZURE BLOB] Failed to initialise Azure Blob Container. Operating in local private storage mode.");
                _containerClient = null;
            }
        }
        else
        {
            _logger.LogInformation("[AZURE BLOB] Connection string not configured. Operating in local private storage mode.");
        }
    }

    public static string ValidateReportFileName(string fileName) => SafeBlobFileName(fileName);

    private static string SafeBlobFileName(string fileName)
    {
        if (string.IsNullOrWhiteSpace(fileName))
        {
            throw new AccessProblem(400, "A valid report file name is required.");
        }

        var safe = Path.GetFileName(fileName.Trim());
        if (string.IsNullOrWhiteSpace(safe) ||
            safe != fileName.Trim() ||
            safe.Contains("..", StringComparison.Ordinal) ||
            !safe.EndsWith(".pdf", StringComparison.Ordinal))
        {
            throw new AccessProblem(400, "Invalid report blob identifier.");
        }

        return safe;
    }

    // Uploads a binary report file to private storage and returns its internal blob key.
    public async Task<string> UploadReportBlobAsync(string fileName, byte[] fileData, string contentType)
    {
        var safeFileName = SafeBlobFileName(fileName);
        if (fileData == null || fileData.Length == 0)
        {
            throw new AccessProblem(503, "Report generation produced an empty document.");
        }

        var persistedRemotely = false;
        if (_containerClient != null)
        {
            try
            {
                var blobClient = _containerClient.GetBlobClient(safeFileName);
                using var stream = new MemoryStream(fileData);

                var options = new BlobUploadOptions
                {
                    HttpHeaders = new BlobHttpHeaders { ContentType = string.IsNullOrWhiteSpace(contentType) ? "application/pdf" : contentType }
                };

                await blobClient.UploadAsync(stream, options);
                persistedRemotely = true;
                _logger.LogInformation("[AZURE BLOB] Uploaded private report blob: {FileName} ({Bytes} bytes)", safeFileName, fileData.Length);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "[AZURE BLOB] Cloud upload unavailable for: {FileName}. Persisting in local private storage.", safeFileName);
            }
        }

        try
        {
            var localFilePath = Path.Combine(_storageDirectory, safeFileName);
            await File.WriteAllBytesAsync(localFilePath, fileData);
        }
        catch (Exception ex) when (!persistedRemotely)
        {
            _logger.LogError(ex, "[BLOB] Failed to persist report blob in storage: {FileName}", safeFileName);
            throw new AccessProblem(503, "Unable to persist the ECG PDF report in private storage.");
        }

        return safeFileName;
    }

    // Retrieves a binary report file from private Azure Blob Storage (or local private storage).
    public async Task<byte[]?> DownloadReportBlobAsync(string fileName)
    {
        var safeFileName = SafeBlobFileName(fileName);

        if (_containerClient != null)
        {
            try
            {
                var blobClient = _containerClient.GetBlobClient(safeFileName);
                if (await blobClient.ExistsAsync())
                {
                    var downloadResult = await blobClient.DownloadContentAsync();
                    var contentBytes = downloadResult.Value.Content.ToArray();

                    if (contentBytes.Length > 0)
                    {
                        var localCachePath = Path.Combine(_storageDirectory, safeFileName);
                        await File.WriteAllBytesAsync(localCachePath, contentBytes);
                        return contentBytes;
                    }
                }
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "[AZURE BLOB] Failed to download private blob from Azure. Checking local private storage.");
            }
        }

        var localFilePath = Path.Combine(_storageDirectory, safeFileName);
        if (File.Exists(localFilePath))
        {
            var bytes = await File.ReadAllBytesAsync(localFilePath);
            if (bytes.Length > 0)
            {
                return bytes;
            }
        }

        _logger.LogWarning("[BLOB] Report blob not found: {FileName}", safeFileName);
        return null;
    }

    public async Task<bool> ReportBlobExistsAsync(string fileName)
    {
        var safeFileName = SafeBlobFileName(fileName);
        var localFilePath = Path.Combine(_storageDirectory, safeFileName);
        if (File.Exists(localFilePath) && new FileInfo(localFilePath).Length > 0)
        {
            return true;
        }

        if (_containerClient != null)
        {
            try
            {
                var blobClient = _containerClient.GetBlobClient(safeFileName);
                var exists = await blobClient.ExistsAsync();
                if (exists.Value) return true;
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "[AZURE BLOB] Failed to check private blob existence for: {FileName}", safeFileName);
            }
        }

        return false;
    }
}
