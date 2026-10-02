package com.mckoss.message_killer

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Saves the Spam folder to Downloads as JSON (complete) and CSV (for spreadsheets). */
object SpamExporter {
    data class Result(val count: Int, val files: List<String>)

    /** [pendingOnly]: just the texts waiting to be deleted (the review screen's export). */
    fun export(context: Context, pendingOnly: Boolean = false): Result {
        val entries = SpamStore.get(context).list()
            .filter { !pendingOnly || it.status == SpamStore.STATUS_PENDING }
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        val base = if (pendingOnly) "message-killer-review-$stamp" else "message-killer-spam-$stamp"
        val json = "$base.json"
        val csv = "$base.csv"
        write(context, json, "application/json", toJson(entries))
        write(context, csv, "text/csv", toCsv(entries))
        return Result(entries.size, listOf(json, csv))
    }

    /** Saves the flagged-senders list to Downloads (JSON + CSV), e.g. to share with someone. */
    fun exportFlagged(context: Context): Result {
        val senders = SpamStore.get(context).flaggedSenders()
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        val base = "message-killer-flagged-senders-$stamp"
        val json = JSONArray()
        senders.forEach {
            json.put(JSONObject().apply {
                put("sender", it.sender)
                put("flaggedAt", isoTime(it.addedAt))
                put("textsInSpamFolder", it.messages)
            })
        }
        val csv = buildString {
            append("sender,flagged_at,texts_in_spam_folder\r\n")
            senders.forEach { append(listOf(it.sender, isoTime(it.addedAt), it.messages.toString()).joinToString(",") { v -> csvField(v) }).append("\r\n") }
        }
        write(context, "$base.json", "application/json", json.toString(2))
        write(context, "$base.csv", "text/csv", csv)
        return Result(senders.size, listOf("$base.json", "$base.csv"))
    }

    private fun write(context: Context, name: String, mime: String, content: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create $name in Downloads")
        resolver.openOutputStream(uri).use { out ->
            requireNotNull(out) { "Could not open $name" }.write(content.toByteArray(Charsets.UTF_8))
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun isoTime(millis: Long) = synchronized(iso) { iso.format(Date(millis)) }

    fun toJson(entries: List<SpamStore.Entry>): String {
        val array = JSONArray()
        for (e in entries) {
            array.put(JSONObject().apply {
                put("sender", e.sender)
                put("body", e.body)
                put("receivedAt", isoTime(e.messageTime))
                put("filedAt", isoTime(e.filedAt))
                put("status", e.status)
                put("category", e.category)
                put("source", e.source)
                put("score", e.score)
                put("reasons", JSONArray(e.reasons))
            })
        }
        return array.toString(2)
    }

    fun toCsv(entries: List<SpamStore.Entry>): String = buildString {
        append("received_at,sender,body,category,status,score,reasons\r\n")
        for (e in entries) {
            append(listOf(
                isoTime(e.messageTime), e.sender, e.body, e.category, e.status,
                e.score.toString(), e.reasons.joinToString("; "),
            ).joinToString(",") { csvField(it) })
            append("\r\n")
        }
    }

    private fun csvField(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
}
