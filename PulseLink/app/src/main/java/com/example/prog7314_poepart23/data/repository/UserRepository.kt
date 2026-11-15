/*
 * Repository managing Firebase Authentication, Bearer token synchronisation,
 * and user profile persistence in Firebase Realtime Database.
 *
 * Reference List:
 * - Google Cloud, 2024. Firebase Authentication Documentation. [online] Available at: <https://firebase.google.com/docs/auth> [Accessed 21 September 2026].
 * - Google Cloud, 2024. Firebase Realtime Database Documentation. [online] Available at: <https://firebase.google.com/docs/database> [Accessed 21 September 2026].
 * - Google Developers, 2024. Guide to app architecture. [online] Available at: <https://developer.android.com/topic/architecture> [Accessed 21 September 2026].
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.data.repository

import android.content.Context
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.data.models.CarePlan
import com.example.prog7314_poepart23.data.models.UserProfile
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiClient
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiService
import com.example.prog7314_poepart23.data.remote.models.AccessAccountDto
import com.example.prog7314_poepart23.data.remote.models.PatientProfileDto
import com.example.prog7314_poepart23.data.remote.models.RoleRequestDto
import com.example.prog7314_poepart23.utils.ClinicalPdfReportGenerator
import com.example.prog7314_poepart23.utils.LocaleHelper
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.utils.SessionManager
import com.google.firebase.auth.FirebaseAuth                 // (Google Cloud, 2024)
import com.google.firebase.auth.FirebaseUser                 // (Google Cloud, 2024)
import com.google.firebase.auth.GoogleAuthProvider           // (Google Cloud, 2024)
import com.google.firebase.auth.UserProfileChangeRequest     // (Google Cloud, 2024)
import com.google.firebase.database.FirebaseDatabase        // (Google Cloud, 2024)
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await                        // (JetBrains, 2024; Google Cloud, 2024)

class UserRepository( // (Google Developers, 2024)
    private val authProvider: () -> FirebaseAuth = { FirebaseAuth.getInstance() },
    private val databaseProvider: () -> FirebaseDatabase = {
        FirebaseDatabase.getInstance("https://pulselink-78842-default-rtdb.europe-west1.firebasedatabase.app/")
    },
    private val api: PulseLinkApiService = PulseLinkApiClient.apiService,
    private val currentUidProvider: () -> String? = {
        try { FirebaseAuth.getInstance().currentUser?.uid } catch (_: Exception) { null }
    },
    private val tokenFetcher: (suspend (Boolean) -> String?)? = null
) {

    constructor(auth: FirebaseAuth, database: FirebaseDatabase) : this(
        authProvider = { auth },
        databaseProvider = { database },
        currentUidProvider = { try { auth.currentUser?.uid } catch (_: Exception) { null } }
    )

    val currentUser: FirebaseUser?
        get() = try { authProvider().currentUser } catch (_: Exception) { null }

    private suspend fun obtainAndCacheToken(forceRefresh: Boolean): String? {
        val uid = currentUidProvider() ?: run {
            PulseLinkApiClient.clearAuth()
            return null
        }
        val token = if (tokenFetcher != null) {
            tokenFetcher.invoke(forceRefresh)
        } else {
            val user = authProvider().currentUser?.takeIf { it.uid == uid } ?: return null
            user.getIdToken(forceRefresh).await().token
        }
        if (currentUidProvider() != uid || token.isNullOrBlank()) {
            PulseLinkApiClient.clearAuth()
            return null
        }
        PulseLinkApiClient.setAuthToken(token, uid)
        return token
    }

    // Authenticates a user with email and password, updating the cached Bearer token on success. // (Google Cloud, 2024)
    suspend fun signIn(email: String, password: String): Resource<FirebaseUser> {
        return try {
            val auth = authProvider()
            val result = auth.signInWithEmailAndPassword(email, password).await()
            val user = result.user
            if (user != null) {
                val tokenResult = user.getIdToken(true).await()
                PulseLinkApiClient.setAuthToken(tokenResult.token, user.uid)
                Resource.Success(user)
            } else {
                Resource.Error(LocaleHelper.string(R.string.l10n_err_auth_no_user))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: LocaleHelper.string(R.string.l10n_err_sign_in))
        }
    }

    // Authenticates a user with Google ID token, creating their profile in Firebase RTDB if first sign-in.
    suspend fun signInWithGoogle(idToken: String): Resource<FirebaseUser> {
        return try {
            val auth = authProvider()
            val database = databaseProvider()
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = auth.signInWithCredential(credential).await()
            val user = result.user
            if (user != null) {
                // Ensure profile exists in Realtime Database
                val snapshot = database.reference.child("users").child(user.uid).get().await()
                if (!snapshot.exists()) {
                    val assignedRole = "patient"

                    val initialProfile = mutableMapOf<String, Any?>(
                        "uid" to user.uid,
                        "email" to (user.email ?: ""),
                        "displayName" to (user.displayName ?: ""),
                        "role" to assignedRole,
                        "createdAtUtc" to System.currentTimeMillis()
                    )

                    database.reference.child("users").child(user.uid).setValue(initialProfile).await()
                }

                val tokenResult = user.getIdToken(true).await()
                PulseLinkApiClient.setAuthToken(tokenResult.token, user.uid)
                Resource.Success(user)
            } else {
                Resource.Error(LocaleHelper.string(R.string.l10n_err_google_null))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: LocaleHelper.string(R.string.l10n_err_google_signin))
        }
    }

    // Registers a new user account with Firebase Authentication and initialises their profile.
    suspend fun register(
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
    ): Resource<FirebaseUser> {
        return try {
            val auth = authProvider()
            val database = databaseProvider()
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val user = result.user
            if (user != null) {
                // Persist the real name to Firebase Authentication so `user.displayName`
                // is populated for every session from this point on (source of truth: UID).
                user.updateProfile(
                    UserProfileChangeRequest.Builder().setDisplayName(name).build()
                ).await()

                val assignedRole = "patient"

                val resolvedAssignedDoctor = if (assignedRole == "patient") {
                    assignedDoctorName?.ifBlank { null }
                } else null

                // Mirror all real fields into the user's profile record in Firebase
                // Realtime Database, keyed by their Firebase UID.
                val profileMap = mutableMapOf<String, Any?>(
                    "uid" to user.uid,
                    "email" to email,
                    "displayName" to name,
                    "role" to assignedRole,
                    "requestedRole" to role.takeIf { it == "doctor" || it == "nurse" },
                    "phoneNumber" to (phoneNumber?.ifBlank { null }),
                    "createdAtUtc" to System.currentTimeMillis()
                )

                if (role == "patient") {
                    profileMap["dateOfBirth"] = dateOfBirth?.ifBlank { null }
                    profileMap["gender"] = gender?.ifBlank { null }
                    profileMap["emergencyContactName"] = emergencyContactName?.ifBlank { null }
                    profileMap["emergencyContactPhone"] = emergencyContactPhone?.ifBlank { null }
                    profileMap["emergencyContactRelationship"] = emergencyContactRelationship?.ifBlank { null }
                    profileMap["assignedDoctorName"] = resolvedAssignedDoctor
                } else {
                    profileMap["specialization"] = specialization?.ifBlank { null }
                    profileMap["practiceNumber"] = practiceNumber?.ifBlank { null }
                    profileMap["hospitalAffiliation"] = hospitalAffiliation?.ifBlank { null }
                }

                database.reference.child("users").child(user.uid).setValue(profileMap).await()

                val tokenResult = user.getIdToken(true).await()
                PulseLinkApiClient.setAuthToken(tokenResult.token, user.uid)
                if (role == "doctor" || role == "nurse") {
                    // The request cannot approve itself. It can be retried from the access screen.
                    try { api.requestRole(RoleRequestDto(role)) } catch (_: Exception) {}
                }
                Resource.Success(user)
            } else {
                Resource.Error(LocaleHelper.string(R.string.l10n_err_account_null))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: LocaleHelper.string(R.string.l10n_err_register))
        }
    }

    // Pure classification of a server AccessAccountDto into a routing role.
    fun classifyAccessRole(account: AccessAccountDto?, expectedUid: String): String {
        if (account == null) return SessionManager.ROLE_UNAVAILABLE
        if (account.uid.isNotBlank() && account.uid != expectedUid) return SessionManager.ROLE_UNAVAILABLE
        return if (account.status == "approved" &&
            account.role in setOf("patient", "nurse", "doctor", "administrator")
        ) {
            account.role
        } else {
            SessionManager.ROLE_PENDING
        }
    }

    // Only server-approved access controls dashboard routing; profile fields are not authority.
    suspend fun getUserRole(uid: String): String {
        if (uid.isBlank() || currentUidProvider() != uid) return SessionManager.ROLE_UNAVAILABLE
        return try {
            val token = obtainAndCacheToken(forceRefresh = false)
                ?: obtainAndCacheToken(forceRefresh = true)
                ?: return SessionManager.ROLE_UNAVAILABLE
            if (token.isBlank() || currentUidProvider() != uid) return SessionManager.ROLE_UNAVAILABLE
            val response = api.getAccessAccount()
            if (currentUidProvider() != uid) return SessionManager.ROLE_UNAVAILABLE
            if (response.isSuccessful) {
                classifyAccessRole(response.body(), uid)
            } else {
                SessionManager.ROLE_UNAVAILABLE
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SessionManager.ROLE_UNAVAILABLE
        }
    }

    // Resolves the signed-in patient's linked clinical PAT-* record from the server without confusing Firebase UID with PAT ID.
    suspend fun resolveLinkedClinicalPatient(): PatientPortalResult<PatientProfileDto> {
        val uid = currentUidProvider() ?: return PatientPortalResult.Failed(PatientPortalResult.Reason.SIGN_IN_REQUIRED)
        return try {
            val token = refreshAuthToken() ?: return PatientPortalResult.Failed(PatientPortalResult.Reason.SIGN_IN_REQUIRED)
            if (token.isBlank() || currentUidProvider() != uid) {
                return PatientPortalResult.Failed(PatientPortalResult.Reason.SIGN_IN_REQUIRED)
            }
            val response = api.getOwnPatientRecord()
            if (currentUidProvider() != uid) {
                return PatientPortalResult.Failed(PatientPortalResult.Reason.SIGN_IN_REQUIRED)
            }
            when {
                response.code() == 204 -> PatientPortalResult.NotLinked
                response.isSuccessful && response.body() != null -> PatientPortalResult.Ready(response.body()!!)
                response.code() == 401 -> PatientPortalResult.Failed(PatientPortalResult.Reason.SIGN_IN_REQUIRED)
                response.code() == 403 -> PatientPortalResult.Failed(PatientPortalResult.Reason.DENIED)
                else -> PatientPortalResult.Failed(PatientPortalResult.Reason.UNAVAILABLE)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PatientPortalResult.Failed(PatientPortalResult.Reason.UNAVAILABLE)
        }
    }

    // Loads the authenticated user's profile record from `/users/{uid}` in Firebase
    // Realtime Database, or resolves a clinical patient record via the API when [uid] is a clinical PAT-* ID or another user.
    suspend fun getUserProfile(uid: String): Resource<UserProfile?> {
        if (uid.isBlank()) return Resource.Error(LocaleHelper.string(R.string.l10n_err_load_profile))
        val activeUid = currentUidProvider() ?: return Resource.Error(LocaleHelper.string(R.string.l10n_err_load_profile))
        return try {
            if (uid != activeUid || SessionManager.isClinicalPatientId(uid)) {
                val token = obtainAndCacheToken(forceRefresh = false)
                    ?: obtainAndCacheToken(forceRefresh = true)
                    ?: return Resource.Error(LocaleHelper.string(R.string.l10n_err_load_profile))
                if (token.isBlank() || currentUidProvider() != activeUid) {
                    return Resource.Error(LocaleHelper.string(R.string.l10n_err_load_profile))
                }
                val response = api.getClinicalPatient(uid)
                if (currentUidProvider() != activeUid) {
                    return Resource.Error(LocaleHelper.string(R.string.l10n_err_load_profile))
                }
                val patient = response.body()
                return if (response.isSuccessful && patient != null) Resource.Success(UserProfile(
                    uid = patient.id, email = null, displayName = patient.fullName, role = "patient", dateOfBirth = patient.dateOfBirth
                )) else Resource.Error(LocaleHelper.string(R.string.l10n_err_load_profile))
            }
            val database = databaseProvider()
            val snapshot = database.reference.child("users").child(uid).get().await()
            if (currentUidProvider() != activeUid) {
                return Resource.Error(LocaleHelper.string(R.string.l10n_err_load_profile))
            }
            if (!snapshot.exists()) {
                Resource.Success(null)
            } else {
                val role = getUserRole(uid)
                val currentFirebaseUser = currentUser
                val profile = UserProfile(
                    uid = uid,
                    email = snapshot.child("email").getValue(String::class.java) ?: currentFirebaseUser?.email,
                    displayName = snapshot.child("displayName").getValue(String::class.java) ?: currentFirebaseUser?.displayName,
                    role = role,
                    dateOfBirth = snapshot.child("dateOfBirth").getValue(String::class.java),
                    gender = snapshot.child("gender").getValue(String::class.java),
                    phoneNumber = snapshot.child("phoneNumber").getValue(String::class.java),
                    emergencyContactName = snapshot.child("emergencyContactName").getValue(String::class.java),
                    emergencyContactPhone = snapshot.child("emergencyContactPhone").getValue(String::class.java),
                    emergencyContactRelationship = snapshot.child("emergencyContactRelationship").getValue(String::class.java),
                    assignedDoctorName = snapshot.child("assignedDoctorName").getValue(String::class.java),
                    specialization = snapshot.child("specialization").getValue(String::class.java),
                    practiceNumber = snapshot.child("practiceNumber").getValue(String::class.java),
                    hospitalAffiliation = snapshot.child("hospitalAffiliation").getValue(String::class.java)
                )
                Resource.Success(profile)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: LocaleHelper.string(R.string.l10n_err_load_profile))
        }
    }

    // Updates editable demographic fields for the current user in Firebase Realtime Database.
    suspend fun updateUserProfile(
        uid: String,
        dateOfBirth: String?,
        gender: String?,
        phoneNumber: String?,
        emergencyContactName: String?,
        emergencyContactPhone: String?,
        emergencyContactRelationship: String? = null
    ): Resource<Unit> {
        if (uid.isBlank() || currentUidProvider() != uid) {
            return Resource.Error(LocaleHelper.string(R.string.l10n_err_update_profile))
        }
        return try {
            val updates = mutableMapOf<String, Any?>()
            if (dateOfBirth != null) updates["dateOfBirth"] = dateOfBirth.ifBlank { null }
            if (gender != null) updates["gender"] = gender.ifBlank { null }
            if (phoneNumber != null) updates["phoneNumber"] = phoneNumber.ifBlank { null }
            if (emergencyContactName != null) updates["emergencyContactName"] = emergencyContactName.ifBlank { null }
            if (emergencyContactPhone != null) updates["emergencyContactPhone"] = emergencyContactPhone.ifBlank { null }
            if (emergencyContactRelationship != null) updates["emergencyContactRelationship"] = emergencyContactRelationship.ifBlank { null }

            databaseProvider().reference.child("users").child(uid).updateChildren(updates).await()
            if (currentUidProvider() != uid) {
                return Resource.Error(LocaleHelper.string(R.string.l10n_err_update_profile))
            }
            Resource.Success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: LocaleHelper.string(R.string.l10n_err_update_profile))
        }
    }

    suspend fun updateDoctorSpeciality(uid: String, speciality: String): Resource<Unit> {
        val value = speciality.trim()
        if (currentUidProvider() != uid || value.length !in 2..160)
            return Resource.Error(LocaleHelper.string(R.string.speciality_invalid))
        return try {
            if (getUserRole(uid) != "doctor" || currentUidProvider() != uid)
                return Resource.Error(LocaleHelper.string(R.string.speciality_failed))
            databaseProvider().reference.child("users").child(uid).child("specialization").setValue(value).await()
            if (currentUidProvider() != uid) Resource.Error(LocaleHelper.string(R.string.speciality_failed))
            else Resource.Success(Unit)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Resource.Error(LocaleHelper.string(R.string.speciality_failed)) }
    }

    // Refreshes the active Firebase JWT token for API authorisation.
    suspend fun refreshAuthToken(): String? {
        return try {
            obtainAndCacheToken(forceRefresh = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    // Clinical information is served only after API resource authorisation.
    suspend fun getCarePlan(patientId: String): Resource<CarePlan> {
        if (patientId.isBlank()) return Resource.Error(LocaleHelper.stringOr(R.string.rt_err_careplan_unavailable, "The care plan is not available for this account."))
        val uid = currentUidProvider() ?: return Resource.Error(LocaleHelper.stringOr(R.string.rt_err_careplan_sign_in_view, "Please sign in to view the care plan."))
        return try {
            val token = obtainAndCacheToken(forceRefresh = false)
                ?: obtainAndCacheToken(forceRefresh = true)
                ?: return Resource.Error(LocaleHelper.stringOr(R.string.rt_err_careplan_sign_in_view, "Please sign in to view the care plan."))
            if (token.isBlank() || currentUidProvider() != uid) {
                return Resource.Error(LocaleHelper.stringOr(R.string.rt_err_session_changed, "Session changed. Please sign in again."))
            }
            val response = api.getClinicalCarePlan(patientId)
            if (currentUidProvider() != uid) {
                return Resource.Error(LocaleHelper.stringOr(R.string.rt_err_session_changed, "Session changed. Please sign in again."))
            }
            val plan = response.body()
            if (response.isSuccessful && plan != null) {
                Resource.Success(plan)
            } else {
                Resource.Error(LocaleHelper.stringOr(R.string.rt_err_careplan_unavailable, "The care plan is not available for this account."))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Resource.Error(LocaleHelper.stringOr(R.string.rt_err_careplan_load_failed, "Unable to load the care plan. Please try again."))
        }
    }

    // Saves the care plan through the assigned-doctor API.
    suspend fun saveCarePlan(patientId: String, carePlan: CarePlan): Resource<Unit> {
        if (patientId.isBlank()) return Resource.Error(LocaleHelper.stringOr(R.string.rt_err_careplan_save_failed, "Unable to save the care plan. Check your access and try again."))
        val uid = currentUidProvider() ?: return Resource.Error(LocaleHelper.stringOr(R.string.rt_err_careplan_sign_in_save, "Please sign in to save the care plan."))
        return try {
            val token = obtainAndCacheToken(forceRefresh = false)
                ?: obtainAndCacheToken(forceRefresh = true)
                ?: return Resource.Error(LocaleHelper.stringOr(R.string.rt_err_careplan_sign_in_save, "Please sign in to save the care plan."))
            if (token.isBlank() || currentUidProvider() != uid) {
                return Resource.Error(LocaleHelper.stringOr(R.string.rt_err_session_changed, "Session changed. Please sign in again."))
            }
            val response = api.saveClinicalCarePlan(patientId, carePlan)
            if (currentUidProvider() != uid) {
                return Resource.Error(LocaleHelper.stringOr(R.string.rt_err_session_changed, "Session changed. Please sign in again."))
            }
            if (response.isSuccessful) {
                Resource.Success(Unit)
            } else {
                Resource.Error(LocaleHelper.stringOr(R.string.rt_err_careplan_save_failed, "Unable to save the care plan. Check your access and try again."))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: LocaleHelper.stringOr(R.string.rt_err_careplan_save_generic, "Failed to save care plan."))
        }
    }

    // Signs out the current user session and clears cached authorisation tokens, notifications, and staged reports.
    fun signOut(context: Context? = null) {
        try { authProvider().signOut() } catch (_: Exception) {}
        PulseLinkApiClient.clearAuth()
        try { NotificationRepository.clear() } catch (_: Exception) {}
        if (context != null) {
            try { ClinicalPdfReportGenerator.clearCachedReports(context) } catch (_: Exception) {}
        }
    }
}
