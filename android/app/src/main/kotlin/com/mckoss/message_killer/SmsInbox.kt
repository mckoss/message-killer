package com.mckoss.message_killer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log

/**
 * Reads and deletes messages in Android's SMS and MMS stores. Deleting only works
 * while we are the default SMS app.
 *
 * SMS and MMS rows have separate id spaces, so MMS messages use negative ids here
 * (MMS row 42 is id -42). The Spam folder stores these ids as-is.
 */
object SmsInbox {
    private const val PDU_FROM = 137
    const val PICTURE_ONLY = "[Picture message]"

    data class Message(
        val id: Long,
        val address: String,
        val body: String,
        val date: Long,
        val threadId: Long = 0,
    ) {
        val isMms: Boolean get() = id < 0
    }

    fun mmsId(rowId: Long) = -rowId

    /**
     * Reads received texts and picture messages.
     * [sinceMillis]: only messages received after this time (0 = everything).
     * [threadIds]: only these conversations (null = all).
     */
    fun readInbox(
        context: Context,
        sinceMillis: Long = 0,
        threadIds: Collection<Long>? = null,
        /** Also return picture messages with no text (body "[Picture message]"). */
        includePictureOnly: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): List<Message> {
        if (threadIds != null && threadIds.isEmpty()) return emptyList()
        val sms = readSms(context, sinceMillis, threadIds, onProgress)
        val mms = readMms(context, sinceMillis, threadIds, includePictureOnly, onProgress)
        return (sms + mms).sortedByDescending { it.date }
    }

    /** Conversations in which you've sent (or tried to send) at least one message. */
    fun threadsYouReplied(context: Context): Set<Long> {
        val threads = HashSet<Long>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID),
            "${Telephony.Sms.TYPE} IN (2, 4, 5, 6)", null, null, // sent, outbox, failed, queued
        )?.use { c -> while (c.moveToNext()) threads += c.getLong(0) }
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms.THREAD_ID),
            "${Telephony.Mms.MESSAGE_BOX} IN (2, 4)", null, null, // sent, outbox
        )?.use { c -> while (c.moveToNext()) threads += c.getLong(0) }
        return threads
    }

    /** SQL selection for an optional date floor and conversation filter. */
    private fun selection(dateColumn: String, since: Long, threadIds: Collection<Long>?): Pair<String?, Array<String>?> {
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        if (since > 0) {
            clauses += "$dateColumn > ?"
            args += since.toString()
        }
        if (threadIds != null) {
            clauses += "thread_id IN (${threadIds.joinToString(",") { "?" }})"
            args += threadIds.map { it.toString() }
        }
        return if (clauses.isEmpty()) null to null else clauses.joinToString(" AND ") to args.toTypedArray()
    }

    private fun readSms(
        context: Context, since: Long, threadIds: Collection<Long>?, onProgress: (String) -> Unit,
    ): List<Message> {
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.THREAD_ID,
        )
        val (where, args) = selection(Telephony.Sms.DATE, since, threadIds)
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, projection, where, args, "${Telephony.Sms.DATE} DESC",
        ) ?: return emptyList()
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    if (size % 250 == 0) onProgress("Reading texts… $size")
                    add(Message(
                        id = c.getLong(0),
                        address = c.getString(1) ?: "",
                        body = c.getString(2) ?: "",
                        date = c.getLong(3),
                        threadId = c.getLong(4),
                    ))
                }
            }
        }
    }

    /**
     * Incoming MMS ("picture messages", and long texts carriers send as MMS). The
     * text lives in text/plain parts and the sender in the addr table (type 137 = from).
     */
    private fun readMms(
        context: Context, since: Long, threadIds: Collection<Long>?, includePictureOnly: Boolean,
        onProgress: (String) -> Unit,
    ): List<Message> {
        val resolver = context.contentResolver
        // Matching MMS rows first (MMS dates are in seconds).
        data class Row(val id: Long, val date: Long, val thread: Long)
        val rows = ArrayList<Row>()
        val (where, args) = selection(Telephony.Mms.DATE, since / 1000, threadIds)
        resolver.query(
            Telephony.Mms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.THREAD_ID),
            where, args, null,
        )?.use { c -> while (c.moveToNext()) rows += Row(c.getLong(0), c.getLong(1) * 1000, c.getLong(2)) }
        if (rows.isEmpty()) return emptyList()

        // Their text parts: all at once for a full read, by message id otherwise.
        val texts = HashMap<Long, StringBuilder>()
        val partQueries: List<Pair<String, Array<String>>> = if (since == 0L && threadIds == null) {
            listOf("${Telephony.Mms.Part.CONTENT_TYPE} = ?" to arrayOf("text/plain"))
        } else {
            rows.map { it.id }.chunked(500).map { chunk ->
                "${Telephony.Mms.Part.CONTENT_TYPE} = ? AND ${Telephony.Mms.Part.MSG_ID} IN (${chunk.joinToString(",") { "?" }})" to
                    (arrayOf("text/plain") + chunk.map { it.toString() })
            }
        }
        try {
            var n = 0
            for ((partWhere, partArgs) in partQueries) {
                resolver.query(
                    Uri.parse("content://mms/part"),
                    arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.MSG_ID, Telephony.Mms.Part.TEXT),
                    partWhere, partArgs, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        if (n++ % 250 == 0) onProgress("Reading picture messages… $n")
                        val text = c.getString(2) ?: readPartText(context, c.getLong(0)) ?: continue
                        val sb = texts.getOrPut(c.getLong(1)) { StringBuilder() }
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(text)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("MessageKiller", "Could not read MMS text parts", e)
            return emptyList()
        }

        onProgress("Matching picture messages to senders…")
        val threadSenders = threadSenders(context)
        return rows.mapNotNull { row ->
            val body = texts[row.id]?.toString()?.takeIf { it.isNotBlank() }
                ?: (if (includePictureOnly) PICTURE_ONLY else return@mapNotNull null)
            val sender = if (threadSenders != null) {
                // Conversations with more than one participant (real group chats, but
                // also 1:1 MMS threads that list your own number too) get "" here; the
                // scan looks up the actual sender only for texts that look like spam.
                threadSenders[row.thread] ?: ""
            } else {
                mmsSender(context, row.id) // slow fallback: one query per message
            }
            Message(id = mmsId(row.id), address = sender, body = body, date = row.date, threadId = row.thread)
        }
    }

    /**
     * Sender for every one-to-one conversation, from two bulk queries (thread
     * recipients + canonical addresses) instead of one query per MMS. Group
     * conversations are left out. Returns null if this phone doesn't support it.
     */
    private fun threadSenders(context: Context): Map<Long, String>? = try {
        val addresses = HashMap<Long, String>()
        context.contentResolver.query(
            Uri.parse("content://mms-sms/canonical-addresses"), arrayOf("_id", "address"), null, null, null,
        )?.use { c -> while (c.moveToNext()) addresses[c.getLong(0)] = c.getString(1) ?: "" } ?: return null

        val senders = HashMap<Long, String>()
        context.contentResolver.query(
            Uri.parse("content://mms-sms/conversations?simple=true"), arrayOf("_id", "recipient_ids"), null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val ids = (c.getString(1) ?: "").trim().split(' ').filter { it.isNotEmpty() }
                if (ids.size == 1) addresses[ids[0].toLongOrNull()]?.let { senders[c.getLong(0)] = it }
            }
        } ?: return null
        senders
    } catch (e: Exception) {
        Log.w("MessageKiller", "Bulk thread lookup failed; falling back to per-message", e)
        null
    }

    private fun readPartText(context: Context, partId: Long): String? = try {
        context.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }

    /** Sender of one MMS (by the negative id used in [Message]); one query. */
    fun senderOf(context: Context, message: Message): String =
        if (message.isMms) mmsSender(context, -message.id) else message.address

    private fun mmsSender(context: Context, rowId: Long): String =
        context.contentResolver.query(
            Uri.parse("content://mms/$rowId/addr"),
            arrayOf(Telephony.Mms.Addr.ADDRESS),
            "${Telephony.Mms.Addr.TYPE} = ?",
            arrayOf(PDU_FROM.toString()),
            null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: ""

    /**
     * Deletes the given messages in batches (one provider call per 500 rather than
     * per message). Callers verify by re-reading the inbox.
     */
    fun delete(context: Context, ids: Collection<Long>, onProgress: (String) -> Unit = {}) {
        val sms = ids.filter { it > 0 }
        val mms = ids.filter { it < 0 }.map { -it }
        var done = 0
        // Small batches: the provider deletes each picture message's parts and
        // files one at a time, so a big MMS batch can run for minutes silently.
        for ((uri, rows, size, noun) in listOf(
            Batch(Telephony.Sms.CONTENT_URI, sms, 200, "texts"),
            Batch(Telephony.Mms.CONTENT_URI, mms, 20, "picture messages"),
        )) {
            for (chunk in rows.chunked(size)) {
                onProgress("Deleting $noun… $done of ${ids.size}")
                try {
                    context.contentResolver.delete(
                        uri,
                        "_id IN (${chunk.joinToString(",") { "?" }})",
                        chunk.map { it.toString() }.toTypedArray(),
                    )
                } catch (e: Exception) {
                    Log.w("MessageKiller", "Batch delete failed", e)
                }
                done += chunk.size
            }
        }
        onProgress("Deleting… $done of ${ids.size}")
    }

    private data class Batch(val uri: Uri, val rows: List<Long>, val size: Int, val noun: String)

    /** Which of [ids] (our signed ids) are still in the provider. */
    fun stillPresent(context: Context, ids: Collection<Long>): Set<Long> {
        val present = HashSet<Long>()
        for ((uri, rows, sign) in listOf(
            Triple(Telephony.Sms.CONTENT_URI, ids.filter { it > 0 }, 1L),
            Triple(Telephony.Mms.CONTENT_URI, ids.filter { it < 0 }.map { -it }, -1L),
        )) {
            for (chunk in rows.chunked(500)) {
                context.contentResolver.query(
                    uri, arrayOf("_id"),
                    "_id IN (${chunk.joinToString(",") { "?" }})",
                    chunk.map { it.toString() }.toTypedArray(), null,
                )?.use { c -> while (c.moveToNext()) present += sign * c.getLong(0) }
            }
        }
        return present
    }

    /** Writes an incoming SMS to the inbox (our duty while we hold the default SMS role). */
    /**
     * Writes a text to the inbox (our duty while we hold the default SMS role, and
     * how restores work). Returns false if the provider refused.
     */
    fun insertIncoming(
        context: Context, address: String, body: String, date: Long,
        subscriptionId: Int = -1, read: Boolean = false,
    ): Boolean = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
        put(Telephony.Sms.ADDRESS, address)
        put(Telephony.Sms.BODY, body)
        put(Telephony.Sms.DATE, date)
        put(Telephony.Sms.DATE_SENT, date)
        put(Telephony.Sms.READ, if (read) 1 else 0)
        put(Telephony.Sms.SEEN, if (read) 1 else 0)
        if (subscriptionId != -1) put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
    }) != null
}
