/*
 * Authentication activity providing email/password sign-in, biometric login routing,
 * Google Single Sign-On, custom styled forgot password dialog, and role-based navigation.
 *
 * Reference List:
 * - Google Cloud, 2024. Firebase Authentication Documentation. [online] Available at: <https://firebase.google.com/docs/auth> [Accessed 21 September 2026].
 * - Google Developers, 2024. Authenticate with Google on Android. [online] Available at: <https://developers.google.com/identity/sign-in/android/start-integrating> [Accessed 21 September 2026].
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge           // (Google Developers, 2024)
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels                   // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity       // (Google Developers, 2024)
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.prog7314_poepart23.security.BiometricCredentialVault
import com.example.prog7314_poepart23.security.PendingBiometricEnrollment
import com.example.prog7314_poepart23.utils.GoogleSignInFailure
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.utils.SessionManager
import com.example.prog7314_poepart23.utils.enableFadeThroughEntry
import com.example.prog7314_poepart23.utils.routeAfterPasswordAuth
import com.example.prog7314_poepart23.viewmodels.LoginViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.button.MaterialButton  // (Google Developers, 2024)
import com.google.firebase.auth.FirebaseAuth               // (Google Cloud, 2024)

class LoginActivity : AppCompatActivity() {

    private val loginViewModel: LoginViewModel by viewModels()
    private lateinit var googleSignInClient: GoogleSignInClient
    private var googlePickerOpen = false

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        googlePickerOpen = false
        findViewById<View>(R.id.btnGoogleSignIn).isEnabled = true
        // Google may return its failure in a cancelled activity result; always inspect supplied data.
        if (result.data == null && result.resultCode == RESULT_CANCELED) return@registerForActivityResult
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken.isNullOrBlank()) {
                HapticFeedbackManager.warningBuzz()
                Toast.makeText(this, R.string.l10n_google_token_failed, Toast.LENGTH_LONG).show()
            } else {
                HapticFeedbackManager.confirmClick()
                loginViewModel.signInWithGoogle(idToken)
            }
        } catch (e: ApiException) {
            showGoogleSignInFailure(e.statusCode)
        }
    }

    private fun showGoogleSignInFailure(statusCode: Int) {
        val failure = GoogleSignInFailure.fromStatusCode(statusCode)
        if (failure == GoogleSignInFailure.CANCELLED || failure == GoogleSignInFailure.IN_PROGRESS) return
        // Keep configuration details in logs; never log the account, credential or ID token.
        android.util.Log.w("PulseLinkAuth", "Google sign-in failed: status=$statusCode")
        val message = when (failure) {
            GoogleSignInFailure.NETWORK -> R.string.google_sign_in_network_error
            GoogleSignInFailure.CONFIGURATION -> R.string.google_sign_in_configuration_error
            GoogleSignInFailure.UNAVAILABLE -> R.string.google_sign_in_unavailable
            else -> R.string.google_sign_in_failed
        }
        HapticFeedbackManager.warningBuzz()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableFadeThroughEntry()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.login)

        // Initialise centralised haptics
        HapticFeedbackManager.initialise(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Configure Google Sign-In options
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnSignIn = findViewById<MaterialButton>(R.id.btnSignIn)

        setupPasswordVisibilityToggle(etPassword)

        setupAuthObservers(btnSignIn)

        btnSignIn.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()
            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, getString(R.string.login_toast_missing_fields), Toast.LENGTH_SHORT).show()
            } else {
                // Held in memory only, so BiometricSetupActivity can offer to save this sign-in.
                PendingBiometricEnrollment.stage(email, password)
                loginViewModel.signIn(email, password)
            }
        }

        findViewById<MaterialButton>(R.id.btnBiometricLogin).setOnClickListener {
            HapticFeedbackManager.confirmClick()
            if (BiometricCredentialVault.isEnrolled(this)) {
                startActivity(Intent(this, BiometricLoginActivity::class.java))
            } else {
                // Nothing saved yet. Make sure the offer appears again after the next password sign-in.
                BiometricCredentialVault.resetSetupDeclined(this)
                Toast.makeText(this, getString(R.string.biometric_login_not_set_up), Toast.LENGTH_LONG).show()
            }
        }

        findViewById<View>(R.id.btnGoogleSignIn).setOnClickListener { button ->
            if (googlePickerOpen) return@setOnClickListener
            PendingBiometricEnrollment.clear()
            googlePickerOpen = true
            button.isEnabled = false
            HapticFeedbackManager.confirmClick()
            googleSignInClient.signOut().addOnCompleteListener {
                if (isFinishing || isDestroyed) return@addOnCompleteListener
                try {
                    googleSignInLauncher.launch(googleSignInClient.signInIntent)
                } catch (e: android.content.ActivityNotFoundException) {
                    googlePickerOpen = false
                    button.isEnabled = true
                    showGoogleSignInFailure(12500)
                }
            }
        }

        findViewById<TextView>(R.id.tvForgotPassword).setOnClickListener {
            HapticFeedbackManager.lightTick()
            showCustomForgotPasswordDialog(etEmail.text.toString().trim())
        }

        findViewById<TextView>(R.id.tvRequestAccess).setOnClickListener {
            HapticFeedbackManager.lightTick()
            startActivity(Intent(this, RegistrationActivity::class.java))
        }
    }

    // Displays a user-friendly custom Material 3 dialog for password reset dispatches.
    private fun showCustomForgotPasswordDialog(prefilledEmail: String) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_forgot_password)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val etEmailReset = dialog.findViewById<EditText>(R.id.dialogEtEmailReset)
        val btnCancel = dialog.findViewById<MaterialButton>(R.id.btnCancelResetDialog)
        val btnSend = dialog.findViewById<MaterialButton>(R.id.btnSendResetDialog)

        if (prefilledEmail.isNotBlank()) {
            etEmailReset.setText(prefilledEmail)
            etEmailReset.setSelection(prefilledEmail.length)
        }

        btnCancel.setOnClickListener {
            HapticFeedbackManager.lightTick()
            dialog.dismiss()
        }

        btnSend.setOnClickListener {
            val email = etEmailReset.text.toString().trim()
            if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                HapticFeedbackManager.warningBuzz()
                Toast.makeText(this, getString(R.string.l10n_valid_email_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            HapticFeedbackManager.confirmClick()
            btnSend.isEnabled = false
            btnSend.text = getString(R.string.l10n_sending)

            FirebaseAuth.getInstance().sendPasswordResetEmail(email)
                .addOnSuccessListener {
                    HapticFeedbackManager.confirmClick()
                    Toast.makeText(this, getString(R.string.l10n_reset_email_sent_format, email), Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                }
                .addOnFailureListener { e ->
                    HapticFeedbackManager.warningBuzz()
                    btnSend.isEnabled = true
                    btnSend.text = getString(R.string.l10n_send_link)
                    Toast.makeText(this, e.localizedMessage ?: getString(R.string.l10n_reset_email_failed), Toast.LENGTH_LONG).show()
                }
        }

        dialog.show()
    }

    private fun setupAuthObservers(btnSignIn: MaterialButton) {
        loginViewModel.authState.observe(this) { resource ->
            when (resource) {
                is Resource.Loading -> {
                    findViewById<View>(R.id.btnGoogleSignIn).isEnabled = false
                    btnSignIn.isEnabled = false
                    btnSignIn.text = getString(R.string.sign_in) + "…"
                }
                is Resource.Success -> {
                    findViewById<View>(R.id.btnGoogleSignIn).isEnabled = !googlePickerOpen
                    btnSignIn.isEnabled = true
                    btnSignIn.text = getString(R.string.sign_in)
                    Toast.makeText(this, getString(R.string.l10n_auth_success), Toast.LENGTH_SHORT).show()
                }
                is Resource.Error -> {
                    findViewById<View>(R.id.btnGoogleSignIn).isEnabled = !googlePickerOpen
                    btnSignIn.isEnabled = true
                    btnSignIn.text = getString(R.string.sign_in)
                    PendingBiometricEnrollment.clear()
                    HapticFeedbackManager.warningBuzz()
                    Toast.makeText(this, resource.message ?: getString(R.string.l10n_auth_failed), Toast.LENGTH_LONG).show()
                }
            }
        }

        loginViewModel.userRole.observe(this) { role ->
            SessionManager.saveRole(this, FirebaseAuth.getInstance().currentUser?.uid, role)
            // Offers biometric setup when available, otherwise opens the dashboard.
            routeAfterPasswordAuth(role)
        }
    }

    // Lets the user reveal or re-hide their typed password via the eye icon, keeping the
    // cursor at the end of the text and the font consistent when the input type changes.
    private fun setupPasswordVisibilityToggle(etPassword: EditText) {
        val btnToggle = findViewById<ImageView>(R.id.btnTogglePassword) ?: return
        var isPasswordVisible = false

        btnToggle.setOnClickListener {
            HapticFeedbackManager.lightTick()
            isPasswordVisible = !isPasswordVisible
            val typeface = etPassword.typeface
            etPassword.inputType = if (isPasswordVisible) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            btnToggle.setImageResource(if (isPasswordVisible) R.drawable.ic_eye else R.drawable.ic_eye_off)
            etPassword.typeface = typeface
            etPassword.setSelection(etPassword.text?.length ?: 0)
        }
    }
}
