package com.findphone.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

/**
 * خدمة أمامية لجلسة وسائط عن بُعد.
 * تفتح الكاميرا أو الميكروفون فعلياً (تظهر مؤشرات النظام) وتحدّث حالة الجلسة في Firestore.
 * لا تنقل الصوت/الفيديو عبر الشبكة — ذلك يحتاج WebRTC وخوادم ترحيل في مرحلة لاحقة.
 */
class MediaSessionService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var mode: String = MODE_IDLE
    private var cameraDevice: CameraDevice? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cmdReg: ListenerRegistration? = null
    private var sessionActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_LISTEN
        when (action) {
            ACTION_START_CAMERA -> startSession(MODE_CAMERA)
            ACTION_START_MIC -> startSession(MODE_MIC)
            ACTION_STOP -> stopSession("stopped", "أوقفت الجلسة يدوياً")
            ACTION_LISTEN -> ensureListening()
            else -> ensureListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cmdReg?.remove()
        cmdReg = null
        releaseHardware()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun ensureListening() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            stopSelf()
            return
        }
        if (cmdReg != null) return
        // إشعار خفيف أثناء الاستماع للأوامر (يُستبدل عند بدء جلسة)
        startAsListener(uid)
        cmdReg = FirebaseFirestore.getInstance().collection("media").document(uid)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
                val cmd = snap.getString("command") ?: return@addSnapshotListener
                val status = snap.getString("status") ?: ""
                if (status != "pending") return@addSnapshotListener
                when (cmd) {
                    "camera" -> startSession(MODE_CAMERA)
                    "mic" -> startSession(MODE_MIC)
                    "stop" -> stopSession("stopped", "أوقفت من الهاتف المتحكم")
                }
            }
    }

    private fun startAsListener(uid: String) {
        ensureChannel()
        val n = baseNotif("في انتظار أوامر الوسائط", ongoing = true, stopAction = false)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(this, NOTIF_ID, n, 0)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            try { startForeground(NOTIF_ID, n) } catch (_: Exception) {}
        }
    }

    private fun startSession(newMode: String) {
        val store = Store(this)
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            updateStatus(null, "error", "لا يوجد حساب سحابي")
            stopSelf()
            return
        }

        when (newMode) {
            MODE_CAMERA -> {
                if (!store.allowRemoteCamera) {
                    updateStatus(uid, "denied", "موافقة الكاميرا المحلية مغلقة")
                    return
                }
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                    updateStatus(uid, "denied", "إذن الكاميرا غير ممنوح في النظام")
                    return
                }
            }
            MODE_MIC -> {
                if (!store.allowRemoteMicrophone) {
                    updateStatus(uid, "denied", "موافقة الميكروفون المحلية مغلقة")
                    return
                }
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    updateStatus(uid, "denied", "إذن الميكروفون غير ممنوح في النظام")
                    return
                }
            }
        }

        // أوقف الجلسة السابقة إن وُجدت
        releaseHardware()
        mode = newMode
        sessionActive = true
        acquireWakeLock()

        val title = if (newMode == MODE_CAMERA) "جلسة كاميرا نشطة" else "جلسة ميكروفون نشطة"
        val body = "مؤشر النظام ظاهر. اضغط لإيقاف الجلسة."
        ensureChannel()
        val notif = baseNotif("$title — $body", ongoing = true, stopAction = true)

        val fgsType = when {
            Build.VERSION.SDK_INT < 29 -> 0
            newMode == MODE_CAMERA && Build.VERSION.SDK_INT >= 34 ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            newMode == MODE_MIC && Build.VERSION.SDK_INT >= 34 ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            newMode == MODE_CAMERA && Build.VERSION.SDK_INT >= 30 ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            newMode == MODE_MIC && Build.VERSION.SDK_INT >= 30 ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> 0
        }

        try {
            if (Build.VERSION.SDK_INT >= 29 && fgsType != 0) {
                ServiceCompat.startForeground(this, NOTIF_ID, notif, fgsType)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            updateStatus(uid, "error", "تعذّر بدء الخدمة الأمامية: ${e.message}")
            sessionActive = false
            return
        }

        val ok = when (newMode) {
            MODE_CAMERA -> openCamera()
            MODE_MIC -> openMic()
            else -> false
        }

        if (ok) {
            updateStatus(uid, "active", if (newMode == MODE_CAMERA) "الكاميرا مفتوحة" else "الميكروفون مفتوح")
        } else {
            updateStatus(uid, "error", "فشل فتح العتاد")
            stopSession("error", "فشل فتح العتاد")
        }
    }

    private fun openCamera(): Boolean {
        return try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return false
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { camId ->
                val facing = cm.getCameraCharacteristics(camId)
                    .get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull() ?: return false

            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    // إبقاء الجلسة مفتوحة دون معاينة — يكفي لظهور مؤشر الخصوصية
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    h.post {
                        val uid = FirebaseAuth.getInstance().currentUser?.uid
                        updateStatus(uid, "error", "خطأ كاميرا: $error")
                        stopSession("error", "خطأ كاميرا")
                    }
                }
            }, h)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun openMic(): Boolean {
        return try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return false
            val sampleRate = 16000
            val channel = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
            if (minBuf <= 0) return false
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channel, encoding, minBuf * 2
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return false
            }
            audioRecord = recorder
            recorder.startRecording()
            val buf = ByteArray(minBuf)
            audioThread = Thread {
                try {
                    while (sessionActive && audioRecord === recorder) {
                        val n = recorder.read(buf, 0, buf.size)
                        if (n < 0) break
                        // نقرأ فقط لإبقاء الميكروفون نشطاً (مؤشر النظام). لا إرسال شبكة هنا.
                    }
                } catch (_: Exception) {
                }
            }.also {
                it.isDaemon = true
                it.start()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun stopSession(status: String, message: String) {
        sessionActive = false
        releaseHardware()
        releaseWakeLock()
        mode = MODE_IDLE
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        updateStatus(uid, status, message)
        // ارجع لوضع الاستماع إن وُجد مستخدم
        if (uid != null && cmdReg != null) {
            ensureChannel()
            val n = baseNotif("في انتظار أوامر الوسائط", ongoing = true, stopAction = false)
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ServiceCompat.startForeground(this, NOTIF_ID, n, 0)
                } else {
                    startForeground(NOTIF_ID, n)
                }
            } catch (_: Exception) {}
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun releaseHardware() {
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        audioThread = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "findphone:media").also {
                it.acquire(30 * 60_000L) // حد أقصى 30 دقيقة
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun updateStatus(uid: String?, status: String, message: String) {
        if (uid == null) return
        try {
            val m = hashMapOf<String, Any>(
                "status" to status,
                "message" to message,
                "mode" to mode,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (status == "stopped" || status == "denied" || status == "error") {
                m["command"] = "none"
            }
            FirebaseFirestore.getInstance().collection("media").document(uid)
                .set(m, SetOptions.merge())
        } catch (_: Exception) {}
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "جلسات الكاميرا والميكروفون",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "إشعار ظاهر أثناء فتح الكاميرا أو الميكروفون عن بُعد"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun baseNotif(text: String, ongoing: Boolean, stopAction: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Find Phone — وسائط")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
        if (stopAction) {
            val stopPi = PendingIntent.getService(
                this, 1,
                Intent(this, MediaSessionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPi)
        }
        return b.build()
    }

    companion object {
        const val CHANNEL_ID = "media_session"
        const val NOTIF_ID = 42
        const val ACTION_START_CAMERA = "com.findphone.app.START_CAMERA"
        const val ACTION_START_MIC = "com.findphone.app.START_MIC"
        const val ACTION_STOP = "com.findphone.app.STOP_MEDIA"
        const val ACTION_LISTEN = "com.findphone.app.LISTEN_MEDIA"
        const val MODE_IDLE = "idle"
        const val MODE_CAMERA = "camera"
        const val MODE_MIC = "mic"

        fun startListening(ctx: Context) {
            val i = Intent(ctx, MediaSessionService::class.java).setAction(ACTION_LISTEN)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (_: Exception) {}
        }

        fun requestLocal(ctx: Context, mode: String) {
            val action = when (mode) {
                MODE_CAMERA -> ACTION_START_CAMERA
                MODE_MIC -> ACTION_START_MIC
                else -> ACTION_STOP
            }
            val i = Intent(ctx, MediaSessionService::class.java).setAction(action)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (_: Exception) {}
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, MediaSessionService::class.java).setAction(ACTION_STOP)
            try { ctx.startService(i) } catch (_: Exception) {}
        }
    }
}
