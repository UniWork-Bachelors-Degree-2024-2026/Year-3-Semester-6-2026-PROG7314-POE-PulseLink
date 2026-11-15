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
data class PatientRegistrationDto(val fullName: String, val siteId: String, val primaryDoctorUid: String)

data class PatientAssignmentDto(val primaryDoctorUid: String, val additionalDoctorUids: List<String>, val expectedVersion: Int)

data class DeviceSiteRequestDto(val siteId: String, val active: Boolean = true)

data class ScreenApprovalRequestDto(val code: String)
data class NurseGrantDto(val id: String, val deviceId: String, val siteId: String, val nurseUid: String,
    val nurseName: String, val approvedAtUtc: String, val expiresAtUtc: String, val syncUntilUtc: String)
