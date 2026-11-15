package com.example.prog7314_poepart23.messaging

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PulseLinkMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        ClinicalNotificationSync.enqueue(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (message.data["recipientUid"] != uid) return
        ClinicalNotificationSync.enqueue(applicationContext)
    }
}
