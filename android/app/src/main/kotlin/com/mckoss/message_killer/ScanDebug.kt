package com.mckoss.message_killer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The newest [SAMPLE] texts the last scan looked at, with their scores, for
 * checking what the filter did. Kept in the app's private files (survives a
 * restart; nothing leaves the phone unless you export it).
 */
object ScanDebug {
    const val SAMPLE = 50
    private const val FILE = "last-scan.json"

    fun record(context: Context, full: Boolean, scanned: Int, messages: List<SmsInbox.Message>, results: List<Classifier.Result>) {
        val newest = messages.indices.sortedByDescending { messages[it].date }.take(SAMPLE)
        val rows = JSONArray()
        for (i in newest) {
            val m = messages[i]
            val r = results[i]
            rows.put(JSONObject().apply {
                put("id", m.id)
                put("sender", m.address)
                put("time", m.date)
                put("timeIso", iso(m.date))
                put("body", m.body)
                put("spam", r.isSpam)
                put("category", r.category?.key ?: JSONObject.NULL)
                put("score", r.score)
                put("scores", JSONObject().apply { r.scores.forEach { (c, s) -> put(c.key, s) } })
                put("reasons", JSONObject().apply { r.reasonsBy.forEach { (c, l) -> put(c.key, JSONArray(l)) } })
            })
        }
        val doc = JSONObject().apply {
            put("scanAt", System.currentTimeMillis())
            put("full", full)
            put("scanned", scanned)
            put("rules", Classifier.fingerprint(emptyList(), emptyList()))
            put("version", try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            } catch (e: Exception) {
                ""
            })
            put("rows", rows)
        }
        try {
            File(context.filesDir, FILE).writeText(doc.toString())
        } catch (e: Exception) {
            android.util.Log.w("MessageKiller", "Could not save scan debug info", e)
        }
    }

    /** The saved JSON document, or null before the first scan. */
    fun read(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    /** Saves the document to Downloads. */
    fun export(context: Context): SpamExporter.Result {
        val json = read(context) ?: return SpamExporter.Result(0, emptyList())
        val name = "message-killer-scan-${SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())}.json"
        SpamExporter.write(context, name, "application/json", JSONObject(json).toString(2))
        return SpamExporter.Result(JSONObject(json).getJSONArray("rows").length(), listOf(name))
    }

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    private fun iso(millis: Long) = synchronized(isoFormat) { isoFormat.format(Date(millis)) }
}
