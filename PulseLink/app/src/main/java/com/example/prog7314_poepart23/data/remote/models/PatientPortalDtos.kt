package com.example.prog7314_poepart23.data.remote.models

import com.example.prog7314_poepart23.data.models.CarePlan

data class PatientLoginRequestDto(val accountUid: String, val confirmedEmail: String, val expectedVersion: Int)
data class PatientLoginLinkDto(val patientId: String, val accountUid: String, val confirmedByUid: String, val confirmedAtUtc: String)
data class PatientProfileDto(val patientId: String, val fullName: String)
data class PatientSessionStatusDto(val sessionId: String, val patientId: String, val startTimeUtc: String,
    val endTimeUtc: String?, val status: String, val durationSeconds: Int, val resultsReleased: Boolean, val hasDoctorNotes: Boolean,
    val reportAvailable: Boolean = false)
data class PatientReleaseDto(val sessionId: String, val patientId: String, val releasedByUid: String,
    val releasedAtUtc: String, val notes: List<DoctorNoteDto>, val carePlan: CarePlan,
    val reportState: String = "Unavailable", val reportAvailable: Boolean = false, val reportFileName: String = "")

data class ReportStatusDto(
    val sessionId: String,
    val patientId: String = "",
    val audience: String = "patient",
    val state: String = "Unavailable",
    val reportAvailable: Boolean = false,
    val fileName: String = "",
    val downloadPath: String = "",
    val streamPath: String = "",
    val authenticatedEmailLinkPath: String = "",
    val generatedAtUtc: String? = null,
    val revision: Long? = null,
    val message: String? = null
)

data class ReportDocumentPayload(
    val sessionId: String,
    val fileName: String,
    val pdfBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReportDocumentPayload) return false
        return sessionId == other.sessionId &&
            fileName == other.fileName &&
            pdfBytes.contentEquals(other.pdfBytes)
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + pdfBytes.contentHashCode()
        return result
    }
}
