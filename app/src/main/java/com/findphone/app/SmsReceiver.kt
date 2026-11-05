package com.findphone.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.content.ContextCompat

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(i)
        if (msgs.isNullOrEmpty()) return
        val from = msgs[0].originatingAddress ?: return
        val body = msgs.joinToString("") { it.messageBody ?: "" }.trim()

        val s = Store(c)
        val kw = s.keyword
        if (kw.length < 6) return
        // لوحات المفاتيح تحوّل أول حرف لكبير أحيانًا، لذلك المقارنة بدون حساسية للحالة
        val isRing = body.equals("$kw ring", true)
        val isLock = body.equals("$kw lock", true)
        if (!isRing && !isLock && !body.equals(kw, true)) return

        val now = System.currentTimeMillis()
        if (now - s.lastPlain() < 20_000) return   // حماية من التكرار
        s.setLastPlain(now)

        // الشريحة التي استقبلت الرسالة (نجرّبها أولاً في الرد)
        val sub = i.getIntExtra("subscription", i.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1))
        try {
            ContextCompat.startForegroundService(c, Intent(c, ReplyService::class.java)
                .putExtra("to", from).putExtra("ring", isRing).putExtra("lock", isLock).putExtra("sub", sub))
        } catch (e: Exception) { }
    }
}
