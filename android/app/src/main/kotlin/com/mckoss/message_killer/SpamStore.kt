package com.mckoss.message_killer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.TimeUnit

/**
 * The in-app Spam folder: a copy of every political message we silenced or
 * removed from the inbox. Entries are kept for [RETENTION_DAYS] days.
 */
class SpamStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "spam.db", null, 6) {

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
        val category: String,
        /** You confirmed this really is spam; never offered for restore. */
        val confirmed: Boolean = false,
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
            "category" to category,
            "confirmed" to confirmed,
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
              sms_id INTEGER UNIQUE,
              category TEXT NOT NULL DEFAULT 'political',
              confirmed INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX spam_time ON spam(message_time)")
        createIndexes(db)
        createTainted(db)
        createHistory(db)
    }

    /** For pending-delete lookups and the 90-day purge (both ran full table scans). */
    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS spam_status ON spam(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS spam_filed ON spam(filed_at)")
    }

    /**
     * Bumped on every write, so callers can cache results computed from the
     * Spam folder (e.g. the restorable list) until something changes.
     */
    @Volatile var version = 0L
        private set

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        version++
    }

    private fun changed() {
        version++
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createTainted(db)
            db.rawQuery("SELECT DISTINCT sender FROM spam", null).use { c ->
                while (c.moveToNext()) addTainted(db, c.getString(0))
            }
        }
        if (oldVersion in 1..2) {
            // Everything filed before categories existed was political.
            db.execSQL("ALTER TABLE spam ADD COLUMN category TEXT NOT NULL DEFAULT 'political'")
        }
        if (oldVersion in 1..3) {
            db.execSQL("ALTER TABLE spam ADD COLUMN confirmed INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion in 1..4) createHistory(db)
        if (oldVersion in 1..5) createIndexes(db)
    }

    /**
     * Monthly spam counts for entries already purged from the Spam folder, so the
     * "Spam over time" chart keeps its full history after the 90-day retention.
     */
    private fun createHistory(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS history (month TEXT NOT NULL, category TEXT NOT NULL, " +
                "count INTEGER NOT NULL, PRIMARY KEY (month, category))"
        )
    }

    /** "2026-09" for an entry's received time, in local time. */
    private val monthExpr = "strftime('%Y-%m', message_time / 1000, 'unixepoch', 'localtime')"

    /** Spam texts per month and category (Spam folder + purged history), oldest first. */
    @Synchronized
    fun monthlyCounts(): List<Triple<String, String, Int>> {
        val totals = LinkedHashMap<Pair<String, String>, Int>()
        val db = readableDatabase
        db.rawQuery(
            "SELECT $monthExpr, category, COUNT(*) FROM spam WHERE category != ? GROUP BY 1, 2",
            arrayOf(CATEGORY_PRUNED),
        ).use { c -> while (c.moveToNext()) totals[c.getString(0) to c.getString(1)] = c.getInt(2) }
        db.rawQuery("SELECT month, category, count FROM history", null).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0) to c.getString(1)
                totals[key] = (totals[key] ?: 0) + c.getInt(2)
            }
        }
        return totals.map { (k, n) -> Triple(k.first, k.second, n) }.sortedBy { it.first }
    }

    /**
     * Normalized senders that have sent at least one political text. Kept separately
     * from the Spam folder so a sender stays flagged after its entries are purged.
     */
    private fun createTainted(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS tainted_senders (sender TEXT PRIMARY KEY, added_at INTEGER NOT NULL)")
    }

    private fun addTainted(db: SQLiteDatabase, sender: String) {
        val normalized = Classifier.normalizeSender(sender)
        if (normalized.isEmpty()) return
        db.insertWithOnConflict("tainted_senders", null, ContentValues().apply {
            put("sender", normalized)
            put("added_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** Senders flagged after [since] (ms), e.g. by the live filter between scans. */
    @Synchronized
    fun taintedSince(since: Long): Set<String> =
        readableDatabase.rawQuery("SELECT sender FROM tainted_senders WHERE added_at > ?", arrayOf(since.toString()))
            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    @Synchronized
    fun taintedSenders(): Set<String> =
        readableDatabase.rawQuery("SELECT sender FROM tainted_senders", null)
            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    @Synchronized
    fun markTainted(senders: Collection<String>) = inTransaction {
        val db = writableDatabase
        for (s in senders) addTainted(db, s)
    }

    /** Runs [block] in one database transaction (one disk sync instead of one per write). */
    @Synchronized
    fun <T> inTransaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
            changed()
        }
    }

    data class FlaggedSender(val sender: String, val addedAt: Long, val messages: Int)

    /** Every flagged sender (newest first) with how many of its texts are in the Spam folder. */
    @Synchronized
    fun flaggedSenders(): List<FlaggedSender> {
        val counts = HashMap<String, Int>()
        readableDatabase.rawQuery("SELECT sender FROM spam", null).use { c ->
            while (c.moveToNext()) {
                val key = Classifier.normalizeSender(c.getString(0))
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return readableDatabase.rawQuery("SELECT sender, added_at FROM tainted_senders ORDER BY added_at DESC", null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(FlaggedSender(c.getString(0), c.getLong(1), counts[c.getString(0)] ?: 0))
                }
            }
    }

    /**
     * Replaces the flagged-sender list with [senders] (normalized), keeping the
     * original flag time of senders that stay. Used by full rescans so a sender
     * flagged by an old rule (e.g. a bank alert mentioning ActBlue) un-flags itself.
     */
    @Synchronized
    fun replaceTainted(senders: Set<String>) = inTransaction {
        val db = writableDatabase
        val current = taintedSenders()
        for (s in current - senders) db.delete("tainted_senders", "sender = ?", arrayOf(s))
        for (s in senders - current) addTainted(db, s)
    }

    @Synchronized
    fun untaint(sender: String) {
        writableDatabase.delete("tainted_senders", "sender = ?", arrayOf(Classifier.normalizeSender(sender)))
        changed()
    }

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
            changed()
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

    /** Removes pending (not yet deleted) entries matching [predicate]; returns how many. */
    @Synchronized
    fun removePendingWhere(predicate: (Entry) -> Boolean): Int {
        val doomed = query("WHERE status = ?", STATUS_PENDING).filter(predicate).map { it.id }
        inTransaction { forEachChunk(doomed) { where, args -> writableDatabase.delete("spam", "id $where", args) } }
        return doomed.size
    }

    @Synchronized
    fun markDeleted(smsIds: Collection<Long>) = inTransaction {
        val values = ContentValues().apply { put("status", STATUS_DELETED) }
        forEachChunk(smsIds) { where, args -> writableDatabase.update("spam", values, "sms_id $where", args) }
    }

    /** Runs [block] with "IN (?, ?, …)" and its arguments for each chunk of [ids]. */
    private fun forEachChunk(ids: Collection<Long>, block: (String, Array<String>) -> Unit) {
        for (chunk in ids.chunked(500)) {
            block("IN (${chunk.joinToString(",") { "?" }})", chunk.map { it.toString() }.toTypedArray())
        }
    }

    @Synchronized
    fun get(id: Long): Entry? = query("WHERE id = ?", id.toString()).firstOrNull()

    /** Entries by id, in one query per 500. */
    @Synchronized
    fun getAll(ids: Collection<Long>): List<Entry> = buildList {
        forEachChunk(ids) { where, args -> addAll(query("WHERE id $where", *args)) }
    }

    @Synchronized
    fun list(limit: Int = 100_000): List<Entry> = query("ORDER BY message_time DESC LIMIT ?", limit.toString())

    private fun query(clause: String, vararg args: String): List<Entry> =
        readableDatabase.rawQuery("SELECT * FROM spam $clause", args).use { c -> c.entries() }

    /** Marks entries as confirmed spam so they're never offered for restore. */
    @Synchronized
    fun confirm(ids: Collection<Long>) = inTransaction {
        val values = ContentValues().apply { put("confirmed", 1) }
        forEachChunk(ids) { where, args -> writableDatabase.update("spam", values, "id $where", args) }
    }

    @Synchronized
    fun remove(id: Long) = removeAll(listOf(id))

    @Synchronized
    fun removeAll(ids: Collection<Long>) = inTransaction {
        forEachChunk(ids) { where, args -> writableDatabase.delete("spam", "id $where", args) }
    }

    @Synchronized
    fun purgeExpired(now: Long = System.currentTimeMillis()): Int = inTransaction {
        val cutoff = (now - TimeUnit.DAYS.toMillis(RETENTION_DAYS)).toString()
        val db = writableDatabase
        // Keep the monthly counts for the chart before the entries go.
        // (No UPSERT: Android 10 ships SQLite 3.22.)
        db.rawQuery(
            "SELECT $monthExpr, category, COUNT(*) FROM spam WHERE filed_at < ? AND category != ? GROUP BY 1, 2",
            arrayOf(cutoff, CATEGORY_PRUNED),
        ).use { c ->
            while (c.moveToNext()) {
                val args = arrayOf(c.getInt(2).toString(), c.getString(0), c.getString(1))
                db.execSQL("UPDATE history SET count = count + ? WHERE month = ? AND category = ?", args)
                db.execSQL("INSERT OR IGNORE INTO history (count, month, category) VALUES (?, ?, ?)", args)
            }
        }
        db.delete("spam", "filed_at < ?", arrayOf(cutoff))
    }

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

    /**
     * Files an old message from a conversation you never replied to, about to be
     * pruned. Returns its sms id (also if it was already in the Spam folder).
     */
    @Synchronized
    fun filePruned(sms: SmsInbox.Message): Long {
        val exists = writableDatabase.rawQuery("SELECT id FROM spam WHERE sms_id = ?", arrayOf(sms.id.toString()))
            .use { it.moveToFirst() }
        if (!exists) {
            insert(
                SOURCE_PRUNE, STATUS_PENDING, sms.address, sms.body, sms.date,
                Classifier.Result(0.0, listOf(PRUNE_REASON), null), sms.id, category = CATEGORY_PRUNED,
            )
        }
        return sms.id
    }

    private fun insert(
        source: String, status: String, sender: String, body: String,
        messageTime: Long, result: Classifier.Result, smsId: Long?,
        category: String = (result.category ?: Classifier.Category.POLITICAL).key,
    ) {
        changed()
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
            put("category", category)
        })
        // Only political senders are flagged wholesale; a store that sent one
        // coupon may also send prescription alerts.
        if (result.category == Classifier.Category.POLITICAL) addTainted(writableDatabase, sender)
    }

    /** Same text received within [MATCH_WINDOW_MS] of [time] is treated as the same message. */
    private fun findSimilar(body: String, time: Long, requireNoSmsId: Boolean): Long? {
        // The time range uses the message_time index; matching on body alone
        // scanned the whole table for every message filed.
        val extra = if (requireNoSmsId) " AND sms_id IS NULL" else ""
        return readableDatabase.rawQuery(
            "SELECT id FROM spam WHERE message_time BETWEEN ? AND ? AND body = ?$extra LIMIT 1",
            arrayOf((time - MATCH_WINDOW_MS).toString(), (time + MATCH_WINDOW_MS).toString(), body),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }

    /** Reads every row, looking up column indexes once rather than per row. */
    private fun android.database.Cursor.entries(): List<Entry> {
        val id = getColumnIndexOrThrow("id")
        val source = getColumnIndexOrThrow("source")
        val status = getColumnIndexOrThrow("status")
        val sender = getColumnIndexOrThrow("sender")
        val body = getColumnIndexOrThrow("body")
        val messageTime = getColumnIndexOrThrow("message_time")
        val filedAt = getColumnIndexOrThrow("filed_at")
        val score = getColumnIndexOrThrow("score")
        val reasons = getColumnIndexOrThrow("reasons")
        val smsId = getColumnIndexOrThrow("sms_id")
        val category = getColumnIndexOrThrow("category")
        val confirmed = getColumnIndexOrThrow("confirmed")
        return buildList(count) {
            while (moveToNext()) add(Entry(
                id = getLong(id),
                source = getString(source),
                status = getString(status),
                sender = getString(sender),
                body = getString(body),
                messageTime = getLong(messageTime),
                filedAt = getLong(filedAt),
                score = getDouble(score),
                reasons = getString(reasons).split('\n').filter { it.isNotEmpty() },
                smsId = if (isNull(smsId)) null else getLong(smsId),
                category = getString(category),
                confirmed = getInt(confirmed) != 0,
            ))
        }
    }

    companion object {
        const val RETENTION_DAYS = 90L
        const val SOURCE_NOTIFICATION = "notification"
        const val SOURCE_SMS = "sms"
        const val STATUS_SILENCED = "silenced"
        const val STATUS_PENDING = "pending_delete"
        const val STATUS_DELETED = "deleted"
        const val SOURCE_PRUNE = "prune"
        const val CATEGORY_PRUNED = "pruned"
        const val PRUNE_REASON = "Older than 90 days, in a conversation you never replied to"
        private const val MATCH_WINDOW_MS = 15 * 60 * 1000L

        @Volatile private var instance: SpamStore? = null

        fun get(context: Context): SpamStore =
            instance ?: synchronized(this) { instance ?: SpamStore(context).also { instance = it } }
    }
}
