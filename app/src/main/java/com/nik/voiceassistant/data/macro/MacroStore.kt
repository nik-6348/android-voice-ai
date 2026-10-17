package com.nik.voiceassistant.data.macro

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Saved macros, one JSON file for all of them (there are never many). Atomic writes, same pattern as
 * ConversationHistoryManager: a crash mid-save can't corrupt the file. Call off the main thread.
 */
object MacroStore {
    private const val TAG = "MacroStore"
    private const val FILE_NAME = "macros.json"
    private const val OLD_FILE_NAME = "automations.json" // pre-macro format; migrated once, then left alone
    private val gson = MacroGson.gson

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun getAll(context: Context): List<Macro> {
        migrateOldAutomationsIfNeeded(context)
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            (gson.fromJson(f.readText(), Array<Macro>::class.java)?.toList() ?: emptyList()).mapNotNull(::sanitize)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load macros: ${e.javaClass.simpleName}")
            emptyList() // the write path never overwrites on a load failure (see save())
        }
    }

    /**
     * Gson builds objects by reflection, so a field missing from an older or hand-edited file comes
     * back null in the JVM even though Macro's own type says it can't be — the same way a plain
     * `Macro(...)` constructor call never could. Never trust a loaded one without this.
     */
    @Suppress("SENSELESS_COMPARISON")
    private fun sanitize(m: Macro?): Macro? {
        if (m == null || m.id == null) return null
        return m.copy(
            title = m.title ?: "Automation",
            description = m.description ?: "",
            triggers = m.triggers?.filterNotNull()?.ifEmpty { null } ?: listOf(Trigger.Manual),
            constraints = m.constraints?.filterNotNull() ?: emptyList(),
            instruction = m.instruction ?: "",
            action = m.action ?: Action.AlwaysAsk
        )
    }

    @Synchronized
    fun save(context: Context, macro: Macro) {
        val current = loadRawOrNull(context)?.toMutableList() ?: return
        val i = current.indexOfFirst { it.id == macro.id }
        if (i >= 0) current[i] = macro else current.add(0, macro)
        writeAll(context, current)
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        val current = loadRawOrNull(context) ?: return
        writeAll(context, current.filterNot { it.id == id })
    }

    @Synchronized
    fun update(context: Context, id: String, transform: (Macro) -> Macro) {
        val current = loadRawOrNull(context)?.toMutableList() ?: return
        val i = current.indexOfFirst { it.id == id }
        if (i >= 0) {
            current[i] = transform(current[i])
            writeAll(context, current)
        }
    }

    /** The file's actual contents, or null if it exists but can't be parsed (never overwrite then). */
    private fun loadRawOrNull(context: Context): List<Macro>? {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            (gson.fromJson(f.readText(), Array<Macro>::class.java)?.toList() ?: emptyList()).mapNotNull(::sanitize)
        } catch (e: Exception) {
            Log.e(TAG, "Macros file unreadable, not touching it: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun writeAll(context: Context, macros: List<Macro>) {
        try {
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(gson.toJson(macros))
            if (!tmp.renameTo(file(context))) {
                file(context).writeText(gson.toJson(macros))
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save macros: ${e.javaClass.simpleName}")
        }
    }

    /** The old (pre-macro) automations.json, once, turned into Phrase-triggered Replay macros. */
    private fun migrateOldAutomationsIfNeeded(context: Context) {
        val old = File(context.filesDir, OLD_FILE_NAME)
        if (!old.exists()) return
        val newFile = file(context)
        try {
            data class OldStep(val toolName: String = "", val args: Map<String, Any?> = emptyMap(), val description: String = "", val delayMs: Long = 600L)
            data class OldTask(
                val id: String = "", val title: String = "", val description: String = "", val triggerPhrase: String = "",
                val createdAt: Long = System.currentTimeMillis(), var isFavorite: Boolean = false, val steps: List<OldStep> = emptyList()
            )
            val plain = com.google.gson.Gson()
            val old2 = plain.fromJson(old.readText(), Array<OldTask>::class.java)?.toList().orEmpty()
                // Built-in examples from older versions: dropped, not migrated
                .filterNot { it.id.startsWith("default_") }
            val existing = if (newFile.exists()) gson.fromJson(newFile.readText(), Array<Macro>::class.java)?.toList().orEmpty() else emptyList()
            val migrated = old2.map { t ->
                Macro(
                    id = t.id.ifBlank { java.util.UUID.randomUUID().toString() },
                    title = t.title,
                    description = t.description,
                    triggers = listOfNotNull(t.triggerPhrase.takeIf { it.isNotBlank() }?.let { Trigger.Phrase(it) } ?: Trigger.Manual),
                    instruction = t.description,
                    action = Action.Replay(t.steps.map { RecordedStep(toolName = it.toolName, args = it.args, description = it.description, delayMs = it.delayMs) }),
                    createdAt = t.createdAt,
                    isFavorite = t.isFavorite
                )
            }
            if (migrated.isNotEmpty()) writeAll(context, existing + migrated)
            old.delete()
            Log.i(TAG, "Migrated ${migrated.size} automation(s) to the new macro format")
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't migrate old automations, leaving the old file alone: ${e.javaClass.simpleName}")
        }
    }
}
