package com.nik.voiceassistant.data.history

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nik.voiceassistant.TranscriptItem
import java.io.File
import java.util.UUID

data class ConversationSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "New Conversation",
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    val messages: MutableList<TranscriptItem> = mutableListOf()
)

object ConversationHistoryManager {
    private const val TAG = "HistoryManager"
    private const val DIR_NAME = "conversation_history"
    private val gson = Gson()

    private fun getHistoryDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Synchronized
    fun getAllSessions(context: Context): List<ConversationSession> {
        val dir = getHistoryDir(context)
        val files = dir.listFiles { _, name -> name.endsWith(".json") }.orEmpty()
        val list = mutableListOf<ConversationSession>()
        for (file in files) {
            try {
                val json = file.readText()
                val session = gson.fromJson(json, ConversationSession::class.java)
                if (session != null) list.add(session)
            } catch (e: Exception) {
                Log.w(TAG, "Error reading history file ${file.name}: ${e.message}")
            }
        }
        return list.sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun getSession(context: Context, id: String): ConversationSession? {
        val file = File(getHistoryDir(context), "$id.json")
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), ConversationSession::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Error reading session $id: ${e.message}")
            null
        }
    }

    @Synchronized
    fun saveSession(context: Context, session: ConversationSession) {
        try {
            session.updatedAt = System.currentTimeMillis()
            // Auto-title if it's still default and there's a user message
            if (session.title == "New Conversation" || session.title.isBlank()) {
                val firstUserMsg = session.messages.firstOrNull { it.sender == "user" }?.text
                if (!firstUserMsg.isNullOrBlank()) {
                    session.title = if (firstUserMsg.length > 36) firstUserMsg.take(36) + "…" else firstUserMsg
                }
            }
            val file = File(getHistoryDir(context), "${session.id}.json")
            file.writeText(gson.toJson(session))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session ${session.id}: ${e.message}")
        }
    }

    @Synchronized
    fun deleteSession(context: Context, id: String): Boolean {
        val file = File(getHistoryDir(context), "$id.json")
        return if (file.exists()) file.delete() else false
    }

    @Synchronized
    fun clearAll(context: Context) {
        val dir = getHistoryDir(context)
        dir.listFiles()?.forEach { it.delete() }
    }

    fun createSession(context: Context, initialTitle: String = "New Conversation"): ConversationSession {
        val session = ConversationSession(
            id = UUID.randomUUID().toString(),
            title = initialTitle,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        saveSession(context, session)
        return session
    }
}
