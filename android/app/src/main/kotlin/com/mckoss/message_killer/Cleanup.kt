package com.mckoss.message_killer

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony

/** Scan-and-delete logic shared by the UI and the daily job. */
object Cleanup {
    data class ScanResult(val scanned: Int, val newlyFiled: Int, val pending: Int)

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
        store.removePendingWhere { settings.isAllowed(it.sender) || contacts.isContact(it.sender) }
        val messages = SmsInbox.readInbox(context).filter { !contacts.isContact(it.address) }

        // Pass 1: any sender with a political text by content is tainted…
        val byContent = settings.classifier(taintedSenders = emptyList())
        store.markTainted(messages.filter { byContent.classify(it.body, it.address).isPolitical }.map { it.address })
        // …pass 2: so everything else from that sender is filed too.
        val classifier = settings.classifier()
        var filed = 0
        for (sms in messages) {
            val result = classifier.classify(sms.body, sms.address)
            if (result.isPolitical && store.fileFromInbox(sms, result)) filed++
        }
        return ScanResult(messages.size, filed, store.pendingSmsIds().size)
    }

    /** Deletes pending messages from the inbox. Requires being the default SMS app. */
    fun deletePending(context: Context): Pair<Int, Int> {
        val store = SpamStore.get(context)
        val pending = store.pendingSmsIds()
        if (!isDefaultSmsApp(context)) return 0 to pending.size
        SmsInbox.delete(context, pending)
        // Verify against the inbox rather than trusting delete() counts.
        val stillThere = SmsInbox.readInbox(context).map { it.id }.toSet()
        val gone = pending.filter { it !in stillThere }
        store.markDeleted(gone)
        return gone.size to (pending.size - gone.size)
    }
}
