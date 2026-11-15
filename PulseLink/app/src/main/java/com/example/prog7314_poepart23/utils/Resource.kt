/*
 * Generic sealed class representing asynchronous operation state (Success, Error, Loading)
 * utilised across MVVM ViewModels and Repositories.
 *
 * Reference List:
 * - Google Developers, 2024. Guide to app architecture: UI layer. [online] Available at: <https://developer.android.com/topic/architecture/ui-layer> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.utils

// Encapsulates the UI state for asynchronous data fetching and background operations. // (Google Developers, 2024)
sealed class Resource<out T>(
    val data: T? = null,
    val message: String? = null
) {
    class Success<T>(data: T) : Resource<T>(data)
    class Error<T>(message: String, data: T? = null) : Resource<T>(data, message)
    class Loading<T>(data: T? = null) : Resource<T>(data)
}
