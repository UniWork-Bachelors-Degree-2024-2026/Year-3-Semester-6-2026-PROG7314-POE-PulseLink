/**
 * PulseLink Historical ECG Session Recycler List Adapter
 *
 * Implements asynchronous item diffing and binds historical cardiac session summaries,
 * rhythm classification chips, and average heart rate statistics (AHA, 2020; Google Developers, 2024).
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
import com.example.prog7314_poepart23.data.remote.models.EcgSessionSummaryDto
import com.example.prog7314_poepart23.databinding.ItemSessionCardBinding

class SessionListAdapter(
    private val onSessionClick: (EcgSessionSummaryDto) -> Unit
) : ListAdapter<EcgSessionSummaryDto, SessionListAdapter.SessionViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemSessionCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SessionViewHolder(binding, onSessionClick)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class SessionViewHolder(
        private val binding: ItemSessionCardBinding,
        private val onSessionClick: (EcgSessionSummaryDto) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(session: EcgSessionSummaryDto) {
            val context = binding.root.context

            binding.tvSessionDate.text = readableDate(session.startTimeUtc)
            com.example.prog7314_poepart23.utils.HeartRateStatus.apply(binding.tvAvgBpm, session.averageBpm, if (session.averageBpm > 0) context.getString(R.string.session_bpm_format, session.averageBpm) else context.getString(R.string.l10n_bpm_none))
            binding.tvDuration.text = context.getString(
                R.string.session_duration_format,
                session.durationSeconds / 60,
                session.durationSeconds % 60
            )

            binding.chipClinicalStatus.text = com.example.prog7314_poepart23.utils.LocaleHelper.statusLabel(context, session.status)
            binding.chipClinicalStatus.setTextColor(context.getColor(R.color.pulse_text_secondary))
            binding.ivStatusDot.setImageResource(R.drawable.status_dot_grey)

            binding.root.setOnClickListener { onSessionClick(session) }
        }


    }

    companion object {
        // Shows "22 Sep 2026, 03:29" (device time zone) instead of the raw ISO string; falls back to the raw value.
        private fun readableDate(iso: String): String = try {
            val clean = iso.trim()
            val base = if (clean.contains('.')) clean.substringBefore('.') else clean.removeSuffix("Z")
            val parsed = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(base)
            if (parsed != null) java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(parsed) else iso
        } catch (e: Exception) {
            iso
        }

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<EcgSessionSummaryDto>() {
            override fun areItemsTheSame(
                oldItem: EcgSessionSummaryDto,
                newItem: EcgSessionSummaryDto
            ): Boolean = oldItem.sessionId == newItem.sessionId

            override fun areContentsTheSame(
                oldItem: EcgSessionSummaryDto,
                newItem: EcgSessionSummaryDto
            ): Boolean = oldItem == newItem
        }
    }
}
