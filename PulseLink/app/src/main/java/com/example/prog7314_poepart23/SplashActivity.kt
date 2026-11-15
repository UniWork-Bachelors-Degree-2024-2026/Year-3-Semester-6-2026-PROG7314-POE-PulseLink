/**
 * PulseLink Splash & Dispatch Activity
 *
 * Plays brand entrance animations while evaluating Firebase session persistence,
 * biometric credentials, and user role claims for deterministic activity routing
 * (Google Cloud, 2024; Google Developers, 2024).
 *
 * Reference List:
 * - Google Cloud, 2024. Manage Users in Firebase: Get Started with Firebase Authentication. Google Cloud. Available at: <https://firebase.google.com/docs/auth> [Accessed 18 September 2026].
 * - Google Developers, 2024. Material Design Transitions: Material Fade Through. Android Developers. Available at: <https://developer.android.com/develop/ui/views/animations/transitions> [Accessed 18 September 2026].
 * - JetBrains, 2024. Coroutines guide: Asynchronous flow and concurrency. Kotlin Documentation. Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 18 September 2026].
 */

package com.example.prog7314_poepart23

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.prog7314_poepart23.data.repository.UserRepository
import com.example.prog7314_poepart23.security.BiometricCredentialVault
import com.example.prog7314_poepart23.utils.SessionManager
import com.google.android.material.transition.platform.MaterialFadeThrough
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SplashActivity : AppCompatActivity() {

    // Lazy repository delegate managing role claim resolution and profile retrieval (Google Cloud, 2024)
    private val userRepository by lazy { UserRepository() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Activity transitions must be requested before super.onCreate() (Google Developers, 2024)
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        window.exitTransition = MaterialFadeThrough()
        window.reenterTransition = MaterialFadeThrough()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.splash)

        // Initialise secure token manager and restore cached token for REST client
        com.example.prog7314_poepart23.security.TokenManager.initialise(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        playBrandAnimation()
        routeAfterSplash()
    }

    // ---------------------------------------------------------------------------------------
    // Brand animation (total = 1200ms fade/scale-in + 800ms heartbeat pulse = 2000ms)
    // ---------------------------------------------------------------------------------------
    private fun playBrandAnimation() {
        val logo = findViewById<View>(R.id.circleLogo)
        val name = findViewById<View>(R.id.tvAppName)
        val tagline = findViewById<View>(R.id.tvTagline)

        logo.alpha = 0f
        logo.scaleX = 0.6f
        logo.scaleY = 0.6f
        name.alpha = 0f
        tagline.alpha = 0f

        val logoIn = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(logo, View.SCALE_X, 0.6f, 1f),
                ObjectAnimator.ofFloat(logo, View.SCALE_Y, 0.6f, 1f)
            )
            duration = FADE_IN_MS
            interpolator = DecelerateInterpolator(1.5f)
        }

        val textIn = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(name, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(tagline, View.ALPHA, 0f, 1f)
            )
            duration = 700L
            startDelay = 500L
            interpolator = DecelerateInterpolator()
        }

        // One soft "heartbeat" swell after the logo has settled.
        val pulse = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(logo, View.SCALE_X, 1f, 1.1f, 1f),
                ObjectAnimator.ofFloat(logo, View.SCALE_Y, 1f, 1.1f, 1f)
            )
            duration = PULSE_MS
            interpolator = AccelerateDecelerateInterpolator()
        }

        AnimatorSet().apply {
            play(logoIn)
            play(textIn).with(logoIn)
            play(pulse).after(logoIn)
            start()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Session guard
    // ---------------------------------------------------------------------------------------
    private fun routeAfterSplash() {
        lifecycleScope.launch {
            // Resolve the destination while the animation plays, then wait out the remainder.
            val destination = async { resolveDestination() }
            delay(SPLASH_DURATION_MS)
            navigateTo(destination.await())
        }
    }

    private suspend fun resolveDestination(): Class<out AppCompatActivity> {
        // App-launch challenge: once biometric login is enrolled, nothing else opens until the
        // BiometricPrompt has been passed (or the user falls back to their password).
        if (BiometricCredentialVault.isEnrolled(this)) return BiometricLoginActivity::class.java

        val user = FirebaseAuth.getInstance().currentUser ?: return LoginActivity::class.java

        // Keep the Bearer token used by the REST client fresh on cold start (best effort).
        lifecycleScope.launch { runCatching { userRepository.refreshAuthToken() } }

        // Prefer the role cached at sign-in (works offline); otherwise ask the authoritative API.
        val cachedRole = SessionManager.getCachedRole(this, user.uid)
        val liveRole = if (cachedRole == null) {
            withTimeoutOrNull(ROLE_LOOKUP_TIMEOUT_MS) { userRepository.getUserRole(user.uid) }
        } else null
        val role = SessionManager.resolveRoleWithFallback(liveRole, cachedRole)
        if (cachedRole == null && SessionManager.isCacheableRole(role)) {
            SessionManager.saveRole(this, user.uid, role)
        }

        return if (role.equals(SessionManager.ROLE_DOCTOR, ignoreCase = true)) {
            DoctorDashboardActivity::class.java
        } else if (role.equals(SessionManager.ROLE_PATIENT, ignoreCase = true)) {
            PatientDashboardActivity::class.java
        } else SiteAccessActivity::class.java
    }

    private fun navigateTo(target: Class<out AppCompatActivity>) {
        val options = ActivityOptions.makeSceneTransitionAnimation(this).toBundle()
        val intent = Intent(this, target).apply {
            if (target == BiometricLoginActivity::class.java) {
                putExtra(BiometricLoginActivity.EXTRA_LAUNCH_MODE, true)
            }
        }
        startActivity(intent, options)
        finish()
    }

    private companion object {
        const val FADE_IN_MS = 1200L
        const val PULSE_MS = 800L
        const val SPLASH_DURATION_MS = FADE_IN_MS + PULSE_MS // 2 seconds
        const val ROLE_LOOKUP_TIMEOUT_MS = 4000L
    }
}
