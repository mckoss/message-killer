package com.mckoss.message_killer

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony

/** Reads and deletes messages in Android's SMS store. Deleting only works while we are the default SMS app. */
object SmsInbox {
    data class Message(val id: Long, val address: String, val body: String, val date: Long)

    fun readInbox(context: Context): List<Message> {
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

    /** Deletes the given messages; returns the ids that were actually removed. */
    fun delete(context: Context, ids: Collection<Long>): List<Long> = ids.filter { id ->
        try {
            context.contentResolver.delete(
                Telephony.Sms.CONTENT_URI, "${Telephony.Sms._ID} = ?", arrayOf(id.toString()),
            ) > 0
        } catch (e: SecurityException) {
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
