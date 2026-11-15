/*
 * Activity displaying historical AD8232 ECG recording sessions for the logged-in patient,
 * with rhythm status filtering, real-time query searching, and bottom navigation integration.
 *
 * Reference List:
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End. [online] Available at: <https://www.analog.com/media/en/technical-documentation/data-sheets/AD8232.pdf> [Accessed 21 September 2026].
 * - Google Cloud, 2024. Firebase Authentication Documentation. [online] Available at: <https://firebase.google.com/docs/auth> [Accessed 21 September 2026].
 * - Google Developers, 2024. Display content edge-to-edge in your app. [online] Available at: <https://developer.android.com/develop/ui/views/layout/edge-to-edge> [Accessed 21 September 2026].
 * - Google Developers, 2024. ViewModel Overview - Android Developers. [online] Available at: <https://developer.android.com/topic/libraries/architecture/viewmodel> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge             // (Google Developers, 2024)
import androidx.activity.viewModels                   // (Google Developers, 2024)
import androidx.appcompat.app.AppCompatActivity        // (Google Developers, 2024)
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat                  // (Google Developers, 2024)
import androidx.core.view.WindowInsetsCompat          // (Google Developers, 2024)
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.prog7314_poepart23.adapters.SessionListAdapter
import com.example.prog7314_poepart23.data.remote.models.EcgSessionSummaryDto
import com.example.prog7314_poepart23.databinding.PatientEcgHistoryBinding
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.utils.applyStoredAvatar
import com.example.prog7314_poepart23.viewmodels.SessionHistoryViewModel
import com.google.firebase.auth.FirebaseAuth         // (Google Cloud, 2024)
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import com.example.prog7314_poepart23.utils.openOnClick

class PatientEcgHistoryActivity : AppCompatActivity() { // (Google Developers, 2024)

    private lateinit var binding: PatientEcgHistoryBinding
    private val sessionHistoryViewModel: SessionHistoryViewModel by viewModels()
    private lateinit var sessionListAdapter: SessionListAdapter

    private var allSessions: List<EcgSessionSummaryDto> = emptyList()
    private var selectedFilter: FilterType = FilterType.ALL
    private var searchQuery: String = ""
    private var newestFirst: Boolean = true
    private var tvSort: TextView? = null

    private val auth = FirebaseAuth.getInstance()
    private var currentPatientId: String? = null

    enum class FilterType {
        ALL, NORMAL, IRREGULAR, FLAGGED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = PatientEcgHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        HapticFeedbackManager.initialise(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        currentPatientId = auth.currentUser?.uid

        setUpSessionList()
        setUpFilterChips()
        setUpSearch()
        setUpSortToggle()
        setUpBottomNav()
        observeSessionHistory()
        loadSessions()
    }

    private fun setUpSessionList() {
        sessionListAdapter = SessionListAdapter { session ->
            HapticFeedbackManager.lightTick()
            val intent = Intent(this, SessionDetailsActivity::class.java).apply {
                putExtra(EXTRA_SESSION_ID, session.sessionId)
                putExtra(EXTRA_AVERAGE_BPM, session.averageBpm)
                putExtra(EXTRA_DURATION_SECONDS, session.durationSeconds)
                putExtra(EXTRA_START_TIME_UTC, session.startTimeUtc)
                putExtra(EXTRA_SIGNAL_QUALITY, session.signalQuality)
                putExtra(EXTRA_SESSION_STATUS, session.status)
            }
            startActivity(intent)
        }
        binding.rvSessions.layoutManager = LinearLayoutManager(this)
        binding.rvSessions.adapter = sessionListAdapter
        binding.rvSessions.setHasFixedSize(true)
    }

    private fun setUpFilterChips() {
        val chips = listOf(
            binding.chipAll to FilterType.ALL,
            binding.chipNormal to FilterType.NORMAL,
            binding.chipIrregular to FilterType.IRREGULAR,
            binding.chipFlagged to FilterType.FLAGGED
        )

        chips.forEach { (chipView, filter) ->
            chipView?.setOnClickListener {
                HapticFeedbackManager.lightTick()
                selectedFilter = filter
                updateFilterChipStyles(chips, filter)
                applyFiltersAndSearch()
            }
        }
    }

    private fun updateFilterChipStyles(
        chips: List<Pair<TextView?, FilterType>>,
        activeFilter: FilterType
    ) {
        chips.forEach { (chipView, filter) ->
            if (chipView != null) {
                val isSelected = filter == activeFilter
                chipView.setBackgroundResource(
                    if (isSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
                )
                chipView.setTextColor(
                    ContextCompat.getColor(
                        this,
                        if (isSelected) R.color.white else R.color.pulse_text_secondary
                    )
                )
            }
        }
    }

    private fun setUpSearch() {
        val searchContainer = binding.main.getChildAt(1) as? android.view.ViewGroup
        val searchInputLayout = searchContainer?.getChildAt(0) as? android.view.ViewGroup
        val etSearch = searchInputLayout?.getChildAt(1) as? android.widget.EditText

        etSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                applyFiltersAndSearch()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // "Most recent first" / "Oldest first" toggle, added under the filter chips in code so the XML layout is untouched.
    private fun setUpSortToggle() {
        val content = binding.main.getChildAt(1) as? ViewGroup ?: return
        val toggle = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            minHeight = (40 * resources.displayMetrics.density).toInt()
            setTextColor(ContextCompat.getColor(this@PatientEcgHistoryActivity, R.color.pulse_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_down, 0)
            compoundDrawablePadding = (6 * resources.displayMetrics.density).toInt()
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@PatientEcgHistoryActivity, R.color.pulse_primary)
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                HapticFeedbackManager.lightTick()
                newestFirst = !newestFirst
                updateSortLabel()
                applyFiltersAndSearch()
            }
        }
        content.addView(
            toggle,
            2,
            android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        tvSort = toggle
        updateSortLabel()
    }

    private fun updateSortLabel() {
        tvSort?.text = getString(
            if (newestFirst) R.string.history_sort_newest else R.string.history_sort_oldest
        )
    }

    // Parses an ISO 8601 UTC timestamp to epoch millis; unparseable values sort as oldest.
    private fun sessionTimeMillis(iso: String): Long = try {
        val clean = iso.trim()
        val base = if (clean.contains('.')) clean.substringBefore('.') else clean.removeSuffix("Z")
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(base)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun setUpBottomNav() {
        BottomNavHelper.setUpPatientNav(this, BottomNavHelper.PatientTab.REPORTS)
    }

    private fun observeSessionHistory() {
        sessionHistoryViewModel.sessionHistory.observe(this) { resource ->
            when (resource) {
                is Resource.Success -> {
                    com.example.prog7314_poepart23.utils.ShimmerHelper.stopShimmer(binding.shimmerHistoryLayout.shimmerList, binding.rvSessions)
                    allSessions = resource.data ?: emptyList()
                    applyFiltersAndSearch()
                }
                is Resource.Error -> {
                    com.example.prog7314_poepart23.utils.ShimmerHelper.stopShimmer(binding.shimmerHistoryLayout.shimmerList, binding.rvSessions)
                    allSessions = emptyList()
                    applyFiltersAndSearch()
                    android.widget.Toast.makeText(
                        this,
                        resource.message ?: getString(R.string.l10n_unable_load_ecg_data),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                is Resource.Loading -> {
                    com.example.prog7314_poepart23.utils.ShimmerHelper.startShimmer(binding.shimmerHistoryLayout.shimmerList, binding.rvSessions)
                }
            }
        }
    }

    private fun applyFiltersAndSearch() {
        var filtered = allSessions

        // Apply category filter
        filtered = when (selectedFilter) {
            FilterType.ALL -> filtered
            FilterType.NORMAL -> filtered.filter {
                !it.signalQuality.equals("Poor", ignoreCase = true) &&
                        !it.status.equals("Flagged", ignoreCase = true)
            }
            FilterType.IRREGULAR -> filtered.filter {
                it.signalQuality.equals("Poor", ignoreCase = true) ||
                        it.status.equals("Irregular", ignoreCase = true)
            }
            FilterType.FLAGGED -> filtered.filter {
                it.status.equals("Flagged", ignoreCase = true)
            }
        }

        // Apply search query
        if (searchQuery.isNotBlank()) {
            filtered = filtered.filter { session ->
                session.startTimeUtc.contains(searchQuery, ignoreCase = true) ||
                        session.status.contains(searchQuery, ignoreCase = true) ||
                        session.sessionId.contains(searchQuery, ignoreCase = true)
            }
        }

        // Sort by recording time: most recent first (default) or oldest first.
        filtered = if (newestFirst) filtered.sortedByDescending { sessionTimeMillis(it.startTimeUtc) }
        else filtered.sortedBy { sessionTimeMillis(it.startTimeUtc) }

        sessionListAdapter.submitList(filtered)
    }

    private fun loadSessions() {
        val patientId = currentPatientId ?: return
        sessionHistoryViewModel.loadPatientHistory(patientId)
    }

    override fun onResume() {
        super.onResume()
        applyStoredAvatar(R.id.btnProfileAvatar, currentPatientId)
        loadSessions()
    }

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_AVERAGE_BPM = "extra_average_bpm"
        const val EXTRA_DURATION_SECONDS = "extra_duration_seconds"
        const val EXTRA_START_TIME_UTC = "extra_start_time_utc"
        const val EXTRA_SIGNAL_QUALITY = "extra_signal_quality"
        const val EXTRA_SESSION_STATUS = "extra_session_status"
    }

    private var controlsWired = false

    // Header icons, filter chips and inline text actions exposed by this screen's layout.
    override fun onStart() {
        super.onStart()
        if (controlsWired) return
        controlsWired = true
        HapticFeedbackManager.initialise(this)
        openOnClick(R.id.btnSettings, Patient_Settings::class.java)
        openOnClick(R.id.btnProfileAvatar, PatientProfileActivity::class.java)
    }

}
