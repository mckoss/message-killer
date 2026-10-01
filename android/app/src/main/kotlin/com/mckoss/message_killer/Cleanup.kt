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

    /** Classifies the whole inbox and files political texts in the Spam folder as pending deletion. */
    fun scan(context: Context): ScanResult {
        val store = SpamStore.get(context)
        store.purgeExpired()
        if (!hasSmsPermission(context)) return ScanResult(0, 0, store.pendingSmsIds().size)
        val settings = AppSettings(context)
        val contacts = ContactsChecker(context)
        // Drop anything waiting for deletion whose sender has since been allowed
        // or added to contacts.
        val start = SystemClock.elapsedRealtime()
        fun lap(step: String) = Log.i("MessageKiller", "scan: $step at ${SystemClock.elapsedRealtime() - start} ms")

        progress = "Checking your allowed senders…"
        store.removePendingWhere { settings.isAllowed(it.sender) || contacts.isContact(it.sender) }

        progress = "Reading your messages…"
        val all = SmsInbox.readInbox(context) { progress = it }
        lap("read ${all.size} messages")

        val messages = all.filterIndexed { i, sms ->
            if (i % 250 == 0) progress = "Skipping your contacts… ${i + 1} of ${all.size}"
            !contacts.isContact(sms.address)
        }
        lap("contacts filtered, ${messages.size} left")

        // Classify each message once by content; any sender with a political
        // text is tainted, so everything else from it is filed too.
        val byContent = settings.classifier(taintedSenders = emptyList())
        val results = messages.mapIndexed { i, sms ->
            if (i % 250 == 0) progress = "Checking message ${i + 1} of ${messages.size}…"
            byContent.classify(sms.body, sms.address)
        }
        lap("classified")
        store.markTainted(messages.filterIndexed { i, _ -> results[i].isPolitical }.map { it.address })
        val tainted = store.taintedSenders()

        var filed = 0
        store.inTransaction {
            messages.forEachIndexed { i, sms ->
                if (i % 250 == 0) progress = "Saving to the Spam folder… ${i + 1} of ${messages.size}"
                val result = Classifier.applyTaint(results[i], sms.address, tainted)
                if (result.isPolitical && store.fileFromInbox(sms, result)) filed++
            }
        }
        lap("filed $filed")
        progress = ""
        return ScanResult(messages.size, filed, store.pendingSmsIds().size)
    }

    /** Deletes pending messages from the inbox. Requires being the default SMS app. */
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
