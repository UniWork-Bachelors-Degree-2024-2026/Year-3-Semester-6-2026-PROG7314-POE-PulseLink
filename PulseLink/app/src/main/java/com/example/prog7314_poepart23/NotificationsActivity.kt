/**
 * PulseLink Notifications & Clinical Alert Feed Activity
 *
 * Displays patient in-app notifications, clinical notes, anomaly alerts,
 * and PDF diagnostic report delivery records via reactive Flow collection (Google Developers, 2024).
 *
 * Reference List:
 * - Google Developers, 2024. View Binding: Use View Binding in Activities. Android Developers. Available at: <https://developer.android.com/topic/libraries/view-binding> [Accessed 18 September 2026].
 * - Google Developers, 2024. Create dynamic lists with RecyclerView. Android Developers. Available at: <https://developer.android.com/develop/ui/views/layout/recyclerview> [Accessed 18 September 2026].
 * - JetBrains, 2024. StateFlow and SharedFlow: Asynchronous Cold and Hot Flows. Kotlin Documentation. Available at: <https://kotlinlang.org/docs/flow.html> [Accessed 18 September 2026].
 */

package com.example.prog7314_poepart23

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.prog7314_poepart23.adapters.NotificationListAdapter
import com.example.prog7314_poepart23.data.repository.NotificationRepository
import com.example.prog7314_poepart23.databinding.PatientNotificationsBinding
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NotificationsActivity : AppCompatActivity() {

    private lateinit var binding: PatientNotificationsBinding
    private lateinit var notificationListAdapter: NotificationListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = PatientNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configure system insets (Google Developers, 2024)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        runCatching { NotificationRepository.initialise(this) }
        setUpTopBar()
        setUpNotificationList()
        setUpBottomNav()
        observeNotifications()
    }

    private fun setUpTopBar() {
        binding.ivBack.setOnClickListener {
            HapticFeedbackManager.lightTick()
            finish()
        }

        binding.btnMarkAllRead.setOnClickListener {
            HapticFeedbackManager.confirmClick()
            NotificationRepository.markAllAsRead()
            Toast.makeText(this, getString(R.string.action_marked_all_read), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setUpNotificationList() {
        notificationListAdapter = NotificationListAdapter { notification ->
            HapticFeedbackManager.lightTick()
            NotificationRepository.markAsRead(notification.notificationId)
            startActivity(
                Intent(this, NotificationDetailsActivity::class.java)
                    .putExtra(EXTRA_NOTIFICATION_ID, notification.notificationId)
            )
        }
        binding.rvNotifications.layoutManager = LinearLayoutManager(this)
        binding.rvNotifications.adapter = notificationListAdapter
        binding.rvNotifications.setHasFixedSize(true)
    }

    private fun setUpBottomNav() {
        if (com.example.prog7314_poepart23.security.SecurePreferences.getInstance(this).getUserRole().equals("doctor", true)) {
            (findViewById<android.view.View>(R.id.navPatientDashboard).parent as android.view.View).visibility = android.view.View.GONE
        } else BottomNavHelper.setUpPatientNav(this, BottomNavHelper.PatientTab.DASHBOARD)
    }

    private fun observeNotifications() {
        lifecycleScope.launch {
            NotificationRepository.notifications.collectLatest { list ->
                notificationListAdapter.submitList(list)
            }
        }
    }

    companion object {
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}
