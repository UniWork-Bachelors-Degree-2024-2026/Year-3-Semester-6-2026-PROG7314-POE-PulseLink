/*
 * Screen reader and touch support that applies to every screen without changing any layout.
 *
 *  - Touch targets: any clickable view smaller than 48dp gets a larger touch area (the view itself
 *    keeps its size), so small icon buttons are easy to hit for users with limited mobility.
 *  - Spoken roles: clickable text, cards and icons that are not real buttons are announced as
 *    "button" by TalkBack.
 *  - Large buttons: text buttons shorter than 48dp are raised to 48dp so they are easy to press.
 *  - Headings: bold section titles are marked as headings so TalkBack users can jump between them.
 *  - Screen titles: each screen announces its own title when it opens, not just the app name.
 *  - Menus: the bottom navigation tab for the current screen is announced as the current page.
 *  - Reading labels: heart rate, session timer and ECG waveform views are announced with a
 *    descriptive label that always reflects the value currently shown.
 *
 * Reference List:
 * - Google Developers, 2024. Make apps more accessible. [online] Available at: <https://developer.android.com/guide/topics/ui/accessibility/apps> [Accessed 5 November 2026].
 * - Google Developers, 2024. Touch target size. [online] Available at: <https://support.google.com/accessibility/android/answer/7101858> [Accessed 5 November 2026].
 */

package com.example.prog7314_poepart23.utils

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.views.EcgWaveformView
import com.example.prog7314_poepart23.views.GqeberhaMapView
import com.example.prog7314_poepart23.views.HospitalPaperEcgView
import java.util.WeakHashMap

object AccessibilityHelper {

    private const val MIN_TARGET_DP = 48
    private const val REAPPLY_DELAY_MS = 400L

    private val heartRateIds = setOf(R.id.tvBpmValue, R.id.tvLiveBpm, R.id.tvAvgBpm, R.id.tvAlertBpm, R.id.tvSessionAvgBpm)

    private val layoutListeners = WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>()

    /** Registers the hook that improves each screen. Call once from Application.onCreate(). */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                val decor = activity.window?.decorView ?: return
                decor.post { improveScreen(activity) }
                if (layoutListeners.containsKey(activity)) return
                // Lists and late-loaded content change the layout after onResume, so re-apply (throttled).
                var scheduled = false
                val listener = ViewTreeObserver.OnGlobalLayoutListener {
                    if (!scheduled) {
                        scheduled = true
                        decor.postDelayed({
                            scheduled = false
                            if (!activity.isFinishing && !activity.isDestroyed) improveScreen(activity)
                        }, REAPPLY_DELAY_MS)
                    }
                }
                decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
                layoutListeners[activity] = listener
            }

            override fun onActivityDestroyed(activity: Activity) {
                val listener = layoutListeners.remove(activity) ?: return
                val observer = activity.window?.decorView?.viewTreeObserver
                if (observer != null && observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    private fun improveScreen(activity: Activity) {
        val root = activity.findViewById<View?>(android.R.id.content) ?: return
        val minTarget = (MIN_TARGET_DP * activity.resources.displayMetrics.density).toInt()
        visit(activity, root, minTarget)
        announceScreenTitle(root)
    }

    // Gives the screen a spoken title taken from its first heading (TalkBack announces it on open).
    private fun announceScreenTitle(root: View) {
        val title = findFirstHeading(root)?.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) return
        if (ViewCompat.getAccessibilityPaneTitle(root)?.toString() != title) {
            ViewCompat.setAccessibilityPaneTitle(root, title)
        }
    }

    private fun findFirstHeading(view: View): TextView? {
        if (view.visibility != View.VISIBLE) return null
        if (view is TextView && view !is EditText && ViewCompat.isAccessibilityHeading(view) && !view.text.isNullOrBlank()) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findFirstHeading(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun visit(activity: Activity, view: View, minTarget: Int) {
        if (view.visibility != View.VISIBLE) return
        enlargeTextButton(view, minTarget)
        describe(activity, view)
        if (view is ViewGroup) {
            val small = ArrayList<View>()
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                visit(activity, child, minTarget)
                if (child.visibility == View.VISIBLE && child.isClickable && child.isEnabled &&
                    (child.width < minTarget || child.height < minTarget) && child.width > 0 && child.height > 0
                ) {
                    small.add(child)
                }
            }
            enlargeTouchAreas(view, small, minTarget)
        }
    }

    // Gives every small clickable child of [parent] a 48dp touch area without resizing it.
    private fun enlargeTouchAreas(parent: ViewGroup, children: List<View>, minTarget: Int) {
        val existing = parent.touchDelegate
        if (children.isEmpty()) {
            if (existing is MultiTouchDelegate) parent.touchDelegate = null
            return
        }
        if (existing != null && existing !is MultiTouchDelegate) return // never replace the app's own delegate
        val multi = MultiTouchDelegate(parent)
        for (child in children) {
            val extraX = ((minTarget - child.width).coerceAtLeast(0) + 1) / 2
            val extraY = ((minTarget - child.height).coerceAtLeast(0) + 1) / 2
            val bounds = Rect(child.left - extraX, child.top - extraY, child.right + extraX, child.bottom + extraY)
            bounds.intersect(0, 0, parent.width, parent.height)
            multi.add(TouchDelegate(bounds, child))
        }
        parent.touchDelegate = multi
    }

    private class MultiTouchDelegate(host: View) : TouchDelegate(Rect(), host) {
        private val delegates = ArrayList<TouchDelegate>()
        private var active: TouchDelegate? = null

        fun add(delegate: TouchDelegate) {
            delegates.add(delegate)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) active = null
            val targets = active?.let { listOf(it) } ?: if (event.actionMasked == MotionEvent.ACTION_DOWN) delegates else emptyList()
            for (delegate in targets) {
                val copy = MotionEvent.obtain(event)
                val handled = delegate.onTouchEvent(copy)
                copy.recycle()
                if (handled) {
                    active = if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) null else delegate
                    return true
                }
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) active = null
            return false
        }
    }

    // Adds labels and roles. Delegates read the live value each time TalkBack asks, so nothing has
    // to be updated when a reading changes.
    private fun describe(activity: Activity, view: View) {
        // These two are cheap and run on every pass, because headings and the current tab can change.
        if (view is TextView && view !is EditText) markHeading(view)
        if (view is LinearLayout) announceNavState(activity, view)
        if (view.getTag(R.id.tvBpmValue) == true) return
        val id = view.id
        when {
            view is TextView && view !is EditText && id in heartRateIds -> {
                view.setTag(R.id.tvBpmValue, true)
                ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.contentDescription = heartRateLabel(activity, (host as TextView).text)
                    }
                })
            }
            view is TextView && id == R.id.tvSessionDuration -> {
                view.setTag(R.id.tvBpmValue, true)
                ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.contentDescription = activity.getString(R.string.a11y_session_timer, (host as TextView).text)
                    }
                })
            }
            (view is EcgWaveformView || view is HospitalPaperEcgView) && view.contentDescription.isNullOrBlank() -> {
                view.setTag(R.id.tvBpmValue, true)
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        val rate = VoiceAssistant.heartRateSentence(activity)
                        info.contentDescription = if (rate != null) {
                            activity.getString(R.string.a11y_ecg_chart_with_rate, rate)
                        } else {
                            activity.getString(R.string.a11y_ecg_chart)
                        }
                    }
                })
            }
            view is GqeberhaMapView && view.contentDescription.isNullOrBlank() -> {
                view.setTag(R.id.tvBpmValue, true)
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                view.contentDescription = activity.getString(R.string.a11y_facility_map)
            }
            view.isClickable && !isRealControl(view) && !ViewCompat.hasAccessibilityDelegate(view) -> {
                view.setTag(R.id.tvBpmValue, true)
                ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = Button::class.java.name
                    }
                })
            }
        }
    }

    // Text buttons below 48dp tall are made 48dp so they are large enough for limited mobility.
    private fun enlargeTextButton(view: View, minTarget: Int) {
        if (view !is Button || view is ImageButton) return
        val params = view.layoutParams ?: return
        if (params.height > 0 && params.height < minTarget) {
            params.height = minTarget
            view.layoutParams = params
        }
    }

    // Bold, large, non-interactive text that is not a number or a list row is a section heading.
    private fun markHeading(view: TextView) {
        if (ViewCompat.isAccessibilityHeading(view)) return
        if (view.isClickable || view is Button) return
        if (!view.paint.isFakeBoldText && view.typeface?.isBold != true) return
        val headingSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, view.resources.displayMetrics)
        if (view.textSize < headingSize) return
        val text = view.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text[0].isDigit() || text[0] == '-') return
        if (view.id in heartRateIds || view.id == R.id.tvSessionDuration) return
        val name = entryName(view)
        if (name != null && valueLikeNames.any { name.contains(it, ignoreCase = true) }) return
        var parent = view.parent
        while (parent is View) {
            if (parent is RecyclerView) return
            parent = parent.parent
        }
        ViewCompat.setAccessibilityHeading(view, true)
    }

    private val valueLikeNames = listOf("Count", "Bpm", "Duration", "Name", "Caseload", "Rhythm", "Greeting", "Button", "Status")

    private fun entryName(view: View): String? = try {
        if (view.id == View.NO_ID) null else view.resources.getResourceEntryName(view.id)
    } catch (e: Exception) {
        null
    }

    // Bottom navigation: the current tab is shown with colour and bold text only, so say it aloud too.
    private fun announceNavState(activity: Activity, view: LinearLayout) {
        val name = entryName(view) ?: return
        if (!name.startsWith("nav") || name.endsWith("Icon") || name.endsWith("Label")) return
        var label: TextView? = null
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child is TextView) { label = child; break }
        }
        // BottomNavHelper marks the label of the open tab as selected (the open tab is also not clickable).
        val current = label != null && label.isSelected
        ViewCompat.setScreenReaderFocusable(view, true)
        ViewCompat.setStateDescription(view, if (current) activity.getString(R.string.a11y_nav_current) else null)
    }

    private fun isRealControl(view: View) =
        view is Button || view is ImageButton || view is CompoundButton || view is EditText

    private fun heartRateLabel(activity: Activity, shown: CharSequence?): String {
        val number = Regex("\\d+").find(shown ?: "")?.value?.toIntOrNull()
        return if (number != null && number > 0) {
            val reading = activity.getString(R.string.a11y_heart_rate_reading, activity.getString(R.string.l10n_bpm_value, number))
            // Screen readers also hear the Normal / High / Low status, not just the number.
            val status = HeartRateStatus.levelFor(number)?.let { activity.getString(it.label) }
            if (status != null) activity.getString(R.string.hr_status_with_value, reading, status) else reading
        } else {
            activity.getString(R.string.a11y_heart_rate_none)
        }
    }
}
