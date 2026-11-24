package com.example.prog7314_poepart23.data.remote.models

data class AccessAccountDto(
    val uid: String, val email: String = "", val displayName: String = "",
    val role: String, val status: String, val requestedRole: String? = null,
    val siteIds: List<String> = emptyList(), val version: Int = 0
)
data class AccessPageDto<T>(val items: List<T> = emptyList(), val nextCursor: String? = null)

data class ClinicSiteDto(val id: String, val name: String)

data class RoleRequestDto(val requestedRole: String)

data class ApprovalRequestDto(val role: String, val status: String, val siteIds: List<String>, val expectedVersion: Int)

data class SiteRequestDto(val name: String)

data class ClinicalPatientDto(
    val id: String, val fullName: String, val siteId: String,
    val primaryDoctorUid: String, val additionalDoctorUids: List<String> = emptyList(), val version: Int,
    val dateOfBirth: String? = null
)
data class PatientRegistrationDto(
    val fullName: String,
    val siteId: String,
    val primaryDoctorUid: String,
    val dateOfBirth: String? = null
)

data class ApprovedDoctorDto(
    val uid: String = "",
    val name: String = "",
    val email: String = ""
) {
    fun displayWithEmail(): String {
        val cleanName = name.trim()
        val cleanEmail = email.trim()
        return when {
            cleanName.isNotBlank() && cleanEmail.isNotBlank() && !cleanName.contains(cleanEmail, ignoreCase = true) ->
                "$cleanName - $cleanEmail"
            cleanName.isNotBlank() -> cleanName
            cleanEmail.isNotBlank() -> cleanEmail
            else -> uid
        }
    }
}

data class SupportChatThreadDto(
    val threadId: String = "",
    val patientName: String = "",
    val lastMessage: String = "",
    val lastTimestampMillis: Long = 0L,
    val lastSenderRole: String = "patient"
)

data class SupportChatMessageDto(
    val id: String = "",
    val threadId: String = "",
    val patientName: String = "",
    val senderUid: String = "",
    val senderName: String = "",
    val senderRole: String = "patient",
    val text: String = "",
    val timestampMillis: Long = 0L
)

data class SendSupportMessageDto(
    val text: String,
    val patientName: String? = null
)

data class PatientAssignmentDto(val primaryDoctorUid: String, val additionalDoctorUids: List<String>, val expectedVersion: Int)

data class DeviceSiteRequestDto(val siteId: String, val active: Boolean = true)

data class ScreenApprovalRequestDto(val code: String)
data class NurseGrantDto(val id: String, val deviceId: String, val siteId: String, val nurseUid: String,
    val nurseName: String, val approvedAtUtc: String, val expiresAtUtc: String, val syncUntilUtc: String)
