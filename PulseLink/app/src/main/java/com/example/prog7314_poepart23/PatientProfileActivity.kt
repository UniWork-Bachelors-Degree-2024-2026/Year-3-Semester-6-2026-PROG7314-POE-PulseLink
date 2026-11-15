/*
 * Patient profile activity displaying demographic records, care team contacts,
 * emergency contact details, and account management options.
 *
 * Reference List:
 * - Google Cloud, 2024. Firebase Authentication Documentation. [online] Available at: <https://firebase.google.com/docs/auth> [Accessed 21 September 2026].
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 * - Google Developers, 2024. Material Components for Android. [online] Available at: <https://github.com/material-components/material-components-android> [Accessed 21 September 2026].
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.ImageViewCompat
import java.io.File
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import androidx.lifecycle.lifecycleScope              // (Google Developers, 2024)
import com.example.prog7314_poepart23.data.repository.UserRepository
import com.example.prog7314_poepart23.utils.AvatarStore
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.utils.applyStoredAvatar
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.onClick
import com.example.prog7314_poepart23.utils.signOutToLogin
import com.example.prog7314_poepart23.utils.wireBack
import com.google.android.material.button.MaterialButton // (Google Developers, 2024)
import com.google.firebase.auth.FirebaseAuth         // (Google Cloud, 2024)
import kotlinx.coroutines.launch                     // (JetBrains, 2024)

class PatientProfileActivity : AppCompatActivity() { // (Google Developers, 2024)

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val userRepository = UserRepository()
    private var loadedProfile: com.example.prog7314_poepart23.data.models.UserProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.patient_profile)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)
        loadPatientProfile()
        wireControls()

        // Profile is not one of the primary 4 patient bottom tabs, so tab is detached
        BottomNavHelper.setUpPatientNavDetached(this)
    }

    override fun onResume() {
        super.onResume()
        BottomNavHelper.setUpPatientNavDetached(this)
    }

    // Loads the authenticated patient's real profile from Firebase, keyed by their UID.
    // Firebase Auth is the identity source of truth; the profile record in Realtime
    // Database supplies the display data. No name/email is ever invented,  Loading,
    // Empty and Error states are shown instead, with tap-to-retry on failure.
    private fun loadPatientProfile() {
        val tvName = findViewById<TextView?>(R.id.tvPatientName)
        val tvId = findViewById<TextView?>(R.id.tvPatientId)
        val tvEmail = findViewById<TextView?>(R.id.tvPatientEmail)
        val tvDob = findViewById<TextView?>(R.id.tvPatientDob)
        val tvGender = findViewById<TextView?>(R.id.tvPatientGender)
        val tvPhone = findViewById<TextView?>(R.id.tvPatientPhone)
        val tvDoctorCareName = findViewById<TextView?>(R.id.tvDoctorCareName)
        val tvEmergencyContactName = findViewById<TextView?>(R.id.tvEmergencyContactName)

        val user = auth.currentUser
        val uid = user?.uid
        if (uid == null) {
            tvName?.text = getString(R.string.profile_error)
            tvId?.text = ""
            tvEmail?.text = ""
            return
        }

        applyStoredAvatar(R.id.ivPatientAvatar, uid)

        tvName?.text = getString(R.string.profile_loading)
        tvId?.text = getString(R.string.l10n_patient_id_format, uid.take(5).uppercase())
        tvEmail?.text = getString(R.string.profile_loading)
        tvDob?.text = "..."
        tvGender?.text = "..."
        tvPhone?.text = "..."
        tvDoctorCareName?.text = "..."
        tvEmergencyContactName?.text = "..."
        tvName?.setOnClickListener(null)

        lifecycleScope.launch {
            when (val result = userRepository.getUserProfile(uid)) {
                is Resource.Success -> {
                    val profile = result.data
                    loadedProfile = profile
                    tvName?.text = profile?.displayName?.ifBlank { null } ?: getString(R.string.profile_empty_name)
                    tvEmail?.text = (profile?.email ?: user.email)?.ifBlank { null } ?: getString(R.string.profile_empty_email)

                    tvDob?.text = profile?.dateOfBirth?.ifBlank { null } ?: getString(R.string.l10n_not_provided)
                    tvGender?.text = com.example.prog7314_poepart23.utils.LocaleHelper.genderLabel(this@PatientProfileActivity, profile?.gender) ?: getString(R.string.l10n_not_provided)
                    tvPhone?.text = profile?.phoneNumber?.ifBlank { null } ?: getString(R.string.l10n_not_provided)

                    val docName = profile?.assignedDoctorName?.ifBlank { null } ?: getString(R.string.l10n_not_provided)
                    tvDoctorCareName?.text = docName

                    val emergencyName = profile?.emergencyContactName?.ifBlank { null }
                    val emergencyPhone = profile?.emergencyContactPhone?.ifBlank { null }
                    if (!emergencyName.isNullOrBlank()) {
                        tvEmergencyContactName?.text = if (!emergencyPhone.isNullOrBlank()) "$emergencyName ($emergencyPhone)" else emergencyName
                    } else {
                        tvEmergencyContactName?.text = getString(R.string.l10n_not_provided)
                    }

                    // Wire dynamic contact dialing
                    findViewById<CardView?>(R.id.cardDoctorCareTeam)?.setOnClickListener {
                        HapticFeedbackManager.lightTick()
                        Toast.makeText(this@PatientProfileActivity, getString(R.string.profile_no_doctor_number), Toast.LENGTH_SHORT).show()
                    }
                    findViewById<View?>(R.id.btnCallDoctor)?.setOnClickListener {
                        HapticFeedbackManager.lightTick()
                        Toast.makeText(this@PatientProfileActivity, getString(R.string.profile_no_doctor_number), Toast.LENGTH_SHORT).show()
                    }

                    if (!emergencyPhone.isNullOrBlank()) {
                        findViewById<CardView?>(R.id.cardEmergencyContact)?.setOnClickListener {
                            HapticFeedbackManager.confirmClick()
                            dialPhoneNumber(emergencyPhone)
                        }
                        findViewById<View?>(R.id.btnCallEmergency)?.setOnClickListener {
                            HapticFeedbackManager.confirmClick()
                            dialPhoneNumber(emergencyPhone)
                        }
                    } else {
                        findViewById<CardView?>(R.id.cardEmergencyContact)?.setOnClickListener {
                            Toast.makeText(this@PatientProfileActivity, getString(R.string.l10n_no_emergency_number), Toast.LENGTH_SHORT).show()
                        }
                        findViewById<View?>(R.id.btnCallEmergency)?.setOnClickListener {
                            Toast.makeText(this@PatientProfileActivity, getString(R.string.l10n_no_emergency_number), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                is Resource.Error -> {
                    tvName?.text = getString(R.string.profile_error_retry)
                    tvEmail?.text = getString(R.string.profile_error)
                    tvName?.setOnClickListener {
                        HapticFeedbackManager.lightTick()
                        loadPatientProfile()
                    }
                    Toast.makeText(this@PatientProfileActivity, getString(R.string.profile_error_toast), Toast.LENGTH_SHORT).show()
                }
                is Resource.Loading -> {
                    tvName?.text = getString(R.string.profile_loading)
                }
            }
        }
    }

    private var pendingCameraUri: Uri? = null

    // Saves the chosen/captured image as the avatar and refreshes the preview.
    private fun saveAvatarFrom(uri: Uri?) {
        val uid = auth.currentUser?.uid
        if (uri == null || uid == null) return

        val savedUri = AvatarStore.save(this, uid, uri)
        if (savedUri != null) {
            val ivAvatar = findViewById<ImageView?>(R.id.ivPatientAvatar)
            ivAvatar?.setPadding(0, 0, 0, 0)
            ivAvatar?.clipToOutline = true
            ivAvatar?.setImageURI(null) // force refresh, the file path is the same each time
            ivAvatar?.setImageURI(savedUri)
            HapticFeedbackManager.confirmClick()
            Toast.makeText(this, getString(R.string.l10n_picture_updated), Toast.LENGTH_SHORT).show()
        } else {
            HapticFeedbackManager.warningBuzz()
            Toast.makeText(this, getString(R.string.l10n_picture_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // Upload: pick an existing photo from the gallery / files.
    private val pickImageLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: Uri? -> saveAvatarFrom(uri) }

    // Take photo: the camera app writes a full-resolution picture into a cache file we share via FileProvider.
    private val takePhotoLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) saveAvatarFrom(uri)
    }

    private fun launchCamera() {
        try {
            val dir = File(cacheDir, "camera").apply { if (!exists()) mkdirs() }
            val photoFile = File(dir, "avatar_capture.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
            pendingCameraUri = uri
            takePhotoLauncher.launch(uri)
        } catch (e: Exception) {
            pendingCameraUri = null
            Toast.makeText(this, getString(R.string.profile_photo_camera_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    // Lets the patient choose between taking a new photo and uploading one.
    private fun showPhotoOptions() {
        val options = arrayOf(
            getString(R.string.profile_photo_take),
            getString(R.string.profile_photo_choose)
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_PulseLink_Dialog)
            .setTitle(getString(R.string.cd_change_photo))
            .setItems(options) { dialog, which ->
                if (which == 0) launchCamera() else pickImageLauncher.launch("image/*")
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // Configures click listeners for navigation and session sign-out.
    private fun wireControls() {
        wireBack(R.id.btnBack)

        // The camera badge and the avatar itself both open the photo options (bigger touch target).
        val openPhotoOptions = View.OnClickListener {
            HapticFeedbackManager.lightTick()
            showPhotoOptions()
        }
        findViewById<ImageView?>(R.id.btnChangeAvatar)?.setOnClickListener(openPhotoOptions)
        findViewById<View?>(R.id.circlePatientAvatar)?.setOnClickListener(openPhotoOptions)

        // The call icons are white vector drawables; tint them so they are visible on the light / dark cards.
        val iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.pulse_primary))
        listOf(R.id.btnCallDoctor, R.id.btnCallEmergency).forEach { id ->
            findViewById<ImageView?>(id)?.let { ImageViewCompat.setImageTintList(it, iconTint) }
        }

        findViewById<View?>(R.id.btnEditPersonalDetails)?.setOnClickListener {
            HapticFeedbackManager.lightTick()
            showEditProfileDialog()
        }

        findViewById<CardView?>(R.id.cardPersonalDetails)?.setOnClickListener {
            HapticFeedbackManager.lightTick()
            showEditProfileDialog()
        }

        findViewById<View?>(R.id.btnEditEmergencyContact)?.setOnClickListener {
            HapticFeedbackManager.lightTick()
            showEditProfileDialog()
        }

        findViewById<MaterialButton?>(R.id.btnSignOut)?.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            signOutToLogin()
        }
    }

    // Displays an interactive modal dialog allowing the user to update their Date of Birth, Gender, Phone, and Emergency Contact.
    private fun showEditProfileDialog() {
        val uid = auth.currentUser?.uid ?: return

        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_patient_profile, null, false)
        val etDob = dialogView.findViewById<android.widget.EditText>(R.id.dialogEtDob)
        val spinnerGender = dialogView.findViewById<android.widget.Spinner>(R.id.dialogSpinnerGender)
        val etPhone = dialogView.findViewById<android.widget.EditText>(R.id.dialogEtPhone)
        val etEmergencyName = dialogView.findViewById<android.widget.EditText>(R.id.dialogEtEmergencyName)
        val etEmergencyPhone = dialogView.findViewById<android.widget.EditText>(R.id.dialogEtEmergencyPhone)

        // Dialog field icons: the phone icon is white by default, so tint every icon to a readable colour.
        val dialogIconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.pulse_text_secondary))
        fun tintIcons(v: View) {
            if (v is ImageView) ImageViewCompat.setImageTintList(v, dialogIconTint)
            else if (v is android.view.ViewGroup) for (i in 0 until v.childCount) tintIcons(v.getChildAt(i))
        }
        tintIcons(dialogView)

        val genderOptions = arrayOf(
            getString(R.string.patient_profile_gender_male),
            getString(R.string.patient_profile_gender_female),
            getString(R.string.patient_profile_gender_other),
            getString(R.string.patient_profile_gender_prefer_not_to_say)
        )
        spinnerGender.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, genderOptions)

        // Pre-fill existing values
        val currentDob = findViewById<TextView?>(R.id.tvPatientDob)?.text?.toString()?.replace(getString(R.string.l10n_not_provided), "")?.replace("...", "")?.trim()
        val currentPhone = findViewById<TextView?>(R.id.tvPatientPhone)?.text?.toString()?.replace(getString(R.string.l10n_not_provided), "")?.replace("...", "")?.trim()
        val currentGender = findViewById<TextView?>(R.id.tvPatientGender)?.text?.toString()?.trim()

        etEmergencyName.setText(loadedProfile?.emergencyContactName.orEmpty())
        etEmergencyPhone.setText(loadedProfile?.emergencyContactPhone.orEmpty())
        if (!currentDob.isNullOrBlank()) etDob.setText(currentDob)
        if (!currentPhone.isNullOrBlank()) etPhone.setText(currentPhone)
        val genderIdx = genderOptions.indexOfFirst { it.equals(currentGender, ignoreCase = true) }
        if (genderIdx >= 0) spinnerGender.setSelection(genderIdx)

        etDob.setOnClickListener {
            HapticFeedbackManager.lightTick()
            val cal = java.util.Calendar.getInstance()
            android.app.DatePickerDialog(
                this,
                R.style.ThemeOverlay_PulseLink_DatePicker,
                { _, year, month, dayOfMonth ->
                    etDob.setText(String.format(java.util.Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth))
                },
                cal.get(java.util.Calendar.YEAR) - 25,
                cal.get(java.util.Calendar.MONTH),
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            ).apply {
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }

        var savingDetails = false
        com.example.prog7314_poepart23.utils.PulseLinkModal.show(this, getString(R.string.l10n_edit_details_title), dialogView,
            listOf(getString(R.string.l10n_save_details) to { dialog ->
                HapticFeedbackManager.confirmClick()
                val updatedDob = etDob.text.toString().trim()
                val updatedGender = spinnerGender.selectedItem?.toString() ?: getString(R.string.patient_profile_gender_prefer_not_to_say)
                val updatedPhone = etPhone.text.toString().trim()
                val updatedEmergencyName = etEmergencyName.text.toString().trim()
                val updatedEmergencyPhone = etEmergencyPhone.text.toString().trim()

                if (!savingDetails) lifecycleScope.launch {
                    savingDetails = true
                    val res = userRepository.updateUserProfile(
                        uid = uid,
                        dateOfBirth = updatedDob,
                        gender = updatedGender,
                        phoneNumber = updatedPhone,
                        emergencyContactName = updatedEmergencyName,
                        emergencyContactPhone = updatedEmergencyPhone
                    )
                    savingDetails = false
                    if (res is Resource.Success) {
                        Toast.makeText(this@PatientProfileActivity, getString(R.string.l10n_profile_updated), Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        loadPatientProfile()
                    } else {
                        etPhone.error = getString(R.string.l10n_profile_update_failed)
                    }
                }
            }, getString(R.string.cancel) to { it.dismiss() }))
    }

    // Opens device telephone dialler with the specified contact number.
    private fun dialPhoneNumber(phoneNumber: String) {
        HapticFeedbackManager.lightTick()
        try {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber"))
            startActivity(dialIntent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.l10n_call_failed), Toast.LENGTH_SHORT).show()
        }
    }
}

