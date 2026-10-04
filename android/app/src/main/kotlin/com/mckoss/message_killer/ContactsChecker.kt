package com.mckoss.message_killer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone

/**
 * Answers "is this sender one of my contacts?" (including Google contacts synced
 * to the phone). Contacts are never filtered. Use [get]: one shared instance,
 * dropped whenever the contacts change, so every scan and notification doesn't
 * reload the whole address book.
 */
class ContactsChecker(private val context: Context) {
    val hasPermission: Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /**
     * All contact phone numbers (normalized like [Classifier.normalizeSender]) and
     * names (lower-cased), loaded once with two queries instead of one lookup per
     * sender, which took minutes on a large inbox.
     */
    private val numbers: Set<String> by lazy { loadNumbers() }
    private val names: Set<String> by lazy { loadNames() }

    /**
     * [sender] is a phone number (SMS inbox) or a display name (notifications).
     * [personUri] is the notification's Person URI, when the messaging app provides one.
     */
    fun isContact(sender: String, personUri: String? = null): Boolean {
        if (!hasPermission) return false
        if (personUri != null) {
            if (personUri.startsWith("content://com.android.contacts")) return true
            if (personUri.startsWith("tel:") &&
                Classifier.normalizeSender(Uri.decode(personUri.removePrefix("tel:"))) in numbers
            ) return true
        }
        val trimmed = sender.trim()
        if (trimmed.isEmpty()) return false
        return if (looksLikePhoneNumber(trimmed)) {
            Classifier.normalizeSender(trimmed) in numbers
        } else {
            trimmed.lowercase() in names
        }
    }

    private fun loadNumbers(): Set<String> = try {
        context.contentResolver.query(
            Phone.CONTENT_URI, arrayOf(Phone.NUMBER, Phone.NORMALIZED_NUMBER), null, null, null,
        )?.use { c ->
            buildSet {
                while (c.moveToNext()) {
                    c.getString(0)?.let { add(Classifier.normalizeSender(it)) }
                    c.getString(1)?.let { add(Classifier.normalizeSender(it)) }
                }
            }
        } ?: emptySet()
    } catch (e: Exception) {
        emptySet()
    }

    private fun loadNames(): Set<String> = try {
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null,
        )?.use { c ->
            buildSet { while (c.moveToNext()) c.getString(0)?.let { add(it.trim().lowercase()) } }
        } ?: emptySet()
    } catch (e: Exception) {
        emptySet()
    }

    companion object {
        @Volatile private var shared: ContactsChecker? = null
        @Volatile private var observing = false

        /** Bumped whenever contacts change; part of cache keys that depend on contacts. */
        @Volatile var generation = 0L
            private set

        fun get(context: Context): ContactsChecker {
            shared?.let { if (it.hasPermission) return it }
            val checker = ContactsChecker(context.applicationContext)
            if (checker.hasPermission) {
                synchronized(this) {
                    if (!observing) {
                        observing = true
                        context.applicationContext.contentResolver.registerContentObserver(
                            ContactsContract.AUTHORITY_URI, true,
                            object : ContentObserver(Handler(Looper.getMainLooper())) {
                                override fun onChange(selfChange: Boolean) {
                                    shared = null
                                    generation++
                                }
                            },
                        )
                    }
                }
                shared = checker
                generation++ // permission just granted, or first load
            }
            return checker
        }

        fun looksLikePhoneNumber(s: String): Boolean {
            val digits = s.count { it.isDigit() }
            return digits >= 3 && s.all { it.isDigit() || it in "+-() ." }
        }
    }
}
