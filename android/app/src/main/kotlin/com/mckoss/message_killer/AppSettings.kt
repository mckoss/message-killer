package com.mckoss.message_killer

import android.content.Context
import android.content.SharedPreferences

/** User preferences shared by the UI, the notification listener, and background jobs. */
class AppSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("message_killer", Context.MODE_PRIVATE)

    var liveFilter: Boolean
        get() = prefs.getBoolean(KEY_LIVE_FILTER, true)
        set(value) = prefs.edit().putBoolean(KEY_LIVE_FILTER, value).apply()

    var dailyCleanup: Boolean
        get() = prefs.getBoolean(KEY_DAILY_CLEANUP, false)
        set(value) = prefs.edit().putBoolean(KEY_DAILY_CLEANUP, value).apply()

    var customKeywords: List<String>
        get() = readList(KEY_CUSTOM_KEYWORDS)
        set(value) = writeList(KEY_CUSTOM_KEYWORDS, value)

    var allowedSenders: List<String>
        get() = readList(KEY_ALLOWED_SENDERS)
        set(value) = writeList(KEY_ALLOWED_SENDERS, value)

    /** The messaging app that was the default before we asked for the SMS role. */
    var previousDefaultSmsPackage: String?
        get() = prefs.getString(KEY_PREVIOUS_DEFAULT, null)
        set(value) = prefs.edit().putString(KEY_PREVIOUS_DEFAULT, value).apply()

    fun classifier(taintedSenders: Collection<String> = SpamStore.get(appContext).taintedSenders()) =
        Classifier(customKeywords, allowedSenders, taintedSenders)

    fun isAllowed(sender: String): Boolean {
        val normalized = Classifier.normalizeSender(sender)
        return normalized.isNotEmpty() && allowedSenders.any { Classifier.normalizeSender(it) == normalized }
    }

    fun allowSender(sender: String) {
        if (sender.isBlank()) return
        val normalized = Classifier.normalizeSender(sender)
        val current = allowedSenders
        if (current.none { Classifier.normalizeSender(it) == normalized }) {
            allowedSenders = current + sender.trim()
        }
    }

    // Stored newline-separated so order is preserved (StringSet is unordered).
    private fun readList(key: String): List<String> =
        prefs.getString(key, "")!!.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun writeList(key: String, value: List<String>) {
        val cleaned = value.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        prefs.edit().putString(key, cleaned.joinToString("\n")).apply()
    }

    companion object {
        private const val KEY_LIVE_FILTER = "live_filter"
        private const val KEY_DAILY_CLEANUP = "daily_cleanup"
        private const val KEY_CUSTOM_KEYWORDS = "custom_keywords"
        private const val KEY_ALLOWED_SENDERS = "allowed_senders"
        private const val KEY_PREVIOUS_DEFAULT = "previous_default_sms"
    }
}
