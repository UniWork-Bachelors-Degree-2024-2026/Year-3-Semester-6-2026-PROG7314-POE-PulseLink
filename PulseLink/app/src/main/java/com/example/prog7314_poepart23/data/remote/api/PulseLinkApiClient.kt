/*
 * Singleton Retrofit2 client initialiser configuring OkHttpClient with logging,
 * timeout policies, JSON converters, and Bearer token authorisation headers
 * for the live Azure App Service REST API.
 *
 * Reference List:
 * - Google Developers, 2024. Connect to network resources. [online] Available at: <https://developer.android.com/develop/connectivity/network-ops/connecting> [Accessed 21 September 2026].
 * - Oracle, 2024. Class TimeUnit (Java Platform SE 8). [online] Available at: <https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/TimeUnit.html> [Accessed 21 September 2026].
 * - Square, 2024. OkHttp: An HTTP & HTTP/2 client for Android and Java applications. [online] Available at: <https://square.github.io/okhttp/> [Accessed 21 September 2026].
 * - Square, 2024. Retrofit: A type-safe HTTP client for Android and Java. [online] Available at: <https://square.github.io/retrofit/> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.data.remote.api

import okhttp3.Interceptor                   // (Square, 2024)
import okhttp3.OkHttpClient                    // (Square, 2024)
import okhttp3.logging.HttpLoggingInterceptor  // (Square, 2024)
import retrofit2.Retrofit                      // (Square, 2024)
import retrofit2.converter.gson.GsonConverterFactory // (Square, 2024)
import java.util.concurrent.TimeUnit          // (Oracle, 2024)

object PulseLinkApiClient {

    // Live Azure App Service REST API base endpoint
    const val BASE_URL = "https://pulselinkv2-drhahreccedabcfk.southafricanorth-01.azurewebsites.net/"

    // In-memory bearer token and active Firebase UID cache
    @Volatile
    private var authToken: String? = null

    @Volatile
    private var activeUid: String? = null

    // Updates the cached Bearer token and optional owning Firebase UID used for authenticated API requests.
    @Synchronized
    fun setAuthToken(token: String?, uid: String? = activeUid) {
        val cleanToken = token?.trim()?.ifBlank { null }
        authToken = cleanToken
        activeUid = if (cleanToken == null) null else uid?.trim()?.ifBlank { null }
    }

    // Retrieves the current active Bearer token.
    fun getAuthToken(): String? = authToken

    // Retrieves the Firebase UID associated with the current Bearer token, if bound.
    fun getActiveUid(): String? = activeUid

    // Returns true when an active token is present and belongs to [expectedUid] (or no UID restriction was bound).
    fun isAuthenticatedAs(expectedUid: String?): Boolean {
        val token = authToken
        if (token.isNullOrBlank()) return false
        if (expectedUid.isNullOrBlank()) return false
        val current = activeUid
        return current == null || current == expectedUid
    }

    // Clears both the active Bearer token and the bound Firebase UID atomically on sign-out or account switch.
    @Synchronized
    fun clearAuth() {
        authToken = null
        activeUid = null
    }

    // Interceptor injecting Bearer authorisation header into all outgoing requests when available. // (Square, 2024)
    private val authInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")

        authToken?.let { token ->
            if (token.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }
        }

        chain.proceed(requestBuilder.build())
    }

    // Logging interceptor for telemetry diagnostics. // (Square, 2024)
    private val loggingInterceptor by lazy {
        HttpLoggingInterceptor().apply {
            // Keep bearer tokens, patient details and recordings out of HTTP logs.
            level = HttpLoggingInterceptor.Level.BASIC
            redactHeader("Authorization")
        }
    }

    // Configured OkHttpClient with timeout thresholds (30 seconds) optimised for remote telemetry. // (Square, 2024)
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .retryOnConnectionFailure(true)
            .build()
    }

    // Singleton Retrofit instance bound to the live Azure backend // (Square, 2024)
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    // Public API service instance exposing all endpoints. // (Square, 2024)
    val apiService: PulseLinkApiService by lazy {
        retrofit.create(PulseLinkApiService::class.java)
    }
}
