/*
 * Security & Password management activity providing password modification,
 * Firebase password reset dispatches, biometric Keystore configuration,
 * and account verification status audit.
 *
 * Reference List:
 * - Google Cloud, 2024. Firebase Authentication Documentation. [online] Available at: <https://firebase.google.com/docs/auth> [Accessed 21 September 2026].
 * - Google Developers, 2024. Show a biometric authentication dialog. [online] Available at: <https://developer.android.com/training/sign-in/biometric-auth> [Accessed 21 September 2026].
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import android.provider.Settings
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.prog7314_poepart23.security.BiometricCapability
import com.example.prog7314_poepart23.security.BiometricCredentialVault
import com.example.prog7314_poepart23.security.BiometricStatus
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.wireBack
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SecurityPasswordActivity : AppCompatActivity() {

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private lateinit var biometricSwitchListener: CompoundButton.OnCheckedChangeListener
    private var biometricStatusRefresher: (() -> Unit)? = null
    private var biometricResetPending = false

    override fun onResume() {
        super.onResume()

        if (biometricResetPending) {
            biometricResetPending = false
            MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_PulseLink_Dialog)
                .setTitle(getString(R.string.l10n_reenable_bio_title))
                .setMessage(getString(R.string.l10n_reenable_bio_msg))
                .setNegativeButton(getString(R.string.l10n_not_now)) { _, _ ->
                    biometricStatusRefresher?.invoke()
                }
                .setPositiveButton(getString(R.string.l10n_enable)) { _, _ ->
                    promptPasswordToEnrollBiometrics {
                        biometricStatusRefresher?.invoke()
                    }
                }
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_security_password)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)
        wireBack(R.id.btnBack)
        setupAccountSecurity()
        setupPasswordReset()
        setupInAppPasswordChange()
        setupBiometrics()
    }

    private fun setupAccountSecurity() {
        val user = auth.currentUser
        val tvUserEmail = findViewById<TextView>(R.id.tvUserEmailLabel)
        val tvVerifiedStatus = findViewById<TextView>(R.id.tvEmailVerifiedStatus)
        val btnSendEmailVerification = findViewById<MaterialButton>(R.id.btnSendEmailVerification)

        val email = user?.email ?: getString(R.string.l10n_no_email_associated)
        tvUserEmail.text = email

        if (user?.isEmailVerified == true) {
            tvVerifiedStatus.text = getString(R.string.security_password_text_email_verified)
            tvVerifiedStatus.setTextColor(ContextCompat.getColor(this, R.color.pulse_primary))
            btnSendEmailVerification.visibility = View.GONE
        } else {
            tvVerifiedStatus.text = getString(R.string.security_password_text_email_unverified)
            tvVerifiedStatus.setTextColor(ContextCompat.getColor(this, R.color.pulse_warning))
            btnSendEmailVerification.visibility = View.VISIBLE
            btnSendEmailVerification.setOnClickListener {
                HapticFeedbackManager.confirmClick()
                user?.sendEmailVerification()?.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Toast.makeText(this, getString(R.string.l10n_verification_sent_format, email), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, getString(R.string.l10n_verification_failed_format, task.exception?.localizedMessage.orEmpty()), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun setupPasswordReset() {
        val btnSendResetEmail = findViewById<MaterialButton>(R.id.btnSendResetEmail)
        val email = auth.currentUser?.email

        btnSendResetEmail.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            if (email.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.l10n_no_registered_email), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSendResetEmail.isEnabled = false
            lifecycleScope.launch {
                try {
                    auth.sendPasswordResetEmail(email).await()
                    Toast.makeText(this@SecurityPasswordActivity, getString(R.string.l10n_reset_link_sent_format, email), Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    HapticFeedbackManager.warningBuzz()
                    Toast.makeText(this@SecurityPasswordActivity, getString(R.string.l10n_error_format, e.localizedMessage.orEmpty()), Toast.LENGTH_SHORT).show()
                } finally {
                    btnSendResetEmail.isEnabled = true
                }
            }
        }
    }

    private fun setupInAppPasswordChange() {
        val etNewPassword = findViewById<EditText>(R.id.etNewPassword)
        val etConfirmNewPassword = findViewById<EditText>(R.id.etConfirmNewPassword)
        val btnUpdatePassword = findViewById<MaterialButton>(R.id.btnUpdatePasswordDirect)

        btnUpdatePassword.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            val newPass = etNewPassword.text.toString().trim()
            val confirmPass = etConfirmNewPassword.text.toString().trim()

            if (newPass.length < 6) {
                HapticFeedbackManager.warningBuzz()
                Toast.makeText(this, getString(R.string.l10n_password_min_6), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newPass != confirmPass) {
                HapticFeedbackManager.warningBuzz()
                Toast.makeText(this, getString(R.string.l10n_passwords_mismatch), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val user = auth.currentUser
            if (user == null) {
                Toast.makeText(this, getString(R.string.l10n_session_expired), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnUpdatePassword.isEnabled = false
            lifecycleScope.launch {
                try {
                    user.updatePassword(newPass).await()
                    HapticFeedbackManager.confirmClick()
                    Toast.makeText(this@SecurityPasswordActivity, getString(R.string.l10n_password_updated), Toast.LENGTH_SHORT).show()
                    etNewPassword.text.clear()
                    etConfirmNewPassword.text.clear()
                } catch (e: Exception) {
                    HapticFeedbackManager.warningBuzz()
                    Toast.makeText(this@SecurityPasswordActivity, getString(R.string.l10n_password_update_failed_format, e.localizedMessage.orEmpty()), Toast.LENGTH_LONG).show()
                } finally {
                    btnUpdatePassword.isEnabled = true
                }
            }
        }
    }

    private fun setupBiometrics() {
        val switchBiometric = findViewById<SwitchCompat>(R.id.switchBiometricAuth)
        val tvStatusDesc = findViewById<TextView>(R.id.tvBiometricStatusDesc)
        val btnTestBiometric = findViewById<MaterialButton>(R.id.btnTestBiometric)
        val btnChangeBiometric = findViewById<MaterialButton>(R.id.btnChangeBiometric)

        fun refreshStatus() {
            val enrolled = BiometricCredentialVault.isEnrolled(this)
            tvStatusDesc.text = if (enrolled) getString(R.string.security_password_text_biometrics_active) else getString(R.string.security_password_text_fingerprint_face_ready)
            switchBiometric.setOnCheckedChangeListener(null)
            switchBiometric.isChecked = enrolled
            switchBiometric.setOnCheckedChangeListener(biometricSwitchListener)
            btnChangeBiometric.visibility = if (enrolled) View.VISIBLE else View.GONE
        }

        biometricStatusRefresher = { refreshStatus() }

        val status = BiometricCapability.evaluate(this)

        if (status != BiometricStatus.READY) {
            tvStatusDesc.text = getString(R.string.security_password_text_biometrics_unavailable)
            switchBiometric.isEnabled = false
            switchBiometric.isChecked = false
            btnTestBiometric.isEnabled = false
            btnChangeBiometric.visibility = View.GONE
            return
        }

        biometricSwitchListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
            HapticFeedbackManager.lightTick()
            val enrolled = BiometricCredentialVault.isEnrolled(this)
            if (!isChecked && enrolled) {
                BiometricCredentialVault.clear(this)
                Toast.makeText(this, getString(R.string.l10n_bio_disabled), Toast.LENGTH_SHORT).show()
                refreshStatus()
            } else if (isChecked && !enrolled) {
                promptPasswordToEnrollBiometrics { refreshStatus() }
            }
        }

        refreshStatus()

        // Android owns the actual fingerprint/face enrollment. The app cannot remove one
        // specific fingerprint itself, so this flow clears the PulseLink biometric key first,
        // opens Android's biometric settings, and then lets the user re-enable PulseLink when
        // they return to the app.
        btnChangeBiometric.setOnClickListener {
            HapticFeedbackManager.lightTick()
            resetBiometricsAndOpenSettings { refreshStatus() }
        }

        btnTestBiometric.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.l10n_bio_check_title))
                .setSubtitle(getString(R.string.l10n_bio_check_subtitle))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .setNegativeButtonText(getString(R.string.cancel))
                .build()

            val prompt = BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        HapticFeedbackManager.confirmClick()
                        Toast.makeText(this@SecurityPasswordActivity, getString(R.string.l10n_bio_verified), Toast.LENGTH_SHORT).show()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                            Toast.makeText(this@SecurityPasswordActivity, getString(R.string.l10n_bio_check_error_format, errString.toString()), Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onAuthenticationFailed() {
                        HapticFeedbackManager.warningBuzz()
                        Toast.makeText(this@SecurityPasswordActivity, getString(R.string.l10n_bio_mismatch), Toast.LENGTH_SHORT).show()
                    }
                }
            )
            prompt.authenticate(promptInfo)
        }
    }

    // Resets PulseLink's biometric credential and opens the Android biometric settings page.
    // The app cannot directly delete or replace an individual fingerprint because Android owns
    // the system biometric enrollment. After the user returns, onResume() offers to enable
    // PulseLink biometric login again.
    private fun resetBiometricsAndOpenSettings(onFinished: () -> Unit) {
        BiometricCredentialVault.clear(this)
        onFinished()
        biometricResetPending = true

        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                putExtra(
                    Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                )
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }

        try {
            startActivity(intent)
            Toast.makeText(
                this,
                getString(R.string.l10n_bio_settings_hint),
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            biometricResetPending = false
            Toast.makeText(
                this,
                getString(R.string.l10n_bio_settings_failed),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Lets the user (re-)enroll Keystore biometrics from Settings, without needing to sign out
    // first. Firebase never exposes the current password, so it is re-collected here, verified
    // with a lightweight reauthentication, then used to encrypt the credential vault entry that
    // BiometricLoginActivity later decrypts after a successful fingerprint/face match.
    private fun promptPasswordToEnrollBiometrics(onFinished: () -> Unit) {
        val user = auth.currentUser
        val email = user?.email
        if (user == null || email.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.l10n_session_expired), Toast.LENGTH_SHORT).show()
            onFinished()
            return
        }

        val etPassword = EditText(this).apply {
            hint = getString(R.string.l10n_current_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_PulseLink_Dialog)
            .setTitle(getString(R.string.l10n_confirm_password_title))
            .setMessage(getString(R.string.l10n_confirm_password_msg))
            .setView(etPassword)
            .setCancelable(false)
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
                onFinished()
            }
            .setPositiveButton(getString(R.string.l10n_continue)) { dialog, _ ->
                dialog.dismiss()
                val password = etPassword.text.toString()
                if (password.isBlank()) {
                    Toast.makeText(this, getString(R.string.l10n_password_blank), Toast.LENGTH_SHORT).show()
                    onFinished()
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    try {
                        user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
                        beginBiometricEnrollment(email, password, onFinished)
                    } catch (e: Exception) {
                        HapticFeedbackManager.warningBuzz()
                        Toast.makeText(this@SecurityPasswordActivity, getString(R.string.l10n_incorrect_password_bio), Toast.LENGTH_LONG).show()
                        onFinished()
                    }
                }
            }
            .show()
    }

    // Shows the fingerprint/face prompt bound to a fresh Keystore cipher, then encrypts and
    // stores the verified credentials so future logins can unlock them biometrically.
    private fun beginBiometricEnrollment(email: String, password: String, onFinished: () -> Unit) {
        val cipher = try {
            BiometricCredentialVault.createEnrollmentCipher()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.l10n_bio_setup_unavailable), Toast.LENGTH_LONG).show()
            onFinished()
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.l10n_enable_bio_title))
            .setSubtitle(getString(R.string.l10n_enable_bio_subtitle))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(R.string.cancel))
            .build()

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val resultCipher = result.cryptoObject?.cipher
                    val saved = resultCipher != null &&
                            BiometricCredentialVault.saveEnrollment(this@SecurityPasswordActivity, resultCipher, email, password.toCharArray())
                    if (saved) {
                        HapticFeedbackManager.confirmClick()
                        Toast.makeText(this@SecurityPasswordActivity, getString(R.string.l10n_bio_enabled), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@SecurityPasswordActivity, getString(R.string.l10n_bio_save_failed), Toast.LENGTH_LONG).show()
                    }
                    onFinished()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                        Toast.makeText(this@SecurityPasswordActivity, getString(R.string.l10n_bio_setup_error_format, errString.toString()), Toast.LENGTH_SHORT).show()
                    }
                    onFinished()
                }

                override fun onAuthenticationFailed() {
                    HapticFeedbackManager.warningBuzz()
                    Toast.makeText(this@SecurityPasswordActivity, getString(R.string.l10n_bio_mismatch), Toast.LENGTH_SHORT).show()
                }
            }
        )
        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }
}
