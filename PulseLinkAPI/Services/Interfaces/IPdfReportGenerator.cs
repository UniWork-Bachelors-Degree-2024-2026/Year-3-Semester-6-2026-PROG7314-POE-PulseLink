 //Service interface for generating formatted HTML clinical ECG session summaries
 //and converting them into downloadable PDF documents.

using PulseLinkAPI.Models.Domain;

namespace PulseLinkAPI.Services.Interfaces;

// Contract for HTML-to-PDF clinical ECG report generation.
public interface IPdfReportGenerator
{
    // Generates a clinical Lead I ECG PDF report from session data and attached doctor notes.
    byte[] GenerateSessionPdf(EcgSession session, List<DoctorClinicalNote>? doctorNotes);
}
