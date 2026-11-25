package com.supernova.launcher.search

import android.content.Context
import android.content.Intent
import android.provider.Telephony

// The SMS apps installed besides the phone's SMS app: apps that can receive
// texts, which is what makes an app one Android can make the SMS app. Apps
// that only open sms: links (WhatsApp) don't count. Empty when the phone has
// no SMS app, as on a tablet: there's no telling which one is in use.
object SmsApps {
    fun others(context: Context): Set<String> {
        val default = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
            ?: return emptySet()
        val receivers = runCatching {
            context.packageManager.queryBroadcastReceivers(Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION), 0)
        }.getOrDefault(emptyList())
        return receivers.mapTo(HashSet()) { it.activityInfo.packageName } - default
    }
}
