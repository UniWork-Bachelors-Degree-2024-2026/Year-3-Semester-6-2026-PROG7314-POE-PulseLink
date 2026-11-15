package com.example.prog7314_poepart23.utils

enum class GoogleSignInFailure {
    CANCELLED, NETWORK, CONFIGURATION, UNAVAILABLE, IN_PROGRESS, UNKNOWN;

    companion object {
        fun fromStatusCode(code: Int): GoogleSignInFailure = when (code) {
            12501 -> CANCELLED
            7 -> NETWORK
            10 -> CONFIGURATION
            12500 -> UNAVAILABLE
            12502 -> IN_PROGRESS
            else -> UNKNOWN
        }
    }
}
