//Main entry point and service configuration for the PulseLink REST API backend.
//Initialises application routing, cross-origin resource sharing (CORS), OpenAPI
//generation, and Scalar interactive API documentation.

//This backend is strictly designed to handle AD8232 ECG analogue signal batches,
// estimated BPM calculations, device connection telemetry, and clinical review notes.

using PulseLinkAPI.Services.Implementations;
using PulseLinkAPI.Services.Interfaces;
using Scalar.AspNetCore; // (Scalar, 2025)
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using PulseLinkAPI.Access;
using Microsoft.OpenApi.Models;
using PulseLinkAPI.Transport;

var builder = WebApplication.CreateBuilder(args); // (Microsoft, 2024)

// Service registration

// Register API Controllers for modular request routing (Microsoft, 2024)
builder.Services.AddControllers(options =>
{
    options.Filters.Add<AccessExceptionFilter>();
    options.Filters.Add<ClinicalAccessFilter>();
});
builder.Services.AddSingleton<FirebaseRuntime>();
builder.Services.AddSingleton<IFirebaseTokenVerifier>(services => services.GetRequiredService<FirebaseRuntime>());
builder.Services.AddSingleton<IFirebaseAccessTokenSource>(services => services.GetRequiredService<FirebaseRuntime>());
builder.Services.AddHttpClient<IAccessStore, FirebaseAccessStore>();
builder.Services.AddScoped<AccessDirectory>();
builder.Services.AddSingleton<PublicFacilityDirectory>();
builder.Services.AddScoped<PatientPortal>();
builder.Services.AddScoped<ClinicalNotifications>();
builder.Services.AddScoped<ClinicalNotificationDispatcher>();
builder.Services.AddSingleton<IClinicalPushSender, FirebaseClinicalPushSender>();
builder.Services.AddHostedService<ClinicalNotificationWorker>();
builder.Services.AddScoped<DoctorChartService>();
builder.Services.AddScoped<DeviceCredentials>();
builder.Services.AddScoped<DeviceRecordingAccess>();
builder.Services.AddScoped<DeviceEvents>();
builder.Services.AddScoped<NurseWorkflow>();
builder.Services.AddAuthentication(FirebaseBearerHandler.SchemeName)
    .AddScheme<AuthenticationSchemeOptions, FirebaseBearerHandler>(FirebaseBearerHandler.SchemeName, _ => { })
    .AddScheme<AuthenticationSchemeOptions, DeviceAuthenticationHandler>(DeviceAuthenticationHandler.SchemeName, _ => { });
builder.Services.AddAuthorization(options => options.FallbackPolicy = new AuthorizationPolicyBuilder()
    .RequireAuthenticatedUser().Build());

// Register OpenAPI specification services (Microsoft, 2024)
builder.Services.AddOpenApi(options =>
{
    options.AddDocumentTransformer((document, context, ct) =>
    {
        document.Components ??= new OpenApiComponents();
        document.Components.SecuritySchemes ??= new Dictionary<string, OpenApiSecurityScheme>();
        document.Components.SecuritySchemes[FirebaseBearerHandler.SchemeName] = new OpenApiSecurityScheme
        {
            Type = SecuritySchemeType.Http,
            Scheme = "bearer",
            BearerFormat = "Firebase ID token"
        };
        document.Components.SecuritySchemes[DeviceAuthenticationHandler.SchemeName] = new OpenApiSecurityScheme
        {
            Type = SecuritySchemeType.ApiKey,
            In = ParameterLocation.Header,
            Name = "Authorization",
            Description = "Device identifier:token, prefixed with Device and a space."
        };
        return Task.CompletedTask;
    });
    options.AddOperationTransformer((operation, context, ct) =>
    {
        if (!context.Description.ActionDescriptor.EndpointMetadata.OfType<IAllowAnonymous>().Any())
        {
            operation.Security ??= [];
            var scheme = context.Description.ActionDescriptor.EndpointMetadata.OfType<AuthorizeAttribute>()
                .Any(a => a.AuthenticationSchemes == DeviceAuthenticationHandler.SchemeName)
                ? DeviceAuthenticationHandler.SchemeName : FirebaseBearerHandler.SchemeName;
            operation.Security.Add(new OpenApiSecurityRequirement
            {
                [new OpenApiSecurityScheme
                {
                    Reference = new OpenApiReference
                    {
                        Type = ReferenceType.SecurityScheme,
                        Id = scheme
                    }
                }] = []
            });
        }
        return Task.CompletedTask;
    });
});

// Register HTML-to-PDF report generator service
builder.Services.AddSingleton<IPdfReportGenerator, PdfReportGenerator>();

// Register Blob Storage persistence service (Microsoft, 2024)
builder.Services.AddSingleton<IBlobStorageService, BlobStorageService>();

// Per-request services use persistent manifests and immutable Firebase chunks.
builder.Services.AddScoped<IEcgDataService, EcgDataService>();

// Configure Cross-Origin Resource Sharing (CORS) to allow testing from local development environments (Mozilla, 2024)
builder.Services.AddCors(options =>
{
    options.AddPolicy("ConfiguredOrigins", policy =>
    {
        var origins = builder.Configuration.GetSection("Cors:AllowedOrigins").Get<string[]>() ?? [];
        if (origins.Length > 0) policy.WithOrigins(origins).AllowAnyMethod().AllowAnyHeader();
    });
});
var app = builder.Build();

// HTTP request pipeline configuration

// Enable OpenAPI and Scalar documentation in development and production environments
app.MapOpenApi().AllowAnonymous();
app.MapScalarApiReference(options => options.WithPreferredScheme(FirebaseBearerHandler.SchemeName)).AllowAnonymous();

// Enable CORS policy
app.UseCors("ConfiguredOrigins");
// Enable HTTPS redirection for secure communication (IETF, 2000)
app.UseHttpsRedirection();

app.UseAuthentication();
app.UseAuthorization();

// Enable Controller endpoint mapping
app.MapControllers();

// Root Redirect to Scalar API Reference
// (Scalar, 2025)
app.MapGet("/", () => Results.Redirect("/scalar/v1")).AllowAnonymous();

app.Run();

public partial class Program { }
