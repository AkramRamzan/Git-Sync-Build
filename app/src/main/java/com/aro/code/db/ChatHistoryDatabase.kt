package com.aro.code.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ChatMessageEntity(
    val id: Long = 0,
    val sessionId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sender: String, // "USER" or "AGENT"
    val attachedFiles: List<String> = emptyList(),
    val messageText: String
)

/**
 * Module 2.3 — Local SQLite Database (chat_history.db) for Anti-Gravity Chat History.
 * Implements persistent conversation storage, session isolation, and automatic restoration.
 */
@Singleton
class ChatHistoryDatabase @Inject constructor(
    @ApplicationContext private val context: Context
) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "chat_history.db"
        const val DATABASE_VERSION = 1

        const val TABLE_MESSAGES = "chat_history"
        const val COL_ID = "id"
        const val COL_SESSION_ID = "session_id"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_SENDER = "sender"
        const val COL_ATTACHED_FILES = "attached_files"
        const val COL_MESSAGE_TEXT = "message_text"

        const val TABLE_META = "chat_metadata"
        const val COL_META_KEY = "meta_key"
        const val COL_META_VAL = "meta_val"
        const val KEY_LAST_SESSION = "last_active_session_id"
        // Per-engine last session keys
        const val KEY_LAST_AG_SESSION = "last_antigravity_session_id"
        const val KEY_LAST_GEM_SESSION = "last_gemini_session_id"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_MESSAGES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SESSION_ID TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_SENDER TEXT NOT NULL,
                $COL_ATTACHED_FILES TEXT NOT NULL,
                $COL_MESSAGE_TEXT TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_chat_session ON $TABLE_MESSAGES ($COL_SESSION_ID)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_META (
                $COL_META_KEY TEXT PRIMARY KEY,
                $COL_META_VAL TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Safe non-destructive schema migration: preserve all conversation history
        if (oldVersion < 2) {
            // Ensure meta table exists without dropping message data
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_META (
                    $COL_META_KEY TEXT PRIMARY KEY,
                    $COL_META_VAL TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    // ── DAO Operations with Coroutines ───────────────────────────────────────

    suspend fun insertMessage(
        sessionId: String,
        sender: String,
        messageText: String,
        attachedFiles: List<String>
    ): Long = withContext(Dispatchers.IO) {
        val jsonArray = JSONArray(attachedFiles).toString()
        val cv = ContentValues().apply {
            put(COL_SESSION_ID, sessionId)
            put(COL_TIMESTAMP, System.currentTimeMillis())
            put(COL_SENDER, sender)
            put(COL_ATTACHED_FILES, jsonArray)
            put(COL_MESSAGE_TEXT, messageText)
        }
        val db = writableDatabase
        // Wrap insert + session-id update in a single atomic transaction
        db.beginTransaction()
        var rowId = -1L
        try {
            rowId = db.insert(TABLE_MESSAGES, null, cv)
            // Save the last-used global session key
            val metaCv = ContentValues().apply {
                put(COL_META_KEY, KEY_LAST_SESSION)
                put(COL_META_VAL, sessionId)
            }
            db.insertWithOnConflict(TABLE_META, null, metaCv, SQLiteDatabase.CONFLICT_REPLACE)
            // Also save per-engine last session key (antigravity_ or gemini_ prefix)
            val enginePrefix = when {
                sessionId.startsWith("antigravity") -> "antigravity"
                sessionId.startsWith("gemini") -> "gemini"
                else -> null
            }
            if (enginePrefix != null) {
                val engineCv = ContentValues().apply {
                    put(COL_META_KEY, "last_${enginePrefix}_session_id")
                    put(COL_META_VAL, sessionId)
                }
                db.insertWithOnConflict(TABLE_META, null, engineCv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        rowId
    }

    suspend fun getMessagesForSession(sessionId: String): List<ChatMessageEntity> = withContext(Dispatchers.IO) {
        val messages = mutableListOf<ChatMessageEntity>()
        val cursor = readableDatabase.query(
            TABLE_MESSAGES,
            arrayOf(COL_ID, COL_SESSION_ID, COL_TIMESTAMP, COL_SENDER, COL_ATTACHED_FILES, COL_MESSAGE_TEXT),
            "$COL_SESSION_ID = ?",
            arrayOf(sessionId),
            null,
            null,
            "$COL_TIMESTAMP ASC"
        )
        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(COL_ID)
            val sessionIdx = c.getColumnIndexOrThrow(COL_SESSION_ID)
            val timeIdx = c.getColumnIndexOrThrow(COL_TIMESTAMP)
            val senderIdx = c.getColumnIndexOrThrow(COL_SENDER)
            val filesIdx = c.getColumnIndexOrThrow(COL_ATTACHED_FILES)
            val textIdx = c.getColumnIndexOrThrow(COL_MESSAGE_TEXT)

            while (c.moveToNext()) {
                val filesJson = c.getString(filesIdx)
                val fileList = mutableListOf<String>()
                runCatching {
                    val arr = JSONArray(filesJson)
                    for (i in 0 until arr.length()) {
                        fileList.add(arr.getString(i))
                    }
                }

                messages.add(
                    ChatMessageEntity(
                        id = c.getLong(idIdx),
                        sessionId = c.getString(sessionIdx),
                        timestamp = c.getLong(timeIdx),
                        sender = c.getString(senderIdx),
                        attachedFiles = fileList,
                        messageText = c.getString(textIdx)
                    )
                )
            }
        }
        messages
    }

    suspend fun getLastActiveSessionId(): String = withContext(Dispatchers.IO) {
        val cursor = readableDatabase.query(
            TABLE_META,
            arrayOf(COL_META_VAL),
            "$COL_META_KEY = ?",
            arrayOf(KEY_LAST_SESSION),
            null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return@withContext it.getString(0)
            }
        }
        // If none exists, create a fresh UUID session
        val newSessionId = UUID.randomUUID().toString()
        saveLastSessionId(newSessionId)
        newSessionId
    }

    suspend fun saveLastSessionId(sessionId: String) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put(COL_META_KEY, KEY_LAST_SESSION)
            put(COL_META_VAL, sessionId)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_META,
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * Returns the last active session ID for the given engine ("antigravity"/"gemini").
     * Returns null if no session has been persisted yet.
     */
    suspend fun getLastSessionId(engine: String): String? = withContext(Dispatchers.IO) {
        val key = "last_${engine}_session_id"
        val cursor = readableDatabase.query(
            TABLE_META, arrayOf(COL_META_VAL), "$COL_META_KEY = ?",
            arrayOf(key), null, null, null
        )
        cursor.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    suspend fun clearSession(sessionId: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_MESSAGES, "$COL_SESSION_ID = ?", arrayOf(sessionId))
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_MESSAGES, null, null)
        writableDatabase.delete(TABLE_META, null, null)
    }
}
