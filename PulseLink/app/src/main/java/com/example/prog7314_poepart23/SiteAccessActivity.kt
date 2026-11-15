package com.example.prog7314_poepart23

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.addTextChangedListener
import com.google.android.material.card.MaterialCardView
import androidx.lifecycle.lifecycleScope
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiClient
import com.example.prog7314_poepart23.data.remote.models.*
import com.example.prog7314_poepart23.data.repository.UserRepository
import com.example.prog7314_poepart23.data.repository.NurseScreenApproval
import com.example.prog7314_poepart23.data.repository.NurseScreenApprovalRepository
import com.example.prog7314_poepart23.utils.DateTimeFormatter
import com.example.prog7314_poepart23.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import retrofit2.Response

/** Staff approval and site administration. The backend validates every operation. */
class SiteAccessActivity : AppCompatActivity() {
    private val api get() = PulseLinkApiClient.apiService
    private val users = UserRepository()
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var account: AccessAccountDto? = null
    private val accounts = mutableListOf<AccessAccountDto>()
    private val sites = mutableListOf<ClinicSiteDto>()
    private val patients = mutableListOf<ClinicalPatientDto>()
    private var accountCursor: String? = null
    private var siteCursor: String? = null
    private var patientCursor: String? = null
    private var busy = false
    private val nurseScreens = NurseScreenApprovalRepository()
    private var screenApproval: NurseScreenApproval = NurseScreenApproval.NotActive
    private fun rememberedScreens() = getSharedPreferences("nurse_screen_devices", MODE_PRIVATE)

    // Nurse status presentation. The data layer supplies sync counts through statusSource.
    var statusSource: NurseStatusSource = NurseStatusSource.None
    private val bleManager by lazy { com.example.prog7314_poepart23.ble.PulseLinkBleManager(this) }
    private var syncSummary: NurseSyncSummary? = null
    private var lastScreenCheck: Long? = null
    private var feedbackView: TextView? = null
    private var feedbackProgress: ProgressBar? = null
    private var approveButton: MaterialButton? = null
    private var nurseSyncStatus: TextView? = null
    // True only after the nurse taps "Approve a different screen" while an approval is already active.
    private var approveAnother = false

    // Administrator feedback (Maxine Task 3). Separate from the nurse feedback fields above.
    private var adminBanner: TextView? = null
    private var adminProgress: ProgressBar? = null
    private var deviceDraft = ""
    private var deviceSiteDraft = 0

    private suspend fun restoreScreen(nurse: AccessAccountDto) {
        val device = rememberedScreens().getString(nurse.uid, null)
        screenApproval = if (device == null) NurseScreenApproval.NotActive else
            nurseScreens.current(device, nurse.siteIds.singleOrNull().orEmpty(), nurse.uid)
        lastScreenCheck = if (screenApproval is NurseScreenApproval.Approved) System.currentTimeMillis() else null
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized && !isFinishing) refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.access_title)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(20))
            setBackgroundColor(ContextCompat.getColor(context, R.color.pulse_bg))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.access_title); textSize = 24f
            setTextColor(ContextCompat.getColor(context, R.color.pulse_text_primary))
        })
        status = TextView(this).apply { textSize = 16f; setPadding(0, dp(12), 0, dp(12)) }
        root.addView(status)
        adminProgress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        root.addView(adminProgress)
        adminBanner = TextView(this).apply {
            textSize = 16f; visibility = View.GONE; setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextColor(ContextCompat.getColor(context, R.color.pulse_text_primary))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(adminBanner)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(button(getString(R.string.access_refresh)) { refresh() })
        root.addView(button(getString(R.string.access_sign_out)) {
            users.signOut(); SessionManager.clear(this)
            startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        })
        setContentView(root)
        if (statusSource === NurseStatusSource.None) statusSource = NurseStatusSource.fromOfflineDatabase(this)
        refresh()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun button(label: String, action: () -> Unit) = MaterialButton(this).apply {
        text = label; isAllCaps = false; minHeight = dp(48)
        setOnClickListener { if (!busy) action() }
    }
    /** Visible, announced result of an administrator action. */
    private fun announce(message: String, background: Int) {
        adminBanner?.apply {
            text = message; visibility = View.VISIBLE
            setBackgroundColor(ContextCompat.getColor(context, background))
        }
    }
    private fun success(message: String) = announce(message, R.color.pulse_success_bg)

    private suspend fun <T> value(response: Response<T>): T {
        if (response.isSuccessful) return response.body() ?: error(getString(R.string.access_unavailable))
        if (response.code() == 409 && account?.role == "administrator") error(getString(R.string.access_conflict))
        val problem = runCatching { org.json.JSONObject(response.errorBody()?.string() ?: "{}")
            .optString("title").takeIf { it.isNotBlank() } }.getOrNull()
        error(problem ?: getString(R.string.access_unavailable))
    }

    private fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        val admin = account?.role == "administrator"
        if (admin) { adminBanner?.visibility = View.GONE; adminProgress?.visibility = View.VISIBLE }
        lifecycleScope.launch {
            try { action() } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val message = error.message ?: getString(R.string.access_unavailable)
                if (admin) announce(message, R.color.pulse_danger_bg) else status.text = message
            } finally { busy = false; adminProgress?.visibility = View.GONE }
        }
    }

    private fun refresh() = work {
        if (users.refreshAuthToken() == null) error(getString(R.string.access_sign_in_required))
        account = value(api.getAccessAccount())
        val current = account!!
        SessionManager.saveRole(this, current.uid, if (current.status == "approved") current.role else "pending")
        if (current.status == "approved" && current.role in setOf("patient", "doctor")) {
            val destination = if (current.role == "doctor") DoctorDashboardActivity::class.java else PatientDashboardActivity::class.java
            startActivity(Intent(this@SiteAccessActivity, destination)); finish(); return@work
        }
        accounts.clear(); sites.clear(); patients.clear()
        if (current.role == "administrator" && current.status == "approved") {
            val a = value(api.getAccessAccounts()); accounts.addAll(a.items); accountCursor = a.nextCursor
            val s = value(api.getAdministrationSites()); sites.addAll(s.items); siteCursor = s.nextCursor
            val p = value(api.getAdministrationPatients()); patients.addAll(p.items); patientCursor = p.nextCursor
        } else if (current.status == "approved") sites.addAll(value(api.getAssignedSites()))
        screenApproval = NurseScreenApproval.NotActive
        lastScreenCheck = null
        syncSummary = null
        approveAnother = false
        if (current.role == "nurse" && current.status == "approved") {
            restoreScreen(current)
            syncSummary = try {
                statusSource.load((screenApproval as? NurseScreenApproval.Approved)?.grant?.deviceId)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                null
            }
        }
        render()
    }

    private fun render() {
        content.removeAllViews()
        val current = account ?: return
        if (current.status != "approved") {
            status.text = getString(if (current.status == "suspended") R.string.access_suspended else R.string.access_pending)
            content.addView(button(getString(R.string.access_request_nurse)) { requestRole("nurse") })
            content.addView(button(getString(R.string.access_request_doctor)) { requestRole("doctor") })
            return
        }
        if (current.role == "nurse") {
            status.text = getString(R.string.access_nurse)
            renderNurse(current)
            return
        }
        status.text = getString(R.string.access_administrator)
        renderAdministrator()
    }

    private fun roleLabel(role: String?): String = getString(when (role) {
        "patient" -> R.string.access_role_patient
        "nurse" -> R.string.access_role_nurse
        "doctor" -> R.string.access_role_doctor
        "administrator" -> R.string.access_role_administrator
        else -> R.string.access_role_none
    })

    private fun statusLabel(value: String): String = getString(when (value) {
        "approved" -> R.string.access_status_approved
        "suspended" -> R.string.access_status_suspended
        else -> R.string.access_status_pending
    })

    private fun siteNames(ids: List<String>): String =
        ids.mapNotNull { id -> sites.firstOrNull { it.id == id }?.name }.joinToString(", ")
            .ifBlank { getString(R.string.access_no_sites) }

    private fun personName(person: AccessAccountDto) = person.displayName.ifBlank { person.email }

    private fun empty(message: String) = body(message).apply {
        setTextColor(ContextCompat.getColor(context, R.color.pulse_text_secondary))
    }

    /** A bordered row: title, detail line and one action button. */
    private fun card(title: String, detail: String, action: String, describedAs: String, onClick: () -> Unit) {
        val card = MaterialCardView(this)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        inner.addView(TextView(this).apply {
            text = title; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.pulse_text_primary))
        })
        if (detail.isNotBlank()) inner.addView(TextView(this).apply {
            text = detail; textSize = 14f; setPadding(0, dp(4), 0, dp(8))
            setTextColor(ContextCompat.getColor(context, R.color.pulse_text_secondary))
        })
        inner.addView(button(action, onClick).apply { contentDescription = describedAs })
        card.addView(inner)
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(8)) })
    }

    private fun renderAdministrator() {
        val people = accounts.filter { it.role != "administrator" }
        val waiting = people.filter { it.status != "approved" || it.requestedRole != null }
        val approved = people.filter { it.status == "approved" && it.requestedRole == null }

        heading(getString(R.string.access_section_approvals, waiting.size))
        if (waiting.isEmpty()) empty(getString(R.string.access_approvals_none))
        waiting.forEach { person ->
            val detail = getString(R.string.access_request_detail, roleLabel(person.requestedRole ?: person.role), statusLabel(person.status))
            card(personName(person), detail, getString(R.string.access_review),
                getString(R.string.access_review_for, personName(person))) { approve(person) }
        }
        if (accountCursor != null) content.addView(button(getString(R.string.access_more_accounts)) { work {
            val page = value(api.getAccessAccounts(accountCursor)); accounts.addAll(page.items); accountCursor = page.nextCursor; render()
        } })

        heading(getString(R.string.access_accounts))
        if (approved.isEmpty()) empty(getString(R.string.access_staff_none))
        approved.forEach { person ->
            val detail = getString(R.string.access_staff_detail, roleLabel(person.role), siteNames(person.siteIds))
            card(personName(person), detail, getString(R.string.access_change),
                getString(R.string.access_change_for, personName(person))) { approve(person) }
        }

        heading(getString(R.string.access_section_sites))
        content.addView(button(getString(R.string.access_add_site)) { addSite() })
        if (sites.isEmpty()) empty(getString(R.string.access_sites_none))
        sites.forEach { body(it.name) }
        if (siteCursor != null) content.addView(button(getString(R.string.access_more_sites)) { work {
            val page = value(api.getAdministrationSites(siteCursor)); sites.addAll(page.items); siteCursor = page.nextCursor; render()
        } })

        heading(getString(R.string.access_patients))
        content.addView(button(getString(R.string.access_register_patient)) { registerPatient() })
        if (patients.isEmpty()) empty(getString(R.string.access_patients_none))
        patients.forEach { patient ->
            card(patient.fullName, getString(R.string.access_patient_detail, siteNames(listOf(patient.siteId))),
                getString(R.string.access_assign_doctors), getString(R.string.access_assign_doctors_for, patient.fullName)) { assignPatient(patient) }
        }
        if (patientCursor != null) content.addView(button(getString(R.string.access_more_patients)) { work {
            val page = value(api.getAdministrationPatients(patientCursor)); patients.addAll(page.items); patientCursor = page.nextCursor; render()
        } })

        heading(getString(R.string.access_section_devices))
        body(getString(R.string.access_device_help))
        renderDeviceForm()
    }

    /** Inline form: the entered device identifier survives the list being redrawn. */
    private fun renderDeviceForm() {
        val device = entry(getString(R.string.access_device_id), false, deviceDraft)
        device.addTextChangedListener { deviceDraft = it?.toString().orEmpty() }
        if (sites.isEmpty()) { body(getString(R.string.access_site_required)); return }
        val siteLabel = TextView(this).apply {
            text = getString(R.string.access_choose_site); textSize = 14f; setPadding(0, dp(8), 0, 0)
            setTextColor(ContextCompat.getColor(context, R.color.pulse_text_secondary))
        }
        content.addView(siteLabel)
        val picker = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, sites.map { it.name })
            minimumHeight = dp(48)
            contentDescription = getString(R.string.access_choose_site)
            setSelection(deviceSiteDraft.coerceIn(0, sites.lastIndex))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { deviceSiteDraft = position }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        content.addView(picker)
        content.addView(button(getString(R.string.access_assign_device)) {
            val id = device.text.toString().trim()
            when {
                id.isEmpty() -> { device.error = getString(R.string.access_required); device.requestFocus() }
                !id.matches(Regex("[A-Za-z0-9_-]{1,64}")) -> { device.error = getString(R.string.nurse_device_name_invalid); device.requestFocus() }
                else -> {
                    val site = sites[picker.selectedItemPosition.coerceAtLeast(0)]
                    work {
                        value(api.assignDeviceSite(id, DeviceSiteRequestDto(site.id)))
                        deviceDraft = ""; render()
                        success(getString(R.string.access_device_saved, id, site.name))
                    }
                }
            }
        })
    }

    private fun addSite() = textDialog(R.string.access_add_site, R.string.access_site_name) { name ->
        if (sites.any { it.name.equals(name, ignoreCase = true) }) announce(getString(R.string.access_site_duplicate, name), R.color.pulse_warning_bg)
        else work {
            sites.add(value(api.createSite(SiteRequestDto(name)))); render()
            success(getString(R.string.access_site_saved, name))
        }
    }

    private fun requestRole(role: String) = work {
        account = value(api.requestRole(RoleRequestDto(role)))
        render()
    }

    private fun heading(value: String) { content.addView(TextView(this).apply {
        text = value; textSize = 18f; setPadding(0, dp(20), 0, dp(8))
        setTextColor(ContextCompat.getColor(context, R.color.pulse_text_primary))
        ViewCompat.setAccessibilityHeading(this, true)
    }) }

    private fun body(value: String) = TextView(this).apply {
        text = value; textSize = 16f; setPadding(0, dp(4), 0, dp(4))
        setTextColor(ContextCompat.getColor(context, R.color.pulse_text_primary))
    }.also { content.addView(it) }

    private fun entry(hintText: String, numeric: Boolean, initial: String) = EditText(this).apply {
        hint = hintText; maxLines = 1; minHeight = dp(48)
        if (numeric) {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        if (initial.isNotEmpty()) setText(initial)
    }.also { content.addView(it) }

    private fun renderNurse(nurse: AccessAccountDto) {
        val now = System.currentTimeMillis()
        val grant = (screenApproval as? NurseScreenApproval.Approved)?.grant
        val expiresAt = NurseApproval.parseUtcMillis(grant?.expiresAtUtc)
        val state = NurseApproval.authorisationState(expiresAt, now)
        approveButton = null

        // Assigned site, approved device and shift expiry. The approval is checked automatically
        // whenever this screen opens, so none of this needs a code.
        heading(getString(R.string.nurse_section_access))
        val access = brandedCard()
        val (pillLabel, pillBackground) = when (state) {
            AuthorisationState.ACTIVE -> Pair(R.string.nurse_pill_active, R.drawable.bg_pill_success)
            AuthorisationState.EXPIRED -> Pair(R.string.nurse_pill_expired, R.drawable.bg_pill_danger)
            AuthorisationState.NONE -> Pair(R.string.nurse_pill_none, R.drawable.bg_pill_warning)
        }
        access.addView(pill(getString(pillLabel), pillBackground))
        infoRow(access, getString(R.string.nurse_section_site),
            sites.joinToString(", ") { it.name }.ifBlank { getString(R.string.nurse_site_unassigned) })
        infoRow(access, getString(R.string.nurse_label_device), grant?.deviceId ?: getString(R.string.nurse_device_none))
        val expiryText = when (state) {
            AuthorisationState.ACTIVE -> getString(R.string.nurse_expiry_active,
                DateTimeFormatter.formatIsoTimestamp(grant?.expiresAtUtc.orEmpty()), remainingText(expiresAt ?: now, now))
            AuthorisationState.EXPIRED -> getString(R.string.nurse_expiry_expired,
                DateTimeFormatter.formatIsoTimestamp(grant?.expiresAtUtc.orEmpty()))
            AuthorisationState.NONE -> getString(R.string.nurse_expiry_none)
        }
        infoRow(access, getString(R.string.nurse_label_expiry), expiryText)
        val updated = syncSummary?.updatedAtMillis ?: lastScreenCheck
        access.addView(TextView(this).apply {
            text = if (updated != null) getString(R.string.nurse_last_update, DateTimeFormatter.formatSessionDate(updated))
                else getString(R.string.nurse_last_update_none)
            textSize = 12f; setPadding(0, dp(10), 0, 0)
            setTextColor(tone(R.color.pulse_text_secondary))
        })
        if (sites.isEmpty()) empty(getString(R.string.nurse_site_none))

        feedbackProgress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        content.addView(feedbackProgress)
        feedbackView = TextView(this).apply {
            textSize = 16f; visibility = View.GONE; setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextColor(tone(R.color.pulse_text_primary))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(feedbackView)

        // Code entry appears only when it is needed. BLE pairing stays separate from this approval.
        heading(getString(R.string.nurse_section_authorise))
        (screenApproval as? NurseScreenApproval.Failed)?.let { body(getString(approvalError(it.reason))) }
        if (NurseApproval.needsCode(state, approveAnother)) {
            renderApprovalForm(nurse, state)
        } else {
            body(getString(R.string.nurse_no_code_needed))
            content.addView(outlineButton(getString(R.string.nurse_approve_another)) { approveAnother = true; render() })
        }
        if (screenApproval !is NurseScreenApproval.NotActive) {
            content.addView(outlineButton(getString(R.string.screen_check_approval)) { work { restoreScreen(nurse); render() } },
                fieldParams())
        }

        heading(getString(R.string.nurse_sync_heading))
        nurseSyncStatus = body("")
        body(getString(R.string.nurse_touchscreen_note))
        updateNurseSync()
        if (grant != null && state == AuthorisationState.ACTIVE) {
            content.addView(outlineButton(getString(R.string.ui_ble_sync_recordings)) {
                work {
                    val bonded = bleManager.findBondedTouchscreen(grant.deviceId)
                    if (bonded == null) {
                        showFeedback(getString(R.string.ui_ble_pair_first, grant.deviceId), R.color.pulse_warning_bg)
                        return@work
                    }
                    when (val res = bleManager.connectAndSyncAuthorisedRecordings(bonded, grant.id, nurse.uid)) {
                        is com.example.prog7314_poepart23.ble.BleTransferResult.Success -> {
                            syncSummary = statusSource.load(grant.deviceId)
                            updateNurseSync()
                            showFeedback(resources.getQuantityString(R.plurals.ui_ble_transferred, res.transferredSessions, res.transferredSessions), R.color.pulse_success_bg)
                        }
                        is com.example.prog7314_poepart23.ble.BleTransferResult.Failure -> {
                            showFeedback(getString(R.string.ui_ble_transfer_failed), R.color.pulse_warning_bg)
                        }
                    }
                }
            }, fieldParams())
        }
    }

    /** Branded approval form: device name, six-digit code and Approve. Shown only when a code is needed. */
    private fun renderApprovalForm(nurse: AccessAccountDto, state: AuthorisationState) {
        val form = brandedCard()
        if (state == AuthorisationState.EXPIRED) form.addView(formText(getString(R.string.nurse_code_needed_expired), bold = true))
        form.addView(formText(getString(R.string.screen_approval_help)))
        form.addView(formText(getString(R.string.nurse_pairing_note)))
        val device = brandedEntry(getString(R.string.nurse_device_name), false, rememberedScreens().getString(nurse.uid, "").orEmpty())
        val code = brandedEntry(getString(R.string.screen_code), true, "")
        form.addView(device, fieldParams())
        form.addView(code, fieldParams())
        val approve = primaryButton(getString(R.string.nurse_approve_button)) { submitApproval(nurse, device, code) }
            .apply { isEnabled = sites.isNotEmpty() }
        approveButton = approve
        form.addView(approve, fieldParams())
        if (approveAnother && state == AuthorisationState.ACTIVE) {
            form.addView(outlineButton(getString(R.string.cancel)) { approveAnother = false; render() }, fieldParams())
        }
    }

    // ---- Branded building blocks for the nurse view (PulseLink tokens, no platform defaults) ----

    private fun tone(colorRes: Int) = ContextCompat.getColor(this, colorRes)

    private fun fieldParams() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(10), 0, 0) }

    private fun brandedCard(): LinearLayout {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        val card = MaterialCardView(this)
        card.addView(inner)
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(8)) })
        return inner
    }

    private fun pill(label: String, background: Int) = TextView(this).apply {
        text = label; textSize = 12f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(12), dp(4), dp(12), dp(4)); setBackgroundResource(background)
        setTextColor(tone(R.color.pulse_text_primary))
        layoutParams = LinearLayout.LayoutParams(-2, -2)
    }

    /** Label above value, so large text wraps instead of being cut off. Read by TalkBack as one item. */
    private fun infoRow(parent: LinearLayout, label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0)
            isFocusable = true
            contentDescription = getString(R.string.dashboard_stat_cd_format, label, value)
        }
        row.addView(TextView(this).apply {
            text = label; textSize = 13f; setTextColor(tone(R.color.pulse_text_secondary))
        })
        row.addView(TextView(this).apply {
            text = value; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(tone(R.color.pulse_text_primary))
        })
        parent.addView(row)
    }

    private fun formText(value: String, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = 14f; setPadding(0, dp(4), 0, dp(4))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(tone(R.color.pulse_text_primary))
    }

    private fun brandedEntry(hintText: String, numeric: Boolean, initial: String) = EditText(this).apply {
        hint = hintText; maxLines = 1; minHeight = dp(48); textSize = 16f
        setBackgroundResource(R.drawable.bg_input_field)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setTextColor(tone(R.color.pulse_text_primary)); setHintTextColor(tone(R.color.pulse_text_hint))
        if (numeric) {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        if (initial.isNotEmpty()) setText(initial)
    }

    private fun stateTint(enabledColor: Int, disabledColor: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(tone(disabledColor), tone(enabledColor))
    )

    private fun primaryButton(label: String, action: () -> Unit) = MaterialButton(this).apply {
        text = label; isAllCaps = false; minHeight = dp(48); cornerRadius = dp(8)
        backgroundTintList = stateTint(R.color.pulse_primary_fill, R.color.pulse_input_bg)
        setTextColor(stateTint(R.color.white, R.color.pulse_text_hint))
        setOnClickListener { if (!busy) action() }
    }

    private fun outlineButton(label: String, action: () -> Unit) = MaterialButton(this).apply {
        text = label; isAllCaps = false; minHeight = dp(48); cornerRadius = dp(8)
        backgroundTintList = ColorStateList.valueOf(tone(R.color.pulse_card))
        strokeColor = ColorStateList.valueOf(tone(R.color.pulse_primary)); strokeWidth = dp(2)
        setTextColor(tone(R.color.pulse_primary))
        setOnClickListener { if (!busy) action() }
    }

    private fun bannerBackground(colorRes: Int) = GradientDrawable().apply {
        setCornerRadius(dp(12).toFloat()); setColor(tone(colorRes))
    }

    private fun submitApproval(nurse: AccessAccountDto, device: EditText, code: EditText) {
        val identifier = device.text.toString().trim(); val digits = code.text.toString().trim()
        when {
            identifier.isEmpty() -> { device.error = getString(R.string.access_required); device.requestFocus() }
            !identifier.matches(Regex("[A-Za-z0-9_-]{1,64}")) -> { device.error = getString(R.string.nurse_device_name_invalid); device.requestFocus() }
            !digits.matches(Regex("[0-9]{6}")) -> { code.error = getString(R.string.screen_code_required); code.requestFocus() }
            else -> {
                if (!com.example.prog7314_poepart23.utils.NetworkMonitor.isCurrentlyOnline(this)) {
                    showFeedback(getString(R.string.nurse_feedback_offline), R.color.pulse_warning_bg)
                    return
                }
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(code.windowToken, 0)
                work {
                    showLoading()
                    val result = try {
                        nurseScreens.approve(identifier, digits, nurse.siteIds.singleOrNull().orEmpty(), nurse.uid)
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        NurseScreenApproval.Failed(NurseScreenApproval.Reason.UNAVAILABLE)
                    }
                    when (result) {
                        is NurseScreenApproval.Approved -> {
                            rememberedScreens().edit().putString(nurse.uid, result.grant.deviceId).apply()
                            screenApproval = result
                            lastScreenCheck = System.currentTimeMillis()
                            approveAnother = false
                            render()
                            showFeedback(getString(R.string.nurse_feedback_success, result.grant.deviceId,
                                DateTimeFormatter.formatIsoTimestamp(result.grant.expiresAtUtc)), R.color.pulse_success_bg)
                        }
                        is NurseScreenApproval.Failed -> {
                            if (result.reason == NurseScreenApproval.Reason.INVALID_CODE ||
                                result.reason == NurseScreenApproval.Reason.EXPIRED) { code.text.clear(); code.requestFocus() }
                            showFeedback(getString(submitError(result.reason)), submitColour(result.reason))
                        }
                        NurseScreenApproval.NotActive -> showFeedback(getString(R.string.nurse_feedback_unavailable), R.color.pulse_warning_bg)
                    }
                }
            }
        }
    }

    /** Messages for the approval form. A code that failed is reported as a code problem. */
    private fun submitError(reason: NurseScreenApproval.Reason): Int = when (reason) {
        NurseScreenApproval.Reason.INVALID_DEVICE -> R.string.nurse_feedback_device_not_found
        NurseScreenApproval.Reason.INVALID_CODE -> R.string.nurse_feedback_wrong_code
        NurseScreenApproval.Reason.SIGN_IN_REQUIRED -> R.string.nurse_feedback_sign_in
        NurseScreenApproval.Reason.DENIED -> R.string.nurse_feedback_denied
        NurseScreenApproval.Reason.EXPIRED -> R.string.nurse_feedback_expired
        NurseScreenApproval.Reason.CONFLICT -> R.string.screen_approval_conflict
        NurseScreenApproval.Reason.INVALID_RECEIPT -> R.string.screen_approval_invalid_receipt
        NurseScreenApproval.Reason.UNAVAILABLE -> R.string.nurse_feedback_unavailable
    }

    private fun submitColour(reason: NurseScreenApproval.Reason): Int = when (reason) {
        NurseScreenApproval.Reason.CONFLICT, NurseScreenApproval.Reason.UNAVAILABLE -> R.color.pulse_warning_bg
        else -> R.color.pulse_danger_bg
    }

    private fun approvalError(reason: NurseScreenApproval.Reason): Int = when (reason) {
        NurseScreenApproval.Reason.INVALID_DEVICE -> R.string.access_required
        NurseScreenApproval.Reason.INVALID_CODE -> R.string.screen_code_required
        NurseScreenApproval.Reason.SIGN_IN_REQUIRED -> R.string.access_sign_in_required
        NurseScreenApproval.Reason.DENIED -> R.string.screen_approval_denied
        NurseScreenApproval.Reason.EXPIRED -> R.string.screen_approval_expired
        NurseScreenApproval.Reason.CONFLICT -> R.string.screen_approval_conflict
        NurseScreenApproval.Reason.INVALID_RECEIPT -> R.string.screen_approval_invalid_receipt
        NurseScreenApproval.Reason.UNAVAILABLE -> R.string.access_unavailable
    }

    private fun showLoading() {
        approveButton?.isEnabled = false
        feedbackProgress?.visibility = View.VISIBLE
        feedbackView?.apply {
            text = getString(R.string.nurse_loading); visibility = View.VISIBLE
            setBackground(bannerBackground(R.color.pulse_warning_bg))
        }
    }

    private fun showFeedback(message: String, background: Int) {
        approveButton?.isEnabled = sites.isNotEmpty()
        feedbackProgress?.visibility = View.GONE
        feedbackView?.apply {
            text = message; visibility = View.VISIBLE
            setBackground(bannerBackground(background))
        }
    }

    private fun remainingText(expiresAt: Long, now: Long): String {
        val minutes = ((expiresAt - now) / 60_000L).coerceAtLeast(0L)
        return if (minutes >= 60) getString(R.string.nurse_remaining_hm, minutes / 60, minutes % 60)
        else getString(R.string.nurse_remaining_m, minutes)
    }

    /** Shows only what was supplied. Nothing is presented as uploaded unless the data says so. */
    private fun updateNurseSync() {
        val syncView = nurseSyncStatus ?: return
        val summary = syncSummary
        syncView.text = if (summary == null) getString(R.string.nurse_sync_unavailable) else listOf(
            getString(R.string.nurse_sync_local, summary.local),
            getString(R.string.nurse_sync_synchronising, summary.synchronising),
            getString(R.string.nurse_sync_uploaded, summary.uploaded)
        ).joinToString("\n")
    }

    private fun textDialog(titleId: Int, hintId: Int, save: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(hintId); maxLines = 2; textSize = 16f
            background = ContextCompat.getDrawable(context, R.drawable.bg_dialog_input)
            minHeight = dp(52); setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(ContextCompat.getColor(context, R.color.pulse_text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.pulse_text_hint))
        }
        val holder = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_PulseLink_Dialog).setTitle(titleId).setView(holder)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.access_save, null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) input.error = getString(R.string.access_required)
            else { dialog.dismiss(); save(text) }
        } }
        dialog.show()
    }

    private fun chooseSite(save: (ClinicSiteDto) -> Unit) {
        if (sites.isEmpty()) { announce(getString(R.string.access_site_required), R.color.pulse_warning_bg); return }
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_PulseLink_Dialog).setTitle(R.string.access_choose_site)
            .setItems(sites.map { it.name }.toTypedArray()) { _, index -> save(sites[index]) }.show()
    }

    /** One form for role, status and sites. The expected version is still sent so stale edits are rejected. */
    private fun approve(person: AccessAccountDto) {
        val roles = listOf("patient", "nurse", "doctor")
        val selected = person.siteIds.toMutableSet()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        form.addView(TextView(this).apply {
            text = getString(R.string.access_form_person, personName(person), statusLabel(person.status)); textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.pulse_text_primary))
        })
        form.addView(TextView(this).apply {
            text = getString(R.string.access_form_role); textSize = 14f; setPadding(0, dp(12), 0, dp(4))
            setTextColor(ContextCompat.getColor(context, R.color.pulse_text_secondary))
            ViewCompat.setAccessibilityHeading(this, true)
        })
        val group = RadioGroup(this)
        val start = (person.requestedRole ?: person.role).takeIf { it in roles }
        roles.forEach { role ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId(); tag = role; text = roleLabel(role); minHeight = dp(48)
                isChecked = role == start
                setTextColor(ContextCompat.getColor(context, R.color.pulse_text_primary))
            })
        }
        form.addView(group)
        val siteBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        siteBox.addView(TextView(this).apply {
            text = getString(R.string.access_choose_sites); textSize = 14f; setPadding(0, dp(12), 0, dp(4))
            setTextColor(ContextCompat.getColor(context, R.color.pulse_text_secondary))
            ViewCompat.setAccessibilityHeading(this, true)
        })
        if (sites.isEmpty()) siteBox.addView(TextView(this).apply { text = getString(R.string.access_site_required); textSize = 16f })
        sites.forEach { site ->
            siteBox.addView(CheckBox(this).apply {
                text = site.name; minHeight = dp(48); isChecked = site.id in selected
                setTextColor(ContextCompat.getColor(context, R.color.pulse_text_primary))
                setOnCheckedChangeListener { _, checked -> if (checked) selected.add(site.id) else selected.remove(site.id) }
            })
        }
        form.addView(siteBox)
        fun chosenRole() = group.findViewById<View>(group.checkedRadioButtonId)?.tag as? String
        fun showSites() { siteBox.visibility = if (chosenRole() == "patient") View.GONE else View.VISIBLE }
        group.setOnCheckedChangeListener { _, _ -> showSites() }
        showSites()
        val problem = TextView(this).apply {
            textSize = 14f; visibility = View.GONE; setPadding(0, dp(8), 0, 0)
            setTextColor(ContextCompat.getColor(context, R.color.alert_red))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        form.addView(problem)

        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_PulseLink_Dialog).setTitle(R.string.access_choose_role).setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.access_suspend, null)
            .setPositiveButton(R.string.access_approve, null).create()
        fun submit(statusValue: String) {
            val role = chosenRole()
            val sitesNeeded = if (statusValue == "approved") when (role) { "nurse" -> 1..1; "doctor" -> 1..sites.size.coerceAtLeast(1); else -> 0..0 } else 0..Int.MAX_VALUE
            val message = when {
                role == null -> R.string.access_error_role
                role != "patient" && selected.size !in sitesNeeded -> if (role == "nurse") R.string.access_error_nurse_site else R.string.access_error_doctor_site
                else -> null
            }
            if (message != null || role == null) {
                problem.text = getString(message ?: R.string.access_error_role); problem.visibility = View.VISIBLE
                return
            }
            dialog.dismiss()
            work {
                val updated = value(api.approveAccount(person.uid, ApprovalRequestDto(role, statusValue,
                    if (role == "patient") emptyList() else selected.toList(), person.version)))
                accounts[accounts.indexOfFirst { it.uid == updated.uid }] = updated; render()
                success(getString(if (statusValue == "approved") R.string.access_approved_done else R.string.access_suspended_done,
                    personName(updated), roleLabel(role)))
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit("approved") }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { submit("suspended") }
        }
        dialog.show()
    }

    private fun chooseDoctor(siteId: String, save: (AccessAccountDto) -> Unit) {
        val doctors = accounts.filter { it.role == "doctor" && it.status == "approved" && siteId in it.siteIds }
        if (doctors.isEmpty()) { announce(getString(R.string.access_doctor_required), R.color.pulse_warning_bg); return }
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_PulseLink_Dialog).setTitle(R.string.access_choose_doctor)
            .setItems(doctors.map { it.displayName.ifBlank { it.email } }.toTypedArray()) { _, index -> save(doctors[index]) }.show()
    }

    private fun registerPatient() = textDialog(R.string.access_register_patient, R.string.access_patient_name) { name ->
        chooseSite { site -> chooseDoctor(site.id) { doctor -> work {
            patients.add(value(api.registerClinicalPatient(PatientRegistrationDto(name, site.id, doctor.uid)))); render()
            success(getString(R.string.access_patient_saved, name))
        } } }
    }

    private fun assignPatient(patient: ClinicalPatientDto) = chooseDoctor(patient.siteId) { primary ->
        val doctors = accounts.filter { it.role == "doctor" && it.status == "approved" && patient.siteId in it.siteIds && it.uid != primary.uid }
        val selected = patient.additionalDoctorUids.filter { it != primary.uid }.toMutableSet()
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_PulseLink_Dialog).setTitle(R.string.access_additional_doctors)
            .setMultiChoiceItems(doctors.map { it.displayName.ifBlank { it.email } }.toTypedArray(), doctors.map { it.uid in selected }.toBooleanArray()) { _, index, checked ->
                if (checked) selected.add(doctors[index].uid) else selected.remove(doctors[index].uid)
            }.setPositiveButton(R.string.access_save) { _, _ -> work {
                val updated = value(api.assignClinicalPatient(patient.id, PatientAssignmentDto(primary.uid, selected.toList(), patient.version)))
                patients[patients.indexOfFirst { it.id == updated.id }] = updated; render()
                success(getString(R.string.access_assignment_saved, updated.fullName))
            } }.setNegativeButton(android.R.string.cancel, null).show()
    }
}
