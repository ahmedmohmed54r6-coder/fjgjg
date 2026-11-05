package com.findphone.app

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.ServiceState
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.util.concurrent.atomic.AtomicInteger

object SmsSender {
    /**
     * ترتيب الشرائح للتجربة: الشريحة التي عليها تغطية أولاً، ثم التي استقبلت الرسالة، ثم الافتراضية، ثم الباقي.
     * إن لم نستطع قراءة الشرائح (لا صلاحية) نرجع [-1] = المدير الافتراضي.
     */
    fun candidates(ctx: Context, preferred: Int = -1): List<Int> {
        val ids = LinkedHashSet<Int>()
        try {
            if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val sm = ctx.getSystemService(SubscriptionManager::class.java)
                val all = sm.activeSubscriptionInfoList?.map { it.subscriptionId } ?: emptyList()
                val tm = ctx.getSystemService(TelephonyManager::class.java)
                val def = SubscriptionManager.getDefaultSmsSubscriptionId()
                fun inService(id: Int) = try {
                    tm.createForSubscriptionId(id).serviceState?.state == ServiceState.STATE_IN_SERVICE
                } catch (e: Exception) { false }
                ids.addAll(all.sortedBy { id ->
                    (if (inService(id)) 0 else 10) + (if (id == preferred) 0 else if (id == def) 1 else 2)
                })
            }
        } catch (e: Exception) { }
        if (ids.isEmpty()) ids.add(-1)
        return ids.toList()
    }

    @Suppress("DEPRECATION")
    fun manager(ctx: Context, subId: Int): SmsManager = try {
        if (subId >= 0) {
            if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
            else SmsManager.getSmsManagerForSubscriptionId(subId)
        } else {
            if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        }
    } catch (e: Exception) { SmsManager.getDefault() }

    /** يرسل الرسالة عبر الشريحة المحددة ويرجع عدد الأجزاء */
    fun sendParts(ctx: Context, subId: Int, to: String, text: String, sentFor: (Int) -> PendingIntent): Int {
        val sm = manager(ctx, subId)
        val parts = sm.divideMessage(text)
        val intents = ArrayList<PendingIntent>()
        for (idx in parts.indices) intents.add(sentFor(idx))
        sm.sendMultipartTextMessage(to, null, parts, intents, null)
        return parts.size
    }
}

/**
 * مهمة إرسال رسالة: تجرّب كل الشرائح بالترتيب، وإن فشلت كلها تنتظر وتعيد الجولة (تغطية ضعيفة/مؤقتة).
 */
class SmsJob(
    private val ctx: Context,
    private val to: String,
    private val text: String,
    private val preferredSub: Int = -1,
    private val backoffsMs: LongArray = longArrayOf(5_000, 15_000, 30_000, 60_000, 120_000, 180_000),
    private val onStatus: (String) -> Unit = {},
    private val onFinished: (Boolean) -> Unit = {}
) {
    private val h = Handler(Looper.getMainLooper())
    private val action = "com.findphone.app.SENT_" + counter.incrementAndGet()
    private var subs: List<Int> = listOf(-1)
    private var simTries = 0
    private var round = 0
    private var attemptId = 0
    private var partsLeft = 0
    private var handled = false
    private var registered = false
    private var done = false

    private val rcv = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (done || handled || i.getIntExtra("att", -1) != attemptId) return
            if (resultCode == Activity.RESULT_OK) {
                partsLeft--
                if (partsLeft <= 0) { handled = true; finish(true) }
            } else {
                handled = true
                onFail()
            }
        }
    }

    fun start() {
        val f = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(rcv, f)
        registered = true
        attempt()
    }

    private fun attempt() {
        if (done) return
        if (simTries == 0) subs = SmsSender.candidates(ctx, preferredSub)
        val sub = subs[simTries % subs.size]
        attemptId++
        handled = false
        val simInfo = if (subs.size > 1) " (شريحة ${simTries % subs.size + 1} من ${subs.size})" else ""
        onStatus(if (round == 0 && simTries == 0) "جارٍ الإرسال$simInfo..." else "إعادة المحاولة$simInfo...")
        val id = attemptId
        try {
            partsLeft = SmsSender.sendParts(ctx, sub, to, text) { idx ->
                PendingIntent.getBroadcast(ctx, id * 10 + idx,
                    Intent(action).setPackage(ctx.packageName).putExtra("att", id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
        } catch (e: Exception) {
            handled = true
            onFail()
        }
    }

    private fun onFail() {
        if (done) return
        simTries++
        if (simTries < subs.size) { h.postDelayed({ attempt() }, 1_500); return }   // جرّب الشريحة الأخرى فورًا
        simTries = 0
        if (round >= backoffsMs.size) { finish(false); return }
        val d = backoffsMs[round]
        round++
        onStatus("لا توجد تغطية على أي شريحة، محاولة جديدة بعد ${d / 1000} ث")
        h.postDelayed({ attempt() }, d)
    }

    private fun finish(ok: Boolean) {
        if (done) return
        cancel()
        onFinished(ok)
    }

    fun cancel() {
        done = true
        h.removeCallbacksAndMessages(null)
        if (registered) try { ctx.unregisterReceiver(rcv) } catch (e: Exception) { }
        registered = false
    }

    companion object { private val counter = AtomicInteger(0) }
}
