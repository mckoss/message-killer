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

    /** Your first name and nicknames, for spotting wrong-number scams ("Hi Anna"). */
    var ownerNames: List<String>
        get() = readList(KEY_OWNER_NAMES)
        set(value) = writeList(KEY_OWNER_NAMES, value)

    /** The messaging app that was the default before we asked for the SMS role. */
    var previousDefaultSmsPackage: String?
        get() = prefs.getString(KEY_PREVIOUS_DEFAULT, null)
        set(value) = prefs.edit().putString(KEY_PREVIOUS_DEFAULT, value).apply()

    /** Start time of the last completed scan (ms); 0 = never scanned. */
    var lastScanAt: Long
        get() = prefs.getLong(KEY_LAST_SCAN_AT, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_SCAN_AT, value).apply()

    /** [Classifier.fingerprint] of the rules the last scan used. */
    var lastScanFingerprint: String?
        get() = prefs.getString(KEY_LAST_SCAN_FINGERPRINT, null)
        set(value) = prefs.edit().putString(KEY_LAST_SCAN_FINGERPRINT, value).apply()

    /** Which kinds of spam to filter. All on by default. */
    var enabledCategories: Set<Classifier.Category>
        get() = prefs.getString(KEY_CATEGORIES, null)
            ?.split(',')?.mapNotNull { Classifier.Category.fromKey(it) }?.toSet()
            ?: Classifier.Category.entries.toSet()
        set(value) = prefs.edit().putString(KEY_CATEGORIES, value.joinToString(",") { it.key }).apply()

    fun scanFingerprint() = Classifier.fingerprint(customKeywords, allowedSenders, enabledCategories, ownerNames)

    fun classifier(taintedSenders: Collection<String> = SpamStore.get(appContext).taintedSenders()) =
        Classifier(customKeywords, allowedSenders, taintedSenders, enabledCategories, ownerNames)

    /**
     * The classifier for one-off checks (each notification, each incoming SMS),
     * reused until the settings or the Spam folder change, so it keeps its memo
     * and doesn't re-read the flagged senders every time.
     */
    fun liveClassifier(): Classifier {
        val key = listOf(
            prefs.getString(KEY_CUSTOM_KEYWORDS, ""), prefs.getString(KEY_ALLOWED_SENDERS, ""),
            prefs.getString(KEY_CATEGORIES, null), prefs.getString(KEY_OWNER_NAMES, ""),
            SpamStore.get(appContext).version,
        )
        liveCache?.let { (k, c) -> if (k == key) return c }
        return classifier().also { liveCache = key to it }
    }

    /**
     * Normalized allow list, rebuilt only when the stored list changes (scans
     * check every message against it).
     */
    private val allowedNormalized: Set<String>
        get() {
            val raw = prefs.getString(KEY_ALLOWED_SENDERS, "")!!
            allowedCache?.let { (cachedRaw, set) -> if (cachedRaw == raw) return set }
            val set = readList(KEY_ALLOWED_SENDERS).map(Classifier::normalizeSender).filter { it.isNotEmpty() }.toSet()
            allowedCache = raw to set
            return set
        }

    fun isAllowed(sender: String): Boolean {
        val normalized = Classifier.normalizeSender(sender)
        return normalized.isNotEmpty() && normalized in allowedNormalized
    }

    fun allowSender(sender: String) {
        if (sender.isBlank()) return
        val normalized = Classifier.normalizeSender(sender)
        if (normalized !in allowedNormalized) allowedSenders = allowedSenders + sender.trim()
    }

    // Stored newline-separated so order is preserved (StringSet is unordered).
    private fun readList(key: String): List<String> =
        prefs.getString(key, "")!!.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun writeList(key: String, value: List<String>) {
        val cleaned = value.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        prefs.edit().putString(key, cleaned.joinToString("\n")).apply()
    }

    companion object {
        /** (stored allow list, its normalized set); shared by all instances. */
        @Volatile private var allowedCache: Pair<String, Set<String>>? = null
        @Volatile private var liveCache: Pair<List<Any?>, Classifier>? = null

        private const val KEY_LIVE_FILTER = "live_filter"
        private const val KEY_DAILY_CLEANUP = "daily_cleanup"
        private const val KEY_CUSTOM_KEYWORDS = "custom_keywords"
        private const val KEY_ALLOWED_SENDERS = "allowed_senders"
        private const val KEY_PREVIOUS_DEFAULT = "previous_default_sms"
        private const val KEY_LAST_SCAN_AT = "last_scan_at"
        private const val KEY_CATEGORIES = "categories"
        private const val KEY_LAST_SCAN_FINGERPRINT = "last_scan_fingerprint"
        private const val KEY_OWNER_NAMES = "owner_names"
    }
}
