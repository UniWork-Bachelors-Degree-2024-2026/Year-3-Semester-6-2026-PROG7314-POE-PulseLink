/*
 * Activity facilitating doctor clinical impressions, diagnostic observations,
 * and follow-up recommendations for recorded patient ECG sessions.
 *
 * Reference List:
 * - Google Cloud, 2024. Firebase Authentication Documentation. [online] Available at: <https://firebase.google.com/docs/auth> [Accessed 21 September 2026].
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import com.example.prog7314_poepart23.data.repository.PatientPortalResult
import com.example.prog7314_poepart23.viewmodels.DoctorReviewViewModel
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import androidx.lifecycle.lifecycleScope              // (Google Developers, 2024)
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.prog7314_poepart23.adapters.NotesListAdapter
import com.example.prog7314_poepart23.data.remote.models.CreateDoctorNoteDto
import com.example.prog7314_poepart23.data.repository.EcgRepository
import com.example.prog7314_poepart23.databinding.DoctorSessionNotesBinding
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.utils.onClick
import com.example.prog7314_poepart23.utils.wireBack
import com.google.firebase.auth.FirebaseAuth         // (Google Cloud, 2024)
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch                     // (JetBrains, 2024)
import kotlinx.coroutines.withContext
import java.util.Locale

class DoctorSessionNotesActivity : AppCompatActivity() { // (Google Developers, 2024)

    private lateinit var binding: DoctorSessionNotesBinding
    private lateinit var notesListAdapter: NotesListAdapter
    private val review: DoctorReviewViewModel by viewModels()
    private val auth = FirebaseAuth.getInstance()

    private var sessionId: String? = null
    private var patientId: String? = null
    private var patientName: String? = null
    private var averageBpm: Int = 0

    // Interactive recommendations selection state
    private var recMonitoring: Boolean = true
    private var recFollowUp: Boolean = false
    private var recMedication: Boolean = false
    private var recIntervention: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = DoctorSessionNotesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        sessionId = intent.getStringExtra(PatientEcgHistoryActivity.EXTRA_SESSION_ID)
        patientId = intent.getStringExtra(PatientListActivity.EXTRA_PATIENT_ID)?.ifBlank { null }

        patientName = intent.getStringExtra(DoctorAlertsActivity.EXTRA_PATIENT_NAME)

        averageBpm = intent.getIntExtra(PatientEcgHistoryActivity.EXTRA_AVERAGE_BPM, 0)

        if (sessionId.isNullOrBlank() || patientId.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.l10n_unable_load_notes_screen), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        HapticFeedbackManager.initialise(this)
        populatePatientInfo()
        binding.hospitalPaperEcgView.visibility = android.view.View.GONE
        setupRecommendations()
        setUpPreviousNotesList()
        review.sessionReview.observe(this) { result ->
            if (result is PatientPortalResult.Ready) {
                notesListAdapter.submitList(result.value.draftNotes)
                binding.tvSeeMoreNotes.text = getString(R.string.doctor_visits)
                patientId = result.value.patientId
                patientName = result.value.patientName
                populatePatientInfo()
            } else binding.tvSeeMoreNotes.text = getString(R.string.doctor_load_failed)
        }
        review.savedNote.observe(this) { result ->
            binding.btnSaveClinicalNote.isEnabled = true
            if (result is PatientPortalResult.Ready) {
                binding.etClinicalObservation.text.clear()
                Toast.makeText(this, getString(R.string.l10n_note_saved_ok), Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this, getString(R.string.l10n_unable_save_note), Toast.LENGTH_SHORT).show()
        }
        loadPreviousNotes()
        wireControls()
        BottomNavHelper.setUpDoctorNavDetached(this)
    }

    override fun onResume() {
        super.onResume()
        BottomNavHelper.setUpDoctorNavDetached(this)
    }

    private fun populatePatientInfo() {
        val pName = patientName ?: getString(R.string.greeting_empty)
        val pId = patientId ?: return
        binding.tvPatientName.text = pName
        binding.tvPatientId.text = getString(R.string.l10n_id_format, pId)
        com.example.prog7314_poepart23.utils.HeartRateStatus.apply(binding.tvHeartRate, averageBpm, if (averageBpm > 0) getString(R.string.l10n_bpm_value, averageBpm) else getString(R.string.l10n_bpm_none))
    }

    private fun setupRecommendations() {
        updateRecommendationViews()

        binding.layoutRecMonitoring.setOnClickListener {
            HapticFeedbackManager.lightTick()
            recMonitoring = !recMonitoring
            updateRecommendationViews()
        }

        binding.layoutRecFollowUp.setOnClickListener {
            HapticFeedbackManager.lightTick()
            recFollowUp = !recFollowUp
            updateRecommendationViews()
        }

        binding.layoutRecMedication.setOnClickListener {
            HapticFeedbackManager.lightTick()
            recMedication = !recMedication
            updateRecommendationViews()
        }

        binding.layoutRecIntervention.setOnClickListener {
            HapticFeedbackManager.warningBuzz()
            recIntervention = !recIntervention
            updateRecommendationViews()
        }
    }

    private fun updateRecommendationViews() {
        binding.ivRecMonitoringCheck.setImageResource(
            if (recMonitoring) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox_unchecked
        )
        binding.layoutRecMonitoring.setBackgroundResource(
            if (recMonitoring) R.drawable.bg_card_selected else R.drawable.bg_card_stroke
        )

        binding.ivRecFollowUpCheck.setImageResource(
            if (recFollowUp) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox_unchecked
        )
        binding.layoutRecFollowUp.setBackgroundResource(
            if (recFollowUp) R.drawable.bg_card_selected else R.drawable.bg_card_stroke
        )

        binding.ivRecMedicationCheck.setImageResource(
            if (recMedication) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox_unchecked
        )
        binding.layoutRecMedication.setBackgroundResource(
            if (recMedication) R.drawable.bg_card_selected else R.drawable.bg_card_stroke
        )

        binding.ivRecInterventionCheck.setImageResource(
            if (recIntervention) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox_unchecked
        )
        binding.layoutRecIntervention.setBackgroundResource(
            if (recIntervention) R.drawable.bg_card_danger_light else R.drawable.bg_card_stroke
        )
    }

    private fun getSelectedRecommendationsList(): List<String> {
        val list = mutableListOf<String>()
        if (recMonitoring) list.add(getString(R.string.rt_rec_monitoring))
        if (recFollowUp) list.add(getString(R.string.rt_rec_follow_up))
        if (recMedication) list.add(getString(R.string.rt_rec_medication))
        if (recIntervention) list.add(getString(R.string.rt_rec_intervention))
        return list
    }

    private fun setUpPreviousNotesList() {
        notesListAdapter = NotesListAdapter { note ->
            HapticFeedbackManager.lightTick()
            Toast.makeText(this, "${note.doctorName}: ${note.clinicalImpression}", Toast.LENGTH_SHORT).show()
        }
        binding.rvPreviousNotes.layoutManager = LinearLayoutManager(this)
        binding.rvPreviousNotes.adapter = notesListAdapter
        binding.rvPreviousNotes.setHasFixedSize(false)
    }

    private fun loadPreviousNotes() {
        sessionId?.let { review.loadSessionReview(it) }
    }

    private fun saveNote() {
        val observation = binding.etClinicalObservation.text.toString().trim()
        if (observation.isEmpty()) {
            Toast.makeText(this, getString(R.string.l10n_enter_observation), Toast.LENGTH_SHORT).show()
            return
        }

        val id = sessionId ?: return
        val pId = patientId ?: return
        val doctorUid = auth.currentUser?.uid
        if (doctorUid == null) {
            Toast.makeText(this, getString(R.string.l10n_signed_in_to_save_note), Toast.LENGTH_SHORT).show()
            return
        }
        val doctorName = auth.currentUser?.displayName?.ifBlank { null } ?: auth.currentUser?.email ?: getString(R.string.l10n_unknown_doctor)

        HapticFeedbackManager.confirmClick()

        val request = CreateDoctorNoteDto(
            sessionId = id,
            patientId = pId,
            doctorId = doctorUid,
            doctorName = doctorName,
            clinicalImpression = observation,
            recommendations = getSelectedRecommendationsList().joinToString("; "),
            followUpRequired = recFollowUp || recIntervention
        )

        binding.btnSaveClinicalNote.isEnabled = false
        review.saveAssessmentNote(request)
    }

    private fun wireControls() {
        wireBack(R.id.btnBack)
        onClick(R.id.btnSaveClinicalNote) { saveNote() }
        onClick(R.id.tvSeeMoreNotes) { loadPreviousNotes() }
    }
}
