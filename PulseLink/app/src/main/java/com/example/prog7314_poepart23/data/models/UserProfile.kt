/**
 * PulseLink User Profile Domain Entity
 *
 * Represents user identity profiles, role-based access attributes, and authentication
 * linkages stored in Firebase Realtime Database (Google Cloud, 2024).
 *
 * Reference List:
 * - Google Cloud, 2024. Read and Write Data in Firebase Realtime Database. Google Cloud. Available at: <https://firebase.google.com/docs/database/android/read-and-write> [Accessed 18 September 2026].
 */

package com.example.prog7314_poepart23.data.models

data class UserProfile(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val role: String,
    val dateOfBirth: String? = null,
    val gender: String? = null,
    val phoneNumber: String? = null,
    val emergencyContactName: String? = null,
    val emergencyContactPhone: String? = null,
    val emergencyContactRelationship: String? = null,
    val assignedDoctorName: String? = null,
    val specialization: String? = null,
    val practiceNumber: String? = null,
    val hospitalAffiliation: String? = null
)
