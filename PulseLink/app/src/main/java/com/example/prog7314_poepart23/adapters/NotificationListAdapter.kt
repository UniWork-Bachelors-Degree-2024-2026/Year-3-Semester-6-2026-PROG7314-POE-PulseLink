/**
 * PulseLink Notification Recycler List Adapter
 *
 * Implements asynchronous item diffing and binds patient notifications,
 * diagnostic alerts, and clinical status updates (Google Developers, 2024).
 *
 * Reference List:
 * - Google Developers, 2024. Create dynamic lists with RecyclerView: ListAdapter and DiffUtil. Android Developers. Available at: <https://developer.android.com/develop/ui/views/layout/recyclerview> [Accessed 18 September 2026].
 */

package com.example.prog7314_poepart23.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.prog7314_poepart23.data.models.NotificationItem
import com.example.prog7314_poepart23.databinding.ItemNotificationBinding

class NotificationListAdapter(
    private val onNotificationClick: (NotificationItem) -> Unit
) : ListAdapter<NotificationItem, NotificationListAdapter.NotificationViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificationViewHolder {
        val binding = ItemNotificationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return NotificationViewHolder(binding, onNotificationClick)
    }

    override fun onBindViewHolder(holder: NotificationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class NotificationViewHolder(
        private val binding: ItemNotificationBinding,
        private val onNotificationClick: (NotificationItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(notification: NotificationItem) {
            binding.tvTitle.text = notification.title
            binding.tvMessagePreview.text = notification.messagePreview
            binding.tvTimestamp.text = notification.timestampLabel
            binding.ivIcon.setImageResource(notification.iconRes)
            binding.ivUnreadDot.visibility = if (notification.isUnread) View.VISIBLE else View.INVISIBLE

            binding.root.setOnClickListener { onNotificationClick(notification) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<NotificationItem>() {
            override fun areItemsTheSame(
                oldItem: NotificationItem,
                newItem: NotificationItem
            ): Boolean = oldItem.notificationId == newItem.notificationId

            override fun areContentsTheSame(
                oldItem: NotificationItem,
                newItem: NotificationItem
            ): Boolean = oldItem == newItem
        }
    }
}
