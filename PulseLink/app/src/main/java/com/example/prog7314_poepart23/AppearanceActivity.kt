/*
 * Appearance screen (Settings > Appearance): Theme selector (System / Light / Dark), Text size slider
 * and High contrast switch. Replaces the old pop-up dialogs so all three live in one uniform screen.
 * Shared by the patient and doctor Settings screens.
 */

package com.example.prog7314_poepart23

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.prog7314_poepart23.utils.ColorBlindHelper
import com.example.prog7314_poepart23.utils.HighContrastHelper
import com.example.prog7314_poepart23.utils.TextSizeHelper
import com.example.prog7314_poepart23.utils.ThemeHelper
import com.example.prog7314_poepart23.utils.VoiceAssistant
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class AppearanceActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val applyTextSize = Runnable {
        val step = textSizeSlider.value.toInt()
        if (step != TextSizeHelper.getStep(this)) TextSizeHelper.setStep(this, step)
    }

    private lateinit var textSizeSlider: Slider
    private lateinit var txtTextSizeValue: TextView
    private lateinit var txtTextSizePreview: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.appearance_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<android.view.View>(R.id.btnAppearanceBack).setOnClickListener { finish() }

        setUpTheme()
        setUpTextSize()
        setUpHighContrast()
        setUpColorBlind()
        setUpVoiceAssistance()
    }

    override fun onDestroy() {
        handler.removeCallbacks(applyTextSize)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------
    // Theme: System / Light / Dark (applied straight away; AppCompat redraws the screens)
    // ---------------------------------------------------------------------------------
    private fun setUpTheme() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.toggleTheme)
        group.check(
            when (ThemeHelper.getMode(this)) {
                ThemeHelper.MODE_LIGHT -> R.id.btnThemeLight
                ThemeHelper.MODE_DARK -> R.id.btnThemeDark
                else -> R.id.btnThemeSystem
            }
        )
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            ThemeHelper.setMode(
                this,
                when (checkedId) {
                    R.id.btnThemeLight -> ThemeHelper.MODE_LIGHT
                    R.id.btnThemeDark -> ThemeHelper.MODE_DARK
                    else -> ThemeHelper.MODE_SYSTEM
                }
            )
        }
    }

    // ---------------------------------------------------------------------------------
    // Text size: slider with a live preview. The new size is applied to the whole app a moment
    // after the slider stops moving, so dragging stays smooth.
    // ---------------------------------------------------------------------------------
    private fun setUpTextSize() {
        textSizeSlider = findViewById(R.id.sliderTextSize)
        txtTextSizeValue = findViewById(R.id.txtTextSizeValue)
        txtTextSizePreview = findViewById(R.id.txtTextSizePreview)

        val labels = arrayOf(
            getString(R.string.text_size_small),
            getString(R.string.text_size_default),
            getString(R.string.text_size_large),
            getString(R.string.text_size_extra_large)
        )

        textSizeSlider.value = TextSizeHelper.getStep(this).toFloat()
        textSizeSlider.setLabelFormatter { labels[it.toInt().coerceIn(0, labels.size - 1)] }
        showStep(textSizeSlider.value.toInt(), labels)

        textSizeSlider.addOnChangeListener { _, value, fromUser ->
            showStep(value.toInt(), labels)
            if (fromUser) {
                handler.removeCallbacks(applyTextSize)
                handler.postDelayed(applyTextSize, 500L)
            }
        }
    }

    private fun showStep(step: Int, labels: Array<String>) {
        txtTextSizeValue.text = labels[step.coerceIn(0, labels.size - 1)]
        // dp (not sp) so the preview shows exactly the size the chosen step will give.
        txtTextSizePreview.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f * TextSizeHelper.scaleFor(step))
    }

    // ---------------------------------------------------------------------------------
    // High contrast switch
    // ---------------------------------------------------------------------------------
    private fun setUpHighContrast() {
        val toggle = findViewById<MaterialSwitch>(R.id.switchHighContrast)
        toggle.isChecked = HighContrastHelper.isEnabled(this)
        toggle.setOnCheckedChangeListener { _, checked ->
            if (checked != HighContrastHelper.isEnabled(this)) HighContrastHelper.setEnabled(this, checked)
        }
        // The whole row is tappable, not just the switch.
        findViewById<android.view.View>(R.id.rowHighContrast).setOnClickListener { toggle.toggle() }
    }

    // ---------------------------------------------------------------------------------
    // Colour-blind friendly switch
    // ---------------------------------------------------------------------------------
    private fun setUpColorBlind() {
        val toggle = findViewById<MaterialSwitch>(R.id.switchColorBlind)
        toggle.isChecked = ColorBlindHelper.isEnabled(this)
        toggle.setOnCheckedChangeListener { _, checked ->
            if (checked != ColorBlindHelper.isEnabled(this)) ColorBlindHelper.setEnabled(this, checked)
        }
        // The whole row is tappable, not just the switch.
        findViewById<android.view.View>(R.id.rowColorBlind).setOnClickListener { toggle.toggle() }
    }

    // ---------------------------------------------------------------------------------
    // Voice assistance switch (reads heart rate, readings and notifications aloud, voice commands)
    // ---------------------------------------------------------------------------------
    private fun setUpVoiceAssistance() {
        val toggle = findViewById<MaterialSwitch>(R.id.switchVoiceAssistance)
        toggle.isChecked = VoiceAssistant.isEnabled(this)
        toggle.setOnCheckedChangeListener { _, checked ->
            if (checked != VoiceAssistant.isEnabled(this)) VoiceAssistant.setEnabled(this, checked)
        }
        // The whole row is tappable, not just the switch.
        findViewById<android.view.View>(R.id.rowVoiceAssistance).setOnClickListener { toggle.toggle() }
    }
}
