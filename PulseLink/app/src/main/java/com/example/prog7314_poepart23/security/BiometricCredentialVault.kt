package com.example.prog7314_poepart23.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec   // (Google Developers, 2024)
import android.security.keystore.KeyProperties          // (Google Developers, 2024)
import android.util.Base64
import androidx.biometric.BiometricManager              // (Google Developers, 2024)
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec               // (NIST, 2007)

// Biometric-gated credential vault.
//
// Two layers protect the saved sign-in:
//   1. An AES-256/GCM key in the Android Keystore that can only be used after a successful
//      BiometricPrompt (auth-per-use, so the prompt must be shown with a CryptoObject wrapping the
//      cipher). The key is invalidated when the device's biometrics change.
//   2. The resulting IV + ciphertext are persisted in EncryptedSharedPreferences (SecurePreferences),
//      so nothing readable ever sits on disk.
//
// Firebase's client SDK cannot sign in from an ID token, so the credential that is protected is the
// email/password pair. It is only ever decrypted in memory, straight after a verified prompt.

object BiometricCredentialVault {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "pulselink_biometric_credential_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val SEPARATOR = '\u0000'

    // Authenticators accepted by the login prompt.
    // Android 11+ can bind a CryptoObject to biometrics OR the device PIN/pattern/password, so the
    // prompt itself offers the PIN/pattern fallback. Android 7-10 cannot, so there the prompt is
    // biometric-only and its negative button hands over to the normal password sign-in.
    val loginAuthenticators: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

    // Decrypted credentials. Call wipe() as soon as they have been used.
    class Credentials(val email: String, val password: CharArray) {
        fun wipe() {
            password.fill('\u0000')
        }
    }

    // ---------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------

    fun isEnrolled(context: Context): Boolean {
        val prefs = SecurePreferences.getInstance(context)
        return prefs.isBiometricEnabled() && prefs.hasBiometricCredentials()
    }

    fun enrolledEmail(context: Context): String? =
        SecurePreferences.getInstance(context).getBiometricEmail()

    fun isSetupDeclined(context: Context, email: String): Boolean =
        SecurePreferences.getInstance(context).getBiometricDeclinedEmail().equals(email, ignoreCase = true)

    fun markSetupDeclined(context: Context, email: String) {
        SecurePreferences.getInstance(context).setBiometricDeclinedEmail(email)
    }

    fun resetSetupDeclined(context: Context) {
        SecurePreferences.getInstance(context).setBiometricDeclinedEmail(null)
    }

    // Removes the Keystore key and every stored biometric value. Safe to call at any time.
    fun clear(context: Context) {
        deleteKey()
        SecurePreferences.getInstance(context).clearBiometricCredentials()
    }

    // ---------------------------------------------------------------------------------------
    // Enrolment
    // ---------------------------------------------------------------------------------------

    // Replaces any previous key with a fresh one and returns an ENCRYPT cipher to wrap in the
    // CryptoObject shown to BiometricPrompt. The cipher is unusable until the prompt succeeds.
    fun createEnrollmentCipher(): Cipher {
        deleteKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, createKey())
        return cipher
    }

    // Encrypts the credentials with the cipher unlocked by BiometricPrompt and stores IV + ciphertext
    // in EncryptedSharedPreferences. Returns false (and leaves nothing behind) on any failure.
    fun saveEnrollment(context: Context, cipher: Cipher, email: String, password: CharArray): Boolean {
        var plain: ByteArray? = null
        return try {
            plain = (email + SEPARATOR + String(password)).toByteArray(Charsets.UTF_8)
            val encrypted = cipher.doFinal(plain)
            val saved = SecurePreferences.getInstance(context).saveBiometricCredentials(
                iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                ciphertext = Base64.encodeToString(encrypted, Base64.NO_WRAP),
                email = email
            )
            if (!saved) clear(context)
            saved
        } catch (e: Exception) {
            clear(context)
            false
        } finally {
            plain?.fill(0)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Challenge
    // ---------------------------------------------------------------------------------------

    // Returns a DECRYPT cipher for the stored IV, to wrap in the CryptoObject shown to BiometricPrompt.
    // Throws KeyPermanentlyInvalidatedException if the device's biometrics changed since enrolment,
    // and IllegalStateException if the key or stored data is missing. Either way the caller should
    // clear() the enrolment and fall back to the password screen.
    fun createDecryptCipher(context: Context): Cipher {
        val iv = SecurePreferences.getInstance(context).getBiometricIv()
            ?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: throw IllegalStateException("No biometric enrolment stored")
        val key = loadKey() ?: throw IllegalStateException("Biometric key is missing")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher
    }

    // Decrypts the stored credentials with the cipher unlocked by BiometricPrompt.
    fun decryptCredentials(context: Context, cipher: Cipher): Credentials {
        val encrypted = SecurePreferences.getInstance(context).getBiometricCiphertext()
            ?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: throw IllegalStateException("No biometric enrolment stored")
        val plain = cipher.doFinal(encrypted)
        try {
            val text = String(plain, Charsets.UTF_8)
            val split = text.indexOf(SEPARATOR)
            if (split <= 0) throw IllegalStateException("Stored credentials are malformed")
            return Credentials(text.substring(0, split), text.substring(split + 1).toCharArray())
        } finally {
            plain.fill(0)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Keystore
    // ---------------------------------------------------------------------------------------

    private fun createKey(): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Timeout 0 = authentication required for every single use of the key.
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun loadKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun deleteKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            // Nothing to delete, or the keystore is unavailable: either way there is no usable key.
        }
    }
}
