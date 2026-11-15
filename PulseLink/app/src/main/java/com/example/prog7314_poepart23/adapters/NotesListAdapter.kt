/**
 * PulseLink Doctor Clinical Notes Recycler List Adapter
 *
 * Facilitates reactive list binding for medical practitioner clinical impressions,
 * diagnosis notes, and follow-up recommendations (Google Developers, 2024; HPCSA, 2021).
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
import com.example.prog7314_poepart23.data.remote.models.DoctorNoteDto
import com.example.prog7314_poepart23.databinding.ItemNoteCardBinding

class NotesListAdapter(
    private val onNoteClick: (DoctorNoteDto) -> Unit
) : ListAdapter<DoctorNoteDto, NotesListAdapter.NoteViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val binding = ItemNoteCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return NoteViewHolder(binding, onNoteClick)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class NoteViewHolder(
        private val binding: ItemNoteCardBinding,
        private val onNoteClick: (DoctorNoteDto) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(note: DoctorNoteDto) {
            val context = binding.root.context

            binding.tvDoctorName.text = note.doctorName.ifBlank { context.getString(R.string.l10n_unknown_doctor) }

            binding.tvTimestamp.text = com.example.prog7314_poepart23.utils.DateTimeFormatter.formatIsoTimestamp(note.createdAtUtc)
            binding.tvNoteText.text = note.clinicalImpression

            val rec = note.recommendations?.trim().orEmpty()
            if (rec.isNotEmpty()) {
                binding.chipRecommendation.visibility = android.view.View.VISIBLE
                binding.chipRecommendation.text = rec.uppercase(java.util.Locale.UK)
            } else if (note.followUpRequired) {
                binding.chipRecommendation.visibility = android.view.View.VISIBLE
                binding.chipRecommendation.text = context.getString(R.string.note_follow_up_required)
            } else {
                binding.chipRecommendation.visibility = android.view.View.GONE
            }

            binding.root.setOnClickListener { onNoteClick(note) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DoctorNoteDto>() {
            override fun areItemsTheSame(oldItem: DoctorNoteDto, newItem: DoctorNoteDto): Boolean =
                oldItem.noteId == newItem.noteId

            override fun areContentsTheSame(oldItem: DoctorNoteDto, newItem: DoctorNoteDto): Boolean =
                oldItem == newItem
        }
    }
}
