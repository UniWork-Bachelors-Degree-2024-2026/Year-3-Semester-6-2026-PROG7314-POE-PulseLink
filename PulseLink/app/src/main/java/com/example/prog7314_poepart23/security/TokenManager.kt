/*
 * Singleton manager coordinating Firebase JWT Bearer token lifecycle,
 * encrypted persistence, and automatic injection into Retrofit2 API client.
 *
 * Reference List:
 * - Google Cloud, 2024. Manage User Sessions and Tokens in Firebase. [online] Available at: <https://firebase.google.com/docs/auth/admin/manage-sessions> [Accessed 21 September 2026].
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.security

import android.content.Context
import android.util.Log
import com.example.prog7314_poepart23.data.local.OfflineEcgDatabase
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiClient
import com.example.prog7314_poepart23.data.repository.NotificationRepository
import com.google.firebase.auth.FirebaseAuth         // (Google Cloud, 2024)
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await                // (JetBrains, 2024; Google Cloud, 2024)

object TokenManager { // (Google Cloud, 2024)

    private const val TAG = "TokenManager"

    // Initialises the API client auth token from encrypted storage on app startup, only if the stored UID matches the active Firebase user.
    fun initialise(context: Context) {
        val securePrefs = SecurePreferences.getInstance(context)
        val token = securePrefs.getAuthToken()
        val storedUid = securePrefs.getUserId()
        val currentUid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        if (!token.isNullOrBlank() && (currentUid == null || storedUid == null || storedUid == currentUid)) {
            val activeUid = currentUid ?: storedUid
            PulseLinkApiClient.setAuthToken(token, activeUid)
            runCatching { NotificationRepository.initialise(context, activeUid) }
            runCatching { OfflineEcgDatabase.getInstance(context).recoverInterruptedUploads(activeUid) }
            Log.d(TAG, "Restored active auth token into API client.")
        } else if (currentUid != null && storedUid != null && storedUid != currentUid) {
            PulseLinkApiClient.clearAuth()
            runCatching { NotificationRepository.initialise(context, currentUid) }
        }
    }

    // Fetches a fresh Firebase ID token, persists it in encrypted storage, and updates the API client.
    suspend fun refreshToken(context: Context, forceRefresh: Boolean = false): String? {
        val user = FirebaseAuth.getInstance().currentUser ?: run {
            PulseLinkApiClient.clearAuth()
            return null
        }
        val uid = user.uid
        return try {
            val tokenResult = user.getIdToken(forceRefresh).await()
            val token = tokenResult.token
            if (FirebaseAuth.getInstance().currentUser?.uid != uid) {
                PulseLinkApiClient.clearAuth()
                return null
            }
            if (!token.isNullOrBlank()) {
                val prefs = SecurePreferences.getInstance(context)
                prefs.saveAuthToken(token)
                prefs.saveUserId(uid)
                PulseLinkApiClient.setAuthToken(token, uid)
                runCatching { NotificationRepository.initialise(context, uid) }
                Log.d(TAG, "Successfully refreshed and cached auth token.")
            }
            token
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh Firebase token: ${e.localizedMessage}")
            null
        }
    }

    // Clears all stored tokens and resets the API client interceptor.
    fun clearToken(context: Context) {
        SecurePreferences.getInstance(context).clearAll()
        PulseLinkApiClient.clearAuth()
    }
}
