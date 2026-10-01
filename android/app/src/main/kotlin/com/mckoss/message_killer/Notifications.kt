package com.mckoss.message_killer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Message Killer's own notifications (cleanup reminders, SMS received while we hold the role). */
object Notifications {
    private const val CHANNEL_CLEANUP = "cleanup"
    private const val CHANNEL_INCOMING = "incoming"
    const val ID_CLEANUP_READY = 1
    private const val ID_INCOMING_BASE = 1000

    const val EXTRA_ACTION = "message_killer_action"
    const val ACTION_CLEANUP = "cleanup"

    private fun manager(context: Context) =
        context.getSystemService(NotificationManager::class.java)

    private fun ensureChannels(context: Context) {
        manager(context).createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_CLEANUP, "Daily cleanup", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Political texts ready to delete" },
            NotificationChannel(CHANNEL_INCOMING, "Incoming texts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Texts received while Message Killer is temporarily the default SMS app" },
        ))
    }

    private fun canPost(context: Context) =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(context: Context, action: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (action != null) intent.putExtra(EXTRA_ACTION, action)
        return PendingIntent.getActivity(
            context, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun showCleanupReady(context: Context, pending: Int) {
        if (!canPost(context)) return
        ensureChannels(context)
        val text = if (pending == 1) "1 political text is ready to delete" else "$pending political texts are ready to delete"
        manager(context).notify(ID_CLEANUP_READY, Notification.Builder(context, CHANNEL_CLEANUP)
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setContentTitle("Message Killer")
            .setContentText("$text. Tap to clean up.")
            .setContentIntent(openAppIntent(context, ACTION_CLEANUP))
            .setAutoCancel(true)
            .build())
    }

    fun cancelCleanupReady(context: Context) = manager(context).cancel(ID_CLEANUP_READY)

    fun showIncoming(context: Context, sender: String, body: String) {
        if (!canPost(context)) return
        ensureChannels(context)
        manager(context).notify(ID_INCOMING_BASE + (sender.hashCode() and 0xffff), Notification.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(sender)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openAppIntent(context, null))
            .setAutoCancel(true)
            .build())
    }

    fun showWarning(context: Context, title: String, text: String) {
        if (!canPost(context)) return
        ensureChannels(context)
        manager(context).notify(ID_INCOMING_BASE - 1, Notification.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context, null))
            .setAutoCancel(true)
            .build())
    }
}
