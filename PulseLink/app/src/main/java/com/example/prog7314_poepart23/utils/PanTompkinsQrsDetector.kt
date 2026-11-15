/*
 * Pan-Tompkins real-time QRS detection algorithm for AD8232 single-lead (Lead I) ECG signals
 * sampled at 250 Hz.
 *
 * Processing Pipeline:
 * 1. High-Pass Baseline Wander Attenuation: Removes low-frequency respiration and movement drift.
 * 2. 5-Point Derivative Filter: Accentuates the steep slope of the QRS complex:
 *    y[n] = (2x[n] + x[n-1] - x[n-3] - 2x[n-4]) / 8
 * 3. Squaring Function: y[n] = (x[n])^2 to non-linearly amplify high-frequency components.
 * 4. Moving Window Integrator (MWI): Integrates energy over a 120 ms window (~30 samples at 250 Hz).
 * 5. Dual Adaptive Thresholding: Dynamically updates Signal Peak (SPKI) and Noise Peak (NPKI) estimates.
 *    Refractory blanking period of 200 ms (50 samples) prevents double-counting T-waves.
 * 6. Time-Windowed (about 5 second) RR-Interval Average with Smoothing:
 *    Calculates instantaneous BPM = (60 * Fs) / RR_samples.
 *    Rejects non-physiological outliers (< 30 BPM or > 240 BPM).
 *    Keeps every valid RR interval from the last 5 seconds, drops intervals that disagree with the
 *    window median by more than 30% (missed or double-detected beats), averages the rest, and
 *    eases the displayed value towards that average so the on-screen BPM changes gently
 *    instead of jumping from beat to beat.
 *
 * Reference List:
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End. [online] Available at: <https://www.analog.com/media/en/technical-documentation/data-sheets/AD8232.pdf> [Accessed 21 September 2026].
 * - Pan, J. and Tompkins, W.J., 1985. A real-time QRS detection algorithm. IEEE Transactions on Biomedical Engineering, BME-32(3), pp.230-236.
 */

package com.example.prog7314_poepart23.utils

import java.util.LinkedList

class PanTompkinsQrsDetector( // (Pan and Tompkins, 1985; Analog Devices, 2020)
    private val sampleRate: Int = 250
) {

    // Delay buffer for 5-point derivative filter: x[n], x[n-1], x[n-2], x[n-3], x[n-4]
    private val xHistory = FloatArray(5)

    // Moving window integrator circular buffer (30 samples = 120 ms at 250 Hz)
    private val mwiWindowSize = (0.120f * sampleRate).toInt().coerceAtLeast(8)
    private val mwiBuffer = FloatArray(mwiWindowSize)
    private var mwiIndex = 0
    private var mwiSum = 0f

    // Dynamic baseline wander tracking (exponential moving average)
    private var baselineEstimate = 2048f
    private val baselineAlpha = 0.05f

    // Adaptive threshold state
    private var spki = 500f // Initial Signal Peak estimate
    private var npki = 100f // Initial Noise Peak estimate
    private var threshold = 200f

    // Local peak detection within MWI output
    private var prevMwiVal = 0f
    private var isRising = false
    private var currentLocalPeak = 0f
    private var currentLocalPeakIndex = 0L

    // Timing and refractory counters
    private var sampleCounter = 0L
    private var lastRPeakSampleIndex = -1L
    private val refractorySamples = (0.200f * sampleRate).toInt() // 200 ms blanking = 50 samples

    // RR intervals (end sample index, interval length in samples) kept for the averaging window.
    // The window is time based (about 5 seconds) rather than beat based, so the reading follows
    // heart-rate changes quickly at any rate instead of waiting for 8 beats to roll over.
    private val rrWindow = LinkedList<LongArray>()
    private val bpmWindowSeconds = 5
    private val bpmWindowSamples = (bpmWindowSeconds * sampleRate).toLong()

    // Fraction of the way the displayed BPM moves towards each new window average (0..1).
    // Lower is smoother; 0.35 settles in about 3-4 beats without visible stepping.
    private val displayEaseFactor = 0.35f
    private var easedBpm = 0f

    // Last confirmed stable BPM
    var currentSmoothedBpm: Int = 0
        private set

    // True when the beat-to-beat (RR) intervals inside the ~5 second window vary too much for a regular
    // rhythm (coefficient of variation above 20% over at least 4 intervals), a simple arrhythmia marker.
    val isRhythmIrregular: Boolean
        get() = synchronized(rrWindow) {
            if (rrWindow.size < MIN_INTERVALS_FOR_IRREGULARITY) return false
            val intervals = rrWindow.map { it[1].toDouble() }
            val mean = intervals.average()
            if (mean <= 0.0) return false
            val variance = intervals.sumOf { (it - mean) * (it - mean) } / intervals.size
            Math.sqrt(variance) / mean > IRREGULARITY_CV_THRESHOLD
        }

    // Resets filter states when beginning a new ECG recording session
    fun reset() {
        xHistory.fill(0f)
        mwiBuffer.fill(0f)
        mwiIndex = 0
        mwiSum = 0f
        baselineEstimate = 2048f
        spki = 500f
        npki = 100f
        threshold = 200f
        prevMwiVal = 0f
        isRising = false
        currentLocalPeak = 0f
        currentLocalPeakIndex = 0L
        sampleCounter = 0L
        lastRPeakSampleIndex = -1L
        currentSmoothedBpm = 0
        easedBpm = 0f
        synchronized(rrWindow) {
            rrWindow.clear()
        }
    }

    // Ingests a single raw 12-bit ADC sample from the AD8232 sensor (0 - 4095).
    // Returns true if this sample represents the apex of an identified R-peak.
    fun processSample(rawAdc: Int): Boolean {
        sampleCounter++

        // 1. Dynamic baseline wander cancellation
        baselineEstimate = (baselineAlpha * rawAdc) + ((1f - baselineAlpha) * baselineEstimate)
        val filtered = rawAdc - baselineEstimate

        // 2. Five-point derivative
        // Shift history
        xHistory[4] = xHistory[3]
        xHistory[3] = xHistory[2]
        xHistory[2] = xHistory[1]
        xHistory[1] = xHistory[0]
        xHistory[0] = filtered

        val derivative = (2f * xHistory[0] + xHistory[1] - xHistory[3] - 2f * xHistory[4]) / 8f

        // 3. Squaring function
        val squared = derivative * derivative

        // 4. Moving window integrator
        mwiSum -= mwiBuffer[mwiIndex]
        mwiBuffer[mwiIndex] = squared
        mwiSum += squared
        mwiIndex = (mwiIndex + 1) % mwiWindowSize
        val mwiVal = mwiSum / mwiWindowSize

        // 5. Peak finding on MWI signal
        var rPeakDetected = false

        if (mwiVal > prevMwiVal) {
            isRising = true
            currentLocalPeak = mwiVal
            currentLocalPeakIndex = sampleCounter
        } else if (isRising && mwiVal < prevMwiVal) {
            // Local maximum found at prevMwiVal
            isRising = false
            val peakCandidate = currentLocalPeak
            val peakIndex = currentLocalPeakIndex

            // Evaluate candidate against refractory period and adaptive threshold
            val samplesSinceLastPeak = if (lastRPeakSampleIndex >= 0) peakIndex - lastRPeakSampleIndex else Long.MAX_VALUE

            if (samplesSinceLastPeak >= refractorySamples) {
                if (peakCandidate > threshold) {
                    // Valid R-peak confirmed
                    rPeakDetected = true
                    spki = (0.125f * peakCandidate) + (0.875f * spki)

                    if (lastRPeakSampleIndex >= 0) {
                        val rrSamples = peakIndex - lastRPeakSampleIndex
                        if (rrSamples > 0) {
                            val instantaneousBpm = ((60f * sampleRate) / rrSamples).toInt()

                            // Outlier rejection: Discard physiologically improbable values (< 30 or > 240 BPM)
                            if (instantaneousBpm in 30..240) {
                                synchronized(rrWindow) {
                                    rrWindow.addLast(longArrayOf(peakIndex, rrSamples))
                                    // Drop intervals that ended more than ~5 seconds ago
                                    while (rrWindow.size > 1 && peakIndex - rrWindow.first()[0] > bpmWindowSamples) {
                                        rrWindow.removeFirst()
                                    }
                                    updateSmoothedBpm()
                                }
                            }
                        }
                    }
                    lastRPeakSampleIndex = peakIndex
                } else {
                    // Noise peak
                    npki = (0.125f * peakCandidate) + (0.875f * npki)
                }

                // Update adaptive threshold
                threshold = npki + 0.25f * (spki - npki)
            }
        }

        prevMwiVal = mwiVal
        return rPeakDetected
    }

    private companion object {
        const val MIN_INTERVALS_FOR_IRREGULARITY = 4
        const val IRREGULARITY_CV_THRESHOLD = 0.20
    }

    // Averages the RR intervals inside the ~5 second window (ignoring ones that disagree with the
    // window median by more than 30%) and eases the displayed BPM towards that average.
    // Must be called while holding the rrWindow lock.
    private fun updateSmoothedBpm() {
        if (rrWindow.isEmpty()) return

        val medianRr = computeMedian(rrWindow.map { it[1] })
        val lower = medianRr * 0.7
        val upper = medianRr * 1.3
        val agreeing = rrWindow.map { it[1] }.filter { it >= lower && it <= upper }
        val meanRr = (if (agreeing.isNotEmpty()) agreeing else rrWindow.map { it[1] }).average()
        if (meanRr <= 0.0) return

        val windowBpm = ((60.0 * sampleRate) / meanRr).toFloat()

        easedBpm = if (easedBpm <= 0f) {
            windowBpm
        } else {
            easedBpm + displayEaseFactor * (windowBpm - easedBpm)
        }
        currentSmoothedBpm = Math.round(easedBpm)
    }

    // Computes the median value of a list of RR intervals (in samples)
    private fun computeMedian(values: List<Long>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val size = sorted.size
        return if (size % 2 == 1) {
            sorted[size / 2].toDouble()
        } else {
            (sorted[(size / 2) - 1] + sorted[size / 2]) / 2.0
        }
    }
}
