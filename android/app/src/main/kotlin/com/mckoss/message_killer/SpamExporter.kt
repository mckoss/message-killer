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

    /** Per-sender statistics for everything in the Spam folder (one row per number). */
    data class SenderStats(
        val sender: String,
        val flagged: Boolean,
        val total: Int,
        val byCategory: Map<String, Int>,
        val byStatus: Map<String, Int>,
        val first: Long,
        val last: Long,
        val spanDays: Long,
        val activeDays: Int,
        val maxInOneDay: Int,
    ) {
        val perDay: Double get() = total.toDouble() / spanDays
    }

    fun senderStats(context: Context): List<SenderStats> {
        val store = SpamStore.get(context)
        val tainted = store.taintedSenders()
        val zone = java.time.ZoneId.systemDefault()
        return store.list()
            .groupBy { Classifier.normalizeSender(it.sender) }
            .map { (key, entries) ->
                val first = entries.minOf { it.messageTime }
                val last = entries.maxOf { it.messageTime }
                val perDay = entries.groupingBy {
                    java.time.Instant.ofEpochMilli(it.messageTime).atZone(zone).toLocalDate()
                }.eachCount()
                SenderStats(
                    sender = entries.first().sender,
                    flagged = key in tainted,
                    total = entries.size,
                    byCategory = entries.groupingBy { it.category }.eachCount(),
                    byStatus = entries.groupingBy { it.status }.eachCount(),
                    first = first,
                    last = last,
                    spanDays = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(last - first) + 1,
                    activeDays = perDay.size,
                    maxInOneDay = perDay.values.max(),
                )
            }
            .sortedByDescending { it.total }
    }

    /**
     * Saves a per-sender report (counts by category and status, first/last
     * date, texts per day, busiest day) to Downloads as JSON + CSV, for
     * analysis or sharing.
     */
    fun exportSenderReport(context: Context): Result {
        val stats = senderStats(context)
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        val base = "message-killer-senders-$stamp"
        val categories = listOf("political", "commercial", "phishing", SpamStore.CATEGORY_PRUNED)
        val statuses = listOf(SpamStore.STATUS_DELETED, SpamStore.STATUS_PENDING, SpamStore.STATUS_SILENCED)
        val json = JSONArray()
        stats.forEach { s ->
            json.put(JSONObject().apply {
                put("sender", s.sender)
                put("flagged", s.flagged)
                put("texts", s.total)
                put("byCategory", JSONObject(s.byCategory))
                put("byStatus", JSONObject(s.byStatus))
                put("firstReceived", isoTime(s.first))
                put("lastReceived", isoTime(s.last))
                put("spanDays", s.spanDays)
                put("textsPerDay", "%.3f".format(Locale.US, s.perDay).toDouble())
                put("activeDays", s.activeDays)
                put("maxInOneDay", s.maxInOneDay)
            })
        }
        val csv = buildString {
            append(
                (listOf("sender", "flagged", "texts") + categories + statuses +
                    listOf("first_received", "last_received", "span_days", "texts_per_day", "texts_per_week",
                        "active_days", "max_in_one_day")).joinToString(",")
            ).append("\r\n")
            stats.forEach { s ->
                append(
                    (listOf(s.sender, s.flagged.toString(), s.total.toString()) +
                        categories.map { (s.byCategory[it] ?: 0).toString() } +
                        statuses.map { (s.byStatus[it] ?: 0).toString() } +
                        listOf(
                            isoTime(s.first), isoTime(s.last), s.spanDays.toString(),
                            "%.3f".format(Locale.US, s.perDay), "%.2f".format(Locale.US, s.perDay * 7),
                            s.activeDays.toString(), s.maxInOneDay.toString(),
                        )).joinToString(",") { v -> csvField(v) }
                ).append("\r\n")
            }
        }
        write(context, "$base.json", "application/json", json.toString(2))
        write(context, "$base.csv", "text/csv", csv)
        return Result(stats.size, listOf("$base.json", "$base.csv"))
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
