package com.mckoss.message_killer

import android.content.ContentUris
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

    data class Message(val id: Long, val address: String, val body: String, val date: Long) {
        val isMms: Boolean get() = id < 0
    }

    fun mmsId(rowId: Long) = -rowId

    fun readInbox(context: Context): List<Message> =
        (readSms(context) + readMms(context)).sortedByDescending { it.date }

    private fun readSms(context: Context): List<Message> {
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
        )
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} DESC",
        ) ?: return emptyList()
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Message(
                        id = c.getLong(0),
                        address = c.getString(1) ?: "",
                        body = c.getString(2) ?: "",
                        date = c.getLong(3),
                    ))
                }
            }
        }
    }

    /**
     * Incoming MMS ("picture messages", and long texts carriers send as MMS). The
     * text lives in text/plain parts and the sender in the addr table (type 137 = from).
     */
    private fun readMms(context: Context): List<Message> {
        val resolver = context.contentResolver
        val texts = HashMap<Long, StringBuilder>()
        try {
            resolver.query(
                Uri.parse("content://mms/part"),
                arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.MSG_ID, Telephony.Mms.Part.TEXT),
                "${Telephony.Mms.Part.CONTENT_TYPE} = ?",
                arrayOf("text/plain"),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val text = c.getString(2) ?: readPartText(context, c.getLong(0)) ?: continue
                    val sb = texts.getOrPut(c.getLong(1)) { StringBuilder() }
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(text)
                }
            }
        } catch (e: Exception) {
            Log.w("MessageKiller", "Could not read MMS text parts", e)
            return emptyList()
        }

        val messages = ArrayList<Message>()
        resolver.query(
            Telephony.Mms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val rowId = c.getLong(0)
                val body = texts[rowId]?.toString()?.takeIf { it.isNotBlank() } ?: continue
                messages += Message(
                    id = mmsId(rowId),
                    address = mmsSender(context, rowId),
                    body = body,
                    date = c.getLong(1) * 1000, // MMS dates are in seconds
                )
            }
        }
        return messages
    }

    private fun readPartText(context: Context, partId: Long): String? = try {
        context.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }

    private fun mmsSender(context: Context, rowId: Long): String =
        context.contentResolver.query(
            Uri.parse("content://mms/$rowId/addr"),
            arrayOf(Telephony.Mms.Addr.ADDRESS),
            "${Telephony.Mms.Addr.TYPE} = ?",
            arrayOf(PDU_FROM.toString()),
            null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: ""

    /** Deletes the given messages; returns the ids that were actually removed. */
    fun delete(context: Context, ids: Collection<Long>): List<Long> = ids.filter { id ->
        try {
            val uri = if (id < 0) {
                ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, -id)
            } else {
                ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
            }
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    /** Writes an incoming SMS to the inbox (our duty while we hold the default SMS role). */
    fun insertIncoming(context: Context, address: String, body: String, date: Long, subscriptionId: Int) {
        context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.DATE_SENT, date)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
        })
    }
}
