/**
 * PulseLink Biometric Enrolment & Key Generation Activity
 *
 * Facilitates Class 3 biometric evaluation, Keystore cipher generation, and credential
 * persistence in encrypted storage for future passwordless sessions (Google Developers, 2024; NIST, 2023).
 *
 * Reference List:
 * - Google Developers, 2024. Show a biometric authentication dialog: BiometricPrompt API. Android Developers. Available at: <https://developer.android.com/training/sign-in/biometric-auth> [Accessed 18 September 2026].
 * - National Institute of Standards and Technology (NIST), 2023. Digital Identity Guidelines: Authentication and Lifecycle Management (NIST SP 800-63B). Gaithersburg: NIST.
 */

package com.example.prog7314_poepart23

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.prog7314_poepart23.security.BiometricCapability
import com.example.prog7314_poepart23.security.BiometricCredentialVault
import com.example.prog7314_poepart23.security.BiometricStatus
import com.example.prog7314_poepart23.security.PendingBiometricEnrollment
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.openDashboardForCurrentUser
import com.google.android.material.button.MaterialButton

class BiometricSetupActivity : AppCompatActivity() {

    // Biometric prompt and configuration info (Google Developers, 2024)
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    // Returning from the system "add a fingerprint/face" screen: only continue if it worked.
    private val systemEnrollLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (BiometricCapability.evaluate(this) == BiometricStatus.READY) {
            authenticateForEnrollment()
        }
    }

    private val authCallback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            completeEnrollment(result)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            when (errorCode) {
                // The user backed out: stay on this screen so they can retry or skip.
                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                BiometricPrompt.ERROR_USER_CANCELED,
                BiometricPrompt.ERROR_CANCELED -> Unit

                else -> Toast.makeText(this@BiometricSetupActivity, errString, Toast.LENGTH_LONG).show()
            }
        }

        override fun onAuthenticationFailed() {
            HapticFeedbackManager.warningBuzz()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nothing to enrol (for example the process was recreated and the in-memory hand-off is gone).
        if (!PendingBiometricEnrollment.isStaged()) {
            openDashboardForCurrentUser()
            return
        }

        enableEdgeToEdge()
        setContentView(R.layout.login_biometric_setup)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_enroll_prompt_title))
            .setSubtitle(getString(R.string.biometric_enroll_prompt_subtitle))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(R.string.cancel))
            .setConfirmationRequired(false)
            .build()
        biometricPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), authCallback)

        findViewById<MaterialButton>(R.id.btnSetUpBiometrics).setOnClickListener {
            HapticFeedbackManager.confirmClick()
            beginEnrollment()
        }

        findViewById<TextView>(R.id.tvSkipForNow).setOnClickListener { skipForNow() }

        // Back behaves like "Skip for now" so staged credentials never linger in memory.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                skipForNow()
            }
        })
    }

    override fun onDestroy() {
        if (isFinishing) PendingBiometricEnrollment.clear()
        super.onDestroy()
    }

    // Step 1: capability evaluation.
    private fun beginEnrollment() {
        when (BiometricCapability.evaluate(this)) {
            BiometricStatus.READY -> authenticateForEnrollment()
            BiometricStatus.NONE_ENROLLED -> requestSystemEnrollment()
            BiometricStatus.HW_UNAVAILABLE, BiometricStatus.UNKNOWN ->
                Toast.makeText(this, R.string.biometric_err_generic, Toast.LENGTH_LONG).show()

            BiometricStatus.NO_HARDWARE, BiometricStatus.UNSUPPORTED -> {
                Toast.makeText(this, R.string.biometric_err_unavailable, Toast.LENGTH_LONG).show()
                finishSetup()
            }
        }
    }

    private fun requestSystemEnrollment() {
        Toast.makeText(this, R.string.biometric_err_none_enrolled, Toast.LENGTH_LONG).show()
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        try {
            systemEnrollLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            // The message above already tells the user what to do manually.
        }
    }

    // Step 2: BiometricPrompt with a CryptoObject.
    private fun authenticateForEnrollment() {
        val cipher = try {
            BiometricCredentialVault.createEnrollmentCipher()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.biometric_err_setup_failed, Toast.LENGTH_LONG).show()
            return
        }
        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    // Step 3: encrypt and store, only after the biometric has been confirmed.
    private fun completeEnrollment(result: BiometricPrompt.AuthenticationResult) {
        val cipher = result.cryptoObject?.cipher
        val credentials = PendingBiometricEnrollment.peek()
        if (cipher == null || credentials == null) {
            Toast.makeText(this, R.string.biometric_err_setup_failed, Toast.LENGTH_LONG).show()
            return
        }

        val saved = try {
            BiometricCredentialVault.saveEnrollment(this, cipher, credentials.email, credentials.password)
        } finally {
            credentials.wipe()
        }

        if (saved) {
            HapticFeedbackManager.confirmClick()
            Toast.makeText(this, R.string.biometric_enabled_toast, Toast.LENGTH_SHORT).show()
            finishSetup()
        } else {
            HapticFeedbackManager.warningBuzz()
            Toast.makeText(this, R.string.biometric_err_setup_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun skipForNow() {
        PendingBiometricEnrollment.stagedEmail()?.let { BiometricCredentialVault.markSetupDeclined(this, it) }
        finishSetup()
    }

    private fun finishSetup() {
        PendingBiometricEnrollment.clear()
        openDashboardForCurrentUser()
    }
}
