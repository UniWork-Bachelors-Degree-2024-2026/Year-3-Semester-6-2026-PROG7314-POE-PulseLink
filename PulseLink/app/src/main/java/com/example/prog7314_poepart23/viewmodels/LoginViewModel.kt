/*
 * ViewModel managing user authentication state, credential validation,
 * and navigation role routing for LoginActivity and RegistrationActivity.
 *
 * Reference List:
 * - Google Cloud, 2024. Firebase Authentication Documentation. [online] Available at: <https://firebase.google.com/docs/auth> [Accessed 21 September 2026].
 * - Google Developers, 2024. ViewModel Overview - Android Developers. [online] Available at: <https://developer.android.com/topic/libraries/architecture/viewmodel> [Accessed 21 September 2026].
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.viewmodels

import androidx.lifecycle.LiveData               // (Google Developers, 2024)
import androidx.lifecycle.MutableLiveData        // (Google Developers, 2024)
import androidx.lifecycle.ViewModel              // (Google Developers, 2024)
import androidx.lifecycle.viewModelScope         // (Google Developers, 2024)
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.data.repository.UserRepository
import com.example.prog7314_poepart23.utils.LocaleHelper
import com.example.prog7314_poepart23.utils.Resource
import com.google.firebase.auth.FirebaseUser     // (Google Cloud, 2024)
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch                 // (JetBrains, 2024)

class LoginViewModel( // (Google Developers, 2024)
    private val userRepository: UserRepository = UserRepository()
) : ViewModel() {

    private val _authState = MutableLiveData<Resource<FirebaseUser>>() // (Google Developers, 2024)
    val authState: LiveData<Resource<FirebaseUser>> = _authState        // (Google Developers, 2024)

    private val _userRole = MutableLiveData<String>()                  // (Google Developers, 2024)
    val userRole: LiveData<String> = _userRole                         // (Google Developers, 2024)

    private var authJob: Job? = null

    val currentUser: FirebaseUser?
        get() = userRepository.currentUser

    // Executes user authentication with email and password.
    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _authState.value = Resource.Error(LocaleHelper.string(R.string.l10n_err_email_password_required))
            return
        }

        authJob?.cancel()
        _authState.value = Resource.Loading()
        authJob = viewModelScope.launch {
            val result = userRepository.signIn(email.trim(), password)
            _authState.value = result

            if (result is Resource.Success && result.data != null) {
                val role = userRepository.getUserRole(result.data.uid)
                _userRole.value = role
            }
        }
    }

    // Executes Google Single Sign-On with Firebase credential token.
    fun signInWithGoogle(idToken: String) {
        if (idToken.isBlank()) {
            _authState.value = Resource.Error(LocaleHelper.string(R.string.l10n_google_token_failed))
            return
        }

        authJob?.cancel()
        _authState.value = Resource.Loading()
        authJob = viewModelScope.launch {
            val result = userRepository.signInWithGoogle(idToken)
            _authState.value = result

            if (result is Resource.Success && result.data != null) {
                val role = userRepository.getUserRole(result.data.uid)
                _userRole.value = role
            }
        }
    }

    // Registers a new user account and creates their profile.
    fun register(
        email: String,
        password: String,
        name: String,
        role: String = "patient",
        dateOfBirth: String? = null,
        gender: String? = null,
        phoneNumber: String? = null,
        emergencyContactName: String? = null,
        emergencyContactPhone: String? = null,
        emergencyContactRelationship: String? = null,
        assignedDoctorName: String? = null,
        specialization: String? = null,
        practiceNumber: String? = null,
        hospitalAffiliation: String? = null
    ) {
        if (email.isBlank() || password.isBlank() || name.isBlank()) {
            _authState.value = Resource.Error(LocaleHelper.string(R.string.l10n_err_required_fields))
            return
        }

        authJob?.cancel()
        _authState.value = Resource.Loading()
        authJob = viewModelScope.launch {
            val result = userRepository.register(
                email = email.trim(),
                password = password,
                name = name.trim(),
                role = role,
                dateOfBirth = dateOfBirth,
                gender = gender,
                phoneNumber = phoneNumber,
                emergencyContactName = emergencyContactName,
                emergencyContactPhone = emergencyContactPhone,
                emergencyContactRelationship = emergencyContactRelationship,
                assignedDoctorName = assignedDoctorName,
                specialization = specialization,
                practiceNumber = practiceNumber,
                hospitalAffiliation = hospitalAffiliation
            )
            _authState.value = result
            if (result is Resource.Success && result.data != null) {
                _userRole.value = userRepository.getUserRole(result.data.uid)
            }
        }
    }

    // Signs out of the current Firebase session and cancels any in-flight authentication request.
    fun signOut() {
        authJob?.cancel()
        userRepository.signOut()
    }
}
