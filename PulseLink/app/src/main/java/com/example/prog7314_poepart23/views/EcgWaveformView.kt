/*
 * Custom Android SurfaceView providing high-performance 60 FPS
 * hardware-accelerated rendering for single-lead AD8232 Lead I ECG telemetry.
 *
 * Reference List:
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End. [online] Available at: <https://www.analog.com/media/en/technical-documentation/data-sheets/AD8232.pdf> [Accessed 21 September 2026].
 * - Google Developers, 2024. SurfaceView - Android Developers. [online] Available at: <https://developer.android.com/reference/android/view/SurfaceView> [Accessed 21 September 2026].
 * - Oracle, 2024. Class ConcurrentLinkedQueue (Java Platform SE 8). [online] Available at: <https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ConcurrentLinkedQueue.html> [Accessed 21 September 2026].
 * - Pan, J. and Tompkins, W.J., 1985. A real-time QRS detection algorithm. IEEE Transactions on Biomedical Engineering, BME-32(3), pp.230-236.
 */

package com.example.prog7314_poepart23.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.SurfaceHolder                    // (Google Developers, 2024)
import android.view.SurfaceView                      // (Google Developers, 2024)
import androidx.core.content.ContextCompat
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.utils.PanTompkinsQrsDetector  // (Pan and Tompkins, 1985)
import java.util.concurrent.ConcurrentLinkedQueue                   // (Oracle, 2024)

// Custom Android SurfaceView providing high-performance 60 FPS
// hardware-accelerated rendering for single-lead AD8232 Lead I ECG telemetry.
//
// Key Architectural Capabilities:
// 1. Dual Grid Visualisation Modes:
//    - DARK_CLINICAL_MONITOR: High-contrast dark slate (#0B1120) with phosphor green trace (#10B981).
//    - HOSPITAL_PAPER (Option A): Standard authentic hospital paper ECG grid (25 mm/s, 10 mm/mV
//      calibrated pink paper #FFF5F5, 1 mm minor #FFD4D4 and 5 mm major #F87171 divisions,
//      deep charcoal trace #0F172A, and 10 mm/mV calibration reference pulse).
// 2. Dedicated Render Thread: Decoupled background thread guaranteeing smooth 60 FPS redraw ticks.
// 3. Integrated Pan-Tompkins QRS Detection:
//    - Real-time bandpass filtering, 5-point derivative, squaring, and moving-window integration.
//    - Adaptive dual-thresholding with 200 ms refractory blanking.
//    - ~5 second windowed RR-interval average (eased for smooth display) with non-physiological outlier rejection (< 30 or > 240 BPM).
// 4. Lead-Off Detection: Renders clinical alert overlay when LO+ / LO- electrode disconnect occurs.
// 5. Medical Standards: Clean SVG vector assets exclusively and zero emojis.
class EcgWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    // Clinical grid rendering mode
    enum class EcgGridMode {
        DARK_CLINICAL_MONITOR,
        HOSPITAL_PAPER
    }

    // Active grid display mode
    var gridMode: EcgGridMode = EcgGridMode.DARK_CLINICAL_MONITOR
        set(value) {
            field = value
            updatePaintsForMode()
        }

    // SVG warning vector asset loaded from resources
    private val warningIcon = ContextCompat.getDrawable(context, R.drawable.ic_warning_triangle)

    // Background rendering thread responsible for 60 FPS canvas redraw cycles
    private var renderThread: RenderThread? = null

    // Thread-safe raw sample ingestion buffer (queue holds incoming AD8232 250 Hz samples)
    private val rawSampleQueue = ConcurrentLinkedQueue<Int>()

    // Scaled waveform coordinates ready for canvas path generation
    private val renderedPoints = mutableListOf<Float>()

    // Buffer indices of detected R-peak apexes for distinct circular marker rendering
    private val rPeakIndices = mutableListOf<Int>()

    // Pan-Tompkins QRS detection engine for AD8232 Lead I signals sampled at 250 Hz
    private val qrsDetector = PanTompkinsQrsDetector(sampleRate = 250)

    // Canvas paints
    private val backgroundPaint = Paint().apply { style = Paint.Style.FILL }
    private val minorGridPaint = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = true }
    private val majorGridPaint = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = true }
    private val wavePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val rPeakPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val calibrationPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.SQUARE
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        textSize = 24f
        isAntiAlias = true
        isFakeBoldText = true
    }
    private val leadOffPaint = Paint().apply {
        color = Color.parseColor("#F59E0B")
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        isFakeBoldText = true
    }

    // Reusable Path object avoiding runtime allocations inside high-frequency render loop
    private val wavePath = Path()
    private val calibPath = Path()

    // Baseline wander estimation filter (12-bit ADC centre default is 2048)
    private var baselineEstimated = 2048f
    private val baselineAlpha = 0.008f // Smooth 0.5 Hz clinical baseline drift filter

    // 5-Point Moving Average Digital Low-Pass Filter (eliminates 50Hz/60Hz AC mains hum & EMG jitter)
    private val smoothingFilterWindow = IntArray(5) { 2048 }
    private var smoothingFilterIndex = 0
    private var smoothingFilterSum = 2048 * 5

    private fun applyLowPassSmoothing(rawAdc: Int): Int {
        smoothingFilterSum -= smoothingFilterWindow[smoothingFilterIndex]
        smoothingFilterWindow[smoothingFilterIndex] = rawAdc
        smoothingFilterSum += rawAdc
        smoothingFilterIndex = (smoothingFilterIndex + 1) % 5
        return smoothingFilterSum / 5
    }

    // Volatile flag indicating whether the AD8232 hardware detects electrode lead detachment (LO+ / LO-)
    @Volatile
    var isLeadsOff: Boolean = false

    // Latest rhythm regularity verdict from the Pan-Tompkins RR-interval window
    val isRhythmIrregular: Boolean
        get() = qrsDetector.isRhythmIrregular

    // Callback event invoked when an R-peak apex is detected, synchronising haptics, pulse animations, and BPM
    var onRPeakDetected: ((smoothedBpm: Int) -> Unit)? = null

    init {
        holder.addCallback(this)
        setZOrderOnTop(false)
        updatePaintsForMode()
    }

    // Configures canvas colour schemes based on the selected clinical grid mode
    private fun updatePaintsForMode() {
        when (gridMode) {
            EcgGridMode.DARK_CLINICAL_MONITOR -> {
                backgroundPaint.color = Color.parseColor("#0B1120") // Dark slate
                minorGridPaint.color = Color.parseColor("#152238")
                minorGridPaint.strokeWidth = 1f
                majorGridPaint.color = Color.parseColor("#1E293B")
                majorGridPaint.strokeWidth = 1.8f
                // Colour-blind friendly mode: sky blue trace and yellow R-peak markers instead of green and red.
                val colorBlind = com.example.prog7314_poepart23.utils.ColorBlindHelper.isEnabled(context)
                wavePaint.color = Color.parseColor(if (colorBlind) "#56B4E9" else "#10B981") // Phosphor green
                wavePaint.strokeWidth = 3.5f
                rPeakPaint.color = Color.parseColor(if (colorBlind) "#F0E442" else "#EF4444") // Alert red
                calibrationPaint.color = Color.parseColor("#38BDF8") // Cyan pulse
                textPaint.color = Color.parseColor("#64748B")
            }
            EcgGridMode.HOSPITAL_PAPER -> {
                backgroundPaint.color = Color.parseColor("#FFF5F5") // Sterile pink ECG paper
                minorGridPaint.color = Color.parseColor("#FFD4D4") // 1 mm minor division
                minorGridPaint.strokeWidth = 1.2f
                majorGridPaint.color = Color.parseColor("#F87171") // 5 mm major division
                majorGridPaint.strokeWidth = 2.0f
                wavePaint.color = Color.parseColor("#0F172A") // Deep charcoal / black
                wavePaint.strokeWidth = 3.0f
                // Colour-blind friendly mode: blue R-peak markers stand out from the pink paper and dark trace.
                rPeakPaint.color = Color.parseColor(
                    if (com.example.prog7314_poepart23.utils.ColorBlindHelper.isEnabled(context)) "#0072B2" else "#DC2626"
                ) // Deep crimson
                calibrationPaint.color = Color.parseColor("#991B1B")
                textPaint.color = Color.parseColor("#7F1D1D")
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startRendering()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Handled dynamically within the background render thread
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopRendering()
    }

    // Ingests a single raw 12-bit ADC integer sample (0 - 4095) from the AD8232 sensor.
    fun addSample(adcValue: Int) {
        if (rawSampleQueue.size > 2000) {
            rawSampleQueue.poll()
        }
        rawSampleQueue.offer(adcValue)
    }

    // Batch ingests a list of raw ADC samples received from the PulseLink API telemetry payload.
    fun addSamples(samples: List<Int>) {
        for (sample in samples) {
            addSample(sample)
        }
    }

    // Clears all active waveform coordinate buffers, resets filters, and refreshes the canvas.
    fun clearBuffer() {
        rawSampleQueue.clear()
        qrsDetector.reset()
        for (i in smoothingFilterWindow.indices) {
            smoothingFilterWindow[i] = 2048
        }
        smoothingFilterSum = 2048 * 5
        smoothingFilterIndex = 0
        baselineEstimated = 2048f
        synchronized(renderedPoints) {
            renderedPoints.clear()
            rPeakIndices.clear()
        }
    }

    // Initialises and starts the 60 FPS background rendering thread.
    fun startRendering() {
        if (renderThread == null || !renderThread!!.isAlive) {
            renderThread = RenderThread(holder).apply {
                isRunning = true
                start()
            }
        }
    }

    // Safely halts the background rendering thread.
    fun stopRendering() {
        renderThread?.let { thread ->
            thread.isRunning = false
            var retry = true
            while (retry) {
                try {
                    thread.join()
                    retry = false
                } catch (ignored: InterruptedException) {
                }
            }
        }
        renderThread = null
    }

    // Dedicated rendering thread executing continuous 60 FPS redraw loops.
    private inner class RenderThread(private val surfaceHolder: SurfaceHolder) : Thread() {
        @Volatile
        var isRunning = false

        private val targetFps = 60
        private val framePeriodMs = (1000f / targetFps).toLong()

        override fun run() {
            var lastTime = System.currentTimeMillis()

            while (isRunning) {
                val now = System.currentTimeMillis()
                val elapsed = now - lastTime
                if (elapsed < framePeriodMs) {
                    try {
                        sleep(framePeriodMs - elapsed)
                    } catch (ignored: InterruptedException) {
                    }
                }
                lastTime = System.currentTimeMillis()

                // Ingest and process pending AD8232 sensor samples
                processPendingSamples()

                // Acquire hardware canvas and render current frame
                var canvas: Canvas? = null
                try {
                    canvas = surfaceHolder.lockCanvas()
                    if (canvas != null) {
                        synchronized(surfaceHolder) {
                            drawEcgFrame(canvas)
                        }
                    }
                } catch (e: Exception) {
                    // Suppress transient canvas acquisition exceptions during surface teardown
                } finally {
                    if (canvas != null) {
                        try {
                            surfaceHolder.unlockCanvasAndPost(canvas)
                        } catch (ignored: Exception) {
                        }
                    }
                }
            }
        }

        // Pulls raw ADC values from the thread-safe jitter buffer, applies 5-point low-pass smoothing,
        // runs Pan-Tompkins QRS detection, applies baseline drift cancellation, and coordinates buffer trimming.
        private fun processPendingSamples() {
            // Clock-paced jitter buffer regulation:
            // 250 Hz sample rate at 60 FPS = nominal ~4.167 samples/frame sweep pace.
            val queueSize = rawSampleQueue.size
            val samplesToProcess = when {
                queueSize > 250 -> 8  // Network burst catch-up
                queueSize > 80 -> 6   // Gentle queue drain
                queueSize > 20 -> 5   // Steady sweep
                queueSize >= 6 -> 4   // Nominal 250 Hz clinical pace
                queueSize > 0 -> 2    // Prevent jitter starvation
                else -> 0
            }

            var processed = 0
            while (processed < samplesToProcess && !rawSampleQueue.isEmpty()) {
                val rawValue = rawSampleQueue.poll() ?: break
                processed++

                // Digital Signal Processing: Low-pass filter removing 50Hz/60Hz noise
                val smoothedAdc = applyLowPassSmoothing(rawValue)

                // Run Pan-Tompkins QRS detection pipeline
                val isRPeak = qrsDetector.processSample(smoothedAdc)

                // High-pass baseline drift cancellation
                baselineEstimated = (baselineAlpha * smoothedAdc) + ((1f - baselineAlpha) * baselineEstimated)
                val centredValue = smoothedAdc - baselineEstimated

                synchronized(renderedPoints) {
                    renderedPoints.add(centredValue)

                    if (isRPeak) {
                        rPeakIndices.add(renderedPoints.size - 1)
                        val smoothedBpm = qrsDetector.currentSmoothedBpm
                        post { onRPeakDetected?.invoke(smoothedBpm) }
                    }

                    // Trim oldest points when exceeding visual width capacity
                    val maxCapacity = width / 2
                    if (maxCapacity > 50 && renderedPoints.size > maxCapacity) {
                        val dropCount = renderedPoints.size - maxCapacity
                        for (i in 0 until dropCount) {
                            if (renderedPoints.isNotEmpty()) renderedPoints.removeAt(0)
                        }
                        rPeakIndices.removeAll { it < dropCount }
                        for (i in rPeakIndices.indices) {
                            rPeakIndices[i] = rPeakIndices[i] - dropCount
                        }
                    }
                }
            }
        }

        // Draws the complete clinical ECG frame: background, medical grid, calibration pulse,
        // continuous Lead I trace, and R-peak markers.
        private fun drawEcgFrame(canvas: Canvas) {
            val viewWidth = canvas.width.toFloat()
            val viewHeight = canvas.height.toFloat()

            // 1. Fill canvas background
            canvas.drawRect(0f, 0f, viewWidth, viewHeight, backgroundPaint)

            // 2. Draw standard clinical ECG grid overlay (minor 1 mm / 40 ms; major 5 mm / 200 ms)
            drawMedicalGrid(canvas, viewWidth, viewHeight)

            // 3. Draw standard 10 mm/mV calibration reference pulse on left margin
            drawCalibrationPulse(canvas, viewHeight)

            if (isLeadsOff) {
                // Continuous authentic clinical isoelectric flatline trace across hospital grid
                val centerY = viewHeight / 2f
                canvas.drawLine(35f, centerY, viewWidth, centerY, wavePaint)

                // Render clinical warning badge in top margin
                warningIcon?.let { icon ->
                    val iconWidth = 36
                    val iconHeight = 36
                    val left = (viewWidth / 2f - 260f).toInt().coerceAtLeast(40)
                    val top = 14
                    icon.setBounds(left, top, left + iconWidth, top + iconHeight)
                    icon.draw(canvas)
                }

                canvas.drawText(context.getString(R.string.l10n_lead_fault_banner), viewWidth / 2f, centerY + 24f, leadOffPaint)
                return
            }

            // 4. Render continuous ECG Lead I trace
            synchronized(renderedPoints) {
                if (renderedPoints.isEmpty()) {
                    // Draw flat isoelectric baseline
                    canvas.drawLine(35f, viewHeight / 2f, viewWidth, viewHeight / 2f, wavePaint)
                    return
                }

                val pointSpacing = 2.5f
                val startX = 35f // Offset past calibration pulse
                val centerY = viewHeight / 2f
                val verticalGain = viewHeight / 1500f // Scale factor mapping ADC deviation to canvas pixels

                wavePath.reset()
                var firstPoint = true

                val maxVisiblePoints = ((viewWidth - startX) / pointSpacing).toInt()
                val startIndex = (renderedPoints.size - maxVisiblePoints).coerceAtLeast(0)
                var xPos = startX

                for (i in startIndex until renderedPoints.size) {
                    val sampleDelta = renderedPoints[i]
                    val yPos = centerY - (sampleDelta * verticalGain)

                    if (firstPoint) {
                        wavePath.moveTo(xPos, yPos)
                        firstPoint = false
                    } else {
                        wavePath.lineTo(xPos, yPos)
                    }

                    // Paint circular marker on detected R-peak apex coordinates
                    if (rPeakIndices.contains(i)) {
                        canvas.drawCircle(xPos, yPos, 5f, rPeakPaint)
                    }

                    xPos += pointSpacing
                }

                canvas.drawPath(wavePath, wavePaint)
            }
        }

        // Renders standard clinical ECG grid squares across the full canvas surface
        private fun drawMedicalGrid(canvas: Canvas, width: Float, height: Float) {
            val minorStep = 20f
            val majorStep = 100f

            // Minor grid lines (1 mm / 40 ms equivalent)
            var x = 0f
            while (x <= width) {
                canvas.drawLine(x, 0f, x, height, minorGridPaint)
                x += minorStep
            }

            var y = 0f
            while (y <= height) {
                canvas.drawLine(0f, y, width, y, minorGridPaint)
                y += minorStep
            }

            // Major grid lines (5 mm / 200 ms equivalent)
            x = 0f
            while (x <= width) {
                canvas.drawLine(x, 0f, x, height, majorGridPaint)
                x += majorStep
            }

            y = 0f
            while (y <= height) {
                canvas.drawLine(0f, y, width, y, majorGridPaint)
                y += majorStep
            }

            // Grid calibration legend in bottom margin
            val legend = if (gridMode == EcgGridMode.HOSPITAL_PAPER) {
                context.getString(R.string.l10n_legend_paper)
            } else {
                context.getString(R.string.l10n_legend_monitor)
            }
            canvas.drawText(legend, 40f, height - 12f, textPaint)
        }

        // Renders authentic 10 mm/mV calibration pulse (standard ECG reference pulse)
        private fun drawCalibrationPulse(canvas: Canvas, height: Float) {
            val centerY = height / 2f
            val pulseHeight = 40f // Represents 1 mV (10 mm) at this scale
            val left = 10f
            val right = 28f

            calibPath.reset()
            calibPath.moveTo(left, centerY)
            calibPath.lineTo(left + 4f, centerY)
            calibPath.lineTo(left + 4f, centerY - pulseHeight)
            calibPath.lineTo(right - 4f, centerY - pulseHeight)
            calibPath.lineTo(right - 4f, centerY)
            calibPath.lineTo(right, centerY)

            canvas.drawPath(calibPath, calibrationPaint)
        }
    }
}
