/*
 * Care Plan and Medication Domain Entities for PulseLink Patient Profiles.
 * Loaded and saved through the authorised clinical patient API.
 */

package com.example.prog7314_poepart23.data.models

data class CarePlan(
    val primaryCondition: String = "",
    val secondaryStatus: String = "",
    val medications: List<MedicationItem> = emptyList(),
    val clinicalInstructions: String = "",
    val updatedAtUtc: String = "",
    val updatedByDoctor: String = ""
)

data class MedicationItem(
    val name: String = "",
    val dosage: String = "",
    val frequency: String = ""
)
