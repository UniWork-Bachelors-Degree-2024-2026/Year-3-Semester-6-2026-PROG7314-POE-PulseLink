/**
 * PulseLink 3-Lead ECG Electrode Placement Guide Activity
 *
 * Provides visual instructions for 3-lead Einthoven triangle electrode placement
 * (RA, LA, RL) conforming to clinical telemetry standards (AHA, 2020; Analog Devices, 2020).
 *
 * Reference List:
 * - American Heart Association (AHA), 2020. Recommendations for the Standardization and Interpretation of the Electrocardiogram. Circulation, 142(15), pp. 810-830.
 * - Analog Devices, 2020. AD8232: Single-Lead, Heart Rate Monitor Front End Data Sheet. Norwood: Analog Devices Inc.
 * - Google Developers, 2024. View binding and Material Design components in Android. Android Developers. Available at: <https://developer.android.com/guide/topics/ui> [Accessed 18 September 2026].
 */

package com.example.prog7314_poepart23

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ElectrodePlacementGuideActivity : AppCompatActivity() {

    private data class Step(val title: String, val description: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.patient_electrode_placement_guide)

        // Adjust insets for edge-to-edge system bars (Google Developers, 2024)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<View>(R.id.ivBack).setOnClickListener { finish() }

        populateSteps()
    }

    // Builds the numbered instruction list from string resources so the guide
    // stays translatable and easy to extend with future steps.
    private fun populateSteps() {
        val steps = listOf(
            Step(
                getString(R.string.patient_electrode_placement_guide_step1_title),
                getString(R.string.patient_electrode_placement_guide_step1_desc)
            ),
            Step(
                getString(R.string.patient_electrode_placement_guide_step2_title),
                getString(R.string.patient_electrode_placement_guide_step2_desc)
            ),
            Step(
                getString(R.string.patient_electrode_placement_guide_step3_title),
                getString(R.string.patient_electrode_placement_guide_step3_desc)
            ),
            Step(
                getString(R.string.patient_electrode_placement_guide_step4_title),
                getString(R.string.patient_electrode_placement_guide_step4_desc)
            ),
            Step(
                getString(R.string.patient_electrode_placement_guide_step5_title),
                getString(R.string.patient_electrode_placement_guide_step5_desc)
            ),
            Step(
                getString(R.string.patient_electrode_placement_guide_step6_title),
                getString(R.string.patient_electrode_placement_guide_step6_desc)
            )
        )

        val container = findViewById<android.widget.LinearLayout>(R.id.layoutSteps)
        val inflater = LayoutInflater.from(this)

        steps.forEachIndexed { index, step ->
            val row = inflater.inflate(R.layout.item_placement_step, container, false)
            row.findViewById<TextView>(R.id.tvStepNumber).text = (index + 1).toString()
            row.findViewById<TextView>(R.id.tvStepTitle).text = step.title
            row.findViewById<TextView>(R.id.tvStepDescription).text = step.description
            container.addView(row)
        }
    }
}
