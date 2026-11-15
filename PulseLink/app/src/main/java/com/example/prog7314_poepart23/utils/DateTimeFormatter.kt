/**
 * PulseLink Centralised Date, Time & Duration Formatting Utility
 *
 * Implements ISO 8601 parsing, relative time delta calculations, and localised plurals
 * formatting for clinical telemetry records (Google Developers, 2024; ISO, 2019).
 *
 * Reference List:
 * - Google Developers, 2024. String Resources: Quantity Strings (Plurals). Android Developers. Available at: <https://developer.android.com/guide/topics/resources/string-resource#Plurals> [Accessed 18 September 2026].
 * - International Organization for Standardization (ISO), 2019. ISO 8601-1:2019 Date and time — Representations for information interchange. Geneva: ISO.
 */

package com.example.prog7314_poepart23.utils

import android.content.Context
import androidx.annotation.PluralsRes
import com.example.prog7314_poepart23.R
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object DateTimeFormatter {

    // ---------------------------------------------------------------------
    // Localisation
    // ---------------------------------------------------------------------

    private var appContext: Context? = null

    /**
     * Wires up string-resource lookups so relative-time phrases ("Just now",
     * "Yesterday", "3 hours ago") are localised. Safe to call more than once
     * (e.g. from [android.app.Application.onCreate]). If never called, the
     * English fallback copy below is used instead.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun quantityStr(@PluralsRes resId: Int, count: Int, fallbackSingular: String, fallbackPlural: String): String {
        val ctx = appContext
        return if (ctx != null) {
            LocaleHelper.wrapContext(ctx).resources.getQuantityString(resId, count, count)
        } else {
            String.format(Locale.getDefault(), if (count == 1) fallbackSingular else fallbackPlural, count)
        }
    }

    private fun str(resId: Int, fallback: String): String =
        appContext?.let { LocaleHelper.wrapContext(it).getString(resId) } ?: fallback

    // ---------------------------------------------------------------------
    // Formatters
    // ---------------------------------------------------------------------

    /** e.g. "18 Sep 2026, 14:32". For session history lists & summary headers. */
    fun formatSessionDate(timestamp: Long): String {
        val format = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
        return format.format(Date(timestamp))
    }

    /**
     * e.g. "Just now", "5 minutes ago", "2 hours ago", "Yesterday", "4 days ago".
     * Beyond 7 days, falls back to [formatSessionDate] so old items still show a
     * concrete date instead of an ever-growing "N days ago".
     * For notification lists, the alert feed, and dashboard badges.
     */
    fun formatTimeAgo(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diffMs = (now - timestamp).coerceAtLeast(0)

        val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
        val hours = TimeUnit.MILLISECONDS.toHours(diffMs)

        return when {
            minutes < 1 -> str(R.string.time_ago_just_now, "Just now")
            minutes < 60 -> quantityStr(
                R.plurals.time_ago_minutes, minutes.toInt(),
                "%d minute ago", "%d minutes ago"
            )
            hours < 24 -> quantityStr(
                R.plurals.time_ago_hours, hours.toInt(),
                "%d hour ago", "%d hours ago"
            )
            isYesterday(timestamp, now) -> str(R.string.time_ago_yesterday, "Yesterday")
            hours < 24 * 7 -> quantityStr(
                R.plurals.time_ago_days, TimeUnit.MILLISECONDS.toDays(diffMs).toInt(),
                "%d day ago", "%d days ago"
            )
            else -> formatSessionDate(timestamp)
        }
    }

    private fun isYesterday(timestamp: Long, now: Long): Boolean {
        val target = Calendar.getInstance().apply { timeInMillis = timestamp }
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -1)
        }
        return target.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
            target.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * e.g. "1:30", "0:45", or "1:05:30" once the duration reaches an hour.
     * For the session recording timer and report details.
     */
    fun formatDuration(seconds: Int): String {
        val total = seconds.coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    /** e.g. "Thursday, 18 September 2026". For PDF report headers & the detailed diagnostic review. */
    fun formatFullDate(timestamp: Long): String {
        val format = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
        return format.format(Date(timestamp))
    }

    /**
     * Parses an ISO 8601 UTC string (e.g. "2026-09-22T01:29:00.948500Z") and
     * converts it to a human-readable relative time ("Just now", "5 minutes ago", etc.)
     * or standard session date if older than 7 days (ISO, 2019; Google Developers, 2024).
     */
    fun formatIsoTimestamp(isoString: String?): String {
        if (isoString.isNullOrBlank()) return ""
        return try {
            val clean = isoString.trim()
            val baseIso = if (clean.contains('.')) clean.substringBefore('.') else clean.removeSuffix("Z")
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val parsedDate = parser.parse(baseIso)
            if (parsedDate != null) {
                formatTimeAgo(parsedDate.time)
            } else {
                clean
            }
        } catch (e: Exception) {
            isoString ?: ""
        }
    }

    /**
     * ISO-8601 UTC string, e.g. "2026-09-18T14:32:00Z". Always uses UTC and
     * [Locale.US] regardless of device locale/timezone, since this is what
     * every API request payload and query parameter should carry.
     */
    fun formatApiTimestamp(timestamp: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(timestamp))
    }
}
