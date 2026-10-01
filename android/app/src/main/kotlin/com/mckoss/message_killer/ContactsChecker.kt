package com.mckoss.message_killer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/**
 * Answers "is this sender one of my contacts?" (including Google contacts synced
 * to the phone). Contacts are never filtered. Results are cached per instance,
 * so create one per scan / notification rather than keeping it around.
 */
class ContactsChecker(private val context: Context) {
    private val cache = HashMap<String, Boolean>()

    val hasPermission: Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /**
     * [sender] is a phone number (SMS inbox) or a display name (notifications).
     * [personUri] is the notification's Person URI, when the messaging app provides one.
     */
    fun isContact(sender: String, personUri: String? = null): Boolean {
        if (!hasPermission) return false
        if (personUri != null) {
            if (personUri.startsWith("content://com.android.contacts")) return true
            if (personUri.startsWith("tel:") && isContactNumber(Uri.decode(personUri.removePrefix("tel:")))) return true
        }
        val trimmed = sender.trim()
        if (trimmed.isEmpty()) return false
        return cache.getOrPut(trimmed) {
            if (looksLikePhoneNumber(trimmed)) isContactNumber(trimmed) else isContactName(trimmed)
        }
    }

    private fun isContactNumber(number: String): Boolean = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        false
    }

    private fun isContactName(name: String): Boolean = try {
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            "${ContactsContract.Contacts.DISPLAY_NAME} = ?",
            arrayOf(name),
            null,
        )?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        false
    }

    companion object {
        fun looksLikePhoneNumber(s: String): Boolean {
            val digits = s.count { it.isDigit() }
            return digits >= 3 && s.all { it.isDigit() || it in "+-() ." }
        }
    }
}
