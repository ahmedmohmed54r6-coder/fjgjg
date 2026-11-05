package com.findphone.app

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

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
}
