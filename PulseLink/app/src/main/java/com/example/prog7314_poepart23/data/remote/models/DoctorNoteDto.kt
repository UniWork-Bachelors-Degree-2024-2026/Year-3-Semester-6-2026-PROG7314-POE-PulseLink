/*
 * Data Transfer Objects for clinical session assessment notes authored by authorised doctors.
 *
 * Reference List:
 * - Google Developers, 2024. JSON in Android and Gson Library Guide. [online] Available at: <https://developer.android.com/> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.data.remote.models

import com.example.prog7314_poepart23.data.models.MedicationItem
import com.google.gson.annotations.SerializedName // (Google Developers, 2024)

// Payload submitted by an authorised doctor when evaluating a patient's recorded ECG session.
// DoctorId and DoctorName are overwritten server-side from the authenticated doctor's access record.
data class CreateDoctorNoteDto(
    @SerializedName("sessionId") // (Google Developers, 2024)
    val sessionId: String,

    @SerializedName("patientId") // (Google Developers, 2024)
    val patientId: String,

    @SerializedName("doctorId") // (Google Developers, 2024)
    val doctorId: String = "",

    @SerializedName("doctorName") // (Google Developers, 2024)
    val doctorName: String = "",

    @SerializedName("clinicalImpression") // (Google Developers, 2024)
    val clinicalImpression: String,

    @SerializedName("recommendations") // (Google Developers, 2024)
    val recommendations: String? = null,

    @SerializedName("followUpRequired") // (Google Developers, 2024)
    val followUpRequired: Boolean = false,

    @SerializedName("followUpInstructions")
    val followUpInstructions: String? = null,

    @SerializedName("followUpInDays")
    val followUpInDays: Int? = null,

    @SerializedName("prescriptions")
    val prescriptions: List<MedicationItem> = emptyList()
)

// Clinical note details returned to patients and healthcare practitioners.
data class DoctorNoteDto(
    @SerializedName("noteId") // (Google Developers, 2024)
    val noteId: String = "",

    @SerializedName("sessionId") // (Google Developers, 2024)
    val sessionId: String = "",

    @SerializedName("patientId") // (Google Developers, 2024)
    val patientId: String = "",

    @SerializedName("doctorId") // (Google Developers, 2024)
    val doctorId: String = "",

    @SerializedName("doctorName") // (Google Developers, 2024)
    val doctorName: String = "",

    @SerializedName("clinicalImpression") // (Google Developers, 2024)
    val clinicalImpression: String = "",

    @SerializedName("recommendations") // (Google Developers, 2024)
    val recommendations: String? = null,

    @SerializedName("followUpRequired") // (Google Developers, 2024)
    val followUpRequired: Boolean = false,

    @SerializedName("followUpInstructions")
    val followUpInstructions: String? = null,

    @SerializedName("followUpInDays")
    val followUpInDays: Int? = null,

    @SerializedName("prescriptions")
    val prescriptions: List<MedicationItem> = emptyList(),

    @SerializedName("createdAtUtc") // (Google Developers, 2024)
    val createdAtUtc: String = ""
)
