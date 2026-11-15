/*
 * Comprehensive patient clinical profile view displaying patient medical background,
 * assigned hardware telemetry, care plan management, and direct clinical action shortcuts.
 *
 * Reference List:
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import android.view.View
import android.widget.LinearLayout
import com.example.prog7314_poepart23.data.repository.PatientPortalResult
import com.example.prog7314_poepart23.viewmodels.DoctorReviewViewModel
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import androidx.lifecycle.lifecycleScope              // (Google Developers, 2024)
import com.example.prog7314_poepart23.data.models.CarePlan
import com.example.prog7314_poepart23.data.models.MedicationItem
import com.example.prog7314_poepart23.data.repository.UserRepository
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.utils.onClick
import com.example.prog7314_poepart23.utils.toastOnClick
import com.example.prog7314_poepart23.utils.wireBack
import kotlinx.coroutines.launch                     // (JetBrains, 2024)
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DoctorPatientProfileActivity : AppCompatActivity() { // (Google Developers, 2024)

    private val userRepository = UserRepository()
    private val review: DoctorReviewViewModel by viewModels()
    private var patientId: String = ""
    private var patientName: String = ""
    private var currentSessionId: String? = null
    private var activeCarePlan: CarePlan = CarePlan()
    private var carePlanDialog: android.app.Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.doctor_patient_profile)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        patientId = intent.getStringExtra(PatientListActivity.EXTRA_PATIENT_ID).orEmpty()
        if (patientId.isNullOrBlank()) {
            android.widget.Toast.makeText(this, getString(R.string.l10n_no_patient_selected), android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val passedName = intent.getStringExtra(DoctorAlertsActivity.EXTRA_PATIENT_NAME)
        patientName = when {
            !passedName.isNullOrBlank() -> passedName
            else -> "Patient ${patientId.take(8)}"
        }

        currentSessionId = intent.getStringExtra(PatientEcgHistoryActivity.EXTRA_SESSION_ID)

        HapticFeedbackManager.initialise(this)
        populateHeaderInfo()
        observeChart()

        wireControls()
        BottomNavHelper.setUpDoctorNavDetached(this)
    }

    override fun onResume() {
        super.onResume()
        BottomNavHelper.setUpDoctorNavDetached(this)
        if (patientId.isNotBlank()) review.loadPatientChart(patientId)
    }

    private fun populateHeaderInfo() {
        findViewById<TextView?>(R.id.tvPatientProfileName)?.text = patientName
        findViewById<TextView?>(R.id.tvPatientProfileId)?.text = getString(R.string.l10n_id_format, patientId)
    }

    private fun observeChart() {
        val state = findViewById<TextView>(R.id.chartLoadStatus)
        state.text = getString(R.string.greeting_loading)
        state.setOnClickListener { review.loadPatientChart(patientId) }
        findViewById<View>(R.id.btnUpdateCarePlan).isEnabled = false
        findViewById<View>(R.id.btnEditMedications).isEnabled = false
        review.savedCarePlan.observe(this) { result ->
            carePlanDialog?.findViewById<View>(R.id.btnSaveCarePlan)?.isEnabled = true
            if (result is PatientPortalResult.Ready) {
                activeCarePlan = result.value
                renderCarePlan(activeCarePlan)
                carePlanDialog?.dismiss()
                Toast.makeText(this, R.string.ui_care_plan_saved, Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this, R.string.ui_care_plan_failed, Toast.LENGTH_SHORT).show()
        }
        review.patientChart.observe(this) { result ->
            val recordings = findViewById<LinearLayout>(R.id.chartRecordingRows)
            val visits = findViewById<LinearLayout>(R.id.chartVisitRows)
            recordings.removeAllViews(); visits.removeAllViews()
            if (result is PatientPortalResult.Ready) {
                val chart = result.value
                patientName = chart.patient.fullName
                populateHeaderInfo()
                activeCarePlan = chart.carePlan
                renderCarePlan(activeCarePlan)
                currentSessionId = chart.patient.latestCompletedSessionId
                state.visibility = View.GONE
                findViewById<View>(R.id.btnUpdateCarePlan).isEnabled = true
                findViewById<View>(R.id.btnEditMedications).isEnabled = true
                if (chart.completedRecordings.isEmpty()) addChartRow(recordings, getString(R.string.session_status_none_title))
                for (recording in chart.completedRecordings) {
                    val session = recording.session
                    val date = com.example.prog7314_poepart23.utils.DateTimeFormatter.formatIsoTimestamp(session.startTimeUtc)
                    val status = getString(if (recording.releaseState.resultsReleased) R.string.doctor_released else R.string.ui_review_required)
                    val bpm = if (session.averageBpm > 0) getString(R.string.l10n_bpm_value, session.averageBpm) else getString(R.string.l10n_bpm_none)
                    addChartRow(recordings, "$date\n$bpm · $status") {
                        startActivity(Intent(this, DoctorEcgSessionDetailsActivity::class.java)
                            .putExtra(PatientEcgHistoryActivity.EXTRA_SESSION_ID, session.sessionId)
                            .putExtra(PatientListActivity.EXTRA_PATIENT_ID, patientId))
                    }
                }
                if (chart.priorVisits.isEmpty()) addChartRow(visits, getString(R.string.doctor_no_visits))
                for (visit in chart.priorVisits) {
                    val date = com.example.prog7314_poepart23.utils.DateTimeFormatter.formatIsoTimestamp(visit.recordedAtUtc)
                    addChartRow(visits, listOf(date, visit.doctorName, visit.assessment, visit.recommendations.orEmpty(), visit.followUpInstructions.orEmpty())
                        .filter { it.isNotBlank() }.joinToString("\n"))
                }
                // Session assessments remain visible even when no separate visit was recorded.
                for (recording in chart.completedRecordings) for (note in recording.notes) {
                    addChartRow(visits, listOf(com.example.prog7314_poepart23.utils.DateTimeFormatter.formatIsoTimestamp(note.createdAtUtc),
                        note.doctorName, note.clinicalImpression, note.recommendations.orEmpty()).filter { it.isNotBlank() }.joinToString("\n"))
                }
            } else {
                state.visibility = View.VISIBLE
                state.text = getString(R.string.doctor_load_failed)
                findViewById<View>(R.id.btnUpdateCarePlan).isEnabled = false
                findViewById<View>(R.id.btnEditMedications).isEnabled = false
                renderCarePlan(CarePlan())
            }
        }
    }

    private fun addChartRow(parent: LinearLayout, label: String, action: (() -> Unit)? = null) {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(getColor(R.color.pulse_text_primary))
            val padding = (12 * resources.displayMetrics.density).toInt()
            setPadding(0, padding, 0, padding)
            minHeight = (48 * resources.displayMetrics.density).toInt()
            if (action != null) { isFocusable = true; setOnClickListener { action() } }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun renderCarePlan(plan: CarePlan) {
        findViewById<TextView?>(R.id.tvPrimaryConditionValue)?.text = plan.primaryCondition
        findViewById<TextView?>(R.id.tvSecondaryStatusValue)?.text = plan.secondaryStatus

        val meds = plan.medications
        findViewById<TextView?>(R.id.tvMedication1Name)?.text = meds.getOrNull(0)?.name.orEmpty()
        findViewById<TextView?>(R.id.tvMedication1Dose)?.text = meds.getOrNull(0)?.let { "${it.dosage} - ${it.frequency}" }.orEmpty()
        findViewById<TextView?>(R.id.tvMedication2Name)?.text = meds.getOrNull(1)?.name.orEmpty()
        findViewById<TextView?>(R.id.tvMedication2Dose)?.text = meds.getOrNull(1)?.let { "${it.dosage} - ${it.frequency}" }.orEmpty()
    }

    // Displays an interactive modal dialog allowing the physician to modify and persist care plan & medication data.
    private fun showCarePlanEditorDialog() {
        HapticFeedbackManager.confirmClick()

        val dialog = android.app.Dialog(this)
        carePlanDialog = dialog
        val view = layoutInflater.inflate(R.layout.dialog_update_care_plan, null)
        dialog.setContentView(view)

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val etCondition = view.findViewById<EditText>(R.id.etCarePlanCondition)
        val etMed1Name = view.findViewById<EditText>(R.id.etCarePlanMed1Name)
        val etMed1Dose = view.findViewById<EditText>(R.id.etCarePlanMed1Dose)
        val etMed2Name = view.findViewById<EditText>(R.id.etCarePlanMed2Name)
        val etMed2Dose = view.findViewById<EditText>(R.id.etCarePlanMed2Dose)
        val etInstructions = view.findViewById<EditText>(R.id.etCarePlanInstructions)
        val btnSave = view.findViewById<android.widget.Button>(R.id.btnSaveCarePlan)
        val btnCancel = view.findViewById<android.widget.Button>(R.id.btnCancelCarePlan)

        etCondition.setText(activeCarePlan.primaryCondition)
        etMed1Name.setText(activeCarePlan.medications.getOrNull(0)?.name ?: "")
        etMed1Dose.setText(activeCarePlan.medications.getOrNull(0)?.dosage ?: "")
        etMed2Name.setText(activeCarePlan.medications.getOrNull(1)?.name ?: "")
        etMed2Dose.setText(activeCarePlan.medications.getOrNull(1)?.dosage ?: "")
        etInstructions.setText(activeCarePlan.clinicalInstructions)

        btnCancel.setOnClickListener {
            HapticFeedbackManager.lightTick()
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val doctor = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            if (doctor == null) {
                Toast.makeText(this, R.string.l10n_signed_in_to_save_note, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            HapticFeedbackManager.confirmClick()
            val updatedPlan = CarePlan(
                primaryCondition = etCondition.text.toString().trim().ifBlank { activeCarePlan.primaryCondition },
                secondaryStatus = activeCarePlan.secondaryStatus,
                medications = listOf(
                    MedicationItem(
                        name = etMed1Name.text.toString().trim(),
                        dosage = etMed1Dose.text.toString().trim(),
                        frequency = activeCarePlan.medications.getOrNull(0)?.frequency.orEmpty()
                    ),
                    MedicationItem(
                        name = etMed2Name.text.toString().trim(),
                        dosage = etMed2Dose.text.toString().trim(),
                        frequency = activeCarePlan.medications.getOrNull(1)?.frequency.orEmpty()
                    )
                ).filter { it.name.isNotBlank() },
                clinicalInstructions = etInstructions.text.toString().trim(),
                updatedAtUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.UK).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date()),
                updatedByDoctor = doctor.displayName?.ifBlank { null } ?: doctor.email ?: doctor.uid
            )


            btnSave.isEnabled = false
            review.saveCarePlan(patientId, updatedPlan)
        }

        dialog.show()
    }

    private fun wireControls() {
        wireBack(R.id.btnBack)

        onClick(R.id.btnViewAllVitals) {
            HapticFeedbackManager.lightTick()
            startActivity(
                Intent(this, DoctorPatientEcgHistoryActivity::class.java).apply {
                    putExtra(PatientListActivity.EXTRA_PATIENT_ID, patientId)
                    putExtra(DoctorAlertsActivity.EXTRA_PATIENT_NAME, patientName)
                }
            )
        }

        // Both the pencil icon next to Medications and the Update Care Plan button open the Care Plan editor
        findViewById<ImageView?>(R.id.btnEditMedications)?.setOnClickListener {
            showCarePlanEditorDialog()
        }

        onClick(R.id.btnUpdateCarePlan) {
            showCarePlanEditorDialog()
        }

        findViewById<View>(R.id.btnContactPatient).visibility = View.GONE
    }
}
