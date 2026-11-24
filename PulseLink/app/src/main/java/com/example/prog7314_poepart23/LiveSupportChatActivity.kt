package com.example.prog7314_poepart23

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.prog7314_poepart23.data.remote.models.SupportChatMessageDto
import com.example.prog7314_poepart23.data.remote.models.SupportChatThreadDto
import com.example.prog7314_poepart23.data.repository.SupportChatRepository
import com.example.prog7314_poepart23.data.repository.UserRepository
import com.example.prog7314_poepart23.security.SecurePreferences
import com.example.prog7314_poepart23.utils.BottomNavHelper
import com.example.prog7314_poepart23.utils.HapticFeedbackManager
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.utils.SessionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LiveSupportChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_THREAD_ID = "extra_support_thread_id"
        const val EXTRA_PATIENT_NAME = "extra_support_patient_name"
        private const val POLL_INTERVAL_MS = 4000L
    }

    private val chatRepo = SupportChatRepository()
    private val userRepo = UserRepository()

    private lateinit var tvChatTitle: TextView
    private lateinit var tvChatSubtitle: TextView
    private lateinit var btnSwitchChatThread: TextView
    private lateinit var scrollChatMessages: ScrollView
    private lateinit var layoutChatMessages: LinearLayout
    private lateinit var tvChatEmptyState: TextView
    private lateinit var scrollNurseQuickReplies: View
    private lateinit var etChatMessage: EditText
    private lateinit var btnSendChatMessage: View
    private lateinit var containerNurseChatBottomNav: View

    private var currentUid: String = ""
    private var currentName: String = ""
    private var currentRole: String = SessionManager.ROLE_PATIENT
    private var isNurse: Boolean = false
    private var activeThreadId: String = ""
    private var activePatientName: String = ""
    private var availableThreads: List<SupportChatThreadDto> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_live_support_chat)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        HapticFeedbackManager.initialise(this)

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            finish()
            return
        }
        currentUid = user.uid
        currentName = user.displayName.orEmpty().ifBlank { user.email.orEmpty() }
        currentRole = SessionManager.getCachedRole(this, currentUid)
            ?: SecurePreferences.getInstance(this).getUserRole()?.lowercase(Locale.ROOT)
            ?: SessionManager.ROLE_PATIENT
        isNurse = currentRole == SessionManager.ROLE_NURSE || currentRole == SessionManager.ROLE_ADMINISTRATOR

        bindViews()
        setupRoleUi()
        wireControls()
        resolveCurrentNameAndLoad()
    }

    private fun bindViews() {
        tvChatTitle = findViewById(R.id.tvChatTitle)
        tvChatSubtitle = findViewById(R.id.tvChatSubtitle)
        btnSwitchChatThread = findViewById(R.id.btnSwitchChatThread)
        scrollChatMessages = findViewById(R.id.scrollChatMessages)
        layoutChatMessages = findViewById(R.id.layoutChatMessages)
        tvChatEmptyState = findViewById(R.id.tvChatEmptyState)
        scrollNurseQuickReplies = findViewById(R.id.scrollNurseQuickReplies)
        etChatMessage = findViewById(R.id.etChatMessage)
        btnSendChatMessage = findViewById(R.id.btnSendChatMessage)
        containerNurseChatBottomNav = findViewById(R.id.containerNurseChatBottomNav)
    }

    private fun setupRoleUi() {
        if (isNurse) {
            tvChatTitle.setText(R.string.support_chat_title_nurse)
            scrollNurseQuickReplies.visibility = View.VISIBLE
            containerNurseChatBottomNav.visibility = View.VISIBLE
            BottomNavHelper.setUpNurseNav(this, BottomNavHelper.NurseTab.SUPPORT)

            val explicitThread = intent.getStringExtra(EXTRA_THREAD_ID)
            val explicitName = intent.getStringExtra(EXTRA_PATIENT_NAME)
            if (!explicitThread.isNullOrBlank()) {
                activeThreadId = explicitThread
                activePatientName = explicitName.orEmpty()
            } else {
                val prefs = SecurePreferences.getInstance(this)
                activeThreadId = prefs.getSelectedNursePatientId().orEmpty()
                activePatientName = prefs.getSelectedNursePatientName().orEmpty()
            }
        } else {
            tvChatTitle.setText(R.string.support_chat_title_patient)
            tvChatSubtitle.setText(R.string.support_chat_subtitle_patient)
            scrollNurseQuickReplies.visibility = View.GONE
            containerNurseChatBottomNav.visibility = View.GONE
            activeThreadId = currentUid
            activePatientName = currentName
        }
    }

    private fun wireControls() {
        findViewById<View>(R.id.btnBackChat).setOnClickListener {
            HapticFeedbackManager.lightTick()
            finish()
        }

        findViewById<View>(R.id.btnChatEmergencyCall).setOnClickListener {
            HapticFeedbackManager.warningBuzz()
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + getString(R.string.emergency_number_general))))
        }

        btnSwitchChatThread.setOnClickListener {
            HapticFeedbackManager.lightTick()
            showThreadPickerDialog()
        }

        findViewById<TextView>(R.id.btnQuickReply1).setOnClickListener {
            sendChatText(getString(R.string.support_chat_quick_1))
        }
        findViewById<TextView>(R.id.btnQuickReply2).setOnClickListener {
            sendChatText(getString(R.string.support_chat_quick_2))
        }
        findViewById<TextView>(R.id.btnQuickReply3).setOnClickListener {
            sendChatText(getString(R.string.support_chat_quick_3))
        }

        btnSendChatMessage.setOnClickListener {
            val text = etChatMessage.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            etChatMessage.text.clear()
            sendChatText(text)
        }
    }

    private fun resolveCurrentNameAndLoad() {
        lifecycleScope.launch {
            val profile = userRepo.getUserProfile(currentUid)
            if (profile is Resource.Success && !profile.data?.displayName.isNullOrBlank()) {
                currentName = profile.data!!.displayName
                if (!isNurse) activePatientName = currentName
            }
            refreshConversation(scrollToBottom = true)

            while (isActive) {
                delay(POLL_INTERVAL_MS)
                refreshConversation(scrollToBottom = false)
            }
        }
    }

    private suspend fun refreshConversation(scrollToBottom: Boolean) {
        if (isNurse) {
            availableThreads = chatRepo.loadThreads(this)
            if (availableThreads.isNotEmpty()) {
                btnSwitchChatThread.visibility = View.VISIBLE
                btnSwitchChatThread.text = getString(R.string.support_chat_switch_thread, availableThreads.size)
                if (activeThreadId.isBlank() || availableThreads.none { it.threadId == activeThreadId }) {
                    activeThreadId = availableThreads.first().threadId
                    activePatientName = availableThreads.first().patientName
                }
            } else {
                btnSwitchChatThread.visibility = View.GONE
            }
            tvChatSubtitle.text = if (activePatientName.isNotBlank()) {
                getString(R.string.support_chat_subtitle_nurse_thread, activePatientName)
            } else {
                getString(R.string.support_chat_no_threads)
            }
        }

        val messages = if (activeThreadId.isNotBlank()) {
            chatRepo.loadMessages(this, activeThreadId)
        } else {
            emptyList()
        }
        renderMessages(messages, scrollToBottom)
    }

    private fun sendChatText(text: String) {
        if (activeThreadId.isBlank()) {
            Toast.makeText(this, getString(R.string.support_chat_no_threads), Toast.LENGTH_SHORT).show()
            return
        }
        HapticFeedbackManager.confirmClick()
        lifecycleScope.launch {
            chatRepo.sendMessage(
                context = this@LiveSupportChatActivity,
                threadId = activeThreadId,
                patientName = activePatientName.ifBlank { currentName },
                senderUid = currentUid,
                senderName = currentName,
                senderRole = currentRole,
                text = text
            )
            refreshConversation(scrollToBottom = true)
        }
    }

    private fun showThreadPickerDialog() {
        if (availableThreads.isEmpty()) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_select_support_thread, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.layoutThreadCardsContainer)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelThreadDialog)

        val dialog = AppCompatDialog(
            this,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
        ).apply {
            supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(dialogView)
            window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                val dm = resources.displayMetrics
                val maxWidth = (500 * dm.density).toInt()
                setLayout(
                    minOf(dm.widthPixels - (24 * dm.density).toInt(), maxWidth),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        }

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        availableThreads.forEach { thread ->
            val isSelected = thread.threadId == activeThreadId
            val displayPatientName = thread.patientName.ifBlank { thread.threadId }
            val initials = displayPatientName
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.take(1).uppercase(Locale.getDefault()) }
                .ifBlank { "P" }

            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                radius = 14f * density
                cardElevation = 0f
                setCardBackgroundColor(
                    ContextCompat.getColor(
                        context,
                        if (isSelected) R.color.pulse_blue_bg else R.color.pulse_bg
                    )
                )
                strokeWidth = dp(if (isSelected) 2 else 1)
                strokeColor = ContextCompat.getColor(
                    context,
                    if (isSelected) R.color.pulse_primary else R.color.pulse_border
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    HapticFeedbackManager.lightTick()
                    activeThreadId = thread.threadId
                    activePatientName = displayPatientName
                    dialog.dismiss()
                    lifecycleScope.launch { refreshConversation(scrollToBottom = true) }
                }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }

            val avatar = TextView(this).apply {
                text = initials
                textSize = 13f
                gravity = Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.pulse_picker_accent))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(context, R.color.pulse_card))
                }
            }
            row.addView(avatar, LinearLayout.LayoutParams(dp(40), dp(40)))

            val contentCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                }
            }

            val headerLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tvName = TextView(this).apply {
                text = displayPatientName
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.pulse_text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvTime = TextView(this).apply {
                text = if (thread.lastTimestampMillis > 0L) timeFormat.format(Date(thread.lastTimestampMillis)) else ""
                textSize = 11f
                setTextColor(ContextCompat.getColor(context, R.color.pulse_text_secondary))
                setPadding(dp(8), 0, 0, 0)
            }
            headerLine.addView(tvName)
            headerLine.addView(tvTime)
            contentCol.addView(headerLine)

            val senderPrefix = if (thread.lastSenderRole == SessionManager.ROLE_NURSE) {
                getString(R.string.support_chat_sender_nurse)
            } else {
                getString(R.string.support_chat_sender_patient)
            }
            val tvPreview = TextView(this).apply {
                text = "$senderPrefix: ${thread.lastMessage.ifBlank { getString(R.string.support_chat_empty) }}"
                textSize = 13f
                maxLines = 3
                setTextColor(ContextCompat.getColor(context, R.color.pulse_text_secondary))
                setPadding(0, dp(4), 0, 0)
            }
            contentCol.addView(tvPreview)

            row.addView(contentCol)
            card.addView(row)
            container.addView(card)
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun renderMessages(messages: List<SupportChatMessageDto>, scrollToBottom: Boolean) {
        layoutChatMessages.removeAllViews()
        if (messages.isEmpty()) {
            tvChatEmptyState.visibility = View.VISIBLE
            tvChatEmptyState.setText(if (isNurse && activeThreadId.isBlank()) R.string.support_chat_no_threads else R.string.support_chat_empty)
            return
        }
        tvChatEmptyState.visibility = View.GONE
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        messages.forEach { msg ->
            val fromMe = msg.senderUid == currentUid ||
                (isNurse && msg.senderRole == SessionManager.ROLE_NURSE) ||
                (!isNurse && msg.senderRole == SessionManager.ROLE_PATIENT)

            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (fromMe) Gravity.END else Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
            }

            val senderLabel = TextView(this).apply {
                val roleDisplay = when {
                    msg.senderUid == currentUid -> getString(R.string.support_chat_sender_you)
                    msg.senderRole == SessionManager.ROLE_NURSE -> getString(R.string.support_chat_sender_nurse)
                    else -> getString(R.string.support_chat_sender_patient)
                }
                text = "${msg.senderName.ifBlank { roleDisplay }} - ${timeFormat.format(Date(msg.timestampMillis))}"
                textSize = 11f
                setTextColor(ContextCompat.getColor(context, R.color.pulse_text_secondary))
                setPadding(dp(4), 0, dp(4), dp(3))
            }

            val bubble = TextView(this).apply {
                text = msg.text
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, if (fromMe) R.color.white else R.color.pulse_text_primary))
                setPadding(dp(14), dp(10), dp(14), dp(10))
                maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    if (fromMe) {
                        setColor(ContextCompat.getColor(context, R.color.pulse_primary_fill))
                    } else {
                        setColor(ContextCompat.getColor(context, R.color.pulse_card))
                        setStroke(dp(1), ContextCompat.getColor(context, R.color.pulse_border))
                    }
                }
            }

            val wrapParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            wrapper.addView(senderLabel, wrapParams)
            wrapper.addView(bubble, wrapParams)
            layoutChatMessages.addView(wrapper)
        }

        if (scrollToBottom) {
            scrollChatMessages.post { scrollChatMessages.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
