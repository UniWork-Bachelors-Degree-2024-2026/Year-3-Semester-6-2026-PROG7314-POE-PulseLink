/*
 * Activity presenting the medical practitioner's professional profile,
 * clinical caseload telemetry, and HPCSA practice credentials.
 *
 * Reference List:
 * - Google Cloud, 2024. Firebase Authentication Documentation. [online] Available at: <https://firebase.google.com/docs/auth> [Accessed 21 September 2026].
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.content.FileProvider
import java.io.File
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
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
import com.example.prog7314_poepart23.utils.openOnClick
import com.example.prog7314_poepart23.utils.signOutToLogin
import com.google.firebase.auth.FirebaseAuth         // (Google Cloud, 2024)
import kotlinx.coroutines.launch                     // (JetBrains, 2024)

class DoctorProfileActivity : AppCompatActivity() { // (Google Developers, 2024)

    private val auth = FirebaseAuth.getInstance()
    private val userRepository = UserRepository()

    private var pendingCameraUri: Uri? = null

    private fun saveAvatarFrom(uri: Uri?) {
        val uid = auth.currentUser?.uid
        if (uri == null || uid == null) return

        val savedUri = AvatarStore.save(this, uid, uri)
        if (savedUri != null) {
            val ivAvatar = findViewById<ImageView?>(R.id.ivDoctorAvatar)
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

    // Upload: pick an existing photo.
    private val pickAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> saveAvatarFrom(uri) }

    // Take photo: full-resolution capture written to a cache file shared via FileProvider.
    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) saveAvatarFrom(uri)
    }

    private fun launchCamera() {
        try {
            val dir = File(cacheDir, "camera").apply { if (!exists()) mkdirs() }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(dir, "avatar_capture.jpg"))
            pendingCameraUri = uri
            takePhotoLauncher.launch(uri)
        } catch (e: Exception) {
            pendingCameraUri = null
            Toast.makeText(this, getString(R.string.profile_photo_camera_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPhotoOptions() {
        val options = arrayOf(getString(R.string.profile_photo_take), getString(R.string.profile_photo_choose))
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_PulseLink_Dialog)
            .setTitle(getString(R.string.cd_change_photo))
            .setItems(options) { dialog, which ->
                if (which == 0) launchCamera() else pickAvatarLauncher.launch("image/*")
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.doctor_profile)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)
        loadDoctorProfile()
        wireControls()

        BottomNavHelper.setUpDoctorNav(this, BottomNavHelper.DoctorTab.PROFILE)
    }

    override fun onResume() {
        super.onResume()
        BottomNavHelper.setUpDoctorNav(this, BottomNavHelper.DoctorTab.PROFILE)
        loadDoctorProfile()
    }

    private val ecgRepository = com.example.prog7314_poepart23.data.repository.EcgRepository()

    // Loads the authenticated doctor's real profile from Firebase, keyed by their UID.
    private fun loadDoctorProfile() {
        val tvName = findViewById<TextView?>(R.id.tvDoctorName)
        val tvLicense = findViewById<TextView?>(R.id.tvDoctorLicense)
        val tvEmail = findViewById<TextView?>(R.id.tvDoctorEmail)
        val tvPhone = findViewById<TextView?>(R.id.tvDoctorPhone)
        val tvHospital = findViewById<TextView?>(R.id.tvDoctorHospital)
        val tvPatients = findViewById<TextView?>(R.id.tvCaseloadPatients)

        tvLicense?.text = ""
        tvHospital?.text = ""

        val user = auth.currentUser
        val uid = user?.uid
        if (uid == null) {
            tvName?.text = getString(R.string.profile_error)
            return
        }

        val userEmail = user.email ?: ""
        tvEmail?.text = userEmail
        tvPhone?.text = user.phoneNumber?.ifBlank { null } ?: ""

        applyStoredAvatar(R.id.ivDoctorAvatar, uid)

        tvName?.text = getString(R.string.profile_loading)
        tvName?.setOnClickListener(null)

        lifecycleScope.launch {
            // Load Doctor Profile
            when (val result = userRepository.getUserProfile(uid)) {
                is Resource.Success -> {
                    val profile = result.data
                    findViewById<TextView>(R.id.tvDoctorSpeciality).text = profile?.specialization?.takeIf { it.isNotBlank() } ?: getString(R.string.l10n_not_provided)
                    tvName?.text = profile?.displayName?.ifBlank { null }
                        ?: user.displayName?.ifBlank { null }
                        ?: getString(R.string.profile_empty_name)
                    if (!profile?.email.isNullOrBlank()) {
                        tvEmail?.text = profile?.email
                    }
                    // Qualification line and hospital come from the real profile record only.
                    tvLicense?.text = listOfNotNull(profile?.specialization, profile?.practiceNumber)
                        .filter { it.isNotBlank() }.joinToString(" | ")
                    tvHospital?.text = profile?.hospitalAffiliation.orEmpty()
                    tvPhone?.text = profile?.phoneNumber?.ifBlank { null } ?: tvPhone?.text
                }
                is Resource.Error -> {
                    tvName?.text = getString(R.string.profile_error_retry)
                    tvName?.setOnClickListener {
                        HapticFeedbackManager.lightTick()
                        loadDoctorProfile()
                    }
                    Toast.makeText(this@DoctorProfileActivity, getString(R.string.profile_error_toast), Toast.LENGTH_SHORT).show()
                }
                is Resource.Loading -> {
                    tvName?.text = getString(R.string.profile_loading)
                }
            }

            // Fetch live patient caseload count derived from active monitored alerts
            val alertsResult = ecgRepository.getActiveAlerts()
            if (alertsResult is Resource.Success) {
                val count = alertsResult.data?.map { it.patientId }?.distinct()?.size ?: 0
                tvPatients?.text = count.toString()
            } else {
                tvPatients?.text = "0"
            }
        }
    }

    // Wires navigation shortcuts and secure practitioner logout.
    private fun wireControls() {
        openOnClick(R.id.btnSettings, DoctorSettingsActivity::class.java)
        findViewById<View>(R.id.btnEditDoctorSpeciality)?.setOnClickListener {
            HapticFeedbackManager.lightTick()
            showSpecialitySheet()
        }
        findViewById<View>(R.id.rowDoctorSpeciality)?.setOnClickListener {
            HapticFeedbackManager.lightTick()
            showSpecialitySheet()
        }
        onClick(R.id.btnChangeDoctorAvatar) { showPhotoOptions() }
        // Tapping the avatar itself also opens the photo options (bigger touch target than the badge).
        onClick(R.id.circleDoctorAvatar) { showPhotoOptions() }
        onClick(R.id.btnSignOut) {
            HapticFeedbackManager.confirmClick()
            signOutToLogin()
        }
    }

    private fun showSpecialitySheet() {
        val uid = auth.currentUser?.uid ?: return
        val currentSpeciality = findViewById<TextView?>(R.id.tvDoctorSpeciality)?.text?.toString()
            ?.takeUnless { it == getString(R.string.l10n_not_provided) }.orEmpty()
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.speciality_hint)
            setBackgroundResource(R.drawable.bg_input_field)
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.pulse_text_primary))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.pulse_text_hint))
            val inset = (12 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setSingleLine(true)
            setText(currentSpeciality)
            filters = arrayOf(android.text.InputFilter.LengthFilter(160))
        }
        var saving = false
        com.example.prog7314_poepart23.utils.PulseLinkModal.show(
            this,
            getString(R.string.l10n_label_speciality),
            input,
            listOf(
                getString(R.string.ui_save) to { sheet ->
                    val value = input.text?.toString()?.trim().orEmpty()
                    if (value.length !in 2..160) {
                        input.error = getString(R.string.speciality_invalid)
                        HapticFeedbackManager.warningBuzz()
                    } else if (!saving) {
                        saving = true
                        val saveBtn = sheet.window?.decorView?.findViewWithTag<com.google.android.material.button.MaterialButton>("modal_action_0")
                        saveBtn?.isEnabled = false
                        saveBtn?.text = getString(R.string.speciality_saving)
                        input.isEnabled = false
                        lifecycleScope.launch {
                            val result = userRepository.updateDoctorSpeciality(uid, value)
                            if (result is Resource.Success) {
                                HapticFeedbackManager.confirmClick()
                                sheet.dismiss()
                                loadDoctorProfile()
                            } else {
                                saving = false
                                input.isEnabled = true
                                saveBtn?.isEnabled = true
                                saveBtn?.text = getString(R.string.ui_save)
                                input.error = getString(R.string.speciality_failed)
                                HapticFeedbackManager.warningBuzz()
                            }
                        }
                    }
                },
                getString(R.string.cancel) to { it.dismiss() }
            )
        )
    }
}
