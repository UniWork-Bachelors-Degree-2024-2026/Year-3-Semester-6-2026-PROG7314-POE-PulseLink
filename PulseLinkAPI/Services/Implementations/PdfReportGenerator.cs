// Cross-platform PDF 1.4 generator rendering landscape single-lead AD8232 ECG reports.
// Supports standard A4 landscape for short recordings and extended A3 landscape for full
// 90-second recordings to ensure maximum horizontal time-base and vertical amplitude accuracy.
// Uses native PDF vector graphics and Standard Type1 fonts (Helvetica/Helvetica-Bold) so deployment
// on Azure App Service (Linux x64) and Windows requires no platform-specific browser binaries.

using System.Globalization;
using System.Text;
using PulseLinkAPI.Access;
using PulseLinkAPI.Models.Domain;
using PulseLinkAPI.Services.Interfaces;

namespace PulseLinkAPI.Services.Implementations;

public class PdfReportGenerator : IPdfReportGenerator
{
    private readonly ILogger<PdfReportGenerator> _logger;

    private const double StandardA4Width = 841.89;   // A4 landscape width in points (297 mm)
    private const double StandardA4Height = 595.28;  // A4 landscape height in points (210 mm)
    private const double ExtendedA3Width = 1190.55;  // Extended A3 landscape width in points (420 mm)
    private const double ExtendedA3Height = 841.89;  // Extended A3 landscape height in points (297 mm)
    private const double Margin = 28.0;
    private const int SamplesPerStrip = 2500;  // 10 seconds at 250 Hz per strip
    private const int StripsPerPage = 3;       // Up to 30 seconds per landscape page

    public PdfReportGenerator(ILogger<PdfReportGenerator> logger)
    {
        _logger = logger;
    }

    public byte[] GenerateSessionPdf(EcgSession session, List<DoctorClinicalNote>? doctorNotes)
    {
        ArgumentNullException.ThrowIfNull(session);
        if (string.IsNullOrWhiteSpace(session.SessionId))
        {
            throw new AccessProblem(400, "A valid session identifier is required to generate a PDF report.");
        }

        try
        {
            var notes = doctorNotes ?? [];
            var isExtended = session.DurationSeconds > 60 || session.SampleBuffer.Count > 15000;
            var pageWidth = isExtended ? ExtendedA3Width : StandardA4Width;
            var pageHeight = isExtended ? ExtendedA3Height : StandardA4Height;
            var pages = BuildReportPages(session, notes, pageWidth, pageHeight, isExtended);
            var pdfBytes = CompilePdfDocument(pages, pageWidth, pageHeight);

            if (pdfBytes.Length < 8 ||
                pdfBytes[0] != (byte)'%' ||
                pdfBytes[1] != (byte)'P' ||
                pdfBytes[2] != (byte)'D' ||
                pdfBytes[3] != (byte)'F')
            {
                throw new InvalidOperationException("Compiled report output did not start with a valid PDF header.");
            }

            _logger.LogInformation(
                "[PDF] Generated {PaperFormat} landscape ECG report for Session: {SessionId} ({Pages} pages, {Bytes} bytes)",
                isExtended ? "extended A3" : "A4",
                session.SessionId, pages.Count, pdfBytes.Length);
            return pdfBytes;
        }
        catch (AccessProblem)
        {
            throw;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "[PDF] Failed to render authoritative PDF for session: {SessionId}", session.SessionId);
            throw new AccessProblem(503, "Unable to generate the ECG PDF report at this time.");
        }
    }

    private static List<string> BuildReportPages(
        EcgSession session,
        List<DoctorClinicalNote> notes,
        double pageWidth,
        double pageHeight,
        bool isExtended)
    {
        var sampleRate = session.SampleRateHz > 0 ? session.SampleRateHz : 250;
        var samplesPerStrip = sampleRate * 10; // 10-second strips
        var totalSamples = session.SampleBuffer.Count;
        var stripCount = totalSamples == 0 ? 1 : (int)Math.Ceiling((double)totalSamples / samplesPerStrip);
        var waveformPagesCount = (int)Math.Ceiling((double)stripCount / StripsPerPage);

        // Determine if an extra page is needed when there are many clinical notes, prescriptions, or care instructions.
        var hasExtendedNotes = notes.Count > 2 ||
                               !string.IsNullOrWhiteSpace(session.ReleasedCareInstructions) ||
                               notes.Any(n => (n.Prescriptions?.Count ?? 0) > 0 || !string.IsNullOrWhiteSpace(n.FollowUpInstructions));
        var totalPages = waveformPagesCount + (hasExtendedNotes ? 1 : 0);
        var pages = new List<string>(totalPages);

        for (var pageIndex = 0; pageIndex < waveformPagesCount; pageIndex++)
        {
            var sb = new StringBuilder(16384);
            DrawPageChrome(sb, session, pageIndex + 1, totalPages, pageWidth, pageHeight, isExtended);

            var contentTop = pageHeight - 74.0;
            if (pageIndex == 0)
            {
                contentTop = DrawSummaryBand(sb, session, notes, contentTop, pageWidth);
            }
            else
            {
                contentTop = DrawContinuationSummary(sb, session, contentTop, pageWidth);
            }

            var firstStrip = pageIndex * StripsPerPage;
            var stripsOnThisPage = Math.Min(StripsPerPage, stripCount - firstStrip);
            var availableHeight = contentTop - 64.0;
            var stripSpacing = isExtended ? 16.0 : 14.0;
            var maxStripHeight = isExtended ? 175.0 : 118.0;
            var stripHeight = Math.Clamp((availableHeight - (StripsPerPage - 1) * stripSpacing) / StripsPerPage, 82.0, maxStripHeight);

            for (var s = 0; s < stripsOnThisPage; s++)
            {
                var stripIndex = firstStrip + s;
                var stripTop = contentTop - s * (stripHeight + stripSpacing);
                DrawWaveformStrip(sb, session, stripIndex, samplesPerStrip, sampleRate, Margin, stripTop - stripHeight, pageWidth - 2 * Margin, stripHeight);
            }

            if (pageIndex == waveformPagesCount - 1 && !hasExtendedNotes)
            {
                var notesTop = contentTop - stripsOnThisPage * (stripHeight + stripSpacing) - 4.0;
                DrawCompactNotesBox(sb, session, notes, Margin, 56.0, pageWidth - 2 * Margin, Math.Max(46.0, notesTop - 56.0));
            }

            pages.Add(sb.ToString());
        }

        if (hasExtendedNotes)
        {
            var sb = new StringBuilder(8192);
            DrawPageChrome(sb, session, totalPages, totalPages, pageWidth, pageHeight, isExtended);
            var contentTop = DrawContinuationSummary(sb, session, pageHeight - 74.0, pageWidth);
            DrawFullNotesPage(sb, session, notes, Margin, 58.0, pageWidth - 2 * Margin, contentTop - 62.0);
            pages.Add(sb.ToString());
        }

        return pages;
    }

    private static void DrawPageChrome(StringBuilder sb, EcgSession session, int pageNumber, int totalPages, double pageWidth, double pageHeight, bool isExtended)
    {
        // White page background
        SetFillColor(sb, 1.0, 1.0, 1.0);
        DrawRect(sb, 0, 0, pageWidth, pageHeight, fill: true, stroke: false);

        // Top header bar
        SetFillColor(sb, 0.06, 0.09, 0.16);
        DrawRect(sb, Margin, pageHeight - 62.0, pageWidth - 2 * Margin, 38.0, fill: true, stroke: false);

        var copyBadge = session.IsReleasedPatientCopy
            ? "RELEASED PATIENT COPY (SNAPSHOT)"
            : "AUTHORITATIVE CLINICAL RECORD";

        var formatBadge = isExtended ? " (Extended A3 Landscape)" : "";
        DrawText(sb, "F2", 13.0, Margin + 10.0, pageHeight - 41.0, 1.0, 1.0, 1.0,
            $"PulseLink IoMT Single-Lead AD8232 ECG Report{formatBadge}");
        DrawText(sb, "F1", 8.5, Margin + 10.0, pageHeight - 54.0, 0.80, 0.86, 0.96,
            $"Lead I placement (RA / LA / RL) | Uncalibrated 12-bit ADC (0-4095, midpoint 2048) | {session.SampleRateHz} Hz nominal");

        DrawText(sb, "F2", 9.0, pageWidth - Margin - 235.0, pageHeight - 41.0, 0.58, 0.77, 0.99, copyBadge);
        DrawText(sb, "F1", 8.5, pageWidth - Margin - 235.0, pageHeight - 54.0, 0.85, 0.88, 0.93,
            $"Session: {Truncate(session.SessionId, 28)} | Page {pageNumber} of {totalPages}");

        // Footer limitation and governance notice
        SetStrokeColor(sb, 0.82, 0.85, 0.90);
        SetLineWidth(sb, 0.7);
        DrawLine(sb, Margin, 46.0, pageWidth - Margin, 46.0);

        DrawText(sb, "F2", 7.5, Margin, 34.0, 0.22, 0.25, 0.32,
            "PROTOTYPE LIMITATIONS & HONESTY NOTICE:");
        DrawText(sb, "F1", 7.2, Margin + 162.0, 34.0, 0.30, 0.34, 0.42,
            "Single-lead AD8232 analogue capture is uncalibrated in millivolts and is not a diagnostic 12-lead ECG. Estimated BPM uses a rolling 5-second RR average");
        DrawText(sb, "F1", 7.2, Margin, 23.0, 0.30, 0.34, 0.42,
            "refreshed each second from connected good-quality segments only. No respiration, SpO2, blood pressure, extra leads or autonomous rhythm diagnoses are claimed.");
    }

    private static double DrawSummaryBand(StringBuilder sb, EcgSession session, List<DoctorClinicalNote> notes, double topY, double pageWidth)
    {
        var boxHeight = 68.0;
        var boxBottom = topY - boxHeight;
        var usableWidth = pageWidth - 2 * Margin;

        SetFillColor(sb, 0.97, 0.98, 0.99);
        SetStrokeColor(sb, 0.80, 0.84, 0.90);
        SetLineWidth(sb, 0.8);
        DrawRect(sb, Margin, boxBottom, usableWidth, boxHeight, fill: true, stroke: true);

        var patientLabel = string.IsNullOrWhiteSpace(session.PatientName)
            ? session.PatientId
            : $"{session.PatientName} ({session.PatientId})";
        var startText = $"{session.StartTimeUtc:yyyy-MM-dd HH:mm:ss} UTC";
        var endText = session.EndTimeUtc.HasValue ? $"{session.EndTimeUtc.Value:yyyy-MM-dd HH:mm:ss} UTC" : "Not recorded";
        var durationSeconds = session.DurationSeconds > 0
            ? session.DurationSeconds
            : session.SampleRateHz > 0 ? session.TotalSamplesCount / session.SampleRateHz : 0;

        var avgBpmText = session.AverageBpm > 0 ? $"{session.AverageBpm} BPM (est.)" : "No valid estimate";
        var minMaxBpmText = session.AverageBpm > 0 && session.MinBpm <= session.MaxBpm && session.MinBpm != int.MaxValue
            ? $"Min {session.MinBpm} / Max {session.MaxBpm} BPM"
            : "Min/Max unavailable";

        var leadOffBatches = session.Segments.Count(b => !b.LeadConnected || b.SignalQuality == "LeadsDisconnected");
        var degradedBatches = session.Segments.Count(b => b.SignalQuality == "Degraded");
        var sampleFlagLeadOff = session.SampleFlags.Count(f => (f & 0x03) != 0);
        var gapSummary = BuildGapAndQualitySummary(session, leadOffBatches, degradedBatches, sampleFlagLeadOff);

        var col1 = Margin + 10.0;
        var col2 = Margin + (usableWidth * 0.35);
        var col3 = Margin + (usableWidth * 0.68);

        DrawText(sb, "F2", 8.5, col1, topY - 15.0, 0.10, 0.14, 0.22, $"Patient: {Truncate(patientLabel, 38)}");
        DrawText(sb, "F1", 8.2, col1, topY - 28.0, 0.20, 0.24, 0.32, $"Session ID: {Truncate(session.SessionId, 34)}");
        DrawText(sb, "F1", 8.2, col1, topY - 41.0, 0.20, 0.24, 0.32, $"Capture Start: {startText}");
        DrawText(sb, "F1", 8.2, col1, topY - 54.0, 0.20, 0.24, 0.32, $"Finalised UTC: {endText}");

        DrawText(sb, "F2", 8.5, col2, topY - 15.0, 0.10, 0.28, 0.68, $"Rolling 5s Avg BPM: {avgBpmText}");
        DrawText(sb, "F1", 8.2, col2, topY - 28.0, 0.20, 0.24, 0.32, $"BPM Range: {minMaxBpmText}");
        DrawText(sb, "F1", 8.2, col2, topY - 41.0, 0.20, 0.24, 0.32,
            $"Duration & Samples: {durationSeconds}s ({session.TotalSamplesCount} samples @ {session.SampleRateHz} Hz)");
        DrawText(sb, "F1", 8.2, col2, topY - 54.0, 0.20, 0.24, 0.32,
            $"Hardware Unit: {Truncate(string.IsNullOrWhiteSpace(session.DeviceId) ? "Touchscreen/C3 Unit" : session.DeviceId, 28)}");

        DrawText(sb, "F2", 8.5, col3, topY - 15.0, 0.10, 0.14, 0.22, $"Signal Quality: {session.SignalQuality}");
        DrawText(sb, "F1", 8.0, col3, topY - 28.0, 0.20, 0.24, 0.32, Truncate(gapSummary.Line1, 46));
        DrawText(sb, "F1", 8.0, col3, topY - 41.0, 0.20, 0.24, 0.32, Truncate(gapSummary.Line2, 46));
        var releaseLabel = session.IsReleasedPatientCopy && session.ReleasedAtUtc.HasValue
            ? $"Released: {session.ReleasedAtUtc.Value:yyyy-MM-dd HH:mm} UTC"
            : $"Doctor Notes Attached: {notes.Count}";
        DrawText(sb, "F1", 8.0, col3, topY - 54.0, 0.20, 0.24, 0.32, Truncate(releaseLabel, 46));

        return boxBottom - 10.0;
    }

    private static double DrawContinuationSummary(StringBuilder sb, EcgSession session, double topY, double pageWidth)
    {
        var boxHeight = 24.0;
        var boxBottom = topY - boxHeight;

        SetFillColor(sb, 0.97, 0.98, 0.99);
        SetStrokeColor(sb, 0.82, 0.85, 0.90);
        SetLineWidth(sb, 0.6);
        DrawRect(sb, Margin, boxBottom, pageWidth - 2 * Margin, boxHeight, fill: true, stroke: true);

        var patientLabel = string.IsNullOrWhiteSpace(session.PatientName)
            ? session.PatientId
            : $"{session.PatientName} ({session.PatientId})";
        var avgBpmText = session.AverageBpm > 0 ? $"{session.AverageBpm} BPM (est.)" : "No valid estimate";

        DrawText(sb, "F2", 8.2, Margin + 8.0, boxBottom + 8.0, 0.12, 0.16, 0.24,
            $"Patient: {Truncate(patientLabel, 34)} | Start: {session.StartTimeUtc:yyyy-MM-dd HH:mm:ss} UTC | Duration: {session.DurationSeconds}s | Avg BPM: {avgBpmText} | Samples: {session.TotalSamplesCount}");

        return boxBottom - 10.0;
    }

    private static (string Line1, string Line2) BuildGapAndQualitySummary(
        EcgSession session, int leadOffBatches, int degradedBatches, int sampleFlagLeadOff)
    {
        var line1 = leadOffBatches > 0 || sampleFlagLeadOff > 0
            ? $"Lead-off reported: {leadOffBatches} batch(es), {sampleFlagLeadOff} flagged sample(s)"
            : degradedBatches > 0
                ? $"Degraded signal reported in {degradedBatches} batch(es)"
                : "Lead contact: Connected across recorded batches";

        string line2;
        if (session.TimeGaps.Count > 0)
        {
            var firstGap = session.TimeGaps[0];
            var gapMs = firstGap.DurationMs.HasValue ? $"{firstGap.DurationMs.Value} ms" : "clock reset";
            line2 = $"Time gaps: {session.TimeGaps.Count} (e.g. seq {firstGap.BeforeSequence}->{firstGap.AfterSequence}, {gapMs})";
        }
        else if (session.HasSampleGaps)
        {
            line2 = "Sample index discontinuity detected in recording";
        }
        else if (session.ClockContinuityUnverified)
        {
            line2 = "Clock continuity: Relative boot time (UTC unverified)";
        }
        else
        {
            line2 = "Timing continuity: Contiguous sequence, 0 time gaps";
        }

        return (line1, line2);
    }

    private static void DrawWaveformStrip(
        StringBuilder sb,
        EcgSession session,
        int stripIndex,
        int samplesPerStrip,
        int sampleRate,
        double left,
        double bottom,
        double width,
        double height)
    {
        var top = bottom + height;
        var right = left + width;
        var startSample = stripIndex * samplesPerStrip;
        var endSampleExclusive = Math.Min(session.SampleBuffer.Count, startSample + samplesPerStrip);
        var startSec = (double)startSample / sampleRate;
        var endSec = (double)(startSample + samplesPerStrip) / sampleRate;

        // Background and ECG grid (pink clinical grid lines, uncalibrated ADC amplitude)
        SetFillColor(sb, 1.0, 0.985, 0.985);
        SetStrokeColor(sb, 0.86, 0.65, 0.68);
        SetLineWidth(sb, 0.7);
        DrawRect(sb, left, bottom, width, height, fill: true, stroke: true);

        // Minor grid: 0.2s horizontal divisions (50 samples), 8 vertical divisions
        SetStrokeColor(sb, 0.96, 0.86, 0.88);
        SetLineWidth(sb, 0.35);
        for (var i = 1; i < 50; i++)
        {
            if (i % 5 == 0) continue;
            var x = left + width * (i / 50.0);
            DrawLine(sb, x, bottom, x, top);
        }
        for (var j = 1; j < 8; j++)
        {
            if (j == 4) continue;
            var y = bottom + height * (j / 8.0);
            DrawLine(sb, left, y, right, y);
        }

        // Major grid: 1.0s horizontal divisions, midpoint baseline (ADC 2048)
        SetStrokeColor(sb, 0.91, 0.72, 0.75);
        SetLineWidth(sb, 0.6);
        for (var sec = 1; sec < 10; sec++)
        {
            var x = left + width * (sec / 10.0);
            DrawLine(sb, x, bottom, x, top);
        }
        var midY = bottom + height * 0.5;
        DrawLine(sb, left, midY, right, midY);

        // Strip header label
        DrawText(sb, "F2", 7.8, left + 6.0, top - 10.0, 0.15, 0.18, 0.26,
            $"Strip {stripIndex + 1}: {startSec:0.0}s - {endSec:0.0}s (Samples {startSample} - {Math.Max(startSample, endSampleExclusive - 1)}) | ADC 0-4095 (Midpoint 2048, Uncalibrated)");

        if (session.SampleBuffer.Count == 0 || startSample >= session.SampleBuffer.Count)
        {
            DrawText(sb, "F2", 9.0, left + width * 0.35, midY - 3.0, 0.55, 0.18, 0.18,
                "No recorded ADC samples in this interval.");
            return;
        }

        // Highlight any batch-level or sample-level lead-off / gap intervals within this strip
        var gapMarkers = FindGapsInStrip(session, startSample, endSampleExclusive);
        foreach (var marker in gapMarkers)
        {
            var markerX = left + width * ((double)(marker.SampleIndex - startSample) / samplesPerStrip);
            SetStrokeColor(sb, 0.80, 0.16, 0.16);
            SetLineWidth(sb, 0.9);
            DrawLine(sb, markerX, bottom + 2.0, markerX, top - 14.0);
            DrawText(sb, "F2", 6.8, Math.Clamp(markerX + 3.0, left + 4.0, right - 135.0), bottom + 5.0, 0.75, 0.12, 0.12,
                Truncate(marker.Label, 32));
        }

        // Plot raw 12-bit ADC samples honestly, breaking the path when lead-off or a time gap occurs
        SetStrokeColor(sb, 0.06, 0.10, 0.18);
        SetLineWidth(sb, 0.85);

        var pathOpen = false;
        var pointsInCurrentSubpath = 0;
        var plotBottom = bottom + 6.0;
        var plotHeight = height - 20.0;
        var gapSampleSet = gapMarkers.Where(m => m.BreakTrace).Select(m => m.SampleIndex).ToHashSet();

        for (var idx = startSample; idx < endSampleExclusive; idx++)
        {
            var isLeadOff = IsSampleLeadOff(session, idx);
            if (isLeadOff || gapSampleSet.Contains(idx))
            {
                if (pathOpen)
                {
                    sb.AppendLine("S");
                    pathOpen = false;
                    pointsInCurrentSubpath = 0;
                }
                if (isLeadOff)
                {
                    continue;
                }
            }

            var adc = Math.Clamp(session.SampleBuffer[idx], 0, 4095);
            var x = left + width * ((double)(idx - startSample) / Math.Max(1, samplesPerStrip - 1));
            var normalised = (adc - 2048.0) / 2048.0; // -1.0 to +1.0
            var y = plotBottom + plotHeight * 0.5 + normalised * (plotHeight * 0.45);

            if (!pathOpen)
            {
                sb.AppendFormat(CultureInfo.InvariantCulture, "{0:0.##} {1:0.##} m\n", x, y);
                pathOpen = true;
                pointsInCurrentSubpath = 1;
            }
            else
            {
                sb.AppendFormat(CultureInfo.InvariantCulture, "{0:0.##} {1:0.##} l\n", x, y);
                pointsInCurrentSubpath++;
                if (pointsInCurrentSubpath >= 250)
                {
                    sb.AppendLine("S");
                    sb.AppendFormat(CultureInfo.InvariantCulture, "{0:0.##} {1:0.##} m\n", x, y);
                    pointsInCurrentSubpath = 1;
                }
            }
        }

        if (pathOpen)
        {
            sb.AppendLine("S");
        }
    }

    private sealed record StripMarker(int SampleIndex, string Label, bool BreakTrace);

    private static List<StripMarker> FindGapsInStrip(EcgSession session, int startSample, int endSampleExclusive)
    {
        var markers = new List<StripMarker>();
        if (session.Segments.Count > 0)
        {
            var gapByAfterSeq = session.TimeGaps.ToDictionary(g => g.AfterSequence, g => g);
            foreach (var seg in session.Segments)
            {
                if (seg.FirstSampleIndex >= startSample && seg.FirstSampleIndex < endSampleExclusive)
                {
                    if (gapByAfterSeq.TryGetValue(seg.Sequence, out var gap))
                    {
                        var label = gap.DurationMs.HasValue
                            ? $"GAP {gap.DurationMs.Value} ms ({gap.Reason})"
                            : $"GAP ({gap.Reason})";
                        markers.Add(new StripMarker(seg.FirstSampleIndex, label, BreakTrace: true));
                    }

                    if (!seg.LeadConnected || seg.SignalQuality == "LeadsDisconnected")
                    {
                        markers.Add(new StripMarker(seg.FirstSampleIndex, $"LEAD-OFF (Seq {seg.Sequence})", BreakTrace: true));
                    }
                }
            }
        }

        // Also check sample-level lead-off transitions if SampleFlags are populated
        if (session.SampleFlags.Count >= endSampleExclusive)
        {
            var inLeadOff = false;
            for (var i = startSample; i < endSampleExclusive; i++)
            {
                var lo = (session.SampleFlags[i] & 0x03) != 0;
                if (lo && !inLeadOff)
                {
                    markers.Add(new StripMarker(i, "LEAD-OFF (LO+/LO-)", BreakTrace: true));
                }
                inLeadOff = lo;
            }
        }

        return markers;
    }

    private static bool IsSampleLeadOff(EcgSession session, int sampleIndex)
    {
        if (sampleIndex < session.SampleFlags.Count && (session.SampleFlags[sampleIndex] & 0x03) != 0)
        {
            return true;
        }

        foreach (var seg in session.Segments)
        {
            if (sampleIndex >= seg.FirstSampleIndex && sampleIndex < seg.FirstSampleIndex + seg.SampleCount)
            {
                return !seg.LeadConnected || seg.SignalQuality == "LeadsDisconnected";
            }
        }

        return false;
    }

    private static void DrawCompactNotesBox(
        StringBuilder sb,
        EcgSession session,
        List<DoctorClinicalNote> notes,
        double left,
        double bottom,
        double width,
        double height)
    {
        SetFillColor(sb, 0.97, 0.98, 1.0);
        SetStrokeColor(sb, 0.78, 0.83, 0.91);
        SetLineWidth(sb, 0.7);
        DrawRect(sb, left, bottom, width, height, fill: true, stroke: true);

        var title = session.IsReleasedPatientCopy
            ? "Released Doctor Assessment Snapshot"
            : "Doctor Clinical Assessment & Notes";
        DrawText(sb, "F2", 8.5, left + 8.0, bottom + height - 12.0, 0.10, 0.16, 0.28, title);

        if (notes.Count == 0)
        {
            DrawText(sb, "F1", 8.0, left + 8.0, bottom + height - 26.0, 0.35, 0.38, 0.45,
                "No clinical assessment notes attached to this report copy.");
            return;
        }

        var cursorY = bottom + height - 25.0;
        foreach (var note in notes.Take(2))
        {
            if (cursorY < bottom + 6.0) break;
            var followUp = note.FollowUpRequired
                ? note.FollowUpInDays.HasValue ? $"Follow-up Required ({note.FollowUpInDays.Value}d)" : "Follow-up Required"
                : "No Urgent Follow-up";
            var rec = string.IsNullOrWhiteSpace(note.Recommendations) ? "" : $" | Rec: {note.Recommendations}";
            var line = $"Dr {note.DoctorName} ({note.CreatedAtUtc:yyyy-MM-dd HH:mm} UTC) [{followUp}]: {note.ClinicalImpression}{rec}";
            DrawText(sb, "F1", 7.8, left + 8.0, cursorY, 0.14, 0.18, 0.24, Truncate(line, 132));
            cursorY -= 12.0;
        }
    }

    private static void DrawFullNotesPage(
        StringBuilder sb,
        EcgSession session,
        List<DoctorClinicalNote> notes,
        double left,
        double bottom,
        double width,
        double height)
    {
        SetFillColor(sb, 0.98, 0.99, 1.0);
        SetStrokeColor(sb, 0.78, 0.83, 0.91);
        SetLineWidth(sb, 0.8);
        DrawRect(sb, left, bottom, width, height, fill: true, stroke: true);

        var cursorY = bottom + height - 18.0;
        var heading = session.IsReleasedPatientCopy
            ? "Released Clinical Assessment & Care Instructions Snapshot"
            : "Clinical Assessment Notes & Segment Telemetry Log";
        DrawText(sb, "F2", 10.5, left + 12.0, cursorY, 0.10, 0.22, 0.52, heading);
        cursorY -= 18.0;

        if (!string.IsNullOrWhiteSpace(session.ReleasedCareInstructions))
        {
            DrawText(sb, "F2", 8.8, left + 12.0, cursorY, 0.12, 0.16, 0.24, "Released Care Plan Instructions:");
            cursorY -= 13.0;
            foreach (var line in WrapText(session.ReleasedCareInstructions, 118).Take(4))
            {
                DrawText(sb, "F1", 8.2, left + 18.0, cursorY, 0.18, 0.22, 0.30, line);
                cursorY -= 11.5;
            }
            cursorY -= 6.0;
        }

        if (notes.Count == 0)
        {
            DrawText(sb, "F1", 8.5, left + 12.0, cursorY, 0.35, 0.38, 0.45,
                "No doctor review notes attached.");
        }
        else
        {
            foreach (var note in notes)
            {
                if (cursorY < bottom + 36.0) break;
                var followUp = note.FollowUpRequired
                    ? note.FollowUpInDays.HasValue ? $"Follow-up Required ({note.FollowUpInDays.Value} days)" : "Follow-up Required"
                    : "No Urgent Follow-up";
                DrawText(sb, "F2", 8.6, left + 12.0, cursorY, 0.10, 0.14, 0.22,
                    $"Reviewed by Dr {note.DoctorName} ({note.CreatedAtUtc:yyyy-MM-dd HH:mm:ss} UTC) - {followUp}");
                cursorY -= 12.0;

                foreach (var line in WrapText($"Impression: {note.ClinicalImpression}", 118).Take(3))
                {
                    DrawText(sb, "F1", 8.0, left + 18.0, cursorY, 0.18, 0.22, 0.30, line);
                    cursorY -= 11.0;
                }

                if (!string.IsNullOrWhiteSpace(note.Recommendations))
                {
                    foreach (var line in WrapText($"Recommendations: {note.Recommendations}", 118).Take(2))
                    {
                        DrawText(sb, "F1", 8.0, left + 18.0, cursorY, 0.18, 0.22, 0.30, line);
                        cursorY -= 11.0;
                    }
                }

                if (!string.IsNullOrWhiteSpace(note.FollowUpInstructions))
                {
                    foreach (var line in WrapText($"Follow-up Instructions: {note.FollowUpInstructions}", 118).Take(2))
                    {
                        DrawText(sb, "F1", 8.0, left + 18.0, cursorY, 0.18, 0.22, 0.30, line);
                        cursorY -= 11.0;
                    }
                }

                if (note.Prescriptions is { Count: > 0 })
                {
                    var rxText = string.Join("; ", note.Prescriptions.Select(p =>
                        $"{p.Name} {p.Dosage} ({p.Frequency})".Trim()));
                    foreach (var line in WrapText($"Prescriptions: {rxText}", 118).Take(2))
                    {
                        DrawText(sb, "F1", 8.0, left + 18.0, cursorY, 0.18, 0.22, 0.30, line);
                        cursorY -= 11.0;
                    }
                }
                cursorY -= 6.0;
            }
        }
    }

    private static List<string> WrapText(string text, int maxCharsPerLine)
    {
        var result = new List<string>();
        if (string.IsNullOrWhiteSpace(text)) return result;

        var words = text.Replace('\r', ' ').Replace('\n', ' ').Split(' ', StringSplitOptions.RemoveEmptyEntries);
        var current = new StringBuilder();
        foreach (var word in words)
        {
            if (current.Length + word.Length + 1 > maxCharsPerLine && current.Length > 0)
            {
                result.Add(current.ToString());
                current.Clear();
            }
            if (current.Length > 0) current.Append(' ');
            current.Append(word);
        }
        if (current.Length > 0) result.Add(current.ToString());
        return result;
    }

    private static string Truncate(string? value, int maxLength)
    {
        if (string.IsNullOrEmpty(value)) return string.Empty;
        var clean = value.Replace('\r', ' ').Replace('\n', ' ').Trim();
        return clean.Length <= maxLength ? clean : string.Concat(clean.AsSpan(0, maxLength - 3), "...");
    }

    private static void SetFillColor(StringBuilder sb, double r, double g, double b) =>
        sb.AppendFormat(CultureInfo.InvariantCulture, "{0:0.###} {1:0.###} {2:0.###} rg\n", r, g, b);

    private static void SetStrokeColor(StringBuilder sb, double r, double g, double b) =>
        sb.AppendFormat(CultureInfo.InvariantCulture, "{0:0.###} {1:0.###} {2:0.###} RG\n", r, g, b);

    private static void SetLineWidth(StringBuilder sb, double width) =>
        sb.AppendFormat(CultureInfo.InvariantCulture, "{0:0.##} w\n", width);

    private static void DrawLine(StringBuilder sb, double x1, double y1, double x2, double y2) =>
        sb.AppendFormat(CultureInfo.InvariantCulture, "{0:0.##} {1:0.##} m {2:0.##} {3:0.##} l S\n", x1, y1, x2, y2);

    private static void DrawRect(StringBuilder sb, double x, double y, double w, double h, bool fill, bool stroke)
    {
        var op = fill && stroke ? "B" : fill ? "f" : "S";
        sb.AppendFormat(CultureInfo.InvariantCulture, "{0:0.##} {1:0.##} {2:0.##} {3:0.##} re {4}\n", x, y, w, h, op);
    }

    private static void DrawText(StringBuilder sb, string font, double size, double x, double y, double r, double g, double b, string text)
    {
        SetFillColor(sb, r, g, b);
        sb.AppendFormat(CultureInfo.InvariantCulture, "BT /{0} {1:0.##} Tf {2:0.##} {3:0.##} Td ({4}) Tj ET\n",
            font, size, x, y, EscapePdfString(text));
    }

    private static string EscapePdfString(string input)
    {
        var sb = new StringBuilder(input.Length + 8);
        foreach (var ch in input)
        {
            if (ch == '\\' || ch == '(' || ch == ')')
            {
                sb.Append('\\').Append(ch);
            }
            else if (ch >= 32 && ch <= 126)
            {
                sb.Append(ch);
            }
            else
            {
                sb.Append('?');
            }
        }
        return sb.ToString();
    }

    private static byte[] CompilePdfDocument(List<string> pageContents, double pageWidth, double pageHeight)
    {
        using var ms = new MemoryStream();
        void WriteAscii(string text)
        {
            var bytes = Encoding.ASCII.GetBytes(text);
            ms.Write(bytes, 0, bytes.Length);
        }

        // Object layout:
        // 1: Catalog
        // 2: Pages
        // 3: Font F1 (Helvetica)
        // 4: Font F2 (Helvetica-Bold)
        // For each page i (0..N-1):
        //   pageObj = 5 + i * 2
        //   contentObj = 6 + i * 2
        var totalObjects = 4 + pageContents.Count * 2;
        var offsets = new long[totalObjects + 1];

        WriteAscii("%PDF-1.4\n%\xE2\xE3\xCF\xD3\n");

        offsets[1] = ms.Position;
        WriteAscii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

        var kids = string.Join(" ", Enumerable.Range(0, pageContents.Count).Select(i => $"{5 + i * 2} 0 R"));
        offsets[2] = ms.Position;
        WriteAscii($"2 0 obj\n<< /Type /Pages /Kids [{kids}] /Count {pageContents.Count} >>\nendobj\n");

        offsets[3] = ms.Position;
        WriteAscii("3 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>\nendobj\n");

        offsets[4] = ms.Position;
        WriteAscii("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>\nendobj\n");

        for (var i = 0; i < pageContents.Count; i++)
        {
            var pageObj = 5 + i * 2;
            var contentObj = 6 + i * 2;
            var contentBytes = Encoding.ASCII.GetBytes(pageContents[i]);

            offsets[pageObj] = ms.Position;
            WriteAscii(string.Create(CultureInfo.InvariantCulture,
                $"{pageObj} 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {pageWidth:0.##} {pageHeight:0.##}] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents {contentObj} 0 R >>\nendobj\n"));

            offsets[contentObj] = ms.Position;
            WriteAscii($"{contentObj} 0 obj\n<< /Length {contentBytes.Length} >>\nstream\n");
            ms.Write(contentBytes, 0, contentBytes.Length);
            WriteAscii("\nendstream\nendobj\n");
        }

        var xrefPosition = ms.Position;
        WriteAscii($"xref\n0 {totalObjects + 1}\n0000000000 65535 f \n");
        for (var obj = 1; obj <= totalObjects; obj++)
        {
            WriteAscii($"{offsets[obj]:D10} 00000 n \n");
        }

        WriteAscii($"trailer\n<< /Size {totalObjects + 1} /Root 1 0 R >>\nstartxref\n{xrefPosition}\n%%EOF\n");
        return ms.ToArray();
    }
}
