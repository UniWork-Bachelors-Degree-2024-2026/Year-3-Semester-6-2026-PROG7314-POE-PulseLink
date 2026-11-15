package com.example.prog7314_poepart23.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.data.remote.models.PublicFacilityDto
import com.example.prog7314_poepart23.utils.HapticFeedbackManager

/**
 * Interactive vector map view rendering the Gqeberha / Nelson Mandela Bay public healthcare
 * corridor with interactive facility pins resembling Google Maps styling.
 */
class GqeberhaMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Bounds for Gqeberha healthcare facilities:
    // North (Motherwell): ~ -33.78
    // South (Walmer): ~ -33.99
    // West: ~ 25.54
    // East (Central): ~ 25.63
    private val minLat = -34.00
    private val maxLat = -33.77
    private val minLng = 25.53
    private val maxLng = 25.64

    private var facilities: List<PublicFacilityDto> = emptyList()
    private var selectedFacilityId: String? = null
    var onFacilitySelected: ((PublicFacilityDto) -> Unit)? = null

    // Drawing paints
    private val bgPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFF1F5F9.toInt() // Pale slate
    }

    private val waterPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFE0F2FE.toInt() // Algoa Bay light sky blue
    }

    private val roadPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFCBD5E1.toInt()
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val areaTextPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFF94A3B8.toInt()
        textSize = 28f
        isFakeBoldText = true
    }

    private val hospitalPinPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFDC2626.toInt() // Red for hospitals
        style = Paint.Style.FILL
    }

    private val clinicPinPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFF0D9488.toInt() // Teal for clinics
        style = Paint.Style.FILL
    }

    private val pinStrokePaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val selectionRingPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFF0F172A.toInt() // Navy
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    private val textOnPinPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFFFFFFF.toInt()
        textSize = 22f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val labelBgPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFF0F172A.toInt()
        style = Paint.Style.FILL
    }

    private val labelTextPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFFFFFFF.toInt()
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val pinPositions = mutableListOf<Pair<PublicFacilityDto, RectF>>()

    fun setFacilities(list: List<PublicFacilityDto>, selectedId: String? = null) {
        facilities = list
        selectedFacilityId = selectedId ?: list.firstOrNull()?.facilityId
        invalidate()
    }

    fun setSelectedFacility(facilityId: String) {
        selectedFacilityId = facilityId
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Canvas background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Draw Algoa Bay coastline on the right (East)
        val waterPath = Path().apply {
            moveTo(w * 0.72f, 0f)
            cubicTo(w * 0.75f, h * 0.35f, w * 0.82f, h * 0.65f, w * 0.68f, h)
            lineTo(w, h)
            lineTo(w, 0f)
            close()
        }
        canvas.drawPath(waterPath, waterPaint)

        // 3. Draw stylized highway corridors (N2, R75, Settlers Way)
        val n2Path = Path().apply {
            moveTo(w * 0.15f, h * 0.12f)
            quadTo(w * 0.35f, h * 0.38f, w * 0.68f, h * 0.75f)
        }
        canvas.drawPath(n2Path, roadPaint)

        val r75Path = Path().apply {
            moveTo(w * 0.35f, h * 0.38f)
            lineTo(w * 0.25f, h * 0.85f)
        }
        canvas.drawPath(r75Path, roadPaint)

        // Area labels
        canvas.drawText("Motherwell", w * 0.28f, h * 0.18f, areaTextPaint)
        canvas.drawText("Zwide / Ibhayi", w * 0.20f, h * 0.44f, areaTextPaint)
        canvas.drawText("Korsten", w * 0.32f, h * 0.62f, areaTextPaint)
        canvas.drawText("Central", w * 0.52f, h * 0.76f, areaTextPaint)
        canvas.drawText("Walmer", w * 0.30f, h * 0.88f, areaTextPaint)
        canvas.drawText("Algoa Bay", w * 0.78f, h * 0.45f, areaTextPaint)

        // 4. Map and render facility pins
        pinPositions.clear()
        val padding = 60f
        val usableW = w - 2 * padding
        val usableH = h - 2 * padding

        facilities.forEach { facility ->
            // Normalize coordinates:
            // Lng -> X (25.53..25.64)
            // Lat -> Y (maxLat North -> top, minLat South -> bottom)
            val normX = ((facility.entranceLongitude - minLng) / (maxLng - minLng)).toFloat().coerceIn(0f, 1f)
            val normY = ((maxLat - facility.entranceLatitude) / (maxLat - minLat)).toFloat().coerceIn(0f, 1f)

            val px = padding + normX * usableW
            val py = padding + normY * usableH
            val isSelected = facility.facilityId == selectedFacilityId
            val isHospital = facility.facilityType.contains("Hospital", ignoreCase = true)

            val radius = if (isSelected) 28f else 22f
            val touchRect = RectF(px - 44f, py - 44f, px + 44f, py + 44f)
            pinPositions.add(facility to touchRect)

            if (isSelected) {
                canvas.drawCircle(px, py, radius + 8f, selectionRingPaint)
            }

            val pinPaint = if (isHospital) hospitalPinPaint else clinicPinPaint
            canvas.drawCircle(px, py, radius, pinPaint)
            canvas.drawCircle(px, py, radius, pinStrokePaint)

            // Letter on pin: H for hospital, + for clinic
            val symbol = if (isHospital) "H" else "+"
            canvas.drawText(symbol, px, py + 8f, textOnPinPaint)

            if (isSelected) {
                // Short floating pill label above the pin
                val shortName = when {
                    facility.name.contains("Livingstone") -> context.getString(R.string.rt_facility_short_hospital, "Livingstone")
                    facility.name.contains("Dora Nginza") -> context.getString(R.string.rt_facility_short_hospital, "Dora Nginza")
                    facility.name.contains("Provincial") -> context.getString(R.string.rt_facility_short_hospital, "PE Provincial")
                    facility.name.contains("Motherwell") -> context.getString(R.string.rt_facility_short_chc, "Motherwell")
                    facility.name.contains("Walmer") -> context.getString(R.string.rt_facility_short_clinic, "Walmer")
                    facility.name.contains("Central") -> context.getString(R.string.rt_facility_short_clinic, "Central")
                    facility.name.contains("Schauderville") -> context.getString(R.string.rt_facility_short_clinic, "Schauderville")
                    facility.name.contains("New Brighton") -> context.getString(R.string.rt_facility_short_clinic, "New Brighton")
                    else -> facility.name.take(18)
                }
                val labelWidth = labelTextPaint.measureText(shortName) + 24f
                val labelRect = RectF(px - labelWidth / 2f, py - radius - 44f, px + labelWidth / 2f, py - radius - 10f)
                canvas.drawRoundRect(labelRect, 14f, 14f, labelBgPaint)
                canvas.drawText(shortName, px, py - radius - 20f, labelTextPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val tx = event.x
            val ty = event.y
            val hit = pinPositions.firstOrNull { it.second.contains(tx, ty) }
            if (hit != null) {
                HapticFeedbackManager.lightTick()
                selectedFacilityId = hit.first.facilityId
                invalidate()
                onFacilitySelected?.invoke(hit.first)
                return true
            }
        }
        return true
    }
}
