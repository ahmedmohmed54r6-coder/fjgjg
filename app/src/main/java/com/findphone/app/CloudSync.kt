package com.findphone.app

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/** يرفع آخر موقع إلى السحابة لصاحب الهاتف والهواتف التي وافق عليها فقط (انظر firestore.rules). */
object CloudSync {
    fun uploadLocation(lat: Double, lng: Double, acc: Float, fixTime: Long, bat: Int, src: String, stale: Boolean) {
        try {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val m = hashMapOf<String, Any>(
                "lat" to lat,
                "lng" to lng,
                "acc" to acc.toDouble(),
                "time" to fixTime,
                "bat" to bat.toLong(),
                "src" to src,
                "stale" to stale,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            // إن لم يكن هناك إنترنت تُحفظ الكتابة محليًا وتُرسل تلقائيًا عند عودة الاتصال
            FirebaseFirestore.getInstance().collection("locations").document(uid).set(m)
        } catch (e: Exception) { }
    }

    /**
     * يرسل أمر وسائط إلى هاتف مقترن (يجب أن يكون المرسل ضمن viewers لصاحب الهاتف).
     * @param targetUid معرّف صاحب الهاتف الهدف
     * @param command "camera" | "mic" | "stop"
     * @param requesterName اسم يظهر لصاحب الهاتف
     */
    fun sendMediaCommand(
        targetUid: String,
        command: String,
        requesterName: String,
        onOk: () -> Unit = {},
        onFail: (String) -> Unit = {}
    ) {
        val me = FirebaseAuth.getInstance().currentUser?.uid
        if (me == null) {
            onFail("فعّل الاتصال السحابي أولاً")
            return
        }
        if (targetUid.isBlank()) {
            onFail("لا يوجد هاتف هدف")
            return
        }
        val allowed = setOf("camera", "mic", "stop", "live")
        if (command !in allowed) {
            onFail("أمر غير معروف")
            return
        }
        try {
            val data = hashMapOf<String, Any>(
                "command" to command,
                "status" to "pending",
                "requesterUid" to me,
                "requesterName" to requesterName,
                "message" to when (command) {
                    "camera" -> "طلب فتح الكاميرا"
                    "mic" -> "طلب فتح الميكروفون"
                    "live" -> "طلب بث مباشر (فيديو+صوت)"
                    else -> "طلب إيقاف الجلسة"
                },
                "mode" to if (command == "stop") "idle" else command,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            FirebaseFirestore.getInstance().collection("media").document(targetUid)
                .set(data, SetOptions.merge())
                .addOnSuccessListener { onOk() }
                .addOnFailureListener { e -> onFail(e.localizedMessage ?: "فشل الإرسال") }
        } catch (e: Exception) {
            onFail(e.localizedMessage ?: "خطأ")
        }
    }
}

