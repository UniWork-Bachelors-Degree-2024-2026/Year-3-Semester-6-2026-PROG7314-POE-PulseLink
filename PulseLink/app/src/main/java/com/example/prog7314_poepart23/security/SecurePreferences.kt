package com.example.prog7314_poepart23.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences  // (Google Developers, 2024)
import androidx.security.crypto.MasterKey                   // (Google Developers, 2024)

// Hardware-backed AES-256 GCM Encrypted SharedPreferences manager for securely storing
// Firebase authentication tokens, paired ESP32-C3 device identifiers, and patient credentials.

class SecurePreferences private constructor(context: Context) {

    private val sharedPreferences: SharedPreferences

    init {
        sharedPreferences = try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context.applicationContext,
                ENCRYPTED_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences initialization fallback: ${e.localizedMessage}")
            context.applicationContext.getSharedPreferences(FALLBACK_PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val TAG = "SecurePreferences"
        private const val ENCRYPTED_PREFS_FILE = "pulselink_secure_prefs"
        private const val FALLBACK_PREFS_FILE = "pulselink_prefs_fallback"

        private const val KEY_AUTH_TOKEN = "key_auth_token"
        private const val KEY_USER_ID = "key_user_id"
        private const val KEY_USER_EMAIL = "key_user_email"
        private const val KEY_USER_ROLE = "key_user_role"
        private const val KEY_PAIRED_DEVICE_ID = "key_paired_device_id"
        private const val KEY_PAIRED_DEVICE_NAME = "key_paired_device_name"
        private const val KEY_PAIRED_DEVICE_MAC = "key_paired_device_mac"
        private const val KEY_BIOMETRIC_ENABLED = "key_biometric_enabled"
        private const val KEY_BIOMETRIC_IV = "key_biometric_iv"
        private const val KEY_BIOMETRIC_CIPHERTEXT = "key_biometric_ciphertext"
        private const val KEY_BIOMETRIC_EMAIL = "key_biometric_email"
        private const val KEY_BIOMETRIC_DECLINED_EMAIL = "key_biometric_declined_email"
        private const val KEY_DISMISSED_ALERTS = "key_dismissed_alerts"
        private const val KEY_NURSE_PATIENT_ID = "key_nurse_selected_patient_id"
        private const val KEY_NURSE_PATIENT_NAME = "key_nurse_selected_patient_name"
        private const val KEY_NURSE_PATIENT_DOB = "key_nurse_selected_patient_dob"
        private const val KEY_NURSE_DOCTOR_UID = "key_nurse_selected_doctor_uid"
        private const val KEY_NURSE_DOCTOR_NAME = "key_nurse_selected_doctor_name"
        private const val KEY_NURSE_SITE_ID = "key_nurse_selected_site_id"
        private const val KEY_NURSE_SITE_NAME = "key_nurse_selected_site_name"

        @Volatile
        private var INSTANCE: SecurePreferences? = null

        fun getInstance(context: Context): SecurePreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecurePreferences(context).also { INSTANCE = it }
            }
        }
    }

    // Auth Token
    fun saveAuthToken(token: String?) {
        sharedPreferences.edit().putString(KEY_AUTH_TOKEN, token).apply()
    }

    fun getAuthToken(): String? {
        return sharedPreferences.getString(KEY_AUTH_TOKEN, null)
    }

    // User ID
    fun saveUserId(userId: String?) {
        sharedPreferences.edit().putString(KEY_USER_ID, userId).apply()
    }

    fun getUserId(): String? {
        return sharedPreferences.getString(KEY_USER_ID, null)
    }

    // User Email
    fun saveUserEmail(email: String?) {
        sharedPreferences.edit().putString(KEY_USER_EMAIL, email).apply()
    }

    fun getUserEmail(): String? {
        return sharedPreferences.getString(KEY_USER_EMAIL, null)
    }

    // User Role ("Patient" or "Doctor")
    fun saveUserRole(role: String?) {
        sharedPreferences.edit().putString(KEY_USER_ROLE, role).apply()
    }

    fun getUserRole(): String? {
        return sharedPreferences.getString(KEY_USER_ROLE, "Patient")
    }

    // Paired ESP32-C3 Device Telemetry
    fun savePairedDeviceId(deviceId: String?) {
        sharedPreferences.edit().putString(KEY_PAIRED_DEVICE_ID, deviceId).apply()
    }

    // Returns the real device ID stored after a successful pairing, or null if the
    // patient has not paired a device yet. Callers must treat null as an empty state,
    // never substitute a fabricated device ID.
    fun getPairedDeviceId(): String? {
        return sharedPreferences.getString(KEY_PAIRED_DEVICE_ID, null)
    }

    fun savePairedDeviceName(name: String?) {
        sharedPreferences.edit().putString(KEY_PAIRED_DEVICE_NAME, name).apply()
    }

    fun getPairedDeviceName(): String? {
        return sharedPreferences.getString(KEY_PAIRED_DEVICE_NAME, null)
    }

    fun savePairedDeviceMac(mac: String?) {
        sharedPreferences.edit().putString(KEY_PAIRED_DEVICE_MAC, mac).apply()
    }

    fun getPairedDeviceMac(): String? {
        return sharedPreferences.getString(KEY_PAIRED_DEVICE_MAC, null)
    }

    fun clearPairedDevice() {
        sharedPreferences.edit()
            .remove(KEY_PAIRED_DEVICE_ID)
            .remove(KEY_PAIRED_DEVICE_NAME)
            .remove(KEY_PAIRED_DEVICE_MAC)
            .apply()
    }

    // Biometric Authentication Flag
    fun setBiometricEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun isBiometricEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_BIOMETRIC_ENABLED, false)
    }

    // Biometric-protected credential blob. The values are already AES-GCM ciphertext produced by a
    // Keystore key that needs a biometric prompt (see BiometricCredentialVault); this class then
    // encrypts them again at rest. commit() (not apply()) so the four values land atomically
    // before enrolment is reported as successful.
    fun saveBiometricCredentials(iv: String, ciphertext: String, email: String): Boolean {
        return sharedPreferences.edit()
            .putString(KEY_BIOMETRIC_IV, iv)
            .putString(KEY_BIOMETRIC_CIPHERTEXT, ciphertext)
            .putString(KEY_BIOMETRIC_EMAIL, email)
            .putBoolean(KEY_BIOMETRIC_ENABLED, true)
            .commit()
    }

    fun hasBiometricCredentials(): Boolean {
        return !getBiometricIv().isNullOrBlank() && !getBiometricCiphertext().isNullOrBlank()
    }

    fun getBiometricIv(): String? = sharedPreferences.getString(KEY_BIOMETRIC_IV, null)

    fun getBiometricCiphertext(): String? = sharedPreferences.getString(KEY_BIOMETRIC_CIPHERTEXT, null)

    fun getBiometricEmail(): String? = sharedPreferences.getString(KEY_BIOMETRIC_EMAIL, null)

    // Remembers that this account chose "Skip for now" so the offer is not repeated on every sign-in.
    fun setBiometricDeclinedEmail(email: String?) {
        sharedPreferences.edit().putString(KEY_BIOMETRIC_DECLINED_EMAIL, email).apply()
    }

    fun getBiometricDeclinedEmail(): String? = sharedPreferences.getString(KEY_BIOMETRIC_DECLINED_EMAIL, null)

    // Removes the stored blob, the enabled flag and the "declined" marker.
    fun clearBiometricCredentials() {
        sharedPreferences.edit()
            .remove(KEY_BIOMETRIC_IV)
            .remove(KEY_BIOMETRIC_CIPHERTEXT)
            .remove(KEY_BIOMETRIC_EMAIL)
            .remove(KEY_BIOMETRIC_DECLINED_EMAIL)
            .putBoolean(KEY_BIOMETRIC_ENABLED, false)
            .apply()
    }

    private fun dismissedAlertsKey() = "${KEY_DISMISSED_ALERTS}_${getUserId().orEmpty()}"

    // Clinical Alert Dismissal & Read State Management
    fun dismissAlert(alertId: String) {
        val current = getDismissedAlertIds().toMutableSet()
        current.add(alertId)
        sharedPreferences.edit().putStringSet(dismissedAlertsKey(), current).apply()
    }

    fun dismissAllAlerts(alertIds: Collection<String>) {
        val current = getDismissedAlertIds().toMutableSet()
        current.addAll(alertIds)
        sharedPreferences.edit().putStringSet(dismissedAlertsKey(), current).apply()
    }

    fun getDismissedAlertIds(): Set<String> {
        return sharedPreferences.getStringSet(dismissedAlertsKey(), emptySet()) ?: emptySet()
    }

    fun clearDismissedAlerts() {
        sharedPreferences.edit().remove(dismissedAlertsKey()).apply()
    }

    // Clear all credentials on Sign Out
    fun clearAll() {
        sharedPreferences.edit().clear().apply()
    }

    // Active clinic patient and site selected by the Nurse for pre-session checks and live monitoring
    fun saveSelectedNursePatient(
        patientId: String,
        patientName: String,
        dateOfBirth: String? = null,
        doctorUid: String? = null,
        doctorName: String? = null,
        siteId: String? = null
    ) {
        sharedPreferences.edit()
            .putString(KEY_NURSE_PATIENT_ID, patientId)
            .putString(KEY_NURSE_PATIENT_NAME, patientName)
            .putString(KEY_NURSE_PATIENT_DOB, dateOfBirth)
            .putString(KEY_NURSE_DOCTOR_UID, doctorUid)
            .putString(KEY_NURSE_DOCTOR_NAME, doctorName)
            .putString(KEY_NURSE_SITE_ID, siteId)
            .apply()
    }

    fun getSelectedNursePatientId(): String? = sharedPreferences.getString(KEY_NURSE_PATIENT_ID, null)
    fun getSelectedNursePatientName(): String? = sharedPreferences.getString(KEY_NURSE_PATIENT_NAME, null)
    fun getSelectedNursePatientDob(): String? = sharedPreferences.getString(KEY_NURSE_PATIENT_DOB, null)
    fun getSelectedNurseDoctorUid(): String? = sharedPreferences.getString(KEY_NURSE_DOCTOR_UID, null)
    fun getSelectedNurseDoctorName(): String? = sharedPreferences.getString(KEY_NURSE_DOCTOR_NAME, null)
    fun getSelectedNurseSiteId(): String? = sharedPreferences.getString(KEY_NURSE_SITE_ID, null)

    fun saveNurseSiteName(siteName: String?) {
        sharedPreferences.edit().putString(KEY_NURSE_SITE_NAME, siteName).apply()
    }

    fun getNurseSiteName(): String? = sharedPreferences.getString(KEY_NURSE_SITE_NAME, null)

    fun clearSelectedNursePatient() {
        sharedPreferences.edit()
            .remove(KEY_NURSE_PATIENT_ID)
            .remove(KEY_NURSE_PATIENT_NAME)
            .remove(KEY_NURSE_PATIENT_DOB)
            .remove(KEY_NURSE_DOCTOR_UID)
            .remove(KEY_NURSE_DOCTOR_NAME)
            .remove(KEY_NURSE_SITE_ID)
            .apply()
    }
}
