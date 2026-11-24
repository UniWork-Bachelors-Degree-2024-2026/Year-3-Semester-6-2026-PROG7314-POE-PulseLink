package com.example.prog7314_poepart23.data.repository

import android.content.Context
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiClient
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiService
import com.example.prog7314_poepart23.data.remote.models.SendSupportMessageDto
import com.example.prog7314_poepart23.data.remote.models.SupportChatMessageDto
import com.example.prog7314_poepart23.data.remote.models.SupportChatThreadDto
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Coordinates real-time support messages between Patients (Help & Support -> Live Chat) and
 * Nurses (Nurse Dashboard / Support tab). Uses PulseLinkAPI (/api/support-chat) when available,
 * and persists a shared on-device conversation store so local multi-account workflows and offline
 * periods continue working seamlessly before or between API publishes.
 */
class SupportChatRepository(
    private val api: PulseLinkApiService = PulseLinkApiClient.apiService,
    private val refreshToken: suspend () -> String? = {
        PulseLinkApiClient.getAuthToken()?.takeIf { it.isNotBlank() }
            ?: try { UserRepository().refreshAuthToken() } catch (_: Exception) { null }
    }
) {
    private val gson = Gson()

    suspend fun loadThreads(context: Context): List<SupportChatThreadDto> = withContext(Dispatchers.IO) {
        val local = readLocalThreads(context)
        try {
            refreshToken()
            val response = api.getSupportChatThreads()
            if (response.isSuccessful && response.body() != null) {
                val remote = response.body()!!
                if (remote.isNotEmpty()) {
                    mergeThreadsIntoLocal(context, remote)
                    return@withContext readLocalThreads(context)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
        local
    }

    suspend fun loadMessages(context: Context, threadId: String): List<SupportChatMessageDto> =
        withContext(Dispatchers.IO) {
            val local = readLocalMessages(context, threadId)
            try {
                refreshToken()
                val response = api.getSupportChatMessages(threadId)
                if (response.isSuccessful && response.body() != null) {
                    val remote = response.body()!!
                    if (remote.isNotEmpty()) {
                        mergeMessagesIntoLocal(context, threadId, remote)
                        return@withContext readLocalMessages(context, threadId)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
            local
        }

    suspend fun sendMessage(
        context: Context,
        threadId: String,
        patientName: String,
        senderUid: String,
        senderName: String,
        senderRole: String,
        text: String
    ): SupportChatMessageDto = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        val now = System.currentTimeMillis()
        val localDraft = SupportChatMessageDto(
            id = "LOCAL-${UUID.randomUUID().toString().take(12)}",
            threadId = threadId,
            patientName = patientName.ifBlank { "Clinic Patient" },
            senderUid = senderUid,
            senderName = senderName.ifBlank { if (senderRole == "nurse") "Clinic Nurse" else "Patient" },
            senderRole = senderRole,
            text = trimmed,
            timestampMillis = now
        )

        var authoritative = localDraft
        try {
            refreshToken()
            val response = api.sendSupportChatMessage(
                threadId,
                SendSupportMessageDto(text = trimmed, patientName = patientName.ifBlank { null })
            )
            if (response.isSuccessful && response.body() != null) {
                authoritative = response.body()!!
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }

        appendLocalMessage(context, authoritative)
        authoritative
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readLocalThreads(context: Context): List<SupportChatThreadDto> {
        val raw = prefs(context).getString(KEY_THREADS_JSON, null) ?: return emptyList()
        val type = object : TypeToken<List<SupportChatThreadDto>>() {}.type
        return runCatching { gson.fromJson<List<SupportChatThreadDto>>(raw, type) }.getOrNull()
            .orEmpty()
            .sortedByDescending { it.lastTimestampMillis }
    }

    private fun readLocalMessages(context: Context, threadId: String): List<SupportChatMessageDto> {
        val raw = prefs(context).getString(KEY_MESSAGES_PREFIX + threadId, null) ?: return emptyList()
        val type = object : TypeToken<List<SupportChatMessageDto>>() {}.type
        return runCatching { gson.fromJson<List<SupportChatMessageDto>>(raw, type) }.getOrNull()
            .orEmpty()
            .sortedBy { it.timestampMillis }
    }

    private fun saveLocalThreads(context: Context, threads: List<SupportChatThreadDto>) {
        prefs(context).edit()
            .putString(KEY_THREADS_JSON, gson.toJson(threads.sortedByDescending { it.lastTimestampMillis }))
            .apply()
    }

    private fun saveLocalMessages(context: Context, threadId: String, messages: List<SupportChatMessageDto>) {
        prefs(context).edit()
            .putString(KEY_MESSAGES_PREFIX + threadId, gson.toJson(messages.sortedBy { it.timestampMillis }))
            .apply()
    }

    private fun mergeThreadsIntoLocal(context: Context, remote: List<SupportChatThreadDto>) {
        val map = readLocalThreads(context).associateBy { it.threadId }.toMutableMap()
        remote.forEach { item ->
            val current = map[item.threadId]
            if (current == null || item.lastTimestampMillis >= current.lastTimestampMillis) {
                map[item.threadId] = item
            }
        }
        saveLocalThreads(context, map.values.toList())
    }

    private fun mergeMessagesIntoLocal(context: Context, threadId: String, remote: List<SupportChatMessageDto>) {
        val existing = readLocalMessages(context, threadId).toMutableList()
        val seenIds = existing.map { it.id }.toMutableSet()
        remote.forEach { msg ->
            // Replace optimistic local copy if identical sender + text within 10s
            existing.removeAll { local ->
                local.id.startsWith("LOCAL-") &&
                    local.senderUid == msg.senderUid &&
                    local.text == msg.text &&
                    kotlin.math.abs(local.timestampMillis - msg.timestampMillis) < 15_000L
            }
            if (seenIds.add(msg.id)) {
                existing.add(msg)
            }
        }
        saveLocalMessages(context, threadId, existing)
    }

    private fun appendLocalMessage(context: Context, message: SupportChatMessageDto) {
        val messages = readLocalMessages(context, message.threadId).toMutableList()
        if (messages.none { it.id == message.id }) {
            messages.add(message)
        }
        saveLocalMessages(context, message.threadId, messages)

        val threads = readLocalThreads(context).associateBy { it.threadId }.toMutableMap()
        threads[message.threadId] = SupportChatThreadDto(
            threadId = message.threadId,
            patientName = message.patientName,
            lastMessage = message.text,
            lastTimestampMillis = message.timestampMillis,
            lastSenderRole = message.senderRole
        )
        saveLocalThreads(context, threads.values.toList())
    }

    companion object {
        private const val PREFS_NAME = "pulselink_support_chats_v1"
        private const val KEY_THREADS_JSON = "threads_json"
        private const val KEY_MESSAGES_PREFIX = "messages_"
    }
}
