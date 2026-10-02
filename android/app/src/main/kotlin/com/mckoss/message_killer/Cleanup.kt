package com.mckoss.message_killer

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Telephony
import android.util.Log

/** Scan-and-delete logic shared by the UI and the daily job. */
object Cleanup {
    data class ScanResult(val scanned: Int, val newlyFiled: Int, val pending: Int)

    /** Human-readable progress of the scan in flight, polled by the UI. */
    @Volatile var progress: String = ""

    fun hasSmsPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * Asks RoleManager first: right after the role is granted, Telephony's cached
     * default-SMS package can still report the old app for a moment.
     */
    fun isDefaultSmsApp(context: Context): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS) &&
            roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        ) {
            return true
        }
        return Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }

    data class ScanStats(val texts: Int, val pictureMessages: Int, val full: Boolean)

    /** Stats of the last scan, for the UI. */
    @Volatile var lastStats: ScanStats? = null

    /** Re-check this far back on incremental scans, for late-arriving messages. */
    private val INCREMENTAL_OVERLAP_MS = java.util.concurrent.TimeUnit.DAYS.toMillis(3)

    /**
     * Files political texts from the inbox in the Spam folder as pending deletion.
     *
     * Full scan (every message) the first time, when [forceFull], or when the
     * rules have changed since the last scan (see [Classifier.fingerprint]);
     * otherwise only messages from the last few days, plus the whole
     * conversation of any sender flagged since the last scan.
     */
    @Synchronized // one scan or delete at a time (UI and daily job)
    fun scan(context: Context, forceFull: Boolean = false): ScanResult {
        val store = SpamStore.get(context)
        store.purgeExpired()
        if (!hasSmsPermission(context)) return ScanResult(0, 0, store.pendingSmsIds().size)
        val settings = AppSettings(context)
        val contacts = ContactsChecker(context)
        val startedAt = System.currentTimeMillis()
        val start = SystemClock.elapsedRealtime()
        fun lap(step: String) = Log.i("MessageKiller", "scan: $step at ${SystemClock.elapsedRealtime() - start} ms")

        val fingerprint = settings.scanFingerprint()
        val lastScanAt = settings.lastScanAt
        val full = forceFull || lastScanAt == 0L || settings.lastScanFingerprint != fingerprint
        lap(if (full) "full scan" else "incremental since $lastScanAt")

        // Drop anything waiting for deletion whose sender has since been allowed
        // or added to contacts.
        progress = "Checking your allowed senders…"
        store.removePendingWhere { settings.isAllowed(it.sender) || contacts.isContact(it.sender) }

        progress = if (full) "Reading all your messages…" else "Reading new messages…"
        val since = if (full) 0L else lastScanAt - INCREMENTAL_OVERLAP_MS
        var messages = notFromContacts(SmsInbox.readInbox(context, sinceMillis = since) { progress = it }, contacts)
        lap("read ${messages.size} messages")

        // Classify by content; any sender with a political text is tainted, so
        // everything else from it is filed too.
        val byContent = settings.classifier(taintedSenders = emptyList())
        var results = classifyAll(messages, byContent)
        val alreadyTainted = store.taintedSenders()
        val politicalSenders = messages
            .filterIndexed { i, _ -> results[i].category == Classifier.Category.POLITICAL }
            .map { it.address }
        store.markTainted(politicalSenders)

        if (!full) {
            // Senders flagged since the last scan (here or by the live filter) may
            // have older texts outside the window: re-read their conversations.
            val newlyTainted = store.taintedSince(lastScanAt) +
                politicalSenders.map(Classifier::normalizeSender).filter { it !in alreadyTainted }
            val threads = messages.filter { Classifier.normalizeSender(it.address) in newlyTainted }
                .map { it.threadId }.filter { it > 0 }.toSet()
            if (threads.isNotEmpty()) {
                progress = "Checking older texts from newly flagged senders…"
                val seen = messages.map { it.id }.toSet()
                val older = notFromContacts(SmsInbox.readInbox(context, threadIds = threads), contacts)
                    .filter { it.id !in seen }
                messages = messages + older
                results = results + classifyAll(older, byContent)
                lap("re-read ${older.size} texts from ${threads.size} newly flagged conversations")
            }
        }
        val tainted = store.taintedSenders()

        var filed = 0
        store.inTransaction {
            messages.forEachIndexed { i, sms ->
                if (i % 250 == 0) progress = "Saving to the Spam folder… ${i + 1} of ${messages.size}"
                val result = byContent.withTaint(results[i], sms.address, tainted)
                if (result.isSpam && store.fileFromInbox(sms, result)) filed++
            }
        }
        settings.lastScanAt = startedAt
        settings.lastScanFingerprint = fingerprint
        lastStats = ScanStats(messages.count { !it.isMms }, messages.count { it.isMms }, full)
        lap("filed $filed")
        progress = ""
        return ScanResult(messages.size, filed, store.pendingSmsIds().size)
    }

    private fun notFromContacts(all: List<SmsInbox.Message>, contacts: ContactsChecker) =
        all.filterIndexed { i, sms ->
            if (i % 250 == 0) progress = "Skipping your contacts… ${i + 1} of ${all.size}"
            !contacts.isContact(sms.address)
        }

    private fun classifyAll(messages: List<SmsInbox.Message>, classifier: Classifier) =
        messages.mapIndexed { i, sms ->
            if (i % 250 == 0) progress = "Checking message ${i + 1} of ${messages.size}…"
            classifier.classify(sms.body, sms.address)
        }

    /** Deletes pending messages from the inbox. Requires being the default SMS app. */
    @Synchronized
    fun deletePending(context: Context): Pair<Int, Int> {
        val store = SpamStore.get(context)
        val pending = store.pendingSmsIds()
        if (!isDefaultSmsApp(context)) return 0 to pending.size
        SmsInbox.delete(context, pending) { progress = it }
        // Verify against the inbox rather than trusting delete() counts.
        progress = "Checking that they're gone…"
        val stillThere = SmsInbox.readInbox(context) { progress = "Checking that they're gone… $it" }.map { it.id }.toSet()
        val gone = pending.filter { it !in stillThere }
        store.markDeleted(gone)
        progress = ""
        return gone.size to (pending.size - gone.size)
    }
}
