package com.mckoss.message_killer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The in-app Spam folder: a copy of every political message we silenced or
 * removed from the inbox. Entries are kept for [RETENTION_DAYS] days.
 */
class SpamStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "spam.db", null, 1) {

    data class Entry(
        val id: Long,
        val source: String,
        val status: String,
        val sender: String,
        val body: String,
        val messageTime: Long,
        val filedAt: Long,
        val score: Double,
        val reasons: List<String>,
        val smsId: Long?,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "id" to id,
            "source" to source,
            "status" to status,
            "sender" to sender,
            "body" to body,
            "messageTime" to messageTime,
            "filedAt" to filedAt,
            "score" to score,
            "reasons" to reasons,
            "smsId" to smsId,
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE spam (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              source TEXT NOT NULL,
              status TEXT NOT NULL,
              sender TEXT NOT NULL,
              body TEXT NOT NULL,
              message_time INTEGER NOT NULL,
              filed_at INTEGER NOT NULL,
              score REAL NOT NULL,
              reasons TEXT NOT NULL,
              sms_id INTEGER UNIQUE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX spam_time ON spam(message_time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Records a message whose notification we cancelled. Returns false if it was already recorded. */
    @Synchronized
    fun recordSilenced(sender: String, body: String, messageTime: Long, result: Classifier.Result): Boolean {
        if (findSimilar(body, messageTime, requireNoSmsId = false) != null) return false
        insert(SOURCE_NOTIFICATION, STATUS_SILENCED, sender, body, messageTime, result, smsId = null)
        return true
    }

    /**
     * Files a political SMS found in the inbox as pending deletion, merging it with
     * the matching silenced-notification entry if there is one. Returns true if new.
     */
    @Synchronized
    fun fileFromInbox(sms: SmsInbox.Message, result: Classifier.Result): Boolean {
        val db = writableDatabase
        db.rawQuery("SELECT id FROM spam WHERE sms_id = ?", arrayOf(sms.id.toString())).use {
            if (it.moveToFirst()) return false
        }
        val match = findSimilar(sms.body, sms.date, requireNoSmsId = true)
        if (match != null) {
            db.update(
                "spam",
                ContentValues().apply {
                    put("sms_id", sms.id)
                    put("status", STATUS_PENDING)
                    put("sender", sms.address)
                },
                "id = ?",
                arrayOf(match.toString()),
            )
        } else {
            insert(SOURCE_SMS, STATUS_PENDING, sms.address, sms.body, sms.date, result, sms.id)
        }
        return true
    }

    /** Files an SMS that arrived while we were the default app and never reached the inbox. */
    @Synchronized
    fun recordIntercepted(sender: String, body: String, messageTime: Long, result: Classifier.Result) {
        insert(SOURCE_SMS, STATUS_DELETED, sender, body, messageTime, result, smsId = null)
    }

    @Synchronized
    fun pendingSmsIds(): List<Long> =
        readableDatabase.rawQuery(
            "SELECT sms_id FROM spam WHERE status = ? AND sms_id IS NOT NULL",
            arrayOf(STATUS_PENDING),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    @Synchronized
    fun markDeleted(smsIds: Collection<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in smsIds) {
                db.update("spam", ContentValues().apply { put("status", STATUS_DELETED) },
                    "sms_id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun get(id: Long): Entry? =
        readableDatabase.rawQuery("SELECT * FROM spam WHERE id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) c.toEntry() else null }

    @Synchronized
    fun list(limit: Int = 100_000): List<Entry> =
        readableDatabase.rawQuery(
            "SELECT * FROM spam ORDER BY message_time DESC LIMIT ?", arrayOf(limit.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.toEntry()) } }

    @Synchronized
    fun remove(id: Long) {
        writableDatabase.delete("spam", "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun purgeExpired(now: Long = System.currentTimeMillis()): Int =
        writableDatabase.delete(
            "spam", "filed_at < ?",
            arrayOf((now - TimeUnit.DAYS.toMillis(RETENTION_DAYS)).toString()),
        )

    @Synchronized
    fun counts(now: Long = System.currentTimeMillis()): Map<String, Int> {
        val db = readableDatabase
        fun count(where: String, vararg args: String) =
            db.rawQuery("SELECT COUNT(*) FROM spam WHERE $where", args).use { it.moveToFirst(); it.getInt(0) }
        return mapOf(
            "total" to count("1"),
            "pending" to count("status = ? AND sms_id IS NOT NULL", STATUS_PENDING),
            "silencedToday" to count("source = ? AND filed_at >= ?", SOURCE_NOTIFICATION,
                (now - TimeUnit.DAYS.toMillis(1)).toString()),
        )
    }

    private fun insert(
        source: String, status: String, sender: String, body: String,
        messageTime: Long, result: Classifier.Result, smsId: Long?,
    ) {
        writableDatabase.insert("spam", null, ContentValues().apply {
            put("source", source)
            put("status", status)
            put("sender", sender)
            put("body", body)
            put("message_time", messageTime)
            put("filed_at", System.currentTimeMillis())
            put("score", result.score)
            put("reasons", result.reasons.joinToString("\n"))
            if (smsId != null) put("sms_id", smsId)
        })
    }

    /** Same text received within [MATCH_WINDOW_MS] of [time] is treated as the same message. */
    private fun findSimilar(body: String, time: Long, requireNoSmsId: Boolean): Long? {
        val extra = if (requireNoSmsId) " AND sms_id IS NULL" else ""
        readableDatabase.rawQuery(
            "SELECT id, message_time FROM spam WHERE body = ?$extra", arrayOf(body)
        ).use { c ->
            while (c.moveToNext()) {
                if (abs(c.getLong(1) - time) <= MATCH_WINDOW_MS) return c.getLong(0)
            }
        }
        return null
    }

    private fun android.database.Cursor.toEntry() = Entry(
        id = getLong(getColumnIndexOrThrow("id")),
        source = getString(getColumnIndexOrThrow("source")),
        status = getString(getColumnIndexOrThrow("status")),
        sender = getString(getColumnIndexOrThrow("sender")),
        body = getString(getColumnIndexOrThrow("body")),
        messageTime = getLong(getColumnIndexOrThrow("message_time")),
        filedAt = getLong(getColumnIndexOrThrow("filed_at")),
        score = getDouble(getColumnIndexOrThrow("score")),
        reasons = getString(getColumnIndexOrThrow("reasons")).split('\n').filter { it.isNotEmpty() },
        smsId = getColumnIndexOrThrow("sms_id").let { if (isNull(it)) null else getLong(it) },
    )

    companion object {
        const val RETENTION_DAYS = 90L
        const val SOURCE_NOTIFICATION = "notification"
        const val SOURCE_SMS = "sms"
        const val STATUS_SILENCED = "silenced"
        const val STATUS_PENDING = "pending_delete"
        const val STATUS_DELETED = "deleted"
        private const val MATCH_WINDOW_MS = 15 * 60 * 1000L

        @Volatile private var instance: SpamStore? = null

        fun get(context: Context): SpamStore =
            instance ?: synchronized(this) { instance ?: SpamStore(context).also { instance = it } }
    }
}
