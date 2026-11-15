/**
 * PulseLink Biometric Authentication & Key Decryption Activity
 *
 * Implements hardware-backed biometric verification using AndroidX BiometricPrompt
 * and hardware Keystore cryptographic deciphers for local credential retrieval and
 * silent Firebase authentication (Google Developers, 2024; NIST, 2023).
 *
 * Reference List:
 * - Google Cloud, 2024. Manage Users in Firebase: Get Started with Firebase Authentication. Google Cloud. Available at: <https://firebase.google.com/docs/auth> [Accessed 18 September 2026].
 * - Google Developers, 2024. Show a biometric authentication dialog: BiometricPrompt API. Android Developers. Available at: <https://developer.android.com/training/sign-in/biometric-auth> [Accessed 18 September 2026].
 * - National Institute of Standards and Technology (NIST), 2023. Digital Identity Guidelines: Authentication and Lifecycle Management (NIST SP 800-63B). Gaithersburg: NIST.
 */

package com.example.prog7314_poepart23

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.prog7314_poepart23.data.repository.UserRepository
import com.example.prog7314_poepart23.security.BiometricCapability
import com.example.prog7314_poepart23.security.BiometricCredentialVault
import com.example.prog7314_poepart23.security.BiometricStatus
import com.example.prog7314_poepart23.security.TokenManager
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.SessionManager
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.openDashboardFor
import com.google.android.material.button.MaterialButton
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

class BiometricLoginActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnRetry: MaterialButton
    private lateinit var btnPassword: MaterialButton
    // BiometricPrompt instance managing hardware biometric dialog (Google Developers, 2024)
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    private val launchMode: Boolean by lazy { intent.getBooleanExtra(EXTRA_LAUNCH_MODE, false) }
    private var busy = false

    private sealed interface SignInOutcome {
        // [online] is false when we only unlocked the existing session because there was no connection.
        class Verified(val uid: String, val online: Boolean) : SignInOutcome
        object InvalidCredentials : SignInOutcome
        class Retry(@StringRes val messageRes: Int) : SignInOutcome
    }

    private val authCallback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onVerified(result)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            onPromptError(errorCode, errString)
        }

        override fun onAuthenticationFailed() {
            // The system prompt already shows "not recognised"; add a haptic nudge and let it retry.
            HapticFeedbackManager.warningBuzz()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.login_biometric)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)

        tvStatus = findViewById(R.id.tvBiometricStatus)
        btnRetry = findViewById(R.id.btnRetryBiometric)
        btnPassword = findViewById(R.id.btnUsePasswordInstead)

        btnPassword.setOnClickListener { goToPassword() }
        btnRetry.setOnClickListener { startChallenge() }
        findViewById<TextView>(R.id.tvCancel).setOnClickListener {
            // At launch there is nothing behind this screen, so Cancel closes the app.
            if (launchMode) finishAffinity() else finish()
        }

        if (!BiometricCredentialVault.isEnrolled(this)) {
            goToPassword()
            return
        }

        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_prompt_title))
            .setSubtitle(getString(R.string.biometric_prompt_subtitle))
            .setAllowedAuthenticators(BiometricCredentialVault.loginAuthenticators)
            .setConfirmationRequired(false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // A negative button is only allowed (and required) when device credential is not.
            builder.setNegativeButtonText(getString(R.string.use_password_instead))
        }
        promptInfo = builder.build()

        // Always create the prompt so it can re-attach after recreation, but only show it on first launch.
        biometricPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), authCallback)
        if (savedInstanceState == null) startChallenge()
    }

    // ---------------------------------------------------------------------------------------
    // Challenge
    // ---------------------------------------------------------------------------------------
    private fun startChallenge() {
        if (busy) return

        // Capability evaluation, using the same authenticators the prompt will request.
        when (BiometricCapability.evaluate(this, BiometricCredentialVault.loginAuthenticators)) {
            BiometricStatus.READY -> Unit

            BiometricStatus.HW_UNAVAILABLE, BiometricStatus.UNKNOWN -> {
                showRetry(getString(R.string.biometric_err_generic))
                return
            }

            BiometricStatus.NONE_ENROLLED, BiometricStatus.NO_HARDWARE, BiometricStatus.UNSUPPORTED -> {
                goToPassword(R.string.biometric_err_unavailable, clearEnrollment = true)
                return
            }
        }

        val cipher = try {
            BiometricCredentialVault.createDecryptCipher(this)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Biometrics were added or removed since enrolment: the key is permanently unusable.
            goToPassword(R.string.biometric_err_key_invalidated, clearEnrollment = true)
            return
        } catch (e: Exception) {
            goToPassword(R.string.biometric_err_credentials_invalid, clearEnrollment = true)
            return
        }

        showHint()
        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    private fun onPromptError(errorCode: Int, errString: CharSequence) {
        when (errorCode) {
            // "Use Password Instead" (Android 10 and below).
            BiometricPrompt.ERROR_NEGATIVE_BUTTON -> goToPassword()

            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_CANCELED -> showRetry(getString(R.string.biometric_login_hint))

            BiometricPrompt.ERROR_NO_BIOMETRICS,
            BiometricPrompt.ERROR_HW_NOT_PRESENT,
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL ->
                goToPassword(R.string.biometric_err_unavailable, clearEnrollment = true)

            // Lockout, timeout, hardware trouble: the system message explains it (already localised).
            else -> showRetry(errString.toString())
        }
    }

    // ---------------------------------------------------------------------------------------
    // Verified: decrypt in memory, then sign in to Firebase Auth silently
    // ---------------------------------------------------------------------------------------
    private fun onVerified(result: BiometricPrompt.AuthenticationResult) {
        val cipher = result.cryptoObject?.cipher
        if (cipher == null) {
            showRetry(getString(R.string.biometric_err_generic))
            return
        }

        val credentials = try {
            BiometricCredentialVault.decryptCredentials(this, cipher)
        } catch (e: Exception) {
            goToPassword(R.string.biometric_err_credentials_invalid, clearEnrollment = true)
            return
        }

        setBusy(true)
        setStatus(getString(R.string.biometric_signing_in))

        lifecycleScope.launch {
            val outcome = try {
                signInSilently(credentials)
            } finally {
                credentials.wipe()
            }

            when (outcome) {
                is SignInOutcome.Verified -> openDashboard(outcome)

                SignInOutcome.InvalidCredentials -> {
                    // Password changed or account removed elsewhere: the saved sign-in is dead.
                    setBusy(false)
                    goToPassword(R.string.biometric_err_credentials_invalid, clearEnrollment = true)
                }

                is SignInOutcome.Retry -> {
                    setBusy(false)
                    showRetry(getString(outcome.messageRes))
                }
            }
        }
    }

    private suspend fun signInSilently(credentials: BiometricCredentialVault.Credentials): SignInOutcome {
        val auth = FirebaseAuth.getInstance()
        return try {
            val user = auth.signInWithEmailAndPassword(credentials.email, String(credentials.password))
                .await()
                .user
            if (user == null) {
                SignInOutcome.Retry(R.string.biometric_err_generic)
            } else {
                // Refresh the Bearer token and persist it in EncryptedSharedPreferences.
                TokenManager.refreshToken(applicationContext, forceRefresh = true)
                SignInOutcome.Verified(user.uid, online = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseNetworkException) {
            // Offline. The biometric check has still proved who is holding the phone, so unlock the
            // existing session for the same account instead of locking the user out of cached data.
            val existing = auth.currentUser
            if (existing != null && existing.email.equals(credentials.email, ignoreCase = true)) {
                SignInOutcome.Verified(existing.uid, online = false)
            } else {
                SignInOutcome.Retry(R.string.biometric_err_offline)
            }
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            SignInOutcome.InvalidCredentials
        } catch (e: FirebaseAuthInvalidUserException) {
            SignInOutcome.InvalidCredentials
        } catch (e: Exception) {
            SignInOutcome.Retry(R.string.biometric_err_generic)
        }
    }

    private suspend fun openDashboard(outcome: SignInOutcome.Verified) {
        val cached = SessionManager.getCachedRole(this, outcome.uid)
        val live = if (cached == null && outcome.online) {
            withTimeoutOrNull(ROLE_LOOKUP_TIMEOUT_MS) { UserRepository().getUserRole(outcome.uid) }
        } else null
        val role = SessionManager.resolveRoleWithFallback(live, cached)
        // Only cache a valid role that came from a live sign-in, never "unavailable".
        if (cached == null && outcome.online && SessionManager.isCacheableRole(role)) {
            SessionManager.saveRole(this, outcome.uid, role)
        }

        HapticFeedbackManager.confirmClick()
        openDashboardFor(role)
    }

    // ---------------------------------------------------------------------------------------
    // Fallback and UI state
    // ---------------------------------------------------------------------------------------
    private fun goToPassword(@StringRes messageRes: Int? = null, clearEnrollment: Boolean = false) {
        if (clearEnrollment) BiometricCredentialVault.clear(this)
        if (messageRes != null) Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
        if (launchMode) startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        btnPassword.isEnabled = !value
        btnRetry.isEnabled = !value
        if (value) btnRetry.visibility = View.GONE
    }

    private fun setStatus(text: String) {
        tvStatus.text = text
    }

    private fun showHint() {
        btnRetry.visibility = View.GONE
        setStatus(getString(R.string.biometric_login_hint))
    }

    private fun showRetry(message: String) {
        setStatus(message)
        btnRetry.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_LAUNCH_MODE = "extra_launch_mode"
        private const val ROLE_LOOKUP_TIMEOUT_MS = 4000L
    }
}
