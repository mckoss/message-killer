package com.mckoss.message_killer

import android.app.Notification
import android.app.Person
import android.os.Bundle
import android.os.Parcelable
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Watches the messaging app's notifications and cancels the ones whose new
 * messages are all political, recording them in the Spam folder.
 */
class MessageNotificationListener : NotificationListenerService() {
    data class IncomingMessage(val sender: String, val text: String, val time: Long)

    override fun onListenerConnected() {
        // Catch anything that arrived while we weren't connected.
        try {
            activeNotifications?.forEach { handle(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read active notifications", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to process notification from ${sbn.packageName}", e)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val settings = AppSettings(this)
        if (!settings.liveFilter || !isMessagingApp(sbn.packageName)) return
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val messages = extractMessages(notification, sbn.postTime)
        if (messages.isEmpty()) return

        val classifier = settings.classifier()
        val results = messages.map { it to classifier.classify(it.text, it.sender) }
        if (!results.all { it.second.isPolitical }) return

        cancelNotification(sbn.key)
        val store = SpamStore.get(this)
        for ((message, result) in results) {
            store.recordSilenced(message.sender, message.text, message.time, result)
        }
        cancelOrphanedSummary(sbn)
        Log.i(TAG, "Silenced ${messages.size} political message(s) from ${sbn.packageName}")
    }

    private fun isMessagingApp(pkg: String): Boolean {
        if (pkg == packageName) return false
        if (pkg in KNOWN_MESSAGING_APPS) return true
        val defaultSms = Telephony.Sms.getDefaultSmsPackage(this)
        val previousDefault = AppSettings(this).previousDefaultSmsPackage
        return pkg == defaultSms || pkg == previousDefault
    }

    /** If we cancelled the last child of a notification group, remove the leftover summary too. */
    private fun cancelOrphanedSummary(sbn: StatusBarNotification) {
        val group = sbn.notification.group ?: return
        val siblings = activeNotifications?.filter {
            it.packageName == sbn.packageName && it.notification.group == group && it.key != sbn.key
        } ?: return
        if (siblings.isNotEmpty() && siblings.all { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }) {
            siblings.forEach { cancelNotification(it.key) }
        }
    }

    companion object {
        private const val TAG = "MessageKiller"

        val KNOWN_MESSAGING_APPS = setOf(
            "com.google.android.apps.messaging", // Google Messages
            "com.samsung.android.messaging",     // Samsung Messages
            "com.android.mms",                   // AOSP / some OEMs
        )

        /** Pulls individual incoming messages out of a MessagingStyle (or plain) notification. */
        fun extractMessages(notification: Notification, postTime: Long): List<IncomingMessage> {
            val extras = notification.extras ?: return emptyList()
            val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

            @Suppress("DEPRECATION")
            val bundles: Array<Parcelable>? = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (!bundles.isNullOrEmpty()) {
                val messages = bundles.mapNotNull { parcelable ->
                    val bundle = parcelable as? Bundle ?: return@mapNotNull null
                    val text = bundle.getCharSequence("text")?.toString() ?: return@mapNotNull null
                    @Suppress("DEPRECATION")
                    val person = bundle.getParcelable<Person>("sender_person")
                    val sender = person?.name?.toString() ?: bundle.getCharSequence("sender")?.toString()
                    // A null sender means the message was written by the phone's owner.
                    if (sender == null && person == null) return@mapNotNull null
                    IncomingMessage(sender ?: conversationTitle ?: title ?: "", text, bundle.getLong("time", postTime))
                }
                if (messages.isNotEmpty()) return messages
            }

            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            if (text.isNullOrBlank()) return emptyList()
            return listOf(IncomingMessage(title ?: "", text, postTime))
        }
    }
}
