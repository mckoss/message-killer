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
        resolveUnknownSenders(context, messages, results, byContent, contacts).let { (m, r) -> messages = m; results = r }
        val alreadyTainted = store.taintedSenders()
        val politicalSenders = messages
            .filterIndexed { i, _ -> results[i].category == Classifier.Category.POLITICAL }
            .map { it.address }
        if (full) {
            // Rebuild the flagged list from scratch under the current rules: inbox
            // texts plus everything already in the Spam folder (deleted texts only
            // live there). A sender flagged by an old rule un-flags itself.
            progress = "Re-checking flagged senders…"
            val fromSpamFolder = store.list().filter {
                byContent.classify(it.body, it.sender).category == Classifier.Category.POLITICAL
            }.map { it.sender }
            store.replaceTainted((politicalSenders + fromSpamFolder).map(Classifier::normalizeSender).toSet())
            // Texts waiting to be deleted that no longer count as spam stay in the inbox.
            val tainted = store.taintedSenders()
            store.removePendingWhere { !byContent.withTaint(byContent.classify(it.body, it.sender), it.sender, tainted).isSpam }
            lap("rebuilt flagged senders: ${tainted.size}")
        } else {
            store.markTainted(politicalSenders)
        }

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
                val (olderResolved, olderResults) =
                    resolveUnknownSenders(context, older, classifyAll(older, byContent), byContent, contacts)
                messages = messages + olderResolved
                results = results + olderResults
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

    /**
     * Deleted texts that no longer count as spam under the current rules (e.g.
     * bank alerts deleted because of an old rule) and so can be put back.
     */
    fun restorable(context: Context): List<SpamStore.Entry> {
        val settings = AppSettings(context)
        val store = SpamStore.get(context)
        val byContent = settings.classifier(taintedSenders = emptyList())
        val tainted = store.taintedSenders()
        val contacts = ContactsChecker(context)
        return store.list().filter { e ->
            e.status == SpamStore.STATUS_DELETED && !e.confirmed &&
                e.category != SpamStore.CATEGORY_PRUNED && (
                settings.isAllowed(e.sender) || contacts.isContact(e.sender) ||
                    !byContent.withTaint(byContent.classify(e.body, e.sender), e.sender, tainted).isSpam
                )
        }
    }

    const val PRUNE_AGE_DAYS = 90L

    /** One sender's prunable texts, for the preview. */
    data class PruneGroup(val sender: String, val count: Int, val oldest: Long, val newest: Long, val sample: String)

    /**
     * Received texts older than [PRUNE_AGE_DAYS] in conversations you never sent
     * anything to. Never contacts, allowed senders, or group chats (unknown sender).
     */
    private fun pruneCandidates(context: Context): List<SmsInbox.Message> {
        val settings = AppSettings(context)
        val contacts = ContactsChecker(context)
        val cutoff = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(PRUNE_AGE_DAYS)
        progress = "Finding conversations you've replied to…"
        val replied = SmsInbox.threadsYouReplied(context)
        val all = SmsInbox.readInbox(context, includePictureOnly = true) { progress = it }
        progress = "Checking ${all.size} messages…"
        return all.filter {
            it.date < cutoff && it.threadId !in replied && it.address.isNotEmpty() &&
                !settings.isAllowed(it.address) && !contacts.isContact(it.address)
        }.also { progress = "" }
    }

    @Synchronized
    fun prunePreview(context: Context): List<PruneGroup> =
        pruneCandidates(context)
            .groupBy { Classifier.normalizeSender(it.address) }
            .map { (_, msgs) ->
                PruneGroup(
                    sender = msgs.first().address,
                    count = msgs.size,
                    oldest = msgs.minOf { it.date },
                    newest = msgs.maxOf { it.date },
                    sample = msgs.maxBy { it.date }.body,
                )
            }
            .sortedByDescending { it.count }

    /**
     * Copies the prunable texts from [senders] into the Spam folder ("Old
     * messages") and deletes them from the inbox. Requires the default SMS role.
     * Returns (deleted, failed).
     */
    @Synchronized
    fun prune(context: Context, senders: Collection<String>): Pair<Int, Int> {
        if (!isDefaultSmsApp(context)) return 0 to 0
        val wanted = senders.map(Classifier::normalizeSender).toSet()
        val store = SpamStore.get(context)
        val messages = pruneCandidates(context).filter { Classifier.normalizeSender(it.address) in wanted }
        val ids = store.inTransaction {
            messages.mapIndexed { i, m ->
                if (i % 250 == 0) progress = "Saving copies… ${i + 1} of ${messages.size}"
                store.filePruned(m)
            }
        }
        SmsInbox.delete(context, ids) { progress = it }
        progress = "Checking that they're gone…"
        val stillThere = SmsInbox.readInbox(context, includePictureOnly = true).map { it.id }.toSet()
        val gone = ids.filter { it !in stillThere }
        store.markDeleted(gone)
        progress = ""
        return gone.size to (ids.size - gone.size)
    }

    /**
     * Writes Spam folder entries back into the inbox (as regular texts, already
     * read) and removes them from the Spam folder. Requires the default SMS role.
     * Returns how many were restored.
     */
    @Synchronized
    fun restore(context: Context, ids: Collection<Long>): Int {
        if (!isDefaultSmsApp(context)) return 0
        val store = SpamStore.get(context)
        var restored = 0
        ids.forEachIndexed { i, id ->
            if (i % 25 == 0) progress = "Restoring… $i of ${ids.size}"
            val e = store.get(id) ?: return@forEachIndexed
            if (SmsInbox.insertIncoming(context, e.sender, e.body, e.messageTime, read = true)) {
                store.remove(id)
                restored++
            }
        }
        progress = ""
        return restored
    }

    /**
     * Picture messages from multi-participant conversations arrive without a
     * sender. Look it up (one query each) only for those that look like spam,
     * then re-check them with the sender (contacts, allow list, short codes).
     */
    private fun resolveUnknownSenders(
        context: Context,
        messages: List<SmsInbox.Message>,
        results: List<Classifier.Result>,
        classifier: Classifier,
        contacts: ContactsChecker,
    ): Pair<List<SmsInbox.Message>, List<Classifier.Result>> {
        val outMessages = messages.toMutableList()
        val outResults = results.toMutableList()
        messages.forEachIndexed { i, m ->
            if (m.address.isNotEmpty() || !results[i].isSpam) return@forEachIndexed
            val sender = SmsInbox.senderOf(context, m)
            outMessages[i] = m.copy(address = sender)
            outResults[i] = if (sender.isNotEmpty() && contacts.isContact(sender)) {
                Classifier.Result(0.0, listOf("From a contact"), null)
            } else {
                classifier.classify(m.body, sender)
            }
        }
        return outMessages to outResults
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
        val stillThere = SmsInbox.readInbox(context, includePictureOnly = true) {
            progress = "Checking that they're gone… $it"
        }.map { it.id }.toSet()
        val gone = pending.filter { it !in stillThere }
        store.markDeleted(gone)
        progress = ""
        return gone.size to (pending.size - gone.size)
    }
}
