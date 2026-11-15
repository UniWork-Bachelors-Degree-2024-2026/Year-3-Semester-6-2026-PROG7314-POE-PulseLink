/**
 * PulseLink Clinical Alert Recycler List Adapter
 *
 * Implements asynchronous list difference calculation via DiffUtil and binds
 * urgent patient arrhythmia events to triage card layouts (AHA, 2020; Google Developers, 2024).
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
import com.example.prog7314_poepart23.data.remote.models.AlertDto
import com.example.prog7314_poepart23.databinding.ItemAlertCardBinding

class AlertListAdapter(
    private val onAlertClick: (AlertDto) -> Unit
) : ListAdapter<AlertDto, AlertListAdapter.AlertViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
        val binding = ItemAlertCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return AlertViewHolder(binding, onAlertClick)
    }

    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class AlertViewHolder(
        private val binding: ItemAlertCardBinding,
        private val onAlertClick: (AlertDto) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(alert: AlertDto) {
            val context = binding.root.context

            binding.tvAlertType.text = com.example.prog7314_poepart23.utils.LocaleHelper.alertTypeLabel(context, alert.alertType)
            binding.tvTimestamp.text = com.example.prog7314_poepart23.utils.DateTimeFormatter.formatIsoTimestamp(alert.timestampUtc)
            binding.tvPatientName.text = alert.patientName ?: alert.patientId

            when (alert.severity.lowercase()) {
                "critical" -> {
                    binding.rootAlertCard.setBackgroundResource(R.drawable.bg_card_left_red)
                    binding.ivSeverityIcon.setBackgroundResource(R.drawable.circle_danger_bg)
                    binding.ivSeverityIcon.setImageResource(R.drawable.ic_heart_pulse)
                    binding.chipSeverity.setBackgroundResource(R.drawable.bg_chip_critical)
                    binding.chipSeverity.setTextColor(context.getColor(R.color.pulse_danger))
                    binding.chipSeverity.text = context.getString(R.string.severity_critical)
                }
                "warning" -> {
                    binding.rootAlertCard.setBackgroundResource(R.drawable.bg_card_left_amber)
                    binding.ivSeverityIcon.setBackgroundResource(R.drawable.circle_warning_bg)
                    binding.ivSeverityIcon.setImageResource(R.drawable.ic_warning_triangle)
                    binding.chipSeverity.setBackgroundResource(R.drawable.bg_chip_warning)
                    binding.chipSeverity.setTextColor(context.getColor(R.color.pulse_warning))
                    binding.chipSeverity.text = context.getString(R.string.severity_warning)
                }
                else -> {
                    binding.rootAlertCard.setBackgroundResource(R.drawable.bg_card_left_blue)
                    binding.ivSeverityIcon.setBackgroundResource(R.drawable.circle_gray)
                    binding.ivSeverityIcon.setImageResource(R.drawable.ic_cloud_off)
                    binding.chipSeverity.setBackgroundResource(R.drawable.bg_pill_neutral)
                    binding.chipSeverity.setTextColor(context.getColor(R.color.pulse_text_secondary))
                    binding.chipSeverity.text = context.getString(R.string.severity_system)
                }
            }

            binding.root.setOnClickListener { onAlertClick(alert) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AlertDto>() {
            override fun areItemsTheSame(oldItem: AlertDto, newItem: AlertDto): Boolean =
                oldItem.alertId == newItem.alertId

            override fun areContentsTheSame(oldItem: AlertDto, newItem: AlertDto): Boolean =
                oldItem == newItem
        }
    }
}
