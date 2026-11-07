package com.findphone.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.android.material.R as MatR

class MainActivity : Activity() {
    private lateinit var s: Store
    private var statusBox: LinearLayout? = null
    private var sendStatusTv: TextView? = null
    private var sendJob: SmsJob? = null

    private var unlocked = false
    private var current = ""
    private var stoppedAt = 0L
    private var skipNextResumeRefresh = false
    /** true أثناء حوار أذونات النظام — يمنع قفل PIN وإعادة رسم الشاشة */
    private var awaitingPermission = false
    private var settingsScroll: ScrollView? = null
    private var pendingScrollY = 0
    private var restoreScrollAfterRender = false
    // حاوية بطاقة الحماية (حماية الحذف / منع إيقاف الموقع / منع الفورمات)، لتحديثها في
    // مكانها فقط عند تبديل مفاتيحها، بدل إعادة بناء الشاشة كاملة (يمنع القفز للأعلى ويجعل
    // التبديل فوريًا وسلسًا).
    private var adminCardHolder: FrameLayout? = null
    // مراجع مفاتيح الكاميرا/الميكروفون لتحديثها في مكانها دون إعادة بناء الشاشة
    private var cameraSwitch: Switch? = null
    private var micSwitch: Switch? = null
    private var suppressSwitch = false

    // متتبّع خفيف يعمل فقط أثناء فتح شاشة الإعدادات: يحفظ أي إشارة GPS موثوقة فوراً،
    // ويرفعها للسحابة إن كان الاتصال السحابي مفعّلاً، بدل الاعتماد فقط على لحظة رد SMS.
    private var liveLm: LocationManager? = null
    private var liveListening = false
    private val liveListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            if (l.provider != LocationManager.GPS_PROVIDER) return
            val acc = if (l.hasAccuracy()) l.accuracy else -1f
            if (acc < 0f || acc > 200f) return
            s.saveGoodLocation(l.latitude, l.longitude, acc, l.time)
            if (FirebaseAuth.getInstance().currentUser != null) {
                val bat = try {
                    (getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager)
                        .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                } catch (e: Exception) { -1 }
                CloudSync.uploadLocation(l.latitude, l.longitude, acc, l.time, bat, l.provider ?: "", false)
            }
        }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onStatusChanged(p: String?, st: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    private fun startLiveTracking() {
        if (liveListening) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            val mgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            liveLm = mgr
            mgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4_000L, 0f, liveListener, mainLooper)
            liveListening = true
        } catch (e: Exception) { }
    }

    private fun stopLiveTracking() {
        if (!liveListening) return
        try { liveLm?.removeUpdates(liveListener) } catch (e: Exception) { }
        liveListening = false
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // يمنع لقطات الشاشة وإظهار المحتوى في قائمة التطبيقات الأخيرة
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        s = Store(this)
        s.cleanLegacy()
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    override fun onResume() {
        super.onResume()
        // استرجع جلسة PIN المحفوظة (لا تضيع عند حوار الكاميرا/الميكروفون)
        if (!unlocked && s.isSessionUnlocked()) unlocked = true
        // لا تُقفل أثناء طلب إذن النظام، ولا إن كانت الجلسة ما زالت سارية
        if (!awaitingPermission && unlocked && stoppedAt > 0
            && System.currentTimeMillis() - stoppedAt > 30_000
            && !s.isSessionUnlocked()
        ) {
            unlocked = false
            s.clearSessionUnlock()
        }
        if (awaitingPermission) {
            awaitingPermission = false
            unlocked = true
            s.markSessionUnlocked()
        }
        val want = when {
            !s.hasPin -> "setup"
            !unlocked -> "lock"
            else -> "settings"
        }
        if (skipNextResumeRefresh) {
            // تم بالفعل إعادة رسم الشاشة داخل onRequestPermissionsResult بعد إذن
            // الكاميرا/الميكروفون؛ تجاهل إعادة الرسم المكرّرة هنا كي لا يُعاد
            // ScrollView من الصفر وترجع القائمة لأعلاها.
            skipNextResumeRefresh = false
            current = want
        } else if (want != current) {
            current = want
            when (want) {
                "setup" -> renderSetup()
                "lock" -> renderLock()
                else -> renderSettings(preserveScroll = false)
            }
        } else if (want == "setup") {
            refresh()
        } else if (want == "settings") {
            // لا نعيد بناء كل الإعدادات عند كل onResume حتى لا تقفز الصفحة للأعلى؛
            // نحدّث فقط بطاقة الحماية (قد تكون تغيّرت في شاشة نظام خارجية، مثل تفعيل
            // "مسؤول الجهاز") وبطاقة الحالة، دون لمس باقي الشاشة أو موضع التمرير.
            showStatus()
            refreshAdminCard()
        } else {
            showStatus()
        }
        if (want == "settings") {
            syncConsentSwitches()
            startLiveTracking()
            if (FirebaseAuth.getInstance().currentUser != null) {
                MediaSessionService.startListening(this)
            }
        } else stopLiveTracking()
    }

    override fun onStop() { super.onStop(); stoppedAt = System.currentTimeMillis(); stopLiveTracking() }

    override fun onDestroy() {
        sendJob?.cancel()
        pairReg?.remove()
        mediaStatusReg?.remove()
        stopLiveTracking()
        super.onDestroy()
    }

    // ---------- أدوات مساعدة ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = ContextCompat.getColor(this, id)
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(col(R.color.primary))
        setPadding(0, 0, 0, dp(10))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 13.5f; setTextColor(col(R.color.text_sub))
        setPadding(dp(2), dp(4), dp(2), dp(6))
    }

    private fun card(vararg children: View): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(16) }
        }
        children.forEach { c.addView(it) }
        return c
    }

    private fun et(h: String, v: String = "", phone: Boolean = false, pin: Boolean = false) = EditText(this).apply {
        hint = h; setText(v); textSize = 15f
        setTextColor(col(R.color.text_main)); setHintTextColor(col(R.color.text_sub))
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
        if (phone) inputType = InputType.TYPE_CLASS_PHONE
        if (pin) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6); bottomMargin = dp(6) }
    }

    private fun mbtn(t: String, filled: Boolean = true, f: () -> Unit): MaterialButton {
        val style = if (filled) MatR.attr.materialButtonStyle else MatR.attr.materialButtonOutlinedStyle
        return MaterialButton(this, null, style).apply {
            text = t
            textSize = 15f
            isAllCaps = false
            cornerRadius = dp(16)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(50)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            if (filled) {
                backgroundTintList = android.content.res.ColorStateList.valueOf(col(R.color.primary))
                setTextColor(col(R.color.on_primary))
            } else {
                strokeColor = android.content.res.ColorStateList.valueOf(col(R.color.primary))
                strokeWidth = dp(1)
                setTextColor(col(R.color.primary))
                backgroundTintList = android.content.res.ColorStateList.valueOf(col(R.color.primary_soft))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
            setOnClickListener { f() }
        }
    }

    private fun iconBtn(t: String, bg: Int, f: () -> Unit) = MaterialButton(this, null, MatR.attr.materialButtonStyle).apply {
        text = t
        textSize = 13.5f
        isAllCaps = false
        cornerRadius = dp(16)
        insetTop = 0
        insetBottom = 0
        minHeight = dp(52)
        maxLines = 2
        setPadding(dp(10), dp(14), dp(10), dp(14))
        backgroundTintList = android.content.res.ColorStateList.valueOf(col(bg))
        setTextColor(Color.WHITE)
        // وزن متساوٍ مع هامش متناسق — لا يضغط النص
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
            marginStart = dp(6)
        }
        setOnClickListener { f() }
    }

    /** صفّان من زرّين لتجنب ضغط النص العربي الطويل */
    private fun actionGrid(vararg pairs: Pair<String, Pair<Int, () -> Unit>>): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        pairs.toList().chunked(2).forEach { rowPairs ->
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            rowPairs.forEach { (label, pack) ->
                val (colorId, action) = pack
                row.addView(iconBtn(label, colorId, action))
            }
            // إن كان زراً واحداً في الصف، املأ المساحة بشكل مريح
            if (rowPairs.size == 1) {
                row.addView(View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            wrap.addView(row)
        }
        return wrap
    }

    private fun header(sub: String): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(col(R.color.header_start), col(R.color.header_end))).apply { cornerRadius = dp(24).toFloat() }
            setPadding(dp(20), dp(24), dp(20), dp(24))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(18) }
        }
        wrap.addView(TextView(this).apply {
            text = "Find Phone"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
        })
        wrap.addView(TextView(this).apply {
            text = sub; textSize = 13.5f; setTextColor(Color.parseColor("#D8E6FF")); setPadding(0, dp(4), 0, 0)
        })
        return wrap
    }

    private fun newRoot(preserveScroll: Boolean = false): LinearLayout {
        // احفظ موضع التمرير قبل إعادة بناء الشاشة حتى لا تقفز القائمة للأعلى
        if (preserveScroll) {
            pendingScrollY = settingsScroll?.scrollY ?: pendingScrollY
            restoreScrollAfterRender = true
        } else {
            pendingScrollY = 0
            restoreScrollAfterRender = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
            setBackgroundColor(col(R.color.bg))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(col(R.color.bg))
            isFillViewport = true
            // يمنع القفز التلقائي للتمرير نحو أول حقل عند تغيّر المحتوى
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            isFocusable = true
            isFocusableInTouchMode = true
            addView(root)
        }
        settingsScroll = scroll
        setContentView(scroll)
        if (restoreScrollAfterRender) {
            val y = pendingScrollY
            scroll.post {
                scroll.scrollTo(0, y)
                // محاولة ثانية بعد اكتمال القياس
                scroll.post { scroll.scrollTo(0, y) }
            }
            restoreScrollAfterRender = false
        }
        statusBox = null
        sendStatusTv = null
        return root
    }

    // ---------- شاشة الإعداد الأولي ----------
    private fun renderSetup() {
        val root = newRoot()
        root.addView(header("إعداد لمرة واحدة، ثم يُقفل التطبيق برمز"))

        val kw = et("مثال: 123456789", s.keyword)
        root.addView(card(
            sectionTitle("١) الكلمة السرية"),
            label("أي رسالة نصية تحمل هذه الكلمة فقط، من أي هاتف، يردّ عليها هاتفك تلقائيًا بموقعه (6 أحرف فأكثر). أضف « ring » بعدها لتشغيل الرنين."),
            kw
        ))

        val pin1 = et("رمز القفل (4 أرقام فأكثر)", pin = true)
        val pin2 = et("أعد كتابة الرمز", pin = true)
        root.addView(card(
            sectionTitle("٢) رمز قفل التطبيق"),
            label("بعد الحفظ لا يُفتح التطبيق ولا تتغير الإعدادات إلا بهذا الرمز."),
            pin1, pin2
        ))

        root.addView(permsCard(title = "٣) الصلاحيات"))
        root.addView(adminCard())

        root.addView(mbtn("حفظ وقفل التطبيق") {
            val k = kw.text.toString().trim()
            val a = pin1.text.toString(); val b = pin2.text.toString()
            when {
                k.length < 6 -> toast("الكلمة السرية 6 أحرف فأكثر")
                a.length < 4 -> toast("رمز القفل 4 أرقام على الأقل")
                a != b -> toast("الرمزان غير متطابقين")
                else -> {
                    s.keyword = k; s.setPin(a); s.failCount = 0; s.lockUntil = 0
                    unlocked = false; current = "lock"
                    toast("تم الحفظ، التطبيق مقفل الآن")
                    renderLock()
                }
            }
        })
        showStatus()
    }

    // ---------- شاشة القفل ----------
    private fun renderLock() {
        val root = newRoot()
        root.addView(header("التطبيق مقفل"))
        val pin = et("رمز القفل", pin = true)
        val msg = TextView(this).apply { textSize = 13.5f; setTextColor(col(R.color.danger)); setPadding(dp(2), dp(4), dp(2), dp(4)) }
        root.addView(card(
            sectionTitle("أدخل الرمز لفتح الإعدادات"),
            pin, msg,
            mbtn("فتح") { tryUnlock(pin, msg) }
        ))
        val hint = label("الرد التلقائي على الرسائل يعمل في الخلفية حتى والتطبيق مقفل.")
        root.addView(hint)
    }

    private fun tryUnlock(pin: EditText, msg: TextView) {
        val now = System.currentTimeMillis()
        val wait = s.lockUntil - now
        if (wait > 0) { msg.text = "محاولات كثيرة، انتظر ${wait / 1000 + 1} ثانية"; return }
        if (s.checkPin(pin.text.toString())) {
            s.failCount = 0
            unlocked = true; s.markSessionUnlocked()
            current = "settings"
            renderSettings(preserveScroll = false)
        } else {
            val f = s.failCount + 1
            s.failCount = f
            pin.setText("")
            if (f >= 5) {
                s.lockUntil = now + minOf(60_000L * (f - 4), 600_000L)
                msg.text = "رمز خاطئ. تم إيقاف المحاولات مؤقتًا"
            } else msg.text = "الرمز غير صحيح (${f} من 5)"
        }
    }

    // ---------- شاشة الإعدادات (بعد فتح القفل) ----------
    private fun renderSettings(preserveScroll: Boolean = true) {
        val root = newRoot(preserveScroll = preserveScroll)
        root.addView(header("الإعدادات"))

        val cloudStatus = TextView(this).apply {
            textSize = 13.5f
            setTextColor(col(R.color.text_sub))
            text = "حالة الحساب السحابي: غير مسجل بعد"
            setPadding(dp(2), dp(6), dp(2), dp(6))
        }
        root.addView(card(
            sectionTitle("الحساب السحابي — بدون بريد إلكتروني"),
            label("يسجّل هذا الهاتف بحساب مجهول آمن من Firebase. لا تحتاج إلى بريد أو كلمة مرور. ثم استخدم البطاقتين التاليتين للاقتران بهاتف آخر ومشاهدة الموقع."),
            cloudStatus,
            mbtn("تفعيل الاتصال السحابي") { activateCloud(cloudStatus) }
        ))
        FirebaseAuth.getInstance().currentUser?.let {
            cloudStatus.text = "هذا الهاتف مسجل سحابيًا."
        }
        root.addView(ownerCard())
        root.addView(viewerCard())
        root.addView(mediaConsentCard())
        root.addView(mediaControlCard())

        val kw = et("مثال: 123456789", s.keyword)
        root.addView(card(
            sectionTitle("الكلمة السرية"),
            label("يردّ الهاتف على أي رسالة تحمل هذه الكلمة. أضف « ring » للرنين."),
            kw,
            mbtn("حفظ الكلمة") {
                val k = kw.text.toString().trim()
                if (k.length < 6) toast("الكلمة 6 أحرف فأكثر") else { s.keyword = k; toast("تم الحفظ") }
            }
        ))

        root.addView(permsCard(title = "الصلاحيات والاستقرار"))
        val holder = FrameLayout(this)
        holder.addView(adminCard())
        adminCardHolder = holder
        root.addView(holder)

        // إرسال طلب لهاتف آخر عليه التطبيق (اختياري)
        val num = et("رقم الهاتف المراد تحديده", phone = true)
        val st = TextView(this).apply { textSize = 13f; setTextColor(col(R.color.text_sub)); setPadding(dp(2), dp(8), dp(2), 0) }
        sendStatusTv = st
        val row = actionGrid(
            "طلب الموقع" to (R.color.primary to { sendRequest(num.text.toString(), "") }),
            "رنين" to (R.color.accent to { sendRequest(num.text.toString(), " ring") }),
            "قفل" to (R.color.danger to { sendRequest(num.text.toString(), " lock") })
        )
        root.addView(card(
            sectionTitle("طلب موقع هاتف آخر (اختياري)"),
            label("يرسل الكلمة السرية إلى هاتف عليه التطبيق بنفس الكلمة، ويصلك الرد برسالة نصية فيها رابط الخريطة. « قفل » يُقفل شاشة ذلك الهاتف فورًا (يتطلب تفعيل «حماية الحذف» على الهاتف الآخر)."),
            num, row, st
        ))

        val n1 = et("رمز جديد (4 أرقام فأكثر)", pin = true)
        val n2 = et("أعد كتابة الرمز الجديد", pin = true)
        root.addView(card(
            sectionTitle("تغيير رمز القفل"),
            n1, n2,
            mbtn("تغيير الرمز", filled = false) {
                val a = n1.text.toString()
                when {
                    a.length < 4 -> toast("رمز القفل 4 أرقام على الأقل")
                    a != n2.text.toString() -> toast("الرمزان غير متطابقين")
                    else -> { s.setPin(a); n1.setText(""); n2.setText(""); toast("تم تغيير الرمز") }
                }
            }
        ))

        root.addView(mbtn("قفل التطبيق الآن") {
            unlocked = false; s.clearSessionUnlock(); current = "lock"; renderLock()
        })
        showStatus()
    }

    // ---------- الاقتران وعرض الموقع عبر السحابة ----------
    private var pairReg: ListenerRegistration? = null
    private var mediaStatusReg: ListenerRegistration? = null

    private fun withUid(onUid: (String) -> Unit, onFail: (String) -> Unit) {
        val auth = FirebaseAuth.getInstance()
        val u = auth.currentUser
        if (u != null) { onUid(u.uid); return }
        auth.signInAnonymously()
            .addOnSuccessListener { r -> onUid(r.user!!.uid) }
            .addOnFailureListener { e -> onFail(e.localizedMessage ?: "error") }
    }

    private fun deviceLabel() = "${Build.MANUFACTURER} ${Build.MODEL}"

    private fun newCode(): String {
        val a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = java.security.SecureRandom()
        return (1..8).map { a[r.nextInt(a.length)] }.joinToString("")
    }

    private fun ago(ms: Long): String {
        val m = (System.currentTimeMillis() - ms) / 60_000
        return when {
            ms <= 0 -> "غير معروف"
            m < 1 -> "الآن"
            m < 60 -> "$m د"
            m < 1440 -> "${m / 60} س"
            else -> "${m / 1440} يوم"
        }
    }

    // بطاقة «هذا الهاتف»: أنشئ كودًا ليقترن به هاتف آخر، وارفع الموقع، وأدِر المصرّح لهم
    private fun ownerCard(): View {
        val codeTv = TextView(this).apply {
            textSize = 26f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(col(R.color.text_main))
            gravity = android.view.Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(0, dp(10), 0, dp(10))
        }
        val note = TextView(this).apply {
            textSize = 13f; setTextColor(col(R.color.text_sub)); setPadding(dp(2), dp(2), dp(2), dp(6))
        }
        return card(
            sectionTitle("السماح لهاتف آخر بمشاهدة موقع هذا الهاتف"),
            label("أنشئ كودًا هنا (وهذا الهاتف بيدك)، ثم أدخله في الهاتف الآخر. ستظهر لك رسالة لتوافق قبل أي مشاهدة، ويمكنك إلغاء التصريح لاحقًا."),
            codeTv, note,
            mbtn("إنشاء كود اقتران") {
                codeTv.text = "..."
                withUid({ uid ->
                    val code = newCode()
                    val data = hashMapOf<String, Any?>(
                        "ownerUid" to uid,
                        "ownerName" to deviceLabel(),
                        "expiresAt" to com.google.firebase.Timestamp(java.util.Date(System.currentTimeMillis() + 10 * 60_000L)),
                        "claimedBy" to null
                    )
                    val ref = FirebaseFirestore.getInstance().collection("pairings").document(code)
                    ref.set(data)
                        .addOnSuccessListener {
                            codeTv.text = code.substring(0, 4) + "-" + code.substring(4)
                            note.text = "صالح 10 دقائق. أدخله في الهاتف الثاني ثم وافق هنا."
                            pairReg?.remove()
                            pairReg = ref.addSnapshotListener { snap, _ ->
                                if (snap == null || !snap.exists()) return@addSnapshotListener
                                val by = snap.getString("claimedBy") ?: return@addSnapshotListener
                                val name = snap.getString("claimerName") ?: "هاتف"
                                pairReg?.remove(); pairReg = null
                                askApprove(ref, uid, by, name)
                            }
                        }
                        .addOnFailureListener { e ->
                            codeTv.text = ""
                            toast("تعذّر إنشاء الكود: ${e.localizedMessage}")
                        }
                }, { toast("تعذّر الاتصال: $it") })
            },
            mbtn("رفع آخر موقع الآن", filled = false) { uploadNow(note) },
            mbtn("الهواتف المصرّح لها (إلغاء)", filled = false) { showViewers() }
        )
    }

    private fun askApprove(ref: DocumentReference, ownerUid: String, viewerUid: String, name: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("طلب اقتران")
            .setMessage("الهاتف «$name» يطلب مشاهدة موقع هذا الهاتف. هل توافق؟")
            .setCancelable(false)
            .setPositiveButton("موافقة") { _, _ ->
                val v = hashMapOf<String, Any>("name" to name, "since" to FieldValue.serverTimestamp())
                FirebaseFirestore.getInstance().collection("devices").document(ownerUid)
                    .collection("viewers").document(viewerUid).set(v)
                    .addOnSuccessListener { toast("تمت الموافقة"); ref.delete() }
                    .addOnFailureListener { e -> toast("تعذّرت الموافقة: ${e.localizedMessage}") }
            }
            .setNegativeButton("رفض") { _, _ -> ref.delete(); toast("تم الرفض") }
            .show()
    }

    private fun showViewers() {
        withUid({ uid ->
            val vcol = FirebaseFirestore.getInstance().collection("devices").document(uid).collection("viewers")
            vcol.get()
                .addOnSuccessListener { qs ->
                    if (qs.isEmpty) { toast("لا توجد هواتف مصرّح لها"); return@addOnSuccessListener }
                    val ids = qs.documents.map { it.id }
                    val names = qs.documents.map { it.getString("name") ?: it.id.take(6) }
                    AlertDialog.Builder(this)
                        .setTitle("اضغط على هاتف لإلغاء تصريحه")
                        .setItems(names.toTypedArray()) { _, i ->
                            vcol.document(ids[i]).delete()
                                .addOnSuccessListener { toast("تم إلغاء التصريح") }
                                .addOnFailureListener { e -> toast("تعذّر الإلغاء: ${e.localizedMessage}") }
                        }
                        .setNegativeButton("إغلاق", null)
                        .show()
                }
                .addOnFailureListener { e -> toast("تعذّر التحميل: ${e.localizedMessage}") }
        }, { toast("تعذّر الاتصال: $it") })
    }

    // يرفع آخر موقع معروف (يُستخدم أيضًا تلقائيًا عند الرد على الرسائل)
    private fun uploadNow(st: TextView) {
        if (FirebaseAuth.getInstance().currentUser == null) { st.text = "فعّل الاتصال السحابي أولاً"; return }
        var lat = 0.0; var lng = 0.0; var acc = -1f; var t = 0L; var src = ""; var ok = false; var fromNetwork = false

        // أولاً: آخر GPS مباشر من النظام (الأحدث والأدق إن وُجد)
        try {
            val mgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val gps = runCatching { mgr.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
            if (gps != null) {
                lat = gps.latitude; lng = gps.longitude; acc = if (gps.hasAccuracy()) gps.accuracy else -1f
                t = gps.time; src = gps.provider ?: "gps"; ok = true
            }
        } catch (e: SecurityException) { }

        // ثانيًا: آخر موقع GPS موثوق خزّنه التطبيق بنفسه (أفضل من موقع الشبكة مهما كان عمره)
        if (!ok) {
            val g = s.goodLocation()
            if (g != null) { lat = g.lat; lng = g.lng; acc = g.acc; t = g.time; src = "saved"; ok = true }
        }

        // أخيرًا فقط: موقع الشبكة، مع تنبيه صريح أنه قد يكون غير دقيق
        if (!ok) {
            try {
                val mgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val net = runCatching { mgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
                    ?: runCatching { mgr.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()
                if (net != null) {
                    lat = net.latitude; lng = net.longitude; acc = if (net.hasAccuracy()) net.accuracy else -1f
                    t = net.time; src = net.provider ?: "network"; ok = true; fromNetwork = true
                }
            } catch (e: SecurityException) { }
        }

        if (!ok) { st.text = "لا يوجد موقع معروف بعد. فعّل GPS وجرّب لاحقًا."; return }
        val bat = (getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager)
            .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        CloudSync.uploadLocation(lat, lng, acc, t, bat, src, true)
        st.text = if (fromNetwork) "تم رفع موقع تقريبي من الشبكة فقط (قد يخطئ بكيلومترات). لا يوجد GPS محفوظ بعد."
                  else "تم رفع آخر موقع موثوق (يُرسل عند توفر الإنترنت)."
    }

    // بطاقة «هاتف آخر»: أدخل الكود لتطلب مشاهدة موقعه
    private fun viewerCard(): View {
        val code = et("كود الاقتران (8 أحرف)")
        val st = TextView(this).apply {
            textSize = 13f; setTextColor(col(R.color.text_sub)); setPadding(dp(2), dp(8), dp(2), 0)
        }
        return card(
            sectionTitle("مشاهدة موقع هاتف آخر"),
            label("أدخل الكود الظاهر على الهاتف الآخر. بعد موافقة صاحبه يمكنك رؤية آخر موقع رفعه."),
            code,
            mbtn("اقتران") { pairWith(code, st) },
            mbtn("عرض مواقع الهواتف المرتبطة", filled = false) { showOwners(st) },
            st
        )
    }

    private fun pairWith(input: EditText, st: TextView) {
        val code = input.text.toString().uppercase().filter { it.isLetterOrDigit() }
        if (code.length != 8) { toast("الكود 8 أحرف"); return }
        st.text = "جارٍ الاقتران..."
        withUid({ me ->
            val db = FirebaseFirestore.getInstance()
            val ref = db.collection("pairings").document(code)
            ref.get()
                .addOnSuccessListener { d ->
                    val exp = d.getTimestamp("expiresAt")
                    val ownerUid = d.getString("ownerUid")
                    if (!d.exists() || ownerUid == null || exp == null ||
                        exp.toDate().time < System.currentTimeMillis() || d.get("claimedBy") != null) {
                        st.text = "الكود غير صحيح أو منتهٍ"
                        return@addOnSuccessListener
                    }
                    if (ownerUid == me) { st.text = "هذا كود هذا الهاتف نفسه"; return@addOnSuccessListener }
                    val ownerName = d.getString("ownerName") ?: "هاتف"
                    val upd = hashMapOf<String, Any>("claimedBy" to me, "claimerName" to deviceLabel())
                    ref.update(upd)
                        .addOnSuccessListener {
                            val rec = hashMapOf<String, Any>("name" to ownerName, "since" to FieldValue.serverTimestamp())
                            db.collection("devices").document(me).collection("owners").document(ownerUid).set(rec)
                            st.text = "أُرسل الطلب. بانتظار موافقة صاحب الهاتف الآخر."
                            input.setText("")
                        }
                        .addOnFailureListener { e -> st.text = "تعذّر الاقتران: ${e.localizedMessage}" }
                }
                .addOnFailureListener { e -> st.text = "تعذّر الوصول للكود: ${e.localizedMessage}" }
        }, { st.text = "تعذّر الاتصال: $it" })
    }

    private fun showOwners(st: TextView) {
        withUid({ me ->
            val db = FirebaseFirestore.getInstance()
            db.collection("devices").document(me).collection("owners").get()
                .addOnSuccessListener { qs ->
                    val docs = qs.documents
                    if (docs.isEmpty()) { st.text = "لا توجد هواتف مرتبطة بعد"; return@addOnSuccessListener }
                    st.text = "جارٍ التحميل..."
                    val lines = arrayOfNulls<String>(docs.size)
                    val links = arrayOfNulls<String>(docs.size)
                    var left = docs.size
                    docs.forEachIndexed { i, od ->
                        val nm = od.getString("name") ?: "هاتف"
                        db.collection("locations").document(od.id).get()
                            .addOnCompleteListener { task ->
                                val ld = if (task.isSuccessful) task.result else null
                                val lat = ld?.getDouble("lat")
                                val lng = ld?.getDouble("lng")
                                if (ld != null && ld.exists() && lat != null && lng != null) {
                                    val up = ld.getTimestamp("updatedAt")?.toDate()?.time ?: 0L
                                    val fix = ld.getLong("time") ?: 0L
                                    val bat = ld.getLong("bat") ?: -1L
                                    val old = if (ld.getBoolean("stale") == true) "، عمر الموقع ${ago(fix)}" else ""
                                    lines[i] = "$nm\nتحديث منذ ${ago(up)}$old، البطارية $bat%"
                                    links[i] = "https://maps.google.com/?q=%.6f,%.6f".format(java.util.Locale.US, lat, lng)
                                } else {
                                    lines[i] = "$nm\nبانتظار الموافقة أو لم يُرفع موقع بعد"
                                }
                                left--
                                if (left == 0 && !isFinishing && !isDestroyed) {
                                    st.text = ""
                                    AlertDialog.Builder(this)
                                        .setTitle("اضغط لفتح الخريطة")
                                        .setItems(lines.map { it ?: "" }.toTypedArray()) { _, k ->
                                            links[k]?.let { u -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
                                        }
                                        .setNegativeButton("إغلاق", null)
                                        .show()
                                }
                            }
                    }
                }
                .addOnFailureListener { e -> st.text = "تعذّر التحميل: ${e.localizedMessage}" }
        }, { st.text = "تعذّر الاتصال: $it" })
    }

    private fun activateCloud(status: TextView) {
        status.text = "جارٍ الاتصال بـ Firebase..."
        val auth = FirebaseAuth.getInstance()
        fun saveDevice(uid: String) {
            val device = hashMapOf(
                "uid" to uid,
                "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "platform" to "android",
                "registeredAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "appVersion" to "1.3.1"
            )
            FirebaseFirestore.getInstance().collection("devices").document(uid)
                .set(device, SetOptions.merge())
                .addOnSuccessListener {
                    status.text = "✓ تم تفعيل الاتصال السحابي لهذا الهاتف."
                    toast("تم تسجيل هذا الهاتف دون بريد إلكتروني")
                    MediaSessionService.startListening(this@MainActivity)
                }
                .addOnFailureListener { e ->
                    status.text = "تعذّر حفظ بيانات الجهاز: ${e.localizedMessage}"
                    toast("تحقق من إعدادات Firestore وقواعد الأمان")
                }
        }
        val current = auth.currentUser
        if (current != null) {
            saveDevice(current.uid)
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { result -> saveDevice(result.user!!.uid) }
                .addOnFailureListener { e ->
                    status.text = "فشل تسجيل الدخول: ${e.localizedMessage}"
                    toast("فعّل تسجيل الدخول المجهول في Firebase Authentication")
                }
        }
    }

    private fun sendRequest(num: String, suffix: String) {
        val k = s.keyword
        if (k.length < 6) { toast("احفظ الكلمة السرية أولاً"); return }
        if (Store.key(num).length < 7) { toast("أدخل رقمًا صحيحًا"); return }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) { askPerms(); return }
        sendJob?.cancel()
        sendJob = SmsJob(this, num.trim(), k + suffix,
            backoffsMs = longArrayOf(5_000, 15_000),
            onStatus = { sendStatusTv?.text = it },
            onFinished = { ok ->
                sendStatusTv?.text = if (ok) "تم إرسال الطلب. انتظر الرد برسالة نصية" else "فشل الإرسال: لا توجد تغطية على أي شريحة"
            }).also { it.start() }
    }


    // إعدادات الموافقة المحلية على إمكانية طلب الوسائط.
    // لا يبدأ هذا القسم الكاميرا أو الميكروفون ولا يتجاوز موافقات Android.
    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** يُزامن مفاتيح الموافقة مع أذونات النظام الفعلية (قد يسحبها أندرويد تلقائياً). */
    private fun syncConsentSwitches() {
        if (s.allowRemoteCamera && !hasPerm(Manifest.permission.CAMERA)) s.allowRemoteCamera = false
        if (s.allowRemoteMicrophone && !hasPerm(Manifest.permission.RECORD_AUDIO)) s.allowRemoteMicrophone = false
        suppressSwitch = true
        cameraSwitch?.isChecked = s.allowRemoteCamera
        micSwitch?.isChecked = s.allowRemoteMicrophone
        suppressSwitch = false
    }

    private fun mediaConsentCard(): View {
        val camera = Switch(this).apply {
            text = "السماح بطلب الكاميرا عن بُعد"
            textSize = 14f
            setTextColor(col(R.color.text_main))
            isChecked = s.allowRemoteCamera && hasPerm(Manifest.permission.CAMERA)
            setOnCheckedChangeListener { _, checked ->
                if (suppressSwitch) return@setOnCheckedChangeListener
                if (!checked) {
                    s.allowRemoteCamera = false
                    toast("تم منع طلب الكاميرا عن بُعد")
                } else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    s.allowRemoteCamera = true
                    toast("تم السماح بطلب الكاميرا عن بُعد")
                } else {
                    suppressSwitch = true; isChecked = false; suppressSwitch = false
                    awaitingPermission = true
                    s.markSessionUnlocked()
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
                }
            }
        }
        cameraSwitch = camera
        val microphone = Switch(this).apply {
            text = "السماح بطلب الميكروفون عن بُعد"
            textSize = 14f
            setTextColor(col(R.color.text_main))
            isChecked = s.allowRemoteMicrophone && hasPerm(Manifest.permission.RECORD_AUDIO)
            setOnCheckedChangeListener { _, checked ->
                if (suppressSwitch) return@setOnCheckedChangeListener
                if (!checked) {
                    s.allowRemoteMicrophone = false
                    toast("تم منع طلب الميكروفون عن بُعد")
                } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    s.allowRemoteMicrophone = true
                    toast("تم السماح بطلب الميكروفون عن بُعد")
                } else {
                    suppressSwitch = true; isChecked = false; suppressSwitch = false
                    awaitingPermission = true
                    s.markSessionUnlocked()
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 102)
                }
            }
        }
        micSwitch = microphone
        return card(
            sectionTitle("التحكم في الوصول إلى الكاميرا والميكروفون"),
            label("مفاتيح موافقة محلية على هذا الجهاز. عند وصول أمر عن بُعد يتحقق التطبيق منها قبل فتح الكاميرا أو الميكروفون. يلزم أيضاً منح إذن Android. فعّل المفتاح المناسب ثم اضغط «بدء استماع الأوامر» في البطاقة التالية."),
            camera, microphone
        )
    }

    /**
     * بطاقة التحكم عن بُعد: ترسل أوامر فتح/إيقاف الكاميرا والميكروفون
     * إلى هاتف مقترن عبر Firestore، وتعرض حالة الجلسة.
     */
    private fun mediaControlCard(): View {
        val statusTv = TextView(this).apply {
            textSize = 13f
            setTextColor(col(R.color.text_sub))
            setPadding(dp(2), dp(8), dp(2), dp(4))
            text = "اختر هاتفاً مرتبطاً ثم اطلب فتح الكاميرا. بعد تفعيل البث اضغط «مشاهدة الكاميرا»."
        }
        val targetInfo = TextView(this).apply {
            textSize = 14f
            setTextColor(col(R.color.text_main))
            setPadding(dp(2), dp(4), dp(2), dp(8))
            text = "لم يُحدد هاتف بعد"
        }
        var selectedOwnerUid: String? = null
        var selectedOwnerName: String = ""
        var lastFrameUrl: String? = null

        fun refreshStatusListener(ownerUid: String) {
            mediaStatusReg?.remove()
            mediaStatusReg = FirebaseFirestore.getInstance().collection("media").document(ownerUid)
                .addSnapshotListener { snap, _ ->
                    if (snap == null || !snap.exists()) {
                        statusTv.text = "لا توجد جلسة وسائط بعد."
                        return@addSnapshotListener
                    }
                    val st = snap.getString("status") ?: "—"
                    val msg = snap.getString("message") ?: ""
                    val mode = snap.getString("mode") ?: ""
                    val req = snap.getString("requesterName") ?: ""
                    val frame = snap.getString("frameUrl")
                    if (!frame.isNullOrBlank()) lastFrameUrl = frame
                    statusTv.text = when (st) {
                        "pending" -> "بانتظار الجهاز الهدف… $msg"
                        "active" -> "نشطة ($mode): $msg" + if (!frame.isNullOrBlank()) " — لقطة جاهزة" else if (!snap.getString("audioUrl").isNullOrBlank()) " — صوت جاهز" else ""
                        "denied" -> "مرفوضة: $msg"
                        "stopped" -> "متوقفة: $msg"
                        "error" -> "خطأ: $msg"
                        else -> "$st — $msg" + if (req.isNotEmpty()) " (من $req)" else ""
                    }
                }
        }

        fun pickOwnerAnd(cmd: String) {
            withUid({ me ->
                FirebaseFirestore.getInstance().collection("devices").document(me).collection("owners").get()
                    .addOnSuccessListener { qs ->
                        if (qs.isEmpty) {
                            toast("لا يوجد هاتف مقترن. أنشئ كوداً على الهاتف الآخر ثم اقترن به.")
                            return@addOnSuccessListener
                        }
                        val names = qs.documents.map { it.getString("name") ?: it.id.take(8) }
                        val ids = qs.documents.map { it.id }
                        AlertDialog.Builder(this)
                            .setTitle("اختر الهاتف الهدف")
                            .setItems(names.toTypedArray()) { _, which ->
                                selectedOwnerUid = ids[which]
                                selectedOwnerName = names[which]
                                targetInfo.text = "الهدف: $selectedOwnerName"
                                refreshStatusListener(ids[which])
                                CloudSync.sendMediaCommand(
                                    targetUid = ids[which],
                                    command = cmd,
                                    requesterName = deviceLabel(),
                                    onOk = {
                                        toast(
                                            when (cmd) {
                                                "camera" -> "أُرسل طلب فتح الكاميرا"
                                                "mic" -> "أُرسل طلب فتح الميكروفون"
                                                "live" -> "أُرسل طلب البث المباشر"
                                                else -> "أُرسل طلب الإيقاف"
                                            }
                                        )
                                    },
                                    onFail = { err -> toast("فشل: $err") }
                                )
                            }
                            .setNegativeButton("إلغاء", null)
                            .show()
                    }
                    .addOnFailureListener { e -> toast("تعذّر تحميل الهواتف: ${e.localizedMessage}") }
            }, { toast("فعّل الاتصال السحابي أولاً") })
        }

        fun openCameraViewer() {
            val uid = selectedOwnerUid
            if (uid == null) {
                toast("اختر هاتفاً أولاً عبر «فتح كاميرا»")
                return
            }
            val img = ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                minimumHeight = dp(240)
                setBackgroundColor(0xFF111111.toInt())
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            val info = TextView(this).apply {
                text = "جارٍ انتظار اللقطات…"
                textSize = 13f
                setTextColor(col(R.color.text_sub))
                setPadding(dp(8), dp(8), dp(8), dp(4))
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(info)
                addView(img, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            val dlg = AlertDialog.Builder(this)
                .setTitle("كاميرا $selectedOwnerName")
                .setView(box)
                .setNegativeButton("إغلاق", null)
                .show()

            var viewReg: ListenerRegistration? = null
            var lastLoaded: String? = null
            fun loadUrl(url: String) {
                if (url == lastLoaded) return
                lastLoaded = url
                Thread {
                    try {
                        val conn = java.net.URL(url).openConnection()
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        conn.getInputStream().use { stream ->
                            val bmp = BitmapFactory.decodeStream(stream)
                            runOnUiThread {
                                if (dlg.isShowing && bmp != null) {
                                    img.setImageBitmap(bmp)
                                    info.text = "تحديث مباشر (لقطة كل ~1 ثانية)"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            if (dlg.isShowing) info.text = "تعذّر تحميل اللقطة: ${e.message}"
                        }
                    }
                }.start()
            }
            viewReg = FirebaseFirestore.getInstance().collection("media").document(uid)
                .addSnapshotListener { snap, _ ->
                    if (!dlg.isShowing) {
                        viewReg?.remove()
                        return@addSnapshotListener
                    }
                    val url = snap?.getString("frameUrl")
                    if (!url.isNullOrBlank()) loadUrl(url)
                    else info.text = "بانتظار أول لقطة من الجهاز الهدف…"
                }
            dlg.setOnDismissListener { viewReg?.remove() }
            lastFrameUrl?.let { loadUrl(it) }
        }


        fun openMicListener() {
            val uid = selectedOwnerUid
            if (uid == null) {
                toast("اختر هاتفاً أولاً عبر «فتح ميكروفون»")
                return
            }
            val info = TextView(this).apply {
                text = "جارٍ انتظار المقاطع الصوتية…"
                textSize = 14f
                setTextColor(col(R.color.text_sub))
                setPadding(dp(12), dp(16), dp(12), dp(16))
            }
            var player: MediaPlayer? = null
            var lastAudio: String? = null
            var viewReg: ListenerRegistration? = null
            val dlg = AlertDialog.Builder(this)
                .setTitle("ميكروفون $selectedOwnerName")
                .setView(info)
                .setNegativeButton("إيقاف الاستماع") { _, _ ->
                    try { player?.stop() } catch (_: Exception) {}
                    try { player?.release() } catch (_: Exception) {}
                    player = null
                    viewReg?.remove()
                }
                .show()
            fun playUrl(url: String) {
                if (url == lastAudio) return
                lastAudio = url
                Thread {
                    try {
                        val old = player
                        val mp = MediaPlayer()
                        player = mp
                        mp.setDataSource(url)
                        mp.setOnPreparedListener {
                            try { old?.release() } catch (_: Exception) {}
                            try { it.start() } catch (_: Exception) {}
                            runOnUiThread { if (dlg.isShowing) info.text = "يستمع الآن… (مقاطع ~كل ثانية)" }
                        }
                        mp.setOnCompletionListener {
                            runOnUiThread { if (dlg.isShowing) info.text = "بانتظار المقطع التالي…" }
                        }
                        mp.setOnErrorListener { _, _, _ ->
                            runOnUiThread { if (dlg.isShowing) info.text = "تعذّر تشغيل مقطع" }
                            true
                        }
                        mp.prepareAsync()
                    } catch (e: Exception) {
                        runOnUiThread { if (dlg.isShowing) info.text = "خطأ: ${e.message}" }
                    }
                }.start()
            }
            viewReg = FirebaseFirestore.getInstance().collection("media").document(uid)
                .addSnapshotListener { snap, _ ->
                    if (!dlg.isShowing) {
                        viewReg?.remove()
                        try { player?.release() } catch (_: Exception) {}
                        return@addSnapshotListener
                    }
                    val url = snap?.getString("audioUrl")
                    if (!url.isNullOrBlank()) playUrl(url)
                    else info.text = "بانتظار أول مقطع من الجهاز الهدف…"
                }
            dlg.setOnDismissListener {
                viewReg?.remove()
                try { player?.stop() } catch (_: Exception) {}
                try { player?.release() } catch (_: Exception) {}
            }
        }

        val grid = actionGrid(
            "بث مباشر" to (R.color.primary to { pickOwnerAnd("live") }),
            "فتح كاميرا" to (R.color.accent to { pickOwnerAnd("camera") }),
            "فتح ميكروفون" to (R.color.accent to { pickOwnerAnd("mic") }),
            "إيقاف" to (R.color.danger to { pickOwnerAnd("stop") })
        )

        return card(
            sectionTitle("فتح الكاميرا والميكروفون عن بُعد"),
            label("للبث المتصل: اضغط «بث مباشر» ثم «مشاهدة مباشرة». يحتاج أذونات كاميرا+ميكروفون على الهدف. إن لم يظهر الفيديو على شبكات الجوال قد تحتاج خادم TURN لاحقاً."),
            targetInfo,
            grid,
            mbtn("مشاهدة مباشرة (فيديو+صوت)", filled = true) {
                val uid = selectedOwnerUid
                if (uid == null) {
                    toast("اختر هاتفاً أولاً عبر «بث مباشر»")
                } else {
                    val i = Intent(this, LiveViewActivity::class.java)
                    i.putExtra(LiveViewActivity.EXTRA_OWNER_UID, uid)
                    i.putExtra(LiveViewActivity.EXTRA_NAME, selectedOwnerName)
                    startActivity(i)
                }
            },
            mbtn("مشاهدة الكاميرا (لقطات)", filled = true) { openCameraViewer() },
            mbtn("استماع الميكروفون (مقاطع)", filled = true) { openMicListener() },
            statusTv,
            mbtn("بدء استماع الأوامر على هذا الهاتف", filled = false) {
                if (FirebaseAuth.getInstance().currentUser == null) {
                    toast("فعّل الاتصال السحابي أولاً")
                } else {
                    MediaSessionService.startListening(this)
                    toast("جاري الاستماع لأوامر الوسائط في الخلفية")
                }
            }
        )
    }

    // ---------- بطاقة الصلاحيات ----------
    private fun permsCard(title: String): LinearLayout {
        val ch = mutableListOf<View>(
            sectionTitle(title),
            mbtn("إعطاء الصلاحيات", filled = false) { askPerms() },
            mbtn("إيقاف تحسين البطارية", filled = false) {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                catch (e: Exception) { toast("افتح إعدادات البطارية يدويًا") }
            }
        )
        val oem = oemAutostartIntent()
        if (oem != null) ch += mbtn("إعدادات «${Build.MANUFACTURER}» (تشغيل تلقائي)", filled = false) {
            try { startActivity(oem) } catch (e: Exception) {
                toast("تعذّر فتح الصفحة تلقائيًا، افتحها يدويًا من إعدادات التطبيقات")
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e2: Exception) {}
            }
        }
        statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        }
        ch += statusBox!!
        ch += mbtn("تحقّق من الإعدادات", filled = false) { showStatus() }
        return card(*ch.toTypedArray())
    }

    // يستبدل بطاقة الحماية فقط بمحتواها الجديد، دون لمس بقية الشاشة أو موضع التمرير —
    // تبديل فوري وسلس بلا أي قفزة.
    private fun refreshAdminCard() {
        val h = adminCardHolder ?: return
        val sc = settingsScroll
        val y = sc?.scrollY ?: 0
        h.removeAllViews()
        h.addView(adminCard())
        sc?.post { sc.scrollTo(0, y) }
    }

    private fun devicePolicyManager() = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun adminComponent() = ComponentName(this, AdminReceiver::class.java)

    // نص محايد يظهر في نافذة "هذا الإعداد مُعطَّل" بدل ذكر اسم التطبيق — محايد فقط، وليس انتحالًا لاسم منتج آخر
    private fun setNeutralSupportMessage(dpm: DevicePolicyManager) {
        try {
            val msg = "هذا الإعداد مُدار على هذا الجهاز ولا يمكن تغييره."
            dpm.setShortSupportMessage(adminComponent(), msg)
            dpm.setLongSupportMessage(adminComponent(), msg)
        } catch (e: Exception) { }
    }

    private fun adminCard(): LinearLayout {
        val dpm = devicePolicyManager()
        val active = dpm.isAdminActive(adminComponent())
        val isOwner = try { dpm.isDeviceOwnerApp(packageName) } catch (e: Exception) { false }
        if (isOwner) setNeutralSupportMessage(dpm)
        return if (active) {
            val rows = mutableListOf<View>(
                sectionTitle("حماية الحذف"),
                TextView(this).apply { text = "✓ مفعّلة — لا يمكن حذف التطبيق قبل إلغاء هذه الحماية"; setTextColor(col(R.color.ok)); setTypeface(typeface, Typeface.BOLD) },
                mbtn("إلغاء حماية الحذف", filled = false) {
                    devicePolicyManager().removeActiveAdmin(adminComponent())
                    toast("أُلغيت الحماية"); refreshAdminCard()
                }
            )
            rows += View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)) }
            rows += sectionTitle("منع إيقاف خدمة الموقع")
            if (isOwner) {
                val restricted = try { dpm.getUserRestrictions(adminComponent()).getBoolean(android.os.UserManager.DISALLOW_CONFIG_LOCATION) } catch (e: Exception) { false }
                if (restricted) {
                    rows += TextView(this).apply { text = "✓ مفعّل — لا يمكن إيقاف الموقع من الإعدادات"; setTextColor(col(R.color.ok)); setTypeface(typeface, Typeface.BOLD) }
                    rows += mbtn("إلغاء المنع", filled = false) {
                        try { dpm.clearUserRestriction(adminComponent(), android.os.UserManager.DISALLOW_CONFIG_LOCATION); toast("أُلغي المنع") }
                        catch (e: Exception) { toast("تعذّر: ${e.message}") }
                        refreshAdminCard()
                    }
                } else {
                    rows += label("يمنع المستخدم من فتح إعدادات الموقع أو إيقافه نهائيًا.")
                    rows += mbtn("تفعيل المنع", filled = false) {
                        try { dpm.addUserRestriction(adminComponent(), android.os.UserManager.DISALLOW_CONFIG_LOCATION); toast("تم المنع") }
                        catch (e: Exception) { toast("تعذّر: ${e.message}") }
                        refreshAdminCard()
                    }
                }
            } else {
                rows += label("هذا غير متاح بالصلاحية الحالية. يتطلب أن يكون التطبيق «مالك الجهاز» (Device Owner)، وهذا أقوى من «مسؤول الجهاز» العادي ولا يمكن منحه إلا عبر أمر من كمبيوتر، وعادة قبل إضافة أي حساب جوجل على الهاتف (أو بعد ضبط المصنع).")
                rows += mbtn("عرض خطوات التفعيل (ADB)", filled = false) { showOwnerInstructions() }
            }
            rows += View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)) }
            rows += sectionTitle("منع إعادة ضبط المصنع (الفورمات)")
            if (isOwner) {
                val restricted = try { dpm.getUserRestrictions(adminComponent()).getBoolean(android.os.UserManager.DISALLOW_FACTORY_RESET) } catch (e: Exception) { false }
                if (restricted) {
                    rows += TextView(this).apply { text = "✓ مفعّل — لا يمكن عمل فورمات من الإعدادات"; setTextColor(col(R.color.ok)); setTypeface(typeface, Typeface.BOLD) }
                    rows += mbtn("إلغاء المنع", filled = false) {
                        try { dpm.clearUserRestriction(adminComponent(), android.os.UserManager.DISALLOW_FACTORY_RESET); toast("أُلغي المنع") }
                        catch (e: Exception) { toast("تعذّر: ${e.message}") }
                        refreshAdminCard()
                    }
                } else {
                    rows += label("يمنع إعادة ضبط المصنع من الإعدادات. ملاحظة: قد لا يمنع فورمات عبر وضع الاسترداد (Recovery) على بعض الأجهزة.")
                    rows += mbtn("تفعيل المنع", filled = false) {
                        try { dpm.addUserRestriction(adminComponent(), android.os.UserManager.DISALLOW_FACTORY_RESET); toast("تم المنع") }
                        catch (e: Exception) { toast("تعذّر: ${e.message}") }
                        refreshAdminCard()
                    }
                }
            } else {
                rows += label("يتطلب أيضًا «مالك الجهاز» (نفس الزر أعلاه).")
            }
            card(*rows.toTypedArray())
        } else {
            card(
                sectionTitle("حماية الحذف"),
                label("تمنع حذف التطبيق مباشرة من الإعدادات؛ يلزم إلغاء هذه الحماية أولاً من هنا."),
                mbtn("تفعيل حماية الحذف", filled = false) {
                    val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
                        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "لمنع حذف التطبيق إذا سُرق الهاتف")
                    try { startActivity(i) } catch (e: Exception) { toast("غير مدعوم على هذا الجهاز") }
                }
            )
        }
    }

    private fun refresh() {
        when (current) {
            "setup" -> renderSetup()
            "settings" -> renderSettings(preserveScroll = true)
            else -> {}
        }
    }

    private fun showOwnerInstructions() {
        val cmd = "adb shell dpm set-device-owner com.findphone.app/.AdminReceiver"
        AlertDialog.Builder(this)
            .setTitle("تفعيل «مالك الجهاز»")
            .setMessage(
                "الشروط: لا يوجد أي حساب جوجل أو حساب آخر مُضاف على الهاتف (أو اعمل ضبط مصنع أولًا)، " +
                "وتفعيل «تصحيح أخطاء USB» من إعدادات المطوّر، وتثبيت أدوات Android (adb) على كمبيوتر.\n\n" +
                "الخطوات:\n" +
                "1) ثبّت التطبيق على الهاتف (هذا الملف نفسه).\n" +
                "2) لا تفتحه ولا تضف أي حساب على الهاتف بعد.\n" +
                "3) وصّل الهاتف بالكمبيوتر وفعّل تصحيح USB.\n" +
                "4) نفّذ هذا الأمر من الكمبيوتر:\n\n$cmd\n\n" +
                "5) افتح التطبيق بعدها، ستجد زر «تفعيل المنع» متاحًا.\n\n" +
                "إن كان على الهاتف حساب بالفعل، الطريقة الوحيدة هي ضبط المصنع أولًا ثم تكرار الخطوات قبل إعادة إضافة أي حساب."
            )
            .setPositiveButton("نسخ الأمر") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("adb", cmd))
                toast("نُسخ الأمر")
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun oemAutostartIntent(): Intent? {
        val candidates = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi" -> listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity")
            "huawei", "honor" -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity")
            "oppo", "realme" -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity")
            "vivo" -> listOf("com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            else -> emptyList()
        }
        for ((pkg, cls) in candidates) {
            val i = Intent().apply { component = ComponentName(pkg, cls); flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            if (packageManager.queryIntentActivities(i, 0).isNotEmpty()) return i
        }
        return null
    }

    private fun showStatus() {
        val box = statusBox ?: return
        val problems = mutableListOf<String>()
        val granted = PackageManager.PERMISSION_GRANTED
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != granted)
            problems += "صلاحية الموقع غير ممنوحة (اختر «دقيق»)"
        else if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != granted)
            problems += "صلاحية الموقع ليست «مسموح طوال الوقت»"
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != granted || checkSelfPermission(Manifest.permission.RECEIVE_SMS) != granted)
            problems += "صلاحية الرسائل غير ممنوحة"
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != granted)
            problems += "صلاحية الهاتف غير ممنوحة (تلزم للتبديل التلقائي بين الشريحتين)"
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) problems += "توفير البطارية لم يُستثنَ للتطبيق"
        try {
            val lmgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!lmgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) problems += "الـGPS مغلق: فعّل «الموقع» من إعدادات الهاتف (ضروري بدون إنترنت)"
        } catch (e: Exception) { }

        box.removeAllViews()
        box.background = ContextCompat.getDrawable(this, if (problems.isEmpty()) R.drawable.bg_status_ok else R.drawable.bg_status_warn)
        if (problems.isEmpty()) {
            box.addView(TextView(this).apply { text = "✓ كل الإعدادات سليمة"; setTextColor(col(R.color.ok)); setTypeface(typeface, Typeface.BOLD) })
        } else {
            box.addView(TextView(this).apply {
                text = "أكمل هذه النقاط حتى يعمل التطبيق بثبات:"
                setTextColor(col(R.color.warn)); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(4))
            })
            problems.forEach { p ->
                box.addView(TextView(this).apply { text = "•  $p"; setTextColor(col(R.color.warn)); textSize = 13f })
            }
        }
    }

    private fun askPerms() {
        val l = mutableListOf(
            Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
        awaitingPermission = true
        s.markSessionUnlocked()
        requestPermissions(l.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        awaitingPermission = false
        unlocked = true
        s.markSessionUnlocked()
        if (rc == 101) {
            s.allowRemoteCamera = res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED
            toast(if (s.allowRemoteCamera) "تم السماح بطلب الكاميرا" else "لم يتم منح إذن الكاميرا")
            // تحديث المفتاح فقط — بدون إعادة رسم الشاشة (لا اهتزاز/قفز)
            cameraSwitch?.let { sw -> suppressSwitch = true; sw.isChecked = s.allowRemoteCamera; suppressSwitch = false }
            return
        }
        if (rc == 102) {
            s.allowRemoteMicrophone = res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED
            toast(if (s.allowRemoteMicrophone) "تم السماح بطلب الميكروفون" else "لم يتم منح إذن الميكروفون")
            micSwitch?.let { sw -> suppressSwitch = true; sw.isChecked = s.allowRemoteMicrophone; suppressSwitch = false }
            return
        }
        if (rc == 1 && Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            toast("الآن اختر «السماح طوال الوقت» للموقع")
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
        }
        showStatus()
    }
}
