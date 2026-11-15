/*
 * Shared wiring for both clinician (doctor) and patient bottom navigation bars.
 * Every layout includes one of the layout_bottom_nav_*.xml files, exposing the same view IDs,
 * allowing this helper to bind clicks and synchronise active tab states in one place.
 *
 * Reference List:
 * - Google Developers, 2024. Navigation with bottom navigation bar. [online] Available at: <https://developer.android.com/guide/navigation/navigation-ui#bottom_nav> [Accessed 21 September 2026].
 */
package com.example.prog7314_poepart23.utils

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat
import com.example.prog7314_poepart23.DoctorDashboardActivity
import com.example.prog7314_poepart23.DoctorProfileActivity
import com.example.prog7314_poepart23.DoctorReportsActivity
import com.example.prog7314_poepart23.EmergencySosActivity
import com.example.prog7314_poepart23.PatientDashboardActivity
import com.example.prog7314_poepart23.PatientEcgHistoryActivity
import com.example.prog7314_poepart23.PatientSessionStatusActivity
import com.example.prog7314_poepart23.PatientListActivity
import com.example.prog7314_poepart23.R

object BottomNavHelper {

    // Monotonic timestamp threshold (400ms) to reject spam/glitch clicks on navigation tabs
    private const val NAV_DEBOUNCE_THRESHOLD_MS = 400L
    private var lastNavTimestamp: Long = 0L

    @Synchronized
    private fun shouldThrottleNav(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNavTimestamp < NAV_DEBOUNCE_THRESHOLD_MS) {
            return true
        }
        lastNavTimestamp = now
        return false
    }

    // The four clinical destinations reachable from the doctor bottom bar.
    enum class DoctorTab(
        @IdRes val containerId: Int,
        @IdRes val iconId: Int,
        @IdRes val labelId: Int,
        val destination: Class<out Activity>
    ) {
        DASHBOARD(
            R.id.navDoctorDashboard,
            R.id.navDoctorDashboardIcon,
            R.id.navDoctorDashboardLabel,
            DoctorDashboardActivity::class.java
        ),
        PATIENTS(
            R.id.navDoctorPatients,
            R.id.navDoctorPatientsIcon,
            R.id.navDoctorPatientsLabel,
            PatientListActivity::class.java
        ),
        REPORTS(
            R.id.navDoctorReports,
            R.id.navDoctorReportsIcon,
            R.id.navDoctorReportsLabel,
            DoctorReportsActivity::class.java
        ),
        PROFILE(
            R.id.navDoctorProfile,
            R.id.navDoctorProfileIcon,
            R.id.navDoctorProfileLabel,
            DoctorProfileActivity::class.java
        );
    }

    // The four patient destinations reachable from the patient bottom bar.
    enum class PatientTab(
        @IdRes val containerId: Int,
        @IdRes val iconId: Int,
        @IdRes val labelId: Int,
        val destination: Class<out Activity>
    ) {
        DASHBOARD(
            R.id.navPatientDashboard,
            R.id.navPatientDashboardIcon,
            R.id.navPatientDashboardLabel,
            PatientDashboardActivity::class.java
        ),
        // Second tab. It replaces the former live Monitoring destination; the view ids keep their
        // old names so every bottom bar layout stays unchanged apart from its label.
        SESSION_STATUS(
            R.id.navPatientMonitoring,
            R.id.navPatientMonitoringIcon,
            R.id.navPatientMonitoringLabel,
            PatientSessionStatusActivity::class.java
        ),
        REPORTS(
            R.id.navPatientReports,
            R.id.navPatientReportsIcon,
            R.id.navPatientReportsLabel,
            PatientEcgHistoryActivity::class.java
        ),
        SOS(
            R.id.navPatientSos,
            R.id.navPatientSosIcon,
            R.id.navPatientSosLabel,
            EmergencySosActivity::class.java
        );
    }

    // Binds the doctor bottom navigation bar for [activity].
    @JvmStatic
    fun setUpDoctorNav(activity: Activity, currentTab: DoctorTab) {
        DoctorTab.values().forEach { tab ->
            val container: View = activity.findViewById(tab.containerId) ?: return@forEach
            val isActive = tab == currentTab
            applyDoctorTabState(activity, tab, isActive = isActive)

            if (isActive) {
                container.isClickable = false
                container.isFocusable = false
                container.setOnClickListener(null)
            } else {
                container.isClickable = true
                container.isFocusable = true
                container.setOnClickListener {
                    if (activity.isFinishing || activity.isDestroyed || shouldThrottleNav()) return@setOnClickListener
                    HapticFeedbackManager.lightTick()
                    navigateToDoctor(activity, tab)
                }
            }
        }
    }

    // Binds the doctor bottom bar on a secondary clinician screen (e.g. Alerts, Settings, Patient Profile).
    // All tabs stay unselected but remain interactive.
    @JvmStatic
    fun setUpDoctorNavDetached(activity: Activity) {
        DoctorTab.values().forEach { tab ->
            val container: View = activity.findViewById(tab.containerId) ?: return@forEach
            applyDoctorTabState(activity, tab, isActive = false)
            container.isClickable = true
            container.isFocusable = true
            container.setOnClickListener {
                if (activity.isFinishing || activity.isDestroyed || shouldThrottleNav()) return@setOnClickListener
                HapticFeedbackManager.lightTick()
                navigateToDoctor(activity, tab)
            }
        }
    }

    // Binds the patient bottom navigation bar for [activity].
    @JvmStatic
    fun setUpPatientNav(activity: Activity, currentTab: PatientTab) {
        PatientTab.values().forEach { tab ->
            val container: View = activity.findViewById(tab.containerId) ?: return@forEach
            val isActive = tab == currentTab
            applyPatientTabState(activity, tab, isActive = isActive)

            if (isActive) {
                container.isClickable = false
                container.isFocusable = false
                container.setOnClickListener(null)
            } else {
                container.isClickable = true
                container.isFocusable = true
                container.setOnClickListener {
                    if (activity.isFinishing || activity.isDestroyed || shouldThrottleNav()) return@setOnClickListener
                    HapticFeedbackManager.lightTick()
                    navigateToPatient(activity, tab)
                }
            }
        }
    }

    // Binds the patient bottom bar on a screen that is not itself one of the four tabs
    // (Profile, Settings). Every tab stays inactive but remains tappable.
    @JvmStatic
    fun setUpPatientNavDetached(activity: Activity) {
        PatientTab.values().forEach { tab ->
            val container: View = activity.findViewById(tab.containerId) ?: return@forEach
            applyPatientTabState(activity, tab, isActive = false)
            container.isClickable = true
            container.isFocusable = true
            container.setOnClickListener {
                if (activity.isFinishing || activity.isDestroyed || shouldThrottleNav()) return@setOnClickListener
                HapticFeedbackManager.lightTick()
                navigateToPatient(activity, tab)
            }
        }
    }

    // Applies active / inactive colour and weight to a single doctor tab.
    private fun applyDoctorTabState(activity: Activity, tab: DoctorTab, isActive: Boolean) {
        val colour = ContextCompat.getColor(
            activity,
            if (isActive) R.color.pulse_primary else R.color.pulse_nav_inactive
        )

        activity.findViewById<ImageView>(tab.iconId)?.setColorFilter(colour)

        activity.findViewById<TextView>(tab.labelId)?.apply {
            setTextColor(colour)
            typeface = if (isActive) {
                android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            } else {
                android.graphics.Typeface.create(typeface, android.graphics.Typeface.NORMAL)
            }
            isSelected = isActive
        }
    }

    // Applies active / inactive colour and weight to a single patient tab.
    private fun applyPatientTabState(activity: Activity, tab: PatientTab, isActive: Boolean) {
        val colour = ContextCompat.getColor(
            activity,
            if (isActive) R.color.pulse_primary else R.color.pulse_nav_inactive
        )

        activity.findViewById<ImageView>(tab.iconId)?.setColorFilter(colour)

        activity.findViewById<TextView>(tab.labelId)?.apply {
            setTextColor(colour)
            typeface = if (isActive) {
                android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            } else {
                android.graphics.Typeface.create(typeface, android.graphics.Typeface.NORMAL)
            }
            isSelected = isActive
        }
    }

    // Switches doctor destination tab smoothly without flickering or destroying stack.
    private fun navigateToDoctor(activity: Activity, tab: DoctorTab) {
        val intent = Intent(activity, tab.destination).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        activity.startActivity(intent)
        applyTabTransition(activity)
    }

    // Switches patient destination tab smoothly without flickering or destroying stack.
    private fun navigateToPatient(activity: Activity, tab: PatientTab) {
        val intent = Intent(activity, tab.destination).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        activity.startActivity(intent)
        applyTabTransition(activity)
    }

    // Single source of truth for the tab-switch animation.
    private fun applyTabTransition(activity: Activity) {
        activity.applyFadeThroughPendingTransition()
    }
}