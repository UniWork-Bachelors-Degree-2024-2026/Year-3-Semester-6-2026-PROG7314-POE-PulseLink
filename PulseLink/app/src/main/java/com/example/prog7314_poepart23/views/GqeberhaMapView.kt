package com.example.prog7314_poepart23.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.data.remote.models.PublicFacilityDto
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import kotlin.math.hypot

/**
 * Interactive vector map view rendering the Gqeberha / Nelson Mandela Bay public healthcare
 * corridor with pinch-to-zoom, button zoom, drag-to-pan, and interactive facility pins.
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

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // Zoom & pan state
    private val minScale = 0.70f
    private val maxScale = 4.00f
    private var scaleFactor = 1.0f
    private var panX = 0f
    private var panY = 0f

    // Safe viewport insets updated from overlay heights (top search/chips, bottom peek card)
    private var overlayTopPx = 0
    private var overlayBottomPx = 0

    // Touch gesture tracking
    private var downX = 0f
    private var downY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private var wasScaling = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private var facilities: List<PublicFacilityDto> = emptyList()
    private var selectedFacilityId: String? = null
    var onFacilitySelected: ((PublicFacilityDto) -> Unit)? = null

    // Drawing paints (theme-aware via PulseLink colour tokens)
    private val bgPaint = Paint().apply {
        isAntiAlias = true
        color = ContextCompat.getColor(context, R.color.pulse_bg)
    }

    private val waterPaint = Paint().apply {
        isAntiAlias = true
        color = ContextCompat.getColor(context, R.color.pulse_blue_bg)
    }

    private val roadPaint = Paint().apply {
        isAntiAlias = true
        color = ContextCompat.getColor(context, R.color.pulse_border_strong)
        strokeWidth = 3f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val areaTextPaint = Paint().apply {
        isAntiAlias = true
        color = ContextCompat.getColor(context, R.color.pulse_text_hint)
        textSize = 11f * density
        isFakeBoldText = true
    }

    private val hospitalPinPaint = Paint().apply {
        isAntiAlias = true
        color = ContextCompat.getColor(context, R.color.pulse_danger)
        style = Paint.Style.FILL
    }

    private val clinicPinPaint = Paint().apply {
        isAntiAlias = true
        color = ContextCompat.getColor(context, R.color.pulse_accent_teal)
        style = Paint.Style.FILL
    }

    private val pinStrokePaint = Paint().apply {
        isAntiAlias = true
        color = ContextCompat.getColor(context, R.color.white)
        strokeWidth = 2.5f * density
        style = Paint.Style.STROKE
    }

    private val selectionRingPaint = Paint().apply {
        isAntiAlias = true
        color = ContextCompat.getColor(context, R.color.pulse_primary_fill)
        strokeWidth = 3f * density
        style = Paint.Style.STROKE
    }

    private val textOnPinPaint = Paint().apply {
        isAntiAlias = true
        color = ContextCompat.getColor(context, R.color.white)
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val labelBgPaint = Paint().apply {
        isAntiAlias = true
        color = ContextCompat.getColor(context, R.color.pulse_primary_fill)
        style = Paint.Style.FILL
    }

    private val labelTextPaint = Paint().apply {
        isAntiAlias = true
        color = ContextCompat.getColor(context, R.color.white)
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private data class RenderedPin(
        val facility: PublicFacilityDto,
        val cx: Float,
        val cy: Float,
        val hitRect: RectF
    )

    private val renderedPins = mutableListOf<RenderedPin>()

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                wasScaling = true
                isDragging = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prevScale = scaleFactor
                val nextScale = (prevScale * detector.scaleFactor).coerceIn(minScale, maxScale)
                if (nextScale != prevScale) {
                    val metrics = computeViewportMetrics(width.toFloat(), height.toFloat())
                    val ratio = nextScale / prevScale
                    panX = detector.focusX - metrics.pivotX - (detector.focusX - metrics.pivotX - panX) * ratio
                    panY = detector.focusY - metrics.pivotY - (detector.focusY - metrics.pivotY - panY) * ratio
                    scaleFactor = nextScale
                    clampPan(metrics)
                    invalidate()
                }
                return true
            }
        }
    )

    private val tapGestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val targetScale = if (scaleFactor >= 2.2f) 1.0f else (scaleFactor * 1.6f).coerceIn(minScale, maxScale)
                if (targetScale == 1.0f) {
                    resetZoom()
                } else {
                    val metrics = computeViewportMetrics(width.toFloat(), height.toFloat())
                    val ratio = targetScale / scaleFactor
                    panX = e.x - metrics.pivotX - (e.x - metrics.pivotX - panX) * ratio
                    panY = e.y - metrics.pivotY - (e.y - metrics.pivotY - panY) * ratio
                    scaleFactor = targetScale
                    clampPan(metrics)
                    HapticFeedbackManager.lightTick()
                    invalidate()
                }
                return true
            }
        }
    )

    private data class ViewportMetrics(
        val left: Float,
        val top: Float,
        val usableW: Float,
        val usableH: Float,
        val pivotX: Float,
        val pivotY: Float
    )

    fun setFacilities(list: List<PublicFacilityDto>, selectedId: String? = null) {
        facilities = list
        selectedFacilityId = selectedId ?: list.firstOrNull()?.facilityId
        if (width > 0 && height > 0 && scaleFactor > 1.0f) {
            ensureSelectedFacilityVisible(computeViewportMetrics(width.toFloat(), height.toFloat()))
        }
        invalidate()
    }

    fun setSelectedFacility(facilityId: String) {
        selectedFacilityId = facilityId
        if (width > 0 && height > 0 && scaleFactor > 1.0f) {
            ensureSelectedFacilityVisible(computeViewportMetrics(width.toFloat(), height.toFloat()))
        }
        invalidate()
    }

    /**
     * Updates unobstructed top/bottom safe viewport bounds so map pins stay visible
     * between the floating search/filter overlay and the bottom facility peek card.
     */
    fun setViewportInsets(topPx: Int, bottomPx: Int) {
        if (overlayTopPx == topPx && overlayBottomPx == bottomPx) return
        overlayTopPx = topPx
        overlayBottomPx = bottomPx
        if (width > 0 && height > 0) {
            clampPan(computeViewportMetrics(width.toFloat(), height.toFloat()))
        }
        invalidate()
    }

    fun zoomIn() {
        applyCenteredScale((scaleFactor * 1.35f).coerceIn(minScale, maxScale))
    }

    fun zoomOut() {
        val next = (scaleFactor / 1.35f).coerceIn(minScale, maxScale)
        if (kotlin.math.abs(next - 1.0f) < 0.08f && scaleFactor > 1.0f) {
            resetZoom()
        } else {
            applyCenteredScale(next)
        }
    }

    fun resetZoom() {
        scaleFactor = 1.0f
        panX = 0f
        panY = 0f
        invalidate()
    }

    private fun selectedFacilityCoords(metrics: ViewportMetrics): Pair<Float, Float>? {
        val facility = facilities.firstOrNull { it.facilityId == selectedFacilityId } ?: return null
        val normX = ((facility.entranceLongitude - minLng) / (maxLng - minLng)).toFloat().coerceIn(0f, 1f)
        val normY = ((maxLat - facility.entranceLatitude) / (maxLat - minLat)).toFloat().coerceIn(0f, 1f)
        val baseX = metrics.left + normX * metrics.usableW
        val baseY = metrics.top + normY * metrics.usableH
        return baseX to baseY
    }

    private fun ensureSelectedFacilityVisible(metrics: ViewportMetrics) {
        val (baseX, baseY) = selectedFacilityCoords(metrics) ?: return
        val curX = metrics.pivotX + (baseX - metrics.pivotX) * scaleFactor + panX
        val curY = metrics.pivotY + (baseY - metrics.pivotY) * scaleFactor + panY
        val safeLeft = metrics.left + 36f * density
        val safeRight = (metrics.left + metrics.usableW - 36f * density).coerceAtLeast(safeLeft)
        val safeTop = metrics.top + 12f * density
        val safeBottom = (metrics.top + metrics.usableH - 16f * density).coerceAtLeast(safeTop)
        val targetX = curX.coerceIn(safeLeft, safeRight)
        val targetY = curY.coerceIn(safeTop, safeBottom)
        panX = targetX - metrics.pivotX - (baseX - metrics.pivotX) * scaleFactor
        panY = targetY - metrics.pivotY - (baseY - metrics.pivotY) * scaleFactor
        clampPan(metrics)
    }

    private fun applyCenteredScale(nextScale: Float) {
        val prevScale = scaleFactor
        scaleFactor = nextScale
        if (scaleFactor <= 1.0f) {
            panX = 0f
            panY = 0f
        } else if (width > 0 && height > 0) {
            val metrics = computeViewportMetrics(width.toFloat(), height.toFloat())
            val selected = selectedFacilityCoords(metrics)
            if (selected != null) {
                val (baseX, baseY) = selected
                val curX = metrics.pivotX + (baseX - metrics.pivotX) * prevScale + panX
                val curY = metrics.pivotY + (baseY - metrics.pivotY) * prevScale + panY
                val safeLeft = metrics.left + 36f * density
                val safeRight = (metrics.left + metrics.usableW - 36f * density).coerceAtLeast(safeLeft)
                val safeTop = metrics.top + 12f * density
                val safeBottom = (metrics.top + metrics.usableH - 16f * density).coerceAtLeast(safeTop)
                val targetX = curX.coerceIn(safeLeft, safeRight)
                val targetY = curY.coerceIn(safeTop, safeBottom)
                panX = targetX - metrics.pivotX - (baseX - metrics.pivotX) * scaleFactor
                panY = targetY - metrics.pivotY - (baseY - metrics.pivotY) * scaleFactor
            } else {
                val ratio = scaleFactor / prevScale
                panX *= ratio
                panY *= ratio
            }
            clampPan(metrics)
        }
        invalidate()
    }

    private fun computeViewportMetrics(w: Float, h: Float): ViewportMetrics {
        val sidePad = 32f * density
        val defaultTopPad = 148f * density
        val defaultBottomPad = 290f * density
        val rawTop = if (overlayTopPx > 0) overlayTopPx.toFloat() + 52f * density else defaultTopPad
        val rawBottom = if (overlayBottomPx > 0) overlayBottomPx.toFloat() + 26f * density else defaultBottomPad

        val minUsableH = (110f * density).coerceAtMost(h * 0.65f)
        var top = rawTop.coerceAtLeast(32f * density)
        var bottom = (h - rawBottom).coerceAtMost(h - 32f * density)
        if (bottom - top < minUsableH) {
            val mid = ((top + bottom) / 2f).coerceIn(minUsableH / 2f, (h - minUsableH / 2f).coerceAtLeast(minUsableH / 2f))
            top = (mid - minUsableH / 2f).coerceAtLeast(16f * density)
            bottom = (top + minUsableH).coerceAtMost(h - 16f * density)
        }

        val usableW = (w - 2f * sidePad).coerceAtLeast(64f * density)
        val usableH = (bottom - top).coerceAtLeast(64f * density)
        return ViewportMetrics(
            left = sidePad,
            top = top,
            usableW = usableW,
            usableH = usableH,
            pivotX = sidePad + usableW / 2f,
            pivotY = top + usableH / 2f
        )
    }

    private fun clampPan(metrics: ViewportMetrics) {
        if (scaleFactor <= 1.0f) {
            panX = 0f
            panY = 0f
            return
        }
        val extraW = (metrics.usableW * scaleFactor) * 0.5f + 48f * density
        val extraH = (metrics.usableH * scaleFactor) * 0.5f + 64f * density
        panX = panX.coerceIn(-extraW, extraW)
        panY = panY.coerceIn(-extraH, extraH)
    }

    private fun toScreenX(normX: Float, metrics: ViewportMetrics): Float {
        val baseX = metrics.left + normX * metrics.usableW
        return metrics.pivotX + (baseX - metrics.pivotX) * scaleFactor + panX
    }

    private fun toScreenY(normY: Float, metrics: ViewportMetrics): Float {
        val baseY = metrics.top + normY * metrics.usableH
        return metrics.pivotY + (baseY - metrics.pivotY) * scaleFactor + panY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val metrics = computeViewportMetrics(w, h)
        clampPan(metrics)

        // 1. Canvas background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Transform background corridors & coastline by current zoom and pan
        canvas.save()
        canvas.translate(metrics.pivotX + panX, metrics.pivotY + panY)
        canvas.scale(scaleFactor, scaleFactor)
        canvas.translate(-metrics.pivotX, -metrics.pivotY)

        // Algoa Bay coastline on the East (expanded beyond bounds so zooming out stays filled)
        val waterPath = Path().apply {
            moveTo(w * 0.72f, -h)
            cubicTo(w * 0.75f, h * 0.35f, w * 0.82f, h * 0.65f, w * 0.68f, h * 2f)
            lineTo(w * 3f, h * 2f)
            lineTo(w * 3f, -h)
            close()
        }
        canvas.drawPath(waterPath, waterPaint)

        // Stylized highway corridors aligned to Gqeberha viewport
        roadPaint.strokeWidth = (3f * density) / scaleFactor.coerceAtLeast(0.7f)
        val vpTop = metrics.top
        val vpH = metrics.usableH
        val n2Path = Path().apply {
            moveTo(w * 0.12f, vpTop + vpH * 0.08f)
            quadTo(w * 0.38f, vpTop + vpH * 0.42f, w * 0.72f, vpTop + vpH * 0.86f)
        }
        canvas.drawPath(n2Path, roadPaint)

        val r75Path = Path().apply {
            moveTo(w * 0.38f, vpTop + vpH * 0.42f)
            lineTo(w * 0.25f, vpTop + vpH * 0.96f)
        }
        canvas.drawPath(r75Path, roadPaint)

        // Subtle neighbourhood context labels kept legible across zoom levels
        areaTextPaint.textSize = (11f * density) / scaleFactor.coerceIn(0.8f, 2.0f)
        canvas.drawText("Motherwell", w * 0.22f, vpTop + vpH * 0.15f, areaTextPaint)
        canvas.drawText("Zwide / Ibhayi", w * 0.16f, vpTop + vpH * 0.46f, areaTextPaint)
        canvas.drawText("Korsten", w * 0.30f, vpTop + vpH * 0.65f, areaTextPaint)
        canvas.drawText("Central", w * 0.52f, vpTop + vpH * 0.82f, areaTextPaint)
        canvas.drawText("Walmer", w * 0.28f, vpTop + vpH * 0.98f, areaTextPaint)
        canvas.drawText("Algoa Bay", w * 0.76f, vpTop + vpH * 0.50f, areaTextPaint)

        canvas.restore()

        // 3. Render facility pins at crisp unscaled screen sizes
        renderedPins.clear()
        val hitHalfSize = 24f * density

        // Draw unselected pins first, then selected pin last so its badge renders on top
        val orderedFacilities = facilities.sortedBy { it.facilityId == selectedFacilityId }
        orderedFacilities.forEach { facility ->
            val normX = ((facility.entranceLongitude - minLng) / (maxLng - minLng)).toFloat().coerceIn(0f, 1f)
            val normY = ((maxLat - facility.entranceLatitude) / (maxLat - minLat)).toFloat().coerceIn(0f, 1f)

            val px = toScreenX(normX, metrics)
            val py = toScreenY(normY, metrics)
            val isSelected = facility.facilityId == selectedFacilityId
            val isHospital = facility.facilityType.contains("Hospital", ignoreCase = true)

            val radius = (if (isSelected) 15f else 11.5f) * density
            val touchRect = RectF(px - hitHalfSize, py - hitHalfSize, px + hitHalfSize, py + hitHalfSize)
            renderedPins.add(RenderedPin(facility, px, py, touchRect))

            if (isSelected) {
                canvas.drawCircle(px, py, radius + 4.5f * density, selectionRingPaint)
            }

            val pinPaint = if (isHospital) hospitalPinPaint else clinicPinPaint
            canvas.drawCircle(px, py, radius, pinPaint)
            canvas.drawCircle(px, py, radius, pinStrokePaint)

            val symbol = if (isHospital) "H" else "+"
            val baselineOffset = -(textOnPinPaint.descent() + textOnPinPaint.ascent()) / 2f
            canvas.drawText(symbol, px, py + baselineOffset, textOnPinPaint)

            if (isSelected) {
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
                val labelPadX = 10f * density
                val labelHeight = 22f * density
                val labelWidth = labelTextPaint.measureText(shortName) + labelPadX * 2f
                val labelBottom = py - radius - 6f * density
                val labelTop = labelBottom - labelHeight
                val labelLeft = (px - labelWidth / 2f).coerceIn(8f * density, (w - labelWidth - 8f * density).coerceAtLeast(8f * density))
                val labelRect = RectF(labelLeft, labelTop, labelLeft + labelWidth, labelBottom)
                canvas.drawRoundRect(labelRect, 8f * density, 8f * density, labelBgPaint)
                val labelBaseline = labelRect.centerY() - (labelTextPaint.descent() + labelTextPaint.ascent()) / 2f
                canvas.drawText(shortName, labelRect.centerX(), labelBaseline, labelTextPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        tapGestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
                wasScaling = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                wasScaling = true
                isDragging = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleGestureDetector.isInProgress && !wasScaling) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex >= 0) {
                        val x = event.getX(pointerIndex)
                        val y = event.getY(pointerIndex)
                        val totalMove = hypot(x - downX, y - downY)
                        if (totalMove > touchSlop) {
                            isDragging = true
                        }
                        if (isDragging && scaleFactor > 1.0f && width > 0 && height > 0) {
                            val dx = x - lastTouchX
                            val dy = y - lastTouchY
                            panX += dx
                            panY += dy
                            clampPan(computeViewportMetrics(width.toFloat(), height.toFloat()))
                            invalidate()
                        }
                        lastTouchX = x
                        lastTouchY = y
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newIndex = if (pointerIndex == 0) 1 else 0
                    if (newIndex < event.pointerCount) {
                        lastTouchX = event.getX(newIndex)
                        lastTouchY = event.getY(newIndex)
                        activePointerId = event.getPointerId(newIndex)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging && !wasScaling) {
                    val tx = event.x
                    val ty = event.y
                    val hit = renderedPins
                        .filter { it.hitRect.contains(tx, ty) }
                        .minByOrNull { hypot(tx - it.cx, ty - it.cy) }
                    if (hit != null) {
                        HapticFeedbackManager.lightTick()
                        selectedFacilityId = hit.facility.facilityId
                        invalidate()
                        onFacilitySelected?.invoke(hit.facility)
                    }
                }
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isDragging = false
                wasScaling = false
            }

            MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isDragging = false
                wasScaling = false
            }
        }
        return true
    }
}
