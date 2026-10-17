package com.nik.voiceassistant.data.history

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.nik.voiceassistant.TranscriptItem
import java.io.File
import java.util.UUID

data class ConversationSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "New Conversation",
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    val messages: MutableList<TranscriptItem> = mutableListOf(),
    var resumeHandle: String? = null,
    var resumeAt: Long = 0L
)

/**
 * Saved conversations, one JSON file each, in the app's private storage. Every call does disk I/O:
 * call them off the main thread. Writes are atomic (a temp file renamed over the old one), so a
 * process killed mid-write never leaves a broken conversation behind.
 */
object ConversationHistoryManager {
    private const val TAG = "HistoryManager"
    private const val DIR_NAME = "conversation_history"
    // Oldest lines of a very long conversation are dropped from its file
    const val MAX_MESSAGES = 1000
    private val gson = Gson()

    private fun getHistoryDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Synchronized
    fun getAllSessions(context: Context): List<ConversationSession> {
        val files = getHistoryDir(context).listFiles { _, name -> name.endsWith(".json") }.orEmpty()
        return files.mapNotNull { read(it) }
            // Only sessions with actual conversation content (user or assistant speech)
            .filter { it.messages.any { m -> m.sender == "user" || m.sender == "assistant" } }
            .sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun getSession(context: Context, id: String): ConversationSession? {
        val file = File(getHistoryDir(context), "$id.json")
        return if (file.exists()) read(file) else null
    }

    /** A file as a session, with fields Gson may have left null (old or hand-edited files) filled in. */
    private fun read(file: File): ConversationSession? = try {
        @Suppress("SENSELESS_COMPARISON", "UNNECESSARY_SAFE_CALL")
        gson.fromJson(file.readText(), ConversationSession::class.java)?.let { s ->
            if (s.id == null) return null
            val messages = (s.messages ?: mutableListOf()).filter { it != null && it.text != null && it.sender != null }
            ConversationSession(
                id = s.id,
                title = s.title ?: "New Conversation",
                createdAt = s.createdAt,
                updatedAt = s.updatedAt,
                messages = messages.map { if (it.id == null || it.timeFormatted == null) TranscriptItem(sender = it.sender, text = it.text) else it }.toMutableList(),
                resumeHandle = s.resumeHandle,
                resumeAt = s.resumeAt
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unreadable history file ${file.name}: ${e.javaClass.simpleName}")
        null
    }

    /** Saves a snapshot of a session (not one still being changed on another thread). */
    @Synchronized
    fun saveSession(context: Context, session: ConversationSession) {
        if (session.messages.isEmpty()) return
        try {
            val trimmed = if (session.messages.size > MAX_MESSAGES) session.copy(messages = session.messages.takeLast(MAX_MESSAGES).toMutableList()) else session
            val dir = getHistoryDir(context)
            val tmp = File(dir, "${session.id}.json.tmp")
            tmp.writeText(gson.toJson(trimmed))
            if (!tmp.renameTo(File(dir, "${session.id}.json"))) {
                File(dir, "${session.id}.json").writeText(gson.toJson(trimmed))
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session ${session.id}: ${e.javaClass.simpleName}")
        }
    }

    /** The title for a session still called "New Conversation": its first request. */
    fun autoTitle(session: ConversationSession): String {
        if (session.title != "New Conversation" && session.title.isNotBlank()) return session.title
        val firstUserMsg = session.messages.firstOrNull { it.sender == "user" }?.text?.trim()
        if (firstUserMsg.isNullOrBlank()) return session.title
        val cleanTitle = firstUserMsg.replace(Regex("^(?i)(hi|hey|hello)?\\s*(shiv|nik|assistant)[,\\s]*"), "").trim()
        val candidate = cleanTitle.ifBlank { firstUserMsg }
        return if (candidate.length > 36) candidate.take(36) + "…" else candidate
    }

    @Synchronized
    fun deleteSession(context: Context, id: String): Boolean {
        val file = File(getHistoryDir(context), "$id.json")
        return if (file.exists()) file.delete() else false
    }

    @Synchronized
    fun clearAll(context: Context) {
        getHistoryDir(context).listFiles()?.forEach { it.delete() }
    }

    /** Deletes conversations not touched for this many days (0 = keep all), and stray temp files. */
    @Synchronized
    fun prune(context: Context, days: Int) {
        val dir = getHistoryDir(context)
        dir.listFiles { _, name -> name.endsWith(".tmp") }?.forEach { it.delete() }
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        dir.listFiles { _, name -> name.endsWith(".json") }?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    fun createSession(initialTitle: String = "New Conversation"): ConversationSession =
        ConversationSession(id = UUID.randomUUID().toString(), title = initialTitle)
}
