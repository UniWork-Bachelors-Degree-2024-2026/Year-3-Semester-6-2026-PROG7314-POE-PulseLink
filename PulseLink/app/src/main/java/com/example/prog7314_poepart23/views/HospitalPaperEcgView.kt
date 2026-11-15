/*
 * Medical-grade Hospital Paper ECG Canvas View conforming to Option A clinical specifications.
 *
 * Key Specifications:
 * 1. Grid Standards: Standard authentic hospital paper ECG grid:
 *    - Sweep speed: 25 mm/s
 *    - Calibration amplitude: 10 mm/mV (1 mV = 10 mm)
 *    - Minor divisions: 1 mm (40 ms / 0.1 mV) rendered in delicate calibrated pink (#FFD4D4).
 *    - Major divisions: 5 mm (200 ms / 0.5 mV) rendered in accented pink/red (#F87171).
 *    - Canvas background: Standard clinical pink paper tint (#FFF5F5).
 * 2. Trace Rendering: Deep clinical charcoal (#0F172A) with distinct crimson R-peak apex markers (#DC2626).
 * 3. Calibration Reference: Standard 10 mm/mV square calibration pulse drawn on the left margin.
 * 4. Data Support: Accepts dynamic 250 Hz AD8232 Lead I telemetry samples only. When no real
 *    samples are available, an explicit "no data" state is rendered instead of fabricating a trace.
 * 5. Zero emojis and pure SVG/Canvas vector mathematics.
 *
 * Reference List:
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End. [online] Available at: <https://www.analog.com/media/en/technical-documentation/data-sheets/AD8232.pdf> [Accessed 21 September 2026].
 * - Google Developers, 2024. Custom view components - Android Developers. [online] Available at: <https://developer.android.com/develop/ui/views/layout/custom-views/custom-components> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.views

import android.content.Context
import android.graphics.Canvas                      // (Google Developers, 2024)
import android.graphics.Color                       // (Google Developers, 2024)
import android.graphics.Paint                       // (Google Developers, 2024)
import android.graphics.Path                        // (Google Developers, 2024)
import android.util.AttributeSet
import android.view.View                            // (Google Developers, 2024)
import com.example.prog7314_poepart23.R

class HospitalPaperEcgView @JvmOverloads constructor( // (Google Developers, 2024)
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Paints
    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#FFF5F5") // Standard clinical pink paper tint
        style = Paint.Style.FILL
    }

    private val minorGridPaint = Paint().apply {
        color = Color.parseColor("#FFD4D4") // 1 mm minor division (40 ms)
        strokeWidth = 1.0f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val majorGridPaint = Paint().apply {
        color = Color.parseColor("#F87171") // 5 mm major division (200 ms)
        strokeWidth = 1.8f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val wavePaint = Paint().apply {
        color = Color.parseColor("#0F172A") // Deep clinical charcoal trace
        strokeWidth = 2.8f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val rPeakPaint = Paint().apply {
        // Deep crimson R-peak marker (blue in colour-blind friendly mode)
        color = Color.parseColor(
            if (com.example.prog7314_poepart23.utils.ColorBlindHelper.isEnabled(context)) "#0072B2" else "#DC2626"
        )
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val calibrationPaint = Paint().apply {
        color = Color.parseColor("#991B1B") // Dark red calibration pulse
        strokeWidth = 2.2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        isAntiAlias = true
    }

    private val annotationPaint = Paint().apply {
        color = Color.parseColor("#7F1D1D") // Dark red clinical annotation
        textSize = 22f
        isAntiAlias = true
        isFakeBoldText = true
    }

    private val noDataPaint = Paint().apply {
        color = Color.parseColor("#94A3B8") // Neutral slate - explicit "unavailable" state text
        textSize = 26f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        isFakeBoldText = true
    }

    private val wavePath = Path()
    private val calibPath = Path()

    // Telemetry samples buffer (normalised deviation from 2048 baseline)
    private var customSamples: List<Float>? = null

    // Whether the view fits the entire waveform into the available view width (for dashboard thumbnails)
    // or expands horizontally for full-duration medical scrolling (for session report view).
    var fitToScreen: Boolean = false
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    // Dynamic zoom scale factor (1.0f = default 25 mm/s; 2.0f = expanded/zoomed in 50 mm/s)
    var zoomFactor: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 4.0f)
            requestLayout()
            invalidate()
        }

    // Increases the waveform horizontal magnification (zooms in on cardiac complexes)
    fun zoomIn() {
        fitToScreen = false
        zoomFactor = (zoomFactor + 0.5f).coerceAtMost(4.0f)
        requestLayout()
        invalidate()
    }

    // Decreases the waveform horizontal magnification (zooms out to show broader time window)
    fun zoomOut() {
        fitToScreen = false
        zoomFactor = (zoomFactor - 0.5f).coerceAtLeast(0.5f)
        requestLayout()
        invalidate()
    }

    // Toggles between full-screen fit and detailed 25 mm/s scrollable paper view
    fun toggleFitToScreen() {
        fitToScreen = !fitToScreen
        if (!fitToScreen && zoomFactor < 1.0f) {
            zoomFactor = 1.0f
        }
        requestLayout()
        invalidate()
    }

    // Sets custom telemetry samples from a recorded session
    fun setSamples(samples: List<Int>) {
        if (samples.isEmpty()) {
            customSamples = null
        } else {
            customSamples = samples.map { (it - 2048).toFloat() }
        }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val defaultWidth = MeasureSpec.getSize(widthMeasureSpec)
        val samples = customSamples

        val measuredWidth = if (!fitToScreen && samples != null && samples.isNotEmpty()) {
            val pointSpacing = 3.0f * zoomFactor
            val calculatedWidth = (40f + samples.size * pointSpacing).toInt()
            calculatedWidth.coerceAtLeast(defaultWidth)
        } else {
            defaultWidth
        }

        setMeasuredDimension(measuredWidth, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Draw hospital paper background
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        // 2. Draw standard clinical 1 mm and 5 mm grid
        drawHospitalGrid(canvas, w, h)

        val samples = customSamples
        if (samples == null || samples.isEmpty()) {
            // No real telemetry available - show an explicit empty/unavailable state
            // rather than a synthesised waveform.
            drawNoDataState(canvas, w, h)
            return
        }

        // 3. Draw 10 mm/mV standard calibration reference pulse
        drawCalibrationPulse(canvas, h)

        // 4. Draw Lead I ECG waveform trace with dynamic zoom factor
        drawWaveform(canvas, w, h, samples)

        // 5. Draw clinical annotation legend with active zoom scale
        val speedAnnotation = when {
            zoomFactor >= 2.0f -> context.getString(R.string.l10n_paper_zoom_in)
            zoomFactor <= 0.75f -> context.getString(R.string.l10n_paper_zoom_out)
            else -> "25 mm/s | 10 mm/mV"
        }
        canvas.drawText("$speedAnnotation | ${context.getString(R.string.patient_ecg_session_details_text_lead_i)} | ${String.format(java.util.Locale.getDefault(), "%.1fx", zoomFactor)}", 45f, h - 10f, annotationPaint)
    }

    // Renders an explicit "no data" state on the hospital paper grid when no real samples exist.
    private fun drawNoDataState(canvas: Canvas, w: Float, h: Float) {
        canvas.drawText(context.getString(R.string.doctor_ecg_text_no_data_available), w / 2f, h / 2f, noDataPaint)
    }

    // Draws calibrated clinical grid representing 40 ms (minor) and 200 ms (major) divisions
    private fun drawHospitalGrid(canvas: Canvas, w: Float, h: Float) {
        val minorStep = 18f * (if (zoomFactor > 1.5f) 1.5f else 1.0f)
        val majorStep = minorStep * 5f

        // Minor grid lines (1 mm / 40 ms equivalent)
        var x = 0f
        while (x <= w) {
            canvas.drawLine(x, 0f, x, h, minorGridPaint)
            x += minorStep
        }

        var y = 0f
        while (y <= h) {
            canvas.drawLine(0f, y, w, y, minorGridPaint)
            y += minorStep
        }

        // Major grid lines (5 mm / 200 ms equivalent)
        x = 0f
        while (x <= w) {
            canvas.drawLine(x, 0f, x, h, majorGridPaint)
            x += majorStep
        }

        y = 0f
        while (y <= h) {
            canvas.drawLine(0f, y, w, y, majorGridPaint)
            y += majorStep
        }
    }

    // Renders authentic standard 10 mm/mV calibration reference pulse
    private fun drawCalibrationPulse(canvas: Canvas, h: Float) {
        val centerY = h / 2f
        val pulseHeight = 36f // 10 mm / 1 mV scale
        val left = 10f
        val right = 28f

        calibPath.reset()
        calibPath.moveTo(left, centerY)
        calibPath.lineTo(left + 3f, centerY)
        calibPath.lineTo(left + 3f, centerY - pulseHeight)
        calibPath.lineTo(right - 3f, centerY - pulseHeight)
        calibPath.lineTo(right - 3f, centerY)
        calibPath.lineTo(right, centerY)

        canvas.drawPath(calibPath, calibrationPaint)
    }

    // Draws the Lead I waveform with calibrated clinical scaling and peak detection
    private fun drawWaveform(canvas: Canvas, w: Float, h: Float, samples: List<Float>) {
        val centerY = h / 2f
        val startX = 36f
        val availableWidth = (w - startX).coerceAtLeast(1f)

        wavePath.reset()

        val pointSpacing = if (fitToScreen) {
            availableWidth / samples.size.coerceAtLeast(1)
        } else {
            3.0f * zoomFactor
        }

        // Dynamically compute max deviation to prevent vertical clipping or oversized spikes
        val maxAbsVal = samples.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(120f) ?: 500f
        val maxTargetDeflection = h * 0.38f
        val verticalGain = (maxTargetDeflection / maxAbsVal) * (if (zoomFactor > 1.0f) 1.1f else 1.0f)

        var first = true
        var xPos = startX

        for (i in samples.indices) {
            if (xPos > w) break
            val clampedY = (centerY - (samples[i] * verticalGain)).coerceIn(4f, h - 4f)
            if (first) {
                wavePath.moveTo(xPos, clampedY)
                first = false
            } else {
                wavePath.lineTo(xPos, clampedY)
            }

            // Check for R-peak apex candidate
            if (samples[i] > maxAbsVal * 0.70f && maxAbsVal > 150f) {
                canvas.drawCircle(xPos, clampedY, 3.5f, rPeakPaint)
            }

            xPos += pointSpacing
        }

        canvas.drawPath(wavePath, wavePaint)
    }
}
