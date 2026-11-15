/**
 * PulseLink Centralised Input Validation Engine
 *
 * Implements RFC 5322 compliant email validation, NIST password complexity checks,
 * South African E.164 phone numbering schemas, and Material TextInputLayout binding (Google Developers, 2024; NIST, 2023; Resnick, 2008).
 *
 * Reference List:
 * - Google Developers, 2024. Text fields: Material Design components. Android Developers. Available at: <https://m3.material.io/components/text-fields/overview> [Accessed 18 September 2026].
 * - National Institute of Standards and Technology (NIST), 2023. Digital Identity Guidelines: Authentication and Lifecycle Management (NIST SP 800-63B). Gaithersburg: NIST.
 * - Resnick, P. ed., 2008. Internet Message Format (RFC 5322). Internet Engineering Task Force (IETF).
 */

package com.example.prog7314_poepart23.utils

import android.content.Context
import androidx.annotation.StringRes
import com.example.prog7314_poepart23.R
import com.google.android.material.textfield.TextInputLayout

object InputValidator {

    // ---------------------------------------------------------------------
    // Localisation
    // ---------------------------------------------------------------------

    private var appContext: Context? = null

    /**
     * Wires up string-resource lookups so error messages are localised.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun str(@StringRes resId: Int, fallback: String): String =
        appContext?.let { LocaleHelper.wrapContext(it).getString(resId) } ?: fallback

    // ---------------------------------------------------------------------
    // Regex rules
    // ---------------------------------------------------------------------

    // Practical RFC 5322 address validation regex (the same pattern used by the
    // WHATWG HTML5 spec for <input type="email">) - covers the vast majority of
    // real-world addresses without the full grammar's pathological edge cases.
    private val EMAIL_REGEX = Regex(
        "^[a-zA-Z0-9.!#\$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?" +
            "(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*\$"
    )

    // At least 8 characters, at least 1 uppercase letter, at least 1 digit.
    private val PASSWORD_UPPERCASE_REGEX = Regex(".*[A-Z].*")
    private val PASSWORD_DIGIT_REGEX = Regex(".*\\d.*")
    private const val PASSWORD_MIN_LENGTH = 8

    // Letters only. Spaces are allowed since the target fields are full-name
    // inputs (e.g. "Jane Doe"), not single-word tokens.
    private val NAME_REGEX = Regex("^[A-Za-z]+(?:\\s[A-Za-z]+)*\$")
    private const val NAME_MIN_LENGTH = 2

    // PulseLink ECG device IDs: "ESP32C3-PL" followed by 3 alphanumeric characters,
    // e.g. ESP32C3-PLA1B.
    private val DEVICE_ID_REGEX = Regex("^ESP32C3-PL[A-Z0-9]{3}\$")

    // South African numbers: "+27" or a leading "0", followed by a 9-digit
    // subscriber number starting with 6, 7 or 8 (mobile ranges) - i.e. 10 digits
    // total when written in the local 0XXXXXXXXX form.
    private val PHONE_REGEX = Regex("^(?:\\+27|0)[6-8][0-9]{8}\$")

    // ---------------------------------------------------------------------
    // Validators
    // ---------------------------------------------------------------------

    /** Non-empty + RFC 5322-style format check. Returns an error message, or null if valid. */
    fun validateEmail(email: String): String? {
        val trimmed = email.trim()
        return when {
            trimmed.isEmpty() -> str(R.string.validator_email_required, "Email address is required")
            !EMAIL_REGEX.matches(trimmed) -> str(R.string.validator_email_invalid, "Enter a valid email address")
            else -> null
        }
    }

    /** Minimum 8 characters, at least 1 uppercase letter, at least 1 digit. Returns an error message, or null if valid. */
    fun validatePassword(password: String): String? {
        return when {
            password.isEmpty() -> str(R.string.validator_password_required, "Password is required")
            password.length < PASSWORD_MIN_LENGTH -> str(
                R.string.validator_password_too_short,
                "Password must be at least 8 characters"
            )
            !PASSWORD_UPPERCASE_REGEX.matches(password) -> str(
                R.string.validator_password_missing_uppercase,
                "Password must contain at least 1 uppercase letter"
            )
            !PASSWORD_DIGIT_REGEX.matches(password) -> str(
                R.string.validator_password_missing_digit,
                "Password must contain at least 1 number"
            )
            else -> null
        }
    }

    /** Non-empty, minimum 2 characters, alphabetic characters (and spaces) only. Returns an error message, or null if valid. */
    fun validateName(name: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> str(R.string.validator_name_required, "Name is required")
            trimmed.length < NAME_MIN_LENGTH -> str(
                R.string.validator_name_too_short,
                "Name must be at least 2 characters"
            )
            !NAME_REGEX.matches(trimmed) -> str(
                R.string.validator_name_invalid_chars,
                "Name may only contain letters"
            )
            else -> null
        }
    }

    /** Strict match against the "ESP32C3-PL" + 3 alphanumeric character device ID pattern. Returns an error message, or null if valid. */
    fun validateDeviceId(id: String): String? {
        val trimmed = id.trim().uppercase()
        return when {
            trimmed.isEmpty() -> str(R.string.validator_device_id_required, "Device ID is required")
            !DEVICE_ID_REGEX.matches(trimmed) -> str(
                R.string.validator_device_id_invalid,
                "Enter a valid device ID, e.g. ESP32C3-PLA1B"
            )
            else -> null
        }
    }

    /** South African phone number: "+27" or "0" prefix, 10 digits total. Returns an error message, or null if valid. */
    fun validatePhoneNumber(phone: String): String? {
        val trimmed = phone.trim().replace(" ", "").replace("-", "")
        return when {
            trimmed.isEmpty() -> str(R.string.validator_phone_required, "Phone number is required")
            !PHONE_REGEX.matches(trimmed) -> str(
                R.string.validator_phone_invalid,
                "Enter a valid South African phone number, e.g. 082 123 4567"
            )
            else -> null
        }
    }

    // ---------------------------------------------------------------------
    // Shared UI helper
    // ---------------------------------------------------------------------

    /**
     * Standardised helper for showing/clearing a [TextInputLayout]'s error state.
     * Pass the message returned by one of the `validateX()` functions above -
     * `null` clears the error.
     */
    fun setFieldError(textInputLayout: TextInputLayout, error: String?) {
        textInputLayout.error = error
        textInputLayout.isErrorEnabled = error != null
    }
}
