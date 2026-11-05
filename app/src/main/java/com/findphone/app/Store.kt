package com.findphone.app

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class Store(c: Context) {
    private val p = c.getSharedPreferences("fp", Context.MODE_PRIVATE)

    // الكلمة السرية التي يردّ عليها الهاتف تلقائيًا
    var keyword: String
        get() = p.getString("kw", "") ?: ""
        set(v) { p.edit().putString("kw", v).apply() }

    fun lastPlain() = p.getLong("lastplain", 0)
    fun setLastPlain(v: Long) { p.edit().putLong("lastplain", v).apply() }

    // ---------- قفل التطبيق (رمز PIN) ----------
    val hasPin: Boolean get() = p.getString("pin_h", null) != null

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        p.edit().putString("pin_s", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_h", hash(pin, salt)).apply()
    }

    fun checkPin(pin: String): Boolean {
        val h = p.getString("pin_h", null) ?: return false
        val salt = Base64.decode(p.getString("pin_s", "") ?: "", Base64.NO_WRAP)
        return MessageDigest.isEqual(h.toByteArray(), hash(pin, salt).toByteArray())
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(pin.toCharArray(), salt, 50_000, 256)).encoded
        return Base64.encodeToString(k, Base64.NO_WRAP)
    }

    var failCount: Int
        get() = p.getInt("fails", 0)
        set(v) { p.edit().putInt("fails", v).apply() }
    var lockUntil: Long
        get() = p.getLong("lock_until", 0)
        set(v) { p.edit().putLong("lock_until", v).apply() }

    // موافقات محلية مستقلة لطلبات الوسائط عن بُعد. لا تعني تشغيل الكاميرا/الميكروفون.
    var allowRemoteCamera: Boolean
        get() = p.getBoolean("allow_remote_camera", false)
        set(v) { p.edit().putBoolean("allow_remote_camera", v).apply() }

    var allowRemoteMicrophone: Boolean
        get() = p.getBoolean("allow_remote_microphone", false)
        set(v) { p.edit().putBoolean("allow_remote_microphone", v).apply() }

    var adminActive: Boolean
        get() = p.getBoolean("admin", false)
        set(v) { p.edit().putBoolean("admin", v).apply() }

    // آخر موقع GPS موثوق نجحنا بالتقاطه، نحتفظ به نحن بمعزل عن ذاكرة أندرويد
    // (التي قد تُمسح عند إغلاق/تشغيل الموقع)، لنستخدمه كملاذ قبل الاستسلام أو اللجوء للشبكة.
    fun saveGoodLocation(lat: Double, lng: Double, acc: Float, time: Long) {
        // تُخزَّن كنص لتفادي فقدان الدقة (float وحده لا يكفي لإحداثيات GPS)
        p.edit().putLong("gl_t", time).putString("gl_lat", lat.toString())
            .putString("gl_lng", lng.toString()).putFloat("gl_acc", acc).apply()
    }

    data class GoodLoc(val lat: Double, val lng: Double, val acc: Float, val time: Long)

    fun goodLocation(): GoodLoc? {
        val t = p.getLong("gl_t", 0)
        if (t <= 0) return null
        val lat = p.getString("gl_lat", null)?.toDoubleOrNull() ?: return null
        val lng = p.getString("gl_lng", null)?.toDoubleOrNull() ?: return null
        return GoodLoc(lat, lng, p.getFloat("gl_acc", -1f), t)
    }

    // حذف بيانات النسخة القديمة (المجموعة والأعضاء)
    fun cleanLegacy() {
        p.edit().remove("group").remove("members").remove("result").remove("name").apply()
    }

    companion object {
        fun key(n: String) = n.filter { it.isDigit() }.takeLast(8)
    }
}
