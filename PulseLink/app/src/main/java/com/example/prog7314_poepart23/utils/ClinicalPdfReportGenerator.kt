// Stages and opens authoritative server-generated ECG PDF reports retrieved over authenticated HTTPS.
// Local on-device clinical PDF synthesis has been retired so reports always come from the authoritative API.

package com.example.prog7314_poepart23.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.prog7314_poepart23.data.repository.PatientPortalRepository
import com.example.prog7314_poepart23.data.repository.PatientPortalResult
import com.example.prog7314_poepart23.security.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import com.example.prog7314_poepart23.R

object ClinicalPdfReportGenerator {

    data class ReportMetadata(
        val sessionId: String,
        val patientName: String,
        val patientId: String,
        val averageBpm: Int,
        val durationSeconds: Int,
        val startTimeUtc: String? = null,
        val signalQuality: String = "Unknown",
        val samples: List<Int>? = null
    )

    fun sanitizeReportFileName(sessionId: String, releasedPatientCopy: Boolean = false): String {
        val cleanId = sessionId.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return if (releasedPatientCopy) {
            "PulseLink_Released_Report_$cleanId.pdf"
        } else {
            "PulseLink_Report_$cleanId.pdf"
        }
    }

    fun isValidPdfFile(file: File?): Boolean {
        if (file == null || !file.exists() || !file.isFile || file.length() < 8L) return false
        return try {
            val header = ByteArray(5)
            val read = file.inputStream().use { it.read(header) }
            read == 5 && PatientPortalRepository.isValidPdfPayload(header)
        } catch (_: Exception) {
            false
        }
    }

    fun saveAuthoritativePdfFile(
        context: Context,
        sessionId: String,
        pdfBytes: ByteArray,
        releasedPatientCopy: Boolean = false
    ): File? {
        return saveAuthoritativePdfToDirectory(
            reportsDir = File(context.cacheDir, "reports"),
            sessionId = sessionId,
            pdfBytes = pdfBytes,
            releasedPatientCopy = releasedPatientCopy
        )
    }

    fun saveAuthoritativePdfToDirectory(
        reportsDir: File,
        sessionId: String,
        pdfBytes: ByteArray,
        releasedPatientCopy: Boolean = false
    ): File? {
        if (sessionId.isBlank() || !PatientPortalRepository.isValidPdfPayload(pdfBytes)) {
            return null
        }
        return try {
            if (!reportsDir.exists()) reportsDir.mkdirs()
            val file = File(reportsDir, sanitizeReportFileName(sessionId, releasedPatientCopy))
            FileOutputStream(file).use { output ->
                output.write(pdfBytes)
                output.flush()
            }
            if (isValidPdfFile(file)) file else {
                runCatching { file.delete() }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // Deletes all staged PDF report files on sign-out or account switch so another user never sees cached clinical reports.
    fun clearCachedReports(context: Context): Int =
        clearCachedReportsInDirectory(File(context.cacheDir, "reports"))

    fun clearCachedReportsInDirectory(reportsDir: File): Int {
        if (!reportsDir.exists() || !reportsDir.isDirectory) return 0
        var deleted = 0
        reportsDir.listFiles()?.forEach { file ->
            if (file.isFile && runCatching { file.delete() }.getOrDefault(false)) {
                deleted++
            }
        }
        return deleted
    }

    suspend fun downloadAndOpenAuthoritativeReport(
        context: Context,
        sessionId: String,
        isDoctorOrStaffView: Boolean,
        repository: PatientPortalRepository = PatientPortalRepository()
    ): PatientPortalResult<File> {
        val downloadResult = withContext(Dispatchers.IO) {
            if (isDoctorOrStaffView) {
                repository.downloadStaffReportPdf(sessionId)
            } else {
                repository.downloadReleasedReportPdf(sessionId)
            }
        }

        return when (downloadResult) {
            is PatientPortalResult.Ready -> {
                val file = withContext(Dispatchers.IO) {
                    saveAuthoritativePdfFile(
                        context = context,
                        sessionId = sessionId,
                        pdfBytes = downloadResult.value.pdfBytes,
                        releasedPatientCopy = !isDoctorOrStaffView
                    )
                }
                if (file != null && file.exists()) {
                    openPdf(context, file)
                    PatientPortalResult.Ready(file)
                } else {
                    Toast.makeText(context, context.getString(R.string.rt_pdf_stage_failed), Toast.LENGTH_SHORT).show()
                    PatientPortalResult.Failed(PatientPortalResult.Reason.UNAVAILABLE)
                }
            }
            is PatientPortalResult.NotReleased -> {
                Toast.makeText(context, context.getString(R.string.rt_pdf_not_released), Toast.LENGTH_SHORT).show()
                PatientPortalResult.NotReleased
            }
            is PatientPortalResult.NotLinked -> {
                Toast.makeText(context, context.getString(R.string.rt_pdf_not_linked), Toast.LENGTH_SHORT).show()
                PatientPortalResult.NotLinked
            }
            is PatientPortalResult.Failed -> {
                val msg = when (downloadResult.reason) {
                    PatientPortalResult.Reason.SIGN_IN_REQUIRED -> context.getString(R.string.rt_pdf_sign_in_again)
                    PatientPortalResult.Reason.DENIED -> context.getString(R.string.rt_pdf_denied)
                    PatientPortalResult.Reason.CONFLICT -> context.getString(R.string.rt_pdf_conflict)
                    else -> context.getString(R.string.rt_pdf_unavailable)
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                downloadResult
            }
        }
    }

    // Compatible entry point for existing screens; fetches the authoritative server PDF rather than fabricating a local PDF.
    fun generateAndOpenReport(context: Context, metadata: ReportMetadata): File? {
        if (context is LifecycleOwner && metadata.sessionId.isNotBlank()) {
            val role = try {
                SecurePreferences.getInstance(context).getUserRole()
            } catch (_: Exception) {
                null
            }
            val isStaff = role.equals("Doctor", ignoreCase = true) || role.equals("Nurse", ignoreCase = true)
            context.lifecycleScope.launch {
                downloadAndOpenAuthoritativeReport(
                    context = context,
                    sessionId = metadata.sessionId,
                    isDoctorOrStaffView = isStaff
                )
            }
            return generatePdfFile(context, metadata)
        }
        val existingFile = generatePdfFile(context, metadata)
        if (existingFile != null && existingFile.exists()) {
            openPdf(context, existingFile)
            return existingFile
        }
        return null
    }

    // Returns an already-downloaded authoritative server PDF if cached and valid, or null. Never synthesises local PDFs.
    fun generatePdfFile(context: Context, metadata: ReportMetadata): File? =
        findCachedPdfInDirectory(File(context.cacheDir, "reports"), metadata.sessionId)

    fun findCachedPdfInDirectory(reportsDir: File, sessionId: String): File? {
        if (sessionId.isBlank()) return null
        val releasedFile = File(reportsDir, sanitizeReportFileName(sessionId, releasedPatientCopy = true))
        if (isValidPdfFile(releasedFile)) return releasedFile
        val staffFile = File(reportsDir, sanitizeReportFileName(sessionId, releasedPatientCopy = false))
        if (isValidPdfFile(staffFile)) return staffFile
        return null
    }

    fun openPdf(context: Context, file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            }
            val chooser = Intent.createChooser(intent, context.getString(R.string.rt_pdf_open_chooser))
            context.startActivity(chooser)
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.rt_pdf_no_viewer), Toast.LENGTH_LONG).show()
        }
    }
}
