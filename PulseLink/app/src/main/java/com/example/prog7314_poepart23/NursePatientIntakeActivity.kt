/*
 * Nurse clinic patient intake and selection screen backed by Firebase RTDB via PulseLinkAPI.
 * Allows Nurses to search clinic patients, register incoming patients with an approved
 * attending Doctor, and select an active patient for pre-session checks and ECG capture.
 */
package com.example.prog7314_poepart23

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiClient
import com.example.prog7314_poepart23.data.remote.models.ApprovedDoctorDto
import com.example.prog7314_poepart23.data.remote.models.DoctorPatientSummaryDto
import com.example.prog7314_poepart23.data.remote.models.PatientRegistrationDto
import com.example.prog7314_poepart23.data.repository.UserRepository
import com.example.prog7314_poepart23.security.SecurePreferences
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.wireBack
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class NursePatientIntakeActivity : AppCompatActivity() {

    companion object {
        private const val CACHE_PREFS = "pulselink_nurse_intake_cache_v1"
        private const val MANUAL_DOCTOR_UID_SENTINEL = "__MANUAL__"
        private const val DEFAULT_CLINIC_SITE_ID = "SITE-49b190565d1249a3a15d1bc4132961a5"
        private val SEEDED_SITE_DOCTORS = listOf(
            ApprovedDoctorDto(
                uid = "b028WjwkvbaFj23D9ZQwPQtIRIj2",
                name = "Cameron van Heerden",
                email = "camerondevs.code@gmail.com"
            ),
            ApprovedDoctorDto(
                uid = "1WY21ufmzaRjNtZKYs7vK484NE13",
                name = "Maxine Palm",
                email = "maxinepalm2004@gmail.com"
            )
        )
    }

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val prefs by lazy { SecurePreferences.getInstance(this) }
    private val userRepo = UserRepository()
    private val gson = Gson()

    private lateinit var etSearchClinicPatient: EditText
    private lateinit var tvIntakeSyncStatus: TextView
    private lateinit var tvIntakeCountTotal: TextView
    private lateinit var tvIntakeCountPending: TextView
    private lateinit var progressIntake: ProgressBar
    private lateinit var tvIntakeEmpty: TextView
    private lateinit var layoutClinicPatientsList: LinearLayout
    private lateinit var btnNewPatientIntake: MaterialButton

    private var assignedSiteId: String = ""
    private var assignedSiteName: String = ""
    private val allPatients = mutableListOf<DoctorPatientSummaryDto>()
    private val cachedDoctors = mutableListOf<ApprovedDoctorDto>()
    private var currentQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.nurse_patient_intake)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        if (auth.currentUser == null) {
            finish()
            return
        }

        HapticFeedbackManager.initialise(this)
        wireBack(R.id.btnBackIntake)
        bindViews()
        wireControls()
        BottomNavHelper.setUpNurseNav(this, BottomNavHelper.NurseTab.PATIENTS)

        val uid = auth.currentUser?.uid.orEmpty()
        allPatients.addAll(readCachedPatients(uid))
        if (assignedSiteId.isBlank()) {
            assignedSiteId = prefs.getSelectedNurseSiteId()
                ?.takeIf { it.isNotBlank() }
                ?: allPatients.firstOrNull { it.siteId.isNotBlank() }?.siteId.orEmpty()
        }
        mergeDoctorDirectory()
        renderPatientList()
    }

    override fun onResume() {
        super.onResume()
        BottomNavHelper.setUpNurseNav(this, BottomNavHelper.NurseTab.PATIENTS)
        loadClinicQueueAndDoctors()
    }

    private fun bindViews() {
        etSearchClinicPatient = findViewById(R.id.etSearchClinicPatient)
        tvIntakeSyncStatus = findViewById(R.id.tvIntakeSyncStatus)
        tvIntakeCountTotal = findViewById(R.id.tvIntakeCountTotal)
        tvIntakeCountPending = findViewById(R.id.tvIntakeCountPending)
        progressIntake = findViewById(R.id.progressIntake)
        tvIntakeEmpty = findViewById(R.id.tvIntakeEmpty)
        layoutClinicPatientsList = findViewById(R.id.layoutClinicPatientsList)
        btnNewPatientIntake = findViewById(R.id.btnNewPatientIntake)
    }

    private fun wireControls() {
        btnNewPatientIntake.setOnClickListener {
            HapticFeedbackManager.lightTick()
            openRegisterPatientModal()
        }
        etSearchClinicPatient.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString()?.trim().orEmpty()
                renderPatientList()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun loadClinicQueueAndDoctors() {
        val uid = auth.currentUser?.uid ?: return
        progressIntake.visibility = View.VISIBLE

        lifecycleScope.launch {
            runCatching { userRepo.refreshAuthToken() }

            val meRes = runCatching {
                withContext(Dispatchers.IO) { PulseLinkApiClient.apiService.getAccessAccount() }
            }.getOrNull()
            if (meRes != null && meRes.isSuccessful && meRes.body() != null) {
                assignedSiteId = meRes.body()!!.siteIds.firstOrNull().orEmpty()
            }

            val sitesRes = runCatching {
                withContext(Dispatchers.IO) { PulseLinkApiClient.apiService.getAssignedSites() }
            }.getOrNull()
            if (sitesRes != null && sitesRes.isSuccessful && !sitesRes.body().isNullOrEmpty()) {
                val site = sitesRes.body()!!.first()
                assignedSiteId = site.id
                assignedSiteName = site.name
                prefs.saveNurseSiteName(site.name)
            }

            val patientsRes = runCatching {
                withContext(Dispatchers.IO) { PulseLinkApiClient.apiService.getAssignedClinicalPatients(limit = 50) }
            }.getOrNull()

            if (patientsRes != null && patientsRes.isSuccessful && patientsRes.body() != null) {
                val remoteItems = patientsRes.body()!!.items
                allPatients.clear()
                allPatients.addAll(remoteItems)
                saveCachedPatients(uid, remoteItems)
                tvIntakeSyncStatus.text = getString(R.string.nurse_ui_intake_sync_online)
            } else {
                tvIntakeSyncStatus.text = getString(R.string.nurse_ui_intake_sync_offline)
            }

            val doctorsRes = runCatching {
                withContext(Dispatchers.IO) { PulseLinkApiClient.apiService.getApprovedClinicDoctors() }
            }.getOrNull()
            val remoteDoctors = if (doctorsRes != null && doctorsRes.isSuccessful) {
                doctorsRes.body().orEmpty()
            } else {
                emptyList()
            }
            mergeDoctorDirectory(remoteDoctors)

            progressIntake.visibility = View.GONE
            renderPatientList()
        }
    }

    private fun renderPatientList() {
        val selectedPatientId = prefs.getSelectedNursePatientId()
        tvIntakeCountTotal.text = allPatients.size.toString()
        tvIntakeCountPending.text = allPatients.sumOf { it.awaitingReviewCount }.toString()

        val queryLower = currentQuery.lowercase(Locale.getDefault())
        val filtered = if (queryLower.isBlank()) {
            allPatients
        } else {
            allPatients.filter {
                it.fullName.lowercase(Locale.getDefault()).contains(queryLower) ||
                    it.patientId.lowercase(Locale.getDefault()).contains(queryLower) ||
                    it.primaryDoctorUid.lowercase(Locale.getDefault()).contains(queryLower)
            }
        }

        layoutClinicPatientsList.removeAllViews()
        tvIntakeEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

        filtered.forEach { patient ->
            layoutClinicPatientsList.addView(buildPatientCard(patient, patient.patientId == selectedPatientId))
        }
    }

    private fun buildPatientCard(
        patient: DoctorPatientSummaryDto,
        isSelected: Boolean
    ): MaterialCardView {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            radius = 16f * density
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.pulse_card))
            strokeWidth = dp(if (isSelected) 2 else 1)
            strokeColor = ContextCompat.getColor(
                context,
                if (isSelected) R.color.pulse_primary else R.color.pulse_border
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val idBadge = TextView(this).apply {
            text = patient.patientId
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.pulse_picker_accent))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor(ContextCompat.getColor(context, R.color.pulse_blue_bg))
            }
        }
        topRow.addView(idBadge)

        val statusLabel = when {
            patient.awaitingReviewCount > 0 -> getString(R.string.nurse_ui_intake_pending_label)
            patient.releasedSessionsCount > 0 -> getString(R.string.nurse_ui_intake_released_label)
            else -> patient.siteName.ifBlank { assignedSiteName.ifBlank { getString(R.string.nurse_ui_intake_title) } }
        }
        val statusPill = TextView(this).apply {
            text = statusLabel
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            val isPending = patient.awaitingReviewCount > 0
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isPending) R.color.pulse_warning else R.color.pulse_success
                )
            )
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor(
                    ContextCompat.getColor(
                        context,
                        if (isPending) R.color.pulse_warning_bg else R.color.pulse_success_bg
                    )
                )
            }
        }
        topRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        topRow.addView(statusPill)
        container.addView(topRow)

        val tvName = TextView(this).apply {
            text = patient.fullName
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.pulse_text_primary))
            setPadding(0, dp(10), 0, dp(2))
        }
        container.addView(tvName)

        val dobLine = patient.dateOfBirth?.takeIf { it.isNotBlank() }
            ?: getString(R.string.nurse_ui_intake_dob_unknown)
        val tvDob = TextView(this).apply {
            text = dobLine
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.pulse_text_secondary))
        }
        container.addView(tvDob)

        val doctorName = resolveDoctorLabel(patient.primaryDoctorUid)
        val tvDoctor = TextView(this).apply {
            text = getString(R.string.nurse_ui_intake_doctor_named, doctorName)
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.pulse_text_secondary))
            setPadding(0, dp(4), 0, dp(12))
        }
        container.addView(tvDoctor)

        val btnSelect = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonStyle
        ).apply {
            text = getString(R.string.nurse_ui_intake_select_btn)
            isAllCaps = false
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            cornerRadius = dp(10)
            setBackgroundColor(ContextCompat.getColor(context, R.color.pulse_primary_fill))
            setTextColor(ContextCompat.getColor(context, R.color.white))
            setOnClickListener {
                HapticFeedbackManager.confirmClick()
                selectPatientForEcg(patient)
            }
        }
        container.addView(
            btnSelect,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        )

        card.addView(container)
        return card
    }

    private fun resolveDoctorLabel(doctorUid: String): String {
        if (doctorUid.isBlank()) return getString(R.string.l10n_unavailable)
        val match = cachedDoctors.firstOrNull { it.uid == doctorUid }
        return match?.displayWithEmail() ?: doctorUid
    }

    private fun mergeDoctorDirectory(remoteDoctors: List<ApprovedDoctorDto> = emptyList()) {
        val byUid = LinkedHashMap<String, ApprovedDoctorDto>()
        fun upsert(doc: ApprovedDoctorDto) {
            val cleanUid = doc.uid.trim()
            if (cleanUid.isBlank()) return
            val existing = byUid[cleanUid]
            val parsedEmailFromName = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
                .find(doc.name)?.value.orEmpty()
            val mergedEmail = doc.email.trim()
                .ifBlank { parsedEmailFromName }
                .ifBlank { existing?.email.orEmpty() }
            val mergedName = doc.name.trim()
                .takeIf { it.isNotBlank() && it != cleanUid }
                ?: existing?.name?.takeIf { it.isNotBlank() && it != cleanUid }
                ?: mergedEmail.ifBlank { cleanUid }
            byUid[cleanUid] = ApprovedDoctorDto(uid = cleanUid, name = mergedName, email = mergedEmail)
        }

        SEEDED_SITE_DOCTORS.forEach(::upsert)
        readCachedDoctors().forEach(::upsert)
        remoteDoctors.forEach(::upsert)
        allPatients
            .flatMap { listOf(it.primaryDoctorUid) + it.additionalDoctorUids }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { docUid ->
                if (!byUid.containsKey(docUid)) {
                    upsert(ApprovedDoctorDto(uid = docUid, name = docUid))
                }
            }

        cachedDoctors.clear()
        cachedDoctors.addAll(byUid.values)
        saveCachedDoctors(cachedDoctors)
    }

    private fun readCachedDoctors(): List<ApprovedDoctorDto> {
        val raw = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE).getString("doctors_directory_v1", null)
            ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<ApprovedDoctorDto>>() {}.type
            gson.fromJson<List<ApprovedDoctorDto>>(raw, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun saveCachedDoctors(doctors: List<ApprovedDoctorDto>) {
        getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
            .edit()
            .putString("doctors_directory_v1", gson.toJson(doctors))
            .apply()
    }

    private fun selectPatientForEcg(patient: DoctorPatientSummaryDto) {
        val doctorLabel = resolveDoctorLabel(patient.primaryDoctorUid)
        prefs.saveSelectedNursePatient(
            patientId = patient.patientId,
            patientName = patient.fullName,
            dateOfBirth = patient.dateOfBirth,
            doctorUid = patient.primaryDoctorUid,
            doctorName = doctorLabel,
            siteId = patient.siteId.ifBlank { assignedSiteId }
        )
        Toast.makeText(
            this,
            getString(R.string.nurse_ui_intake_patient_selected, patient.fullName),
            Toast.LENGTH_SHORT
        ).show()
        startActivity(Intent(this, EcgPreSessionCheckActivity::class.java))
    }

    private fun openRegisterPatientModal() {
        val siteId = assignedSiteId
            .ifBlank { prefs.getSelectedNurseSiteId().orEmpty() }
            .ifBlank { allPatients.firstOrNull { it.siteId.isNotBlank() }?.siteId.orEmpty() }
            .ifBlank { DEFAULT_CLINIC_SITE_ID }

        val dialogView = layoutInflater.inflate(R.layout.dialog_register_clinic_patient, null)
        val etFullName = dialogView.findViewById<EditText>(R.id.etDialogPatientFullName)
        val etDob = dialogView.findViewById<EditText>(R.id.etDialogPatientDob)
        val rowDob = dialogView.findViewById<View>(R.id.rowDialogPatientDob)
        val btnPickDob = dialogView.findViewById<View>(R.id.btnPickDobCalendar)
        val rowDoctorPicker = dialogView.findViewById<View>(R.id.rowDialogDoctorSpinner)
        val tvSelectedDoctor = dialogView.findViewById<TextView>(R.id.tvSelectedAttendingDoctor)
        val rowCustomDoctor = dialogView.findViewById<View>(R.id.rowDialogCustomDoctorUid)
        val etCustomDoctorUid = dialogView.findViewById<EditText>(R.id.etDialogCustomDoctorUid)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelRegisterPatientDialog)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirmRegisterPatientDialog)

        val openDobPicker = View.OnClickListener {
            HapticFeedbackManager.lightTick()
            val cal = Calendar.getInstance().apply { set(2000, Calendar.JANUARY, 1) }
            val existing = etDob.text.toString().trim()
            if (existing.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) {
                val parts = existing.split("-")
                cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
            }
            DatePickerDialog(
                this,
                R.style.ThemeOverlay_PulseLink_DatePicker,
                { _, year, month, dayOfMonth ->
                    etDob.setText(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth))
                    HapticFeedbackManager.lightTick()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).apply {
                datePicker.maxDate = System.currentTimeMillis()
                show()
            }
        }
        btnPickDob.setOnClickListener(openDobPicker)
        rowDob.setOnClickListener(openDobPicker)

        if (cachedDoctors.isEmpty()) mergeDoctorDirectory()
        val options = cachedDoctors.toMutableList()
        options.add(
            ApprovedDoctorDto(
                uid = MANUAL_DOCTOR_UID_SENTINEL,
                name = getString(R.string.nurse_ui_modal_doctor_uid_hint)
            )
        )
        var selectedOption = options.first()
        fun syncSelectedDoctorUi() {
            val isManual = selectedOption.uid == MANUAL_DOCTOR_UID_SENTINEL
            tvSelectedDoctor.text = if (isManual) selectedOption.name else selectedOption.displayWithEmail()
            rowCustomDoctor.visibility = if (isManual) View.VISIBLE else View.GONE
        }
        syncSelectedDoctorUi()
        rowDoctorPicker.setOnClickListener {
            HapticFeedbackManager.lightTick()
            showDoctorPickerDialog(options, selectedOption.uid) { chosen ->
                selectedOption = chosen
                syncSelectedDoctorUi()
            }
        }

        val dialog = createCardDialog(dialogView)
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val fullName = etFullName.text.toString().trim()
            if (fullName.isBlank()) {
                etFullName.error = getString(R.string.registration_toast_missing_fields)
                return@setOnClickListener
            }
            val doctorUid = if (selectedOption.uid == MANUAL_DOCTOR_UID_SENTINEL) {
                etCustomDoctorUid.text.toString().trim()
            } else {
                selectedOption.uid
            }
            if (doctorUid.isBlank()) {
                Toast.makeText(this, R.string.nurse_ui_intake_no_doctor, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val dob = etDob.text.toString().trim().takeIf { it.isNotBlank() }

            btnConfirm.isEnabled = false
            lifecycleScope.launch {
                runCatching { userRepo.refreshAuthToken() }
                val res = runCatching {
                    withContext(Dispatchers.IO) {
                        PulseLinkApiClient.apiService.registerClinicalPatient(
                            PatientRegistrationDto(
                                fullName = fullName,
                                siteId = siteId,
                                primaryDoctorUid = doctorUid,
                                dateOfBirth = dob
                            )
                        )
                    }
                }.getOrNull()
                btnConfirm.isEnabled = true

                if (res != null && res.isSuccessful && res.body() != null) {
                    val created = res.body()!!
                    val summary = DoctorPatientSummaryDto(
                        patientId = created.id,
                        fullName = created.fullName,
                        dateOfBirth = created.dateOfBirth ?: dob,
                        siteId = created.siteId,
                        siteName = assignedSiteName,
                        primaryDoctorUid = created.primaryDoctorUid,
                        additionalDoctorUids = created.additionalDoctorUids,
                        version = created.version
                    )
                    allPatients.add(0, summary)
                    auth.currentUser?.uid?.let { saveCachedPatients(it, allPatients) }
                    renderPatientList()
                    Toast.makeText(
                        this@NursePatientIntakeActivity,
                        R.string.nurse_ui_intake_registered_ok,
                        Toast.LENGTH_LONG
                    ).show()
                    dialog.dismiss()
                } else {
                    val code = res?.code() ?: 0
                    Toast.makeText(
                        this@NursePatientIntakeActivity,
                        "${getString(R.string.nurse_ui_intake_no_doctor)} (HTTP $code)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        dialog.show()
    }

    private fun showDoctorPickerDialog(
        options: List<ApprovedDoctorDto>,
        selectedUid: String,
        onSelected: (ApprovedDoctorDto) -> Unit
    ) {
        val pickerView = layoutInflater.inflate(R.layout.dialog_select_attending_doctor, null)
        val container = pickerView.findViewById<LinearLayout>(R.id.layoutDoctorDialogList)
        val btnCancel = pickerView.findViewById<MaterialButton>(R.id.btnCancelDoctorDialog)
        val pickerDialog = createCardDialog(pickerView)

        val density = resources.displayMetrics.density
        fun dpx(v: Int) = (v * density).toInt()

        options.forEach { doc ->
            val isManual = doc.uid == MANUAL_DOCTOR_UID_SENTINEL
            val isSelected = doc.uid == selectedUid
            val initials = if (isManual) {
                "ID"
            } else {
                doc.name.split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .take(2)
                    .joinToString("") { it.take(1).uppercase(Locale.getDefault()) }
                    .ifBlank { "DR" }
            }

            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpx(10) }
                radius = 14f * density
                cardElevation = 0f
                setCardBackgroundColor(
                    ContextCompat.getColor(
                        context,
                        if (isSelected) R.color.pulse_blue_bg else R.color.pulse_bg
                    )
                )
                strokeWidth = dpx(if (isSelected) 2 else 1)
                strokeColor = ContextCompat.getColor(
                    context,
                    if (isSelected) R.color.pulse_primary else R.color.pulse_border
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    HapticFeedbackManager.lightTick()
                    pickerDialog.dismiss()
                    onSelected(doc)
                }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpx(14), dpx(14), dpx(14), dpx(14))
            }

            val badge = TextView(this).apply {
                text = initials
                textSize = 13f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.pulse_picker_accent))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(context, R.color.pulse_card))
                }
            }
            row.addView(badge, LinearLayout.LayoutParams(dpx(40), dpx(40)))

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dpx(12)
                }
            }

            val tvName = TextView(this).apply {
                text = doc.name.ifBlank { doc.email.ifBlank { doc.uid } }
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.pulse_text_primary))
            }
            col.addView(tvName)

            val subtitleText = when {
                isManual -> getString(R.string.nurse_ui_modal_doctor_label)
                doc.email.isNotBlank() -> doc.email
                else -> doc.uid
            }
            val tvEmail = TextView(this).apply {
                text = subtitleText
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.pulse_text_secondary))
                setPadding(0, dpx(2), 0, 0)
            }
            col.addView(tvEmail)

            row.addView(col)
            card.addView(row)
            container.addView(card)
        }

        btnCancel.setOnClickListener {
            HapticFeedbackManager.lightTick()
            pickerDialog.dismiss()
        }
        pickerDialog.show()
    }

    private fun createCardDialog(content: View): AppCompatDialog {
        return AppCompatDialog(
            this,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
        ).apply {
            supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(content)
            window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                val dm = resources.displayMetrics
                val maxWidth = (500 * dm.density).toInt()
                setLayout(minOf(dm.widthPixels - (24 * dm.density).toInt(), maxWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun readCachedPatients(uid: String): List<DoctorPatientSummaryDto> {
        val raw = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE).getString("patients_$uid", null)
            ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<DoctorPatientSummaryDto>>() {}.type
            gson.fromJson<List<DoctorPatientSummaryDto>>(raw, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun saveCachedPatients(uid: String, items: List<DoctorPatientSummaryDto>) {
        getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
            .edit()
            .putString("patients_$uid", gson.toJson(items))
            .apply()
    }
}
