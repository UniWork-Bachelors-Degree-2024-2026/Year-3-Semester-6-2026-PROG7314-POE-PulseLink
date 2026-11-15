/*
 * Repository managing the user-scoped in-app notification inbox, clinical alert records,
 * persistent unread/deleted statuses, and push notification synchronisation.
 *
 * Reference List:
 * - Google Developers, 2024. Guide to app architecture. [online] Available at: <https://developer.android.com/topic/architecture> [Accessed 21 September 2026].
 * - JetBrains, 2024. StateFlow and SharedFlow - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.data.repository

import android.content.Context
import com.example.prog7314_poepart23.data.local.InMemoryOfflineQueueStore
import com.example.prog7314_poepart23.data.local.OfflineEcgDatabase
import com.example.prog7314_poepart23.data.local.OfflineQueueStore
import com.example.prog7314_poepart23.data.models.NotificationItem
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiClient
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow   // (JetBrains, 2024)
import kotlinx.coroutines.flow.StateFlow          // (JetBrains, 2024)
import kotlinx.coroutines.flow.asStateFlow         // (JetBrains, 2024)

object NotificationRepository { // (Google Developers, 2024)

    private const val FALLBACK_UNSCOPED_OWNER = "__active_local_session__"

    @Volatile
    private var store: OfflineQueueStore = InMemoryOfflineQueueStore()

    @Volatile
    private var boundUid: String? = null

    @Volatile
    private var uidProvider: () -> String? = {
        PulseLinkApiClient.getActiveUid() ?: try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (_: Exception) {
            null
        }
    }

    private val _notifications = MutableStateFlow<List<NotificationItem>>(emptyList()) // (JetBrains, 2024)
    val notifications: StateFlow<List<NotificationItem>> = _notifications.asStateFlow() // (JetBrains, 2024)

    /**
     * Initialises durable SQLite-backed notification persistence and restores the active user's inbox.
     */
    @Synchronized
    fun initialise(context: Context, activeUid: String? = null) {
        val db = OfflineEcgDatabase.getInstance(context.applicationContext)
        store = db
        val resolvedUid = activeUid ?: uidProvider()
        bindUser(resolvedUid)
    }

    /**
     * Allows unit tests or custom environments to supply a dedicated [OfflineQueueStore] and UID provider.
     */
    @Synchronized
    fun configureForTesting(
        customStore: OfflineQueueStore,
        customUidProvider: () -> String?
    ) {
        store = customStore
        uidProvider = customUidProvider
        boundUid = customUidProvider()
        refreshFromStore()
    }

    /**
     * Switches the active inbox scope to [uid] and loads that user's durable notifications.
     */
    @Synchronized
    fun bindUser(uid: String?) {
        val cleaned = uid?.trim()?.takeIf { it.isNotEmpty() }
        boundUid = cleaned
        if (cleaned == null) {
            _notifications.value = emptyList()
        } else {
            _notifications.value = store.getNotificationsForUser(cleaned)
        }
    }

    private fun resolveOwnerUid(explicitOwnerUid: String? = null): String {
        val explicit = explicitOwnerUid?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) {
            if (boundUid == null) boundUid = explicit
            return explicit
        }
        val active = uidProvider()?.trim()?.takeIf { it.isNotEmpty() }
        if (active != null) {
            if (boundUid != active) {
                boundUid = active
            }
            return active
        }
        return boundUid ?: FALLBACK_UNSCOPED_OWNER
    }

    @Synchronized
    private fun refreshFromStore(ownerUid: String = resolveOwnerUid()) {
        val active = uidProvider()?.trim()?.takeIf { it.isNotEmpty() } ?: boundUid ?: ownerUid
        if (active == ownerUid) {
            _notifications.value = store.getNotificationsForUser(ownerUid)
        }
    }

    @Synchronized
    fun addNotification(item: NotificationItem, ownerUid: String? = null, createdAtMillis: Long = System.currentTimeMillis()) {
        val targetOwner = resolveOwnerUid(ownerUid)
        store.upsertNotification(targetOwner, item, createdAtMillis)
        refreshFromStore(targetOwner)
    }

    @Synchronized
    fun markAllAsRead() {
        val targetOwner = resolveOwnerUid()
        store.markAllNotificationsRead(targetOwner)
        refreshFromStore(targetOwner)
    }

    @Synchronized
    fun markAsRead(notificationId: String) {
        val targetOwner = resolveOwnerUid()
        store.markNotificationRead(targetOwner, notificationId)
        refreshFromStore(targetOwner)
    }

    @Synchronized
    fun deleteNotification(notificationId: String) {
        val targetOwner = resolveOwnerUid()
        store.deleteNotification(targetOwner, notificationId)
        refreshFromStore(targetOwner)
    }

    @Synchronized
    fun getNotificationById(notificationId: String): NotificationItem? {
        val targetOwner = resolveOwnerUid()
        return store.getNotificationById(targetOwner, notificationId)
            ?: _notifications.value.find { it.notificationId == notificationId }
    }

    /**
     * Clears the in-memory notification feed for the signed-out session so another user signing in
     * on the same device never sees the previous user's notifications. Durable SQLite rows remain
     * user-scoped under their respective `owner_uid` so signing back in restores read/inbox state.
     */
    @Synchronized
    fun clear() {
        if (boundUid == FALLBACK_UNSCOPED_OWNER || boundUid == null) {
            // Clean up unscoped fallback entries used by legacy/unauthenticated test calls
            for (item in _notifications.value) {
                store.deleteNotification(FALLBACK_UNSCOPED_OWNER, item.notificationId)
            }
        }
        boundUid = null
        _notifications.value = emptyList()
    }
}
