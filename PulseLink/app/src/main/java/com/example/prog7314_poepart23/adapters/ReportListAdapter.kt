/**
 * PulseLink Diagnostic Clinical Report Recycler List Adapter
 *
 * Facilitates reactive list binding for medical PDF diagnostic summaries, signing statuses,
 * and download actions (Google Developers, 2024; HPCSA, 2021).
 *
 * Reference List:
 * - Google Developers, 2024. Create dynamic lists with RecyclerView: ListAdapter and DiffUtil. Android Developers. Available at: <https://developer.android.com/develop/ui/views/layout/recyclerview> [Accessed 18 September 2026].
 * - Health Professions Council of South Africa (HPCSA), 2021. Guidelines for Good Practice in the Health Care Professions: Ethical and Professional Rules. Pretoria: HPCSA.
 */

package com.example.prog7314_poepart23.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.data.models.ReportStatus
import com.example.prog7314_poepart23.data.models.ReportSummary
import com.example.prog7314_poepart23.databinding.ItemReportCardBinding

class ReportListAdapter(
    private val onReportClick: (ReportSummary) -> Unit,
    private val onDownloadClick: (ReportSummary) -> Unit
) : ListAdapter<ReportSummary, ReportListAdapter.ReportViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReportViewHolder {
        val binding = ItemReportCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ReportViewHolder(binding, onReportClick, onDownloadClick)
    }

    override fun onBindViewHolder(holder: ReportViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ReportViewHolder(
        private val binding: ItemReportCardBinding,
        private val onReportClick: (ReportSummary) -> Unit,
        private val onDownloadClick: (ReportSummary) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(report: ReportSummary) {
            val context = binding.root.context

            binding.tvPatientName.text = report.patientName
            binding.tvReportDate.text = report.reportDateLabel
            binding.tvDuration.text = context.getString(
                R.string.report_duration_format,
                report.sessionDurationLabel
            )

            val (pillRes, textColorRes, label) = when (report.status) {
                ReportStatus.PENDING -> Triple(
                    R.drawable.bg_pill_warning, R.color.pulse_warning, R.string.report_status_pending
                )
                ReportStatus.FLAGGED -> Triple(
                    R.drawable.bg_pill_danger, R.color.pulse_danger, R.string.report_status_flagged
                )
                ReportStatus.SIGNED_OFF -> Triple(
                    R.drawable.bg_pill_success, R.color.pulse_success, R.string.report_status_signed_off
                )
            }

            binding.chipStatus.setBackgroundResource(pillRes)
            binding.chipStatus.setTextColor(context.getColor(textColorRes))
            binding.chipStatus.text = context.getString(label)

            binding.root.setOnClickListener { onReportClick(report) }
            binding.btnDownload.setOnClickListener { onDownloadClick(report) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ReportSummary>() {
            override fun areItemsTheSame(oldItem: ReportSummary, newItem: ReportSummary): Boolean =
                oldItem.reportId == newItem.reportId

            override fun areContentsTheSame(oldItem: ReportSummary, newItem: ReportSummary): Boolean =
                oldItem == newItem
        }
    }
}
