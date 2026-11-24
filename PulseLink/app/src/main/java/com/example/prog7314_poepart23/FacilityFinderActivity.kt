package com.example.prog7314_poepart23

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.prog7314_poepart23.data.remote.models.AddressCoordinateFallback
import com.example.prog7314_poepart23.data.remote.models.DirectionsLaunchResult
import com.example.prog7314_poepart23.data.remote.models.FacilityDirectoryState
import com.example.prog7314_poepart23.data.remote.models.PublicFacilityDto
import com.example.prog7314_poepart23.data.repository.FacilityDirectionsHelper
import com.example.prog7314_poepart23.data.repository.FacilityDirectoryRepository
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.LocaleHelper
import com.example.prog7314_poepart23.utils.PulseLinkModal
import com.example.prog7314_poepart23.views.GqeberhaMapView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch

class FacilityFinderActivity : AppCompatActivity() {

    private lateinit var repository: FacilityDirectoryRepository

    // Views
    private lateinit var layoutTopOverlay: LinearLayout
    private lateinit var btnBack: ImageButton
    private lateinit var etSearchQuery: EditText
    private lateinit var btnClearSearch: ImageButton
    private lateinit var chipGroupFilters: ChipGroup
    private lateinit var tvOfflineNotice: TextView

    // Map & List containers
    private lateinit var layoutMapContainer: View
    private lateinit var layoutListContainer: View
    private lateinit var mapView: GqeberhaMapView
    private lateinit var cardZoomControls: MaterialCardView
    private lateinit var btnZoomIn: ImageButton
    private lateinit var btnZoomOut: ImageButton
    private lateinit var btnZoomReset: ImageButton
    private lateinit var btnToggleView: MaterialButton
    private lateinit var rvFacilityList: RecyclerView
    private lateinit var layoutEmptySearch: LinearLayout
    private lateinit var tvEmptySearchMessage: TextView

    // Place Peek Card views
    private lateinit var cardPlacePeek: MaterialCardView
    private lateinit var layoutPlacePeekContent: LinearLayout
    private lateinit var tvPlaceName: TextView
    private lateinit var tvPlaceTypeBadge: TextView
    private lateinit var tvPlaceArea: TextView
    private lateinit var tvPlaceAddress: TextView
    private lateinit var tvPlaceCoords: TextView
    private lateinit var tvPlaceReviewedDate: TextView
    private lateinit var tvPlaceSource: TextView
    private lateinit var btnOpenDirections: MaterialButton
    private lateinit var btnCopyCoordinates: MaterialButton

    private val facilityListAdapter = FacilityListAdapter()
    private var allFacilities: List<PublicFacilityDto> = emptyList()
    private var selectedFacility: PublicFacilityDto? = null
    private var currentFilterType: String? = null
    private var currentSearchQuery: String? = null
    private var isListViewActive = false
    private var bottomSystemInsetPx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_facility_finder)

        HapticFeedbackManager.initialise(this)
        repository = FacilityDirectoryRepository(this)

        bindViews()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            bottomSystemInsetPx = systemBars.bottom
            applyBottomSystemInsets()
            updateMapOverlayBounds()
            insets
        }

        setupListeners()
        setupRecyclerView()
        loadDirectory()
    }

    private fun bindViews() {
        layoutTopOverlay = findViewById(R.id.layoutTopOverlay)
        btnBack = findViewById(R.id.btnBack)
        etSearchQuery = findViewById(R.id.etSearchQuery)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        chipGroupFilters = findViewById(R.id.chipGroupFilters)
        tvOfflineNotice = findViewById(R.id.tvOfflineNotice)

        layoutMapContainer = findViewById(R.id.layoutMapContainer)
        layoutListContainer = findViewById(R.id.layoutListContainer)
        mapView = findViewById(R.id.mapView)
        cardZoomControls = findViewById(R.id.cardZoomControls)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        btnZoomReset = findViewById(R.id.btnZoomReset)
        btnToggleView = findViewById(R.id.btnToggleView)
        rvFacilityList = findViewById(R.id.rvFacilityList)
        layoutEmptySearch = findViewById(R.id.layoutEmptySearch)
        tvEmptySearchMessage = findViewById(R.id.tvEmptySearchMessage)

        cardPlacePeek = findViewById(R.id.cardPlacePeek)
        layoutPlacePeekContent = findViewById(R.id.layoutPlacePeekContent)
        tvPlaceName = findViewById(R.id.tvPlaceName)
        tvPlaceTypeBadge = findViewById(R.id.tvPlaceTypeBadge)
        tvPlaceArea = findViewById(R.id.tvPlaceArea)
        tvPlaceAddress = findViewById(R.id.tvPlaceAddress)
        tvPlaceCoords = findViewById(R.id.tvPlaceCoords)
        tvPlaceReviewedDate = findViewById(R.id.tvPlaceReviewedDate)
        tvPlaceSource = findViewById(R.id.tvPlaceSource)
        btnOpenDirections = findViewById(R.id.btnOpenDirections)
        btnCopyCoordinates = findViewById(R.id.btnCopyCoordinates)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun applyBottomSystemInsets() {
        layoutPlacePeekContent.setPadding(
            layoutPlacePeekContent.paddingLeft,
            layoutPlacePeekContent.paddingTop,
            layoutPlacePeekContent.paddingRight,
            dp(20) + bottomSystemInsetPx
        )
        rvFacilityList.setPadding(
            rvFacilityList.paddingLeft,
            rvFacilityList.paddingTop,
            rvFacilityList.paddingRight,
            dp(76) + bottomSystemInsetPx
        )
    }

    private fun updateMapOverlayBounds() {
        val topOverlayHeight = layoutTopOverlay.height
        val peekHeight = if (!isListViewActive && cardPlacePeek.visibility == View.VISIBLE) cardPlacePeek.height else 0

        if (topOverlayHeight > 0) {
            val listLp = layoutListContainer.layoutParams as CoordinatorLayout.LayoutParams
            if (listLp.topMargin != topOverlayHeight) {
                listLp.topMargin = topOverlayHeight
                layoutListContainer.layoutParams = listLp
            }
        }

        val toggleBottomMargin = if (peekHeight > 0) {
            peekHeight + dp(12)
        } else {
            bottomSystemInsetPx + dp(16)
        }
        val toggleLp = btnToggleView.layoutParams as CoordinatorLayout.LayoutParams
        if (toggleLp.bottomMargin != toggleBottomMargin) {
            toggleLp.bottomMargin = toggleBottomMargin
            btnToggleView.layoutParams = toggleLp
        }

        val toggleHeight = if (btnToggleView.height > 0) btnToggleView.height else dp(44)
        val zoomBottomMargin = toggleBottomMargin + toggleHeight + dp(10)
        val zoomLp = cardZoomControls.layoutParams as FrameLayout.LayoutParams
        if (zoomLp.bottomMargin != zoomBottomMargin) {
            zoomLp.bottomMargin = zoomBottomMargin
            cardZoomControls.layoutParams = zoomLp
        }

        mapView.setViewportInsets(topOverlayHeight, peekHeight)
    }

    private fun setupListeners() {
        layoutTopOverlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateMapOverlayBounds()
        }
        cardPlacePeek.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateMapOverlayBounds()
        }

        btnBack.setOnClickListener {
            HapticFeedbackManager.lightTick()
            finish()
        }

        btnZoomIn.setOnClickListener {
            HapticFeedbackManager.lightTick()
            mapView.zoomIn()
        }

        btnZoomOut.setOnClickListener {
            HapticFeedbackManager.lightTick()
            mapView.zoomOut()
        }

        btnZoomReset.setOnClickListener {
            HapticFeedbackManager.lightTick()
            mapView.resetZoom()
        }

        btnClearSearch.setOnClickListener {
            HapticFeedbackManager.lightTick()
            etSearchQuery.text.clear()
        }

        etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim()
                btnClearSearch.visibility = if (q.isNullOrEmpty()) View.GONE else View.VISIBLE
                currentSearchQuery = q?.ifEmpty { null }
                filterAndDisplay()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        chipGroupFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            HapticFeedbackManager.lightTick()
            currentFilterType = when (checkedIds.firstOrNull()) {
                R.id.chipFilterHospitals -> "Hospital"
                R.id.chipFilterClinics -> "Clinic"
                R.id.chipFilterChc -> "Community Health Centre"
                else -> null
            }
            filterAndDisplay()
        }

        mapView.onFacilitySelected = { facility ->
            selectFacility(facility)
        }

        btnToggleView.setOnClickListener {
            HapticFeedbackManager.lightTick()
            isListViewActive = !isListViewActive
            if (isListViewActive) {
                layoutMapContainer.visibility = View.GONE
                layoutListContainer.visibility = View.VISIBLE
                cardPlacePeek.visibility = View.GONE
                btnToggleView.text = getString(R.string.facility_toggle_map)
                btnToggleView.setIconResource(R.drawable.ic_map_pin)
            } else {
                layoutMapContainer.visibility = View.VISIBLE
                layoutListContainer.visibility = View.GONE
                cardPlacePeek.visibility = if (selectedFacility != null) View.VISIBLE else View.GONE
                btnToggleView.text = getString(R.string.facility_toggle_list)
                btnToggleView.setIconResource(R.drawable.ic_list_bullet)
            }
            updateMapOverlayBounds()
        }

        btnOpenDirections.setOnClickListener {
            val target = selectedFacility ?: return@setOnClickListener
            launchGoogleMapsDirections(target)
        }

        btnCopyCoordinates.setOnClickListener {
            val target = selectedFacility ?: return@setOnClickListener
            copyFacilityCoordinates(target)
        }
    }

    private fun setupRecyclerView() {
        rvFacilityList.layoutManager = LinearLayoutManager(this)
        rvFacilityList.adapter = facilityListAdapter
    }

    private fun loadDirectory() {
        val isOnline = isNetworkConnected()
        lifecycleScope.launch {
            when (val state = repository.loadFacilities(isOnline = isOnline)) {
                is FacilityDirectoryState.Ready -> {
                    allFacilities = state.facilities
                    tvOfflineNotice.visibility = if (state.isFromOfflineCache) View.VISIBLE else View.GONE
                    filterAndDisplay()
                    if (selectedFacility == null && allFacilities.isNotEmpty()) {
                        selectFacility(allFacilities.first())
                    }
                }
                is FacilityDirectoryState.Empty -> {
                    allFacilities = emptyList()
                    filterAndDisplay()
                }
                is FacilityDirectoryState.Error -> {
                    Toast.makeText(this@FacilityFinderActivity, state.message, Toast.LENGTH_SHORT).show()
                }
                is FacilityDirectoryState.Loading -> Unit
            }
        }
    }

    private fun filterAndDisplay() {
        var filtered = allFacilities
        val type = currentFilterType
        if (!type.isNullOrBlank()) {
            filtered = filtered.filter { it.facilityType.contains(type, ignoreCase = true) }
        }
        val query = currentSearchQuery
        if (!query.isNullOrBlank()) {
            filtered = filtered.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.area.contains(query, ignoreCase = true) ||
                    it.address.contains(query, ignoreCase = true) ||
                    it.facilityType.contains(query, ignoreCase = true) ||
                    LocaleHelper.facilityTypeLabel(this, it.facilityType).contains(query, ignoreCase = true)
            }
        }

        mapView.setFacilities(filtered, selectedFacility?.facilityId)
        facilityListAdapter.submitList(filtered)

        if (filtered.isEmpty()) {
            layoutEmptySearch.visibility = View.VISIBLE
            tvEmptySearchMessage.text = getString(R.string.facility_empty_search, query.orEmpty())
            cardPlacePeek.visibility = View.GONE
        } else {
            layoutEmptySearch.visibility = View.GONE
            if (!isListViewActive) {
                cardPlacePeek.visibility = View.VISIBLE
            }
            if (selectedFacility == null || !filtered.contains(selectedFacility)) {
                selectFacility(filtered.first())
            }
        }
        updateMapOverlayBounds()
    }

    private fun selectFacility(facility: PublicFacilityDto) {
        selectedFacility = facility
        tvPlaceName.text = facility.name
        tvPlaceTypeBadge.text = LocaleHelper.facilityTypeLabel(this, facility.facilityType)
        tvPlaceArea.text = facility.area
        tvPlaceAddress.text = facility.address
        tvPlaceCoords.text = FacilityDirectionsHelper.formatCoordinates(facility.entranceLatitude, facility.entranceLongitude)
        tvPlaceReviewedDate.text = getString(R.string.facility_verified_date, facility.lastCheckedDate)
        tvPlaceSource.text = getString(R.string.facility_source_label, facility.source)
        mapView.setSelectedFacility(facility.facilityId)
    }

    private fun launchGoogleMapsDirections(facility: PublicFacilityDto) {
        HapticFeedbackManager.confirmClick()
        val isOnline = isNetworkConnected()
        val result = FacilityDirectionsHelper.openDirectionsInGoogleMaps(this, facility, isOnline)
        when (result) {
            is DirectionsLaunchResult.LaunchedMapsApp -> {
                // Handed off directly to the Google Maps Android application
            }
            is DirectionsLaunchResult.LaunchedBrowserMaps -> {
                // Handed off to device web browser for directions
            }
            is DirectionsLaunchResult.FallbackRequired -> {
                showOfflineFallbackModal(result.fallback)
            }
            is DirectionsLaunchResult.InvalidDestination -> {
                Toast.makeText(this, result.reason, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showOfflineFallbackModal(fallback: AddressCoordinateFallback) {
        HapticFeedbackManager.warningBuzz()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val density = resources.displayMetrics.density
            val pad = (12 * density).toInt()
            setPadding(0, pad, 0, pad)

            addView(TextView(context).apply {
                text = fallback.offlineOrUnavailableReason
                textSize = 13f
                setTextColor(getColor(R.color.pulse_text_secondary))
            })
            addView(TextView(context).apply {
                text = fallback.address
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.pulse_text_primary))
                setPadding(0, pad, 0, 0)
            })
            addView(TextView(context).apply {
                text = getString(R.string.rt_facility_entrance_coords, fallback.formattedCoordinates)
                textSize = 13f
                setTextColor(getColor(R.color.pulse_primary))
                setPadding(0, pad / 2, 0, 0)
            })
        }

        PulseLinkModal.show(
            context = this,
            title = fallback.facilityName,
            content = content,
            actions = listOf(
                getString(R.string.facility_copy_coords) to { sheet ->
                    copyCoordinatesToClipboard(fallback.formattedCoordinates)
                    sheet.dismiss()
                },
                getString(R.string.cancel) to { sheet ->
                    sheet.dismiss()
                }
            )
        )
    }

    private fun copyFacilityCoordinates(facility: PublicFacilityDto) {
        HapticFeedbackManager.confirmClick()
        val coords = FacilityDirectionsHelper.formatCoordinates(facility.entranceLatitude, facility.entranceLongitude)
        copyCoordinatesToClipboard(coords)
    }

    private fun copyCoordinatesToClipboard(coords: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(getString(R.string.rt_facility_clip_label), coords)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.facility_coords_copied), Toast.LENGTH_SHORT).show()
    }

    private fun isNetworkConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private inner class FacilityListAdapter : RecyclerView.Adapter<FacilityListAdapter.ViewHolder>() {

        private val items = mutableListOf<PublicFacilityDto>()

        fun submitList(newItems: List<PublicFacilityDto>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_facility_directory, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val facility = items[position]
            holder.tvName.text = facility.name
            holder.tvBadge.text = LocaleHelper.facilityTypeLabel(this@FacilityFinderActivity, facility.facilityType)
            holder.tvArea.text = facility.area
            holder.tvAddress.text = facility.address
            holder.tvCoords.text = FacilityDirectionsHelper.formatCoordinates(facility.entranceLatitude, facility.entranceLongitude)

            holder.itemView.setOnClickListener {
                HapticFeedbackManager.lightTick()
                selectFacility(facility)
                btnToggleView.performClick() // Switch back to map preview focusing on chosen facility
            }

            holder.btnDirections.setOnClickListener {
                launchGoogleMapsDirections(facility)
            }
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvName: TextView = itemView.findViewById(R.id.tvItemName)
            val tvBadge: TextView = itemView.findViewById(R.id.tvItemTypeBadge)
            val tvArea: TextView = itemView.findViewById(R.id.tvItemArea)
            val tvAddress: TextView = itemView.findViewById(R.id.tvItemAddress)
            val tvCoords: TextView = itemView.findViewById(R.id.tvItemCoords)
            val btnDirections: MaterialButton = itemView.findViewById(R.id.btnItemDirections)
        }
    }
}
