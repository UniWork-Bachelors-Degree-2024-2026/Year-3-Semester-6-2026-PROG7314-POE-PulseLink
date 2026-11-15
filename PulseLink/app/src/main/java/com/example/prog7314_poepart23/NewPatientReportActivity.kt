/*
 * Activity facilitating initiation of new patient diagnostic assessments,
 * live telemetry observation, and baseline report authoring.
 *
 * Reference List:
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.os.Bundle
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.comingSoonOnClick
import com.example.prog7314_poepart23.utils.openOnClick

class NewPatientReportActivity : AppCompatActivity() { // (Google Developers, 2024)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.doctor_new_patient_report)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)
        wireControls()
    }

    private fun wireControls() {
        openOnClick(R.id.btnSettings, DoctorSettingsActivity::class.java)
        openOnClick(R.id.ivProfile, DoctorProfileActivity::class.java)

        openOnClick(R.id.btnStartMonitoring, DoctorReportsActivity::class.java)
        openOnClick(R.id.btnAddInitialAssessment, DoctorSessionNotesActivity::class.java)

        comingSoonOnClick(R.id.btnLogVitals, R.id.btnEditProfile)
    }
}
