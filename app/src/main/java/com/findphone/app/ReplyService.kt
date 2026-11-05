package com.findphone.app

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class ReplyService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var best: Location? = null
    private var started = false
    private var anyProvider = false
    private var sentOnce = false   // أول رسالة: تُرسل بسرعة بأفضل ما هو متاح، حتى لو غير دقيق
    private var sentUpdate = false // تحديث واحد فقط إن تحسّن الموقع لاحقًا
    private var player: MediaPlayer? = null
    private var lm: LocationManager? = null
    private var wl: PowerManager.WakeLock? = null
    private var job: SmsJob? = null
    private var updateJob: SmsJob? = null

    private var to = ""
    private var ring = false
    private var lockReq = false
    private var lockDone = false
    private var sub = -1

    // في مناطق التغطية الضعيفة (2G الخ) يكون موقع الشبكة سيئًا جدًا حتى من رقم دقته المعلن،
    // فلا فائدة من إرساله إن تجاوزت دقته المعلنة هذا الحد: نفضّل الصدق بـ"لا إشارة بعد" بدل رقم مضلِّل.
    private val NETWORK_USELESS_ACC = 3000f

    // "مزوّد الشبكة" (أبراج/واي فاي) قد يخطئ بعشرات الكيلومترات في مناطق ضعيفة التغطية، رغم
    // أنه يعلن دقة صغيرة. GPS (الأقمار) هو الموثوق فعلًا ولا يحتاج إنترنت، فنفضّله دائمًا تقريبًا
    // بإضافة "عقوبة" وهمية على نتائج الشبكة بدل الثقة برقم الدقة المعلن وحده.
    private fun weight(l: Location): Float {
        val acc = if (l.hasAccuracy()) l.accuracy else 9999f
        val penalty = if (l.provider != LocationManager.GPS_PROVIDER) 3000f else 0f
        return acc + penalty
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val w = weight(l)
            val b = best
            val bw = if (b != null) weight(b) else Float.MAX_VALUE
            if (b == null || w < bw) best = l
            if (l.provider == LocationManager.GPS_PROVIDER && (!l.hasAccuracy() || l.accuracy <= 200f))
                Store(this@ReplyService).saveGoodLocation(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else -1f, l.time)
            val isGpsGood = l.provider == LocationManager.GPS_PROVIDER && l.hasAccuracy() && l.accuracy <= 30f
            if (!sentOnce && isGpsGood) sendNow(false)
            else if (sentOnce && !sentUpdate && isGpsGood && (b == null || w < bw - 20f)) sendUpdate(l)
        }
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i == null) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        // اسم القناة ونصها عامّان ومنخفضا الأولوية عمدًا حتى لا يلفتا نظر من بحوزته الهاتف.
        // الخدمة تواصل العمل حتى لو عطّل المستخدم إشعارات التطبيق من الإعدادات.
        val ch = NotificationChannel("svc", "خدمات النظام", NotificationManager.IMPORTANCE_MIN)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
        try { goForeground() } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY      // طلب سابق ما زال قيد التنفيذ
        started = true

        to = i.getStringExtra("to") ?: run { stopSelf(); return START_NOT_STICKY }
        ring = i.getBooleanExtra("ring", false)
        lockReq = i.getBooleanExtra("lock", false)
        sub = i.getIntExtra("sub", -1)

        // يبقي المعالج مستيقظًا أثناء انتظار GPS (مهم عند الشاشة المقفلة وبدون إنترنت)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "findphone:reply").apply { acquire(5 * 60_000L) }

        if (ring) startRing()
        if (lockReq) lockDone = tryLockNow()

        val mgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = mgr
        requestUpdates(mgr)

        // لا ننتظر دقيقتين قبل أول رد: نرسل بسرعة بأفضل موقع متاح (حتى لو قديم)،
        // ثم نرسل تحديثًا واحدًا فقط إن وصل موقع أدق خلال الدقيقة التالية.
        h.postDelayed({ if (!sentOnce) sendNow(true) }, 7_000L)
        // نُبقي الاستماع لـGPS فترة أطول بعد الرد الأول، لإعطائه فرصة حقيقية لالتقاط إشارة
        // (قد يحتاج تثبيتًا باردًا 30-90 ثانية بدون إنترنت) قبل إرسال تحديث دقيق لاحقًا
        h.postDelayed({ stopIfIdle() }, 100_000L)
        return START_NOT_STICKY
    }

    // قفل فوري للشاشة عبر صلاحية "مسؤول الجهاز" العادية — استخدام شرعي ومباشر مصمَّم لهذا الغرض
    // تحديدًا (نفس آلية "تأمين الشاشة الآن" في Find My Device). لا يفرض رمزًا جديدًا من الصفر:
    // يُفعّل قفل الشاشة الموجود أصلًا على الهاتف (إن لم يوجد، يُطفئ الشاشة فقط دون رمز حقيقي).
    private fun tryLockNow(): Boolean {
        return try {
            val dpm = getSystemService(DevicePolicyManager::class.java)
            val admin = ComponentName(this, AdminReceiver::class.java)
            if (!dpm.isAdminActive(admin)) return false
            dpm.lockNow()
            true
        } catch (e: Exception) { false }
    }

    private fun requestUpdates(mgr: LocationManager) {
        try {
            // GPS يعمل بدون إنترنت؛ مزوّد الشبكة يساعد فقط عند توفر الإنترنت
            for (pr in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))
                if (mgr.isProviderEnabled(pr)) { mgr.requestLocationUpdates(pr, 0L, 0f, listener, Looper.getMainLooper()); anyProvider = true }
        } catch (e: SecurityException) { }
    }

    private fun stopIfIdle() {
        try { lm?.removeUpdates(listener) } catch (e: Exception) { }
        if (job == null && updateJob == null) stopSelf()
        else h.postDelayed({ stopSelf() }, 10_000L)
    }

    private fun baseNotif(text: String) =
        Notification.Builder(this, "svc").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("خدمات النظام").setContentText(text)
            .setPriority(Notification.PRIORITY_MIN).setOngoing(true).build()

    private fun goForeground() {
        androidx.core.app.ServiceCompat.startForeground(this, 1, baseNotif("مزامنة"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    // تحديث النص لا يفشل العملية إن كانت الإشعارات معطّلة من المستخدم؛ الخدمة تواصل العمل دائمًا
    private fun updateNotification(text: String) {
        try { getSystemService(NotificationManager::class.java).notify(1, baseNotif("مزامنة")) } catch (e: Exception) { }
    }

    // آخر موقع معروف: نفضّل GPS دائمًا مهما كان عمره على "الشبكة" الأحدث، لأن خطأ الشبكة قد
    // يبلغ عشرات الكيلومترات بينما GPS القديم غالبًا ما زال قريبًا من الموقع الفعلي.
    private fun lastKnown(): Location? {
        val mgr = lm ?: return null
        val all = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { mgr.getLastKnownLocation(it) }.getOrNull() }
        all.filter { it.provider == LocationManager.GPS_PROVIDER }.maxByOrNull { it.time }?.let {
            Store(this).saveGoodLocation(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else -1f, it.time)
        }
        return all.filter { it.provider == LocationManager.GPS_PROVIDER }.maxByOrNull { it.time }
            ?: all.maxByOrNull { it.time }
    }

    // آخر موقع GPS موثوق خزّنه تطبيقنا بنفسه، كملاذ أخير قبل "لا إشارة" أو موقع الشبكة الرديء
    private fun ourSavedGood(): Location? {
        val g = Store(this).goodLocation() ?: return null
        return Location("saved").apply {
            latitude = g.lat; longitude = g.lng; time = g.time
            if (g.acc >= 0f) accuracy = g.acc
        }
    }

    private fun format(l: Location?, stale: Boolean, bat: Int): String {
        val sb = StringBuilder("FindPhone: ")
        if (l != null) {
            sb.append("https://maps.google.com/?q=%.6f,%.6f".format(java.util.Locale.US, l.latitude, l.longitude))
            if (l.hasAccuracy()) sb.append(" acc ${l.accuracy.toInt()}m")
            val ageMin = (System.currentTimeMillis() - l.time) / 60_000
            if (stale && l.time > 0 && ageMin >= 1) sb.append(" old ${ageMin}m")
            // تنبيه صريح فقط عندما يكون المصدر فعلاً "الشبكة" (غير GPS وغير محفوظ من قبلنا)
            if (l.provider != LocationManager.GPS_PROVIDER && l.provider != "saved") sb.append(" [network, may be off by km]")
            sb.append(" bat $bat%")
        } else {
            sb.append(if (anyProvider) "no GPS fix yet" else "location is OFF").append(", bat $bat%")
        }
        return sb.toString()
    }

    private fun battery() = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    // أول إرسال: فورًا بأفضل ما هو متاح، لا ننتظر دقة عالية
    private fun sendNow(useFallback: Boolean) {
        if (sentOnce) return
        sentOnce = true
        var l = best
        var stale = false
        if (l == null && useFallback) {
            val sys = lastKnown()
            val sysOk = sys != null && (sys.provider == LocationManager.GPS_PROVIDER ||
                !sys.hasAccuracy() || sys.accuracy <= NETWORK_USELESS_ACC)
            if (sysOk) { l = sys; stale = l != null }
            else {
                // موقع النظام مفقود أو موقع شبكة رديء جدًا: نلجأ لآخر GPS موثوق خزّنه تطبيقنا بنفسه
                val saved = ourSavedGood()
                if (saved != null) { l = saved; stale = true }
                else { l = null; stale = false }   // لا شيء موثوق إطلاقًا: الصدق أفضل من رقم مضلِّل
            }
        }
        val up = l
        if (up != null) CloudSync.uploadLocation(up.latitude, up.longitude, if (up.hasAccuracy()) up.accuracy else -1f,
            up.time, battery(), up.provider ?: "", stale)
        var text = format(l, stale, battery())
        if (lockReq) text += if (lockDone) " [locked]" else " [lock failed: enable device-admin protection]"
        job = SmsJob(this, to, text, sub,
            onStatus = { updateNotification(it) },
            onFinished = { ok ->
                if (!ok) updateNotification("تعذّر الإرسال (لا تغطية)")
                job = null
                h.postDelayed({ stopIfIdle() }, if (ring) 55_000L else 2_000L)
            }).also { it.start() }
    }

    // تحديث واحد فقط إن تحسّن الموقع كثيرًا بعد الإرسال الأول
    private fun sendUpdate(l: Location) {
        if (sentUpdate) return
        sentUpdate = true
        CloudSync.uploadLocation(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else -1f,
            l.time, battery(), l.provider ?: "", false)
        val text = "Update " + format(l, false, battery())
        updateJob = SmsJob(this, to, text, sub,
            backoffsMs = longArrayOf(5_000, 15_000),
            onFinished = { updateJob = null })
            .also { it.start() }
    }

    private fun startRing() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                setDataSource(this@ReplyService, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                isLooping = true
                prepare(); start()
            }
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        job?.cancel()
        updateJob?.cancel()
        try { lm?.removeUpdates(listener) } catch (e: Exception) { }
        try { player?.stop(); player?.release() } catch (e: Exception) { }
        try { if (wl?.isHeld == true) wl?.release() } catch (e: Exception) { }
        super.onDestroy()
    }
}
