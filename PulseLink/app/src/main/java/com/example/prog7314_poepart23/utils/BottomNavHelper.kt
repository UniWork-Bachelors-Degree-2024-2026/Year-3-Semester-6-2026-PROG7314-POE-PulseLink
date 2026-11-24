/*
 * Shared wiring for doctor, patient and nurse bottom navigation bars.
 * Every layout includes one of the layout_bottom_nav_*.xml files, exposing matching view IDs,
 * allowing this helper to bind clicks and synchronise active tab states from one shared path.
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
import com.example.prog7314_poepart23.EcgPreSessionCheckActivity
import com.example.prog7314_poepart23.EmergencySosActivity
import com.example.prog7314_poepart23.LiveSupportChatActivity
import com.example.prog7314_poepart23.NurseDashboardActivity
import com.example.prog7314_poepart23.NursePatientIntakeActivity
import com.example.prog7314_poepart23.PatientDashboardActivity
import com.example.prog7314_poepart23.PatientEcgHistoryActivity
import com.example.prog7314_poepart23.PatientListActivity
import com.example.prog7314_poepart23.PatientSessionStatusActivity
import com.example.prog7314_poepart23.R

object BottomNavHelper {

    private const val NAV_DEBOUNCE_THRESHOLD_MS = 400L
    private var lastNavTimestamp: Long = 0L

    private interface NavTabSpec {
        val containerId: Int
        val iconId: Int
        val labelId: Int
        val destination: Class<out Activity>
    }

    @Synchronized
    private fun shouldThrottleNav(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNavTimestamp < NAV_DEBOUNCE_THRESHOLD_MS) {
            return true
        }
        lastNavTimestamp = now
        return false
    }

    enum class DoctorTab(
        @param:IdRes override val containerId: Int,
        @param:IdRes override val iconId: Int,
        @param:IdRes override val labelId: Int,
        override val destination: Class<out Activity>
    ) : NavTabSpec {
        DASHBOARD(R.id.navDoctorDashboard, R.id.navDoctorDashboardIcon, R.id.navDoctorDashboardLabel, DoctorDashboardActivity::class.java),
        PATIENTS(R.id.navDoctorPatients, R.id.navDoctorPatientsIcon, R.id.navDoctorPatientsLabel, PatientListActivity::class.java),
        REPORTS(R.id.navDoctorReports, R.id.navDoctorReportsIcon, R.id.navDoctorReportsLabel, DoctorReportsActivity::class.java),
        PROFILE(R.id.navDoctorProfile, R.id.navDoctorProfileIcon, R.id.navDoctorProfileLabel, DoctorProfileActivity::class.java);
    }

    enum class PatientTab(
        @param:IdRes override val containerId: Int,
        @param:IdRes override val iconId: Int,
        @param:IdRes override val labelId: Int,
        override val destination: Class<out Activity>
    ) : NavTabSpec {
        DASHBOARD(R.id.navPatientDashboard, R.id.navPatientDashboardIcon, R.id.navPatientDashboardLabel, PatientDashboardActivity::class.java),
        SESSION_STATUS(R.id.navPatientMonitoring, R.id.navPatientMonitoringIcon, R.id.navPatientMonitoringLabel, PatientSessionStatusActivity::class.java),
        REPORTS(R.id.navPatientReports, R.id.navPatientReportsIcon, R.id.navPatientReportsLabel, PatientEcgHistoryActivity::class.java),
        SOS(R.id.navPatientSos, R.id.navPatientSosIcon, R.id.navPatientSosLabel, EmergencySosActivity::class.java);
    }

    enum class NurseTab(
        @param:IdRes override val containerId: Int,
        @param:IdRes override val iconId: Int,
        @param:IdRes override val labelId: Int,
        override val destination: Class<out Activity>
    ) : NavTabSpec {
        DASHBOARD(R.id.navNurseDashboard, R.id.navNurseDashboardIcon, R.id.navNurseDashboardLabel, NurseDashboardActivity::class.java),
        PATIENTS(R.id.navNursePatients, R.id.navNursePatientsIcon, R.id.navNursePatientsLabel, NursePatientIntakeActivity::class.java),
        MONITOR(R.id.navNurseMonitor, R.id.navNurseMonitorIcon, R.id.navNurseMonitorLabel, EcgPreSessionCheckActivity::class.java),
        SUPPORT(R.id.navNurseSupport, R.id.navNurseSupportIcon, R.id.navNurseSupportLabel, LiveSupportChatActivity::class.java);
    }

    @JvmStatic
    fun setUpDoctorNav(activity: Activity, currentTab: DoctorTab) =
        bindTabs(activity, DoctorTab.entries, currentTab)

    @JvmStatic
    fun setUpDoctorNavDetached(activity: Activity) =
        bindTabs(activity, DoctorTab.entries, null)

    @JvmStatic
    fun setUpPatientNav(activity: Activity, currentTab: PatientTab) =
        bindTabs(activity, PatientTab.entries, currentTab)

    @JvmStatic
    fun setUpPatientNavDetached(activity: Activity) =
        bindTabs(activity, PatientTab.entries, null)

    @JvmStatic
    fun setUpNurseNav(activity: Activity, currentTab: NurseTab) =
        bindTabs(activity, NurseTab.entries, currentTab)

    @JvmStatic
    fun setUpNurseNavDetached(activity: Activity) =
        bindTabs(activity, NurseTab.entries, null)

    private fun <T : NavTabSpec> bindTabs(activity: Activity, tabs: List<T>, activeTab: T?) {
        tabs.forEach { tab ->
            val container: View = activity.findViewById(tab.containerId) ?: return@forEach
            val isActive = tab == activeTab
            applyTabState(activity, tab, isActive)
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
                    navigateToTab(activity, tab)
                }
            }
        }
    }

    private fun applyTabState(activity: Activity, tab: NavTabSpec, isActive: Boolean) {
        val colour = ContextCompat.getColor(
            activity,
            if (isActive) R.color.pulse_primary else R.color.pulse_nav_inactive
        )
        activity.findViewById<ImageView>(tab.iconId)?.setColorFilter(colour)
        activity.findViewById<TextView>(tab.labelId)?.apply {
            setTextColor(colour)
            typeface = android.graphics.Typeface.create(
                typeface,
                if (isActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
            isSelected = isActive
        }
    }

    private fun navigateToTab(activity: Activity, tab: NavTabSpec) {
        val intent = Intent(activity, tab.destination).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        activity.startActivity(intent)
        activity.applyFadeThroughPendingTransition()
    }
}
