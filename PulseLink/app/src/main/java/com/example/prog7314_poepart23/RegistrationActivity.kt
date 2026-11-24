/*
 * User account creation activity facilitating patient & clinician registration,
 * demographic profile collection, and subsequent dashboard routing.
 *
 * Reference List:
 * - Google Cloud, 2024. Firebase Authentication Documentation. [online] Available at: <https://firebase.google.com/docs/auth> [Accessed 21 September 2026].
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 * - Google Developers, 2024. Material Components for Android. [online] Available at: <https://github.com/material-components/material-components-android> [Accessed 21 September 2026].
 * - Google Developers, 2024. ViewModel Overview - Android Developers. [online] Available at: <https://developer.android.com/topic/libraries/architecture/viewmodel> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.app.DatePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.activity.viewModels                   // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import com.example.prog7314_poepart23.security.PendingBiometricEnrollment
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.utils.SessionManager
import com.example.prog7314_poepart23.utils.routeAfterPasswordAuth
import com.example.prog7314_poepart23.viewmodels.LoginViewModel
import com.google.android.material.button.MaterialButton // (Google Developers, 2024)
import com.google.firebase.auth.FirebaseAuth         // (Google Cloud, 2024)
import java.util.Calendar
import java.util.Locale

class RegistrationActivity : AppCompatActivity() { // (Google Developers, 2024)

    private val loginViewModel: LoginViewModel by viewModels()
    private var selectedRole: String = "patient"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.login_registration)

        // Initialise centralised haptics
        HapticFeedbackManager.initialise(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val btnRolePatient = findViewById<MaterialButton>(R.id.btnRolePatient)
        val btnRoleDoctor = findViewById<MaterialButton>(R.id.btnRoleDoctor)
        val btnRoleNurse = findViewById<MaterialButton>(R.id.btnRoleNurse)
        val layoutPatientFields = findViewById<LinearLayout>(R.id.layoutPatientFields)
        val layoutDoctorFields = findViewById<LinearLayout>(R.id.layoutDoctorFields)

        val etFullName = findViewById<EditText>(R.id.etFullName)
        val etEmail = findViewById<EditText>(R.id.etEmailRegister)
        val etPhone = findViewById<EditText>(R.id.etPhoneNumber)
        val etDob = findViewById<EditText>(R.id.etDateOfBirth)
        val btnPickDob = findViewById<View>(R.id.btnPickDob)
        val spinnerGender = findViewById<Spinner>(R.id.spinnerGender)
        val etEmergencyName = findViewById<EditText>(R.id.etEmergencyContactName)
        val etEmergencyPhone = findViewById<EditText>(R.id.etEmergencyContactPhone)

        val etDocSpecialization = findViewById<EditText>(R.id.etDoctorSpecialization)
        val etDocPractice = findViewById<EditText>(R.id.etDoctorPracticeNumber)
        val etDocHospital = findViewById<EditText>(R.id.etDoctorHospital)

        val etPassword = findViewById<EditText>(R.id.etPasswordRegister)
        val etConfirmPassword = findViewById<EditText>(R.id.etConfirmPasswordRegister)
        val btnCreateAccount = findViewById<MaterialButton>(R.id.btnCreateAccount)

        // Setup Gender Spinner
        val genderOptions = arrayOf(
            getString(R.string.patient_profile_gender_male),
            getString(R.string.patient_profile_gender_female),
            getString(R.string.patient_profile_gender_other),
            getString(R.string.patient_profile_gender_prefer_not_to_say)
        )
        val genderAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, genderOptions)
        spinnerGender.adapter = genderAdapter

        // Setup DatePicker Dialog
        val calendar = Calendar.getInstance()
        val showDatePicker = {
            val dateSetListener = DatePickerDialog.OnDateSetListener { _, year, month, dayOfMonth ->
                val formattedDob = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth)
                etDob.setText(formattedDob)
                HapticFeedbackManager.lightTick()
            }
            val initialYear = calendar.get(Calendar.YEAR) - 25
            val initialMonth = calendar.get(Calendar.MONTH)
            val initialDay = calendar.get(Calendar.DAY_OF_MONTH)
            DatePickerDialog(this, R.style.ThemeOverlay_PulseLink_DatePicker, dateSetListener, initialYear, initialMonth, initialDay).apply {
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }

        btnPickDob.setOnClickListener {
            HapticFeedbackManager.lightTick()
            showDatePicker()
        }
        etDob.setOnClickListener {
            HapticFeedbackManager.lightTick()
            showDatePicker()
        }

        // Setup Role Selector Tabs
        fun updateRoleUI(role: String) {
            selectedRole = role
            HapticFeedbackManager.lightTick()
            val primaryColor = ContextCompat.getColor(this, R.color.pulse_primary)
            val transparentColor = ContextCompat.getColor(this, android.R.color.transparent)
            val whiteColor = ContextCompat.getColor(this, R.color.white)
            val secondaryTextColor = ContextCompat.getColor(this, R.color.pulse_text_secondary)

            listOf(
                "patient" to btnRolePatient,
                "doctor" to btnRoleDoctor,
                "nurse" to btnRoleNurse
            ).forEach { (tabRole, button) ->
                val active = tabRole == role
                button.backgroundTintList = ColorStateList.valueOf(if (active) primaryColor else transparentColor)
                button.setTextColor(if (active) whiteColor else secondaryTextColor)
            }

            if (role == "patient") {
                layoutPatientFields.visibility = View.VISIBLE
                layoutDoctorFields.visibility = View.GONE
            } else {
                layoutPatientFields.visibility = View.GONE
                layoutDoctorFields.visibility = View.VISIBLE
            }
        }

        btnRolePatient.setOnClickListener { updateRoleUI("patient") }
        btnRoleDoctor.setOnClickListener { updateRoleUI("doctor") }
        btnRoleNurse.setOnClickListener { updateRoleUI("nurse") }

        setupRegistrationObservers(btnCreateAccount)

        btnCreateAccount.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            val fullName = etFullName.text.toString().trim()
            val email = etEmail.text.toString().trim()
            val phone = etPhone.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val confirmPassword = etConfirmPassword.text.toString().trim()

            if (fullName.isEmpty() || email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
                Toast.makeText(this, getString(R.string.registration_toast_missing_fields), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password != confirmPassword) {
                HapticFeedbackManager.warningBuzz()
                Toast.makeText(this, getString(R.string.error_passwords_do_not_match), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Held in memory only, so BiometricSetupActivity can offer to save this sign-in.
            PendingBiometricEnrollment.stage(email, password)

            // A selected staff role is an application for approval, never a privilege grant.
            val assignedRole = selectedRole

            if (assignedRole == "patient") {
                val dob = etDob.text.toString().trim()
                val gender = spinnerGender.selectedItem?.toString() ?: getString(R.string.patient_profile_gender_prefer_not_to_say)
                val emergencyName = etEmergencyName.text.toString().trim()
                val emergencyPhone = etEmergencyPhone.text.toString().trim()

                loginViewModel.register(
                    email = email,
                    password = password,
                    name = fullName,
                    role = "patient",
                    dateOfBirth = dob,
                    gender = gender,
                    phoneNumber = phone,
                    emergencyContactName = emergencyName,
                    emergencyContactPhone = emergencyPhone,
                    emergencyContactRelationship = null,
                    assignedDoctorName = null
                )
            } else {
                val spec = etDocSpecialization.text.toString().trim().ifBlank { null }
                val practice = etDocPractice.text.toString().trim().ifBlank { null }
                val hospital = etDocHospital.text.toString().trim().ifBlank { null }

                loginViewModel.register(
                    email = email,
                    password = password,
                    name = fullName,
                    role = assignedRole,
                    phoneNumber = phone,
                    specialization = spec,
                    practiceNumber = practice,
                    hospitalAffiliation = hospital
                )
            }
        }

        findViewById<TextView>(R.id.tvLogInHere).setOnClickListener {
            HapticFeedbackManager.lightTick()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun setupRegistrationObservers(btnCreateAccount: MaterialButton) {
        loginViewModel.authState.observe(this) { resource ->
            when (resource) {
                is Resource.Loading -> {
                    btnCreateAccount.isEnabled = false
                    btnCreateAccount.text = getString(R.string.create_account) + "…"
                }
                is Resource.Success -> {
                    btnCreateAccount.isEnabled = true
                    btnCreateAccount.text = getString(R.string.create_account)
                    HapticFeedbackManager.confirmClick()
                    Toast.makeText(this, getString(R.string.l10n_profile_registered), Toast.LENGTH_SHORT).show()
                }
                is Resource.Error -> {
                    btnCreateAccount.isEnabled = true
                    btnCreateAccount.text = getString(R.string.create_account)
                    PendingBiometricEnrollment.clear()
                    HapticFeedbackManager.warningBuzz()
                    Toast.makeText(this, resource.message ?: getString(R.string.l10n_registration_failed), Toast.LENGTH_LONG).show()
                }
            }
        }

        loginViewModel.userRole.observe(this) { role ->
            SessionManager.saveRole(this, FirebaseAuth.getInstance().currentUser?.uid, role)
            // Offers biometric setup when available, otherwise opens the dashboard.
            routeAfterPasswordAuth(role)
        }
    }
}
