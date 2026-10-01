package com.mckoss.message_killer

// Android only lets an app become the default SMS app if it declares these four
// components. Message Killer holds that role for a few seconds during cleanup,
// so they do the minimum needed not to lose messages in that window.

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.provider.Telephony
import android.util.Log
import android.widget.Toast

/** SMS_DELIVER: political texts go straight to the Spam folder; everything else is written to the inbox. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val sender = parts[0].displayOriginatingAddress ?: parts[0].originatingAddress ?: ""
        val body = parts.joinToString("") { it.displayMessageBody ?: "" }
        val time = parts[0].timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        val subId = intent.getIntExtra("subscription", -1)

        val result = AppSettings(context).classifier().classify(body, sender)
        if (result.isPolitical && !ContactsChecker(context).isContact(sender)) {
            SpamStore.get(context).recordIntercepted(sender, body, time, result)
            Log.i("MessageKiller", "Intercepted political SMS while default app")
        } else {
            SmsInbox.insertIncoming(context, sender, body, time, subId)
            Notifications.showIncoming(context, sender, body)
        }
    }
}

/** WAP_PUSH_DELIVER (MMS). Downloading MMS isn't supported, so warn the user to switch back. */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifications.showWarning(
            context,
            "Picture/group message arrived",
            "An MMS arrived while Message Killer was your default SMS app. Switch your default back to your messaging app to receive it.",
        )
    }
}

/** RESPOND_VIA_MESSAGE ("reply with text" when declining a call). Not supported during the brief window. */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/** SENDTO sms:/mms: — hand compose requests to the user's real messaging app. */
class ComposeSmsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = AppSettings(this).previousDefaultSmsPackage
        val forwarded = target != null && try {
            startActivity(Intent(intent).setComponent(null).setPackage(target))
            true
        } catch (e: Exception) {
            false
        }
        if (!forwarded) {
            Toast.makeText(this, "Message Killer can't send texts. Switch your default SMS app back.", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
