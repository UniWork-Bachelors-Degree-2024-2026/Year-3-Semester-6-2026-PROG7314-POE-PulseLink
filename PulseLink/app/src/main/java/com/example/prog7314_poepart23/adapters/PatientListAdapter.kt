/**
 * PulseLink Clinician Patient Roster Recycler List Adapter
 *
 * Renders assigned patient summaries, vital triage status indicators, and latest ECG
 * session records via DiffUtil-backed reactive adapters (AHA, 2020; Google Developers, 2024).
 *
 * Reference List:
 * - American Heart Association (AHA), 2020. Recommendations for the Standardization and Interpretation of the Electrocardiogram. Circulation, 142(15), pp. 810-830.
 * - Google Developers, 2024. Create dynamic lists with RecyclerView: ListAdapter and DiffUtil. Android Developers. Available at: <https://developer.android.com/develop/ui/views/layout/recyclerview> [Accessed 18 September 2026].
 */

package com.example.prog7314_poepart23.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.data.models.PatientStatus
import com.example.prog7314_poepart23.data.models.PatientSummary
import com.example.prog7314_poepart23.databinding.ItemPatientCardBinding

class PatientListAdapter(
    private val onPatientClick: (PatientSummary) -> Unit
) : ListAdapter<PatientSummary, PatientListAdapter.PatientViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PatientViewHolder {
        val binding = ItemPatientCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PatientViewHolder(binding, onPatientClick)
    }

    override fun onBindViewHolder(holder: PatientViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class PatientViewHolder(
        private val binding: ItemPatientCardBinding,
        private val onPatientClick: (PatientSummary) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(patient: PatientSummary) {
            val context = binding.root.context

            binding.tvPatientName.text = patient.name
            binding.tvLastSessionDate.text = context.getString(
                R.string.patient_last_session_format,
                patient.lastSessionDateLabel
            )
            binding.tvLastBpm.text = if (patient.lastBpm > 0) context.getString(R.string.session_bpm_format, patient.lastBpm) else context.getString(R.string.l10n_bpm_none)

            val (dotRes, pillRes, textColorRes, label) = when (patient.status) {
                PatientStatus.REGISTERED -> StatusStyle(R.drawable.status_dot_grey, R.drawable.bg_pill_neutral, R.color.pulse_text_secondary, R.string.doctor_registered)
                PatientStatus.FOLLOW_UP -> StatusStyle(R.drawable.status_dot_amber, R.drawable.bg_chip_warning, R.color.pulse_warning, R.string.doctor_follow_up)
                PatientStatus.STABLE -> StatusStyle(
                    R.drawable.status_dot_green, R.drawable.bg_chip_normal,
                    R.color.pulse_success, R.string.patient_status_stable
                )
                PatientStatus.REVIEW_NEEDED -> StatusStyle(
                    R.drawable.status_dot_amber, R.drawable.bg_chip_warning,
                    R.color.pulse_warning, R.string.patient_status_review_needed
                )
                PatientStatus.CRITICAL -> StatusStyle(
                    R.drawable.status_dot_red, R.drawable.bg_chip_critical,
                    R.color.pulse_danger, R.string.patient_status_critical
                )
            }

            binding.ivStatusDot.setImageResource(dotRes)
            binding.tvStatusLabel.setBackgroundResource(pillRes)
            binding.tvStatusLabel.setTextColor(context.getColor(textColorRes))
            binding.tvStatusLabel.text = context.getString(label)

            binding.root.setOnClickListener { onPatientClick(patient) }
        }
    }

    private data class StatusStyle(
        val dotRes: Int,
        val pillRes: Int,
        val textColorRes: Int,
        val labelRes: Int
    )

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<PatientSummary>() {
            override fun areItemsTheSame(
                oldItem: PatientSummary,
                newItem: PatientSummary
            ): Boolean = oldItem.patientId == newItem.patientId

            override fun areContentsTheSame(
                oldItem: PatientSummary,
                newItem: PatientSummary
            ): Boolean = oldItem == newItem
        }
    }
}
