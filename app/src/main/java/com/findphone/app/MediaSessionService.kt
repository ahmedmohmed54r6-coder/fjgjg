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
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
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
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * خدمة أمامية لجلسة وسائط عن بُعد.
 * - الميكروفون: يُفتح محلياً (مؤشر النظام).
 * - الكاميرا: تُفتح وتُرفع لقطات JPEG متتابعة إلى Firebase Storage للمشاهدة عن بُعد.
 */
class MediaSessionService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null

    private var mode: String = MODE_IDLE
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var mediaRecorder: MediaRecorder? = null
    private var audioChunkFile: File? = null
    private var audioLoopRunning = false
    private val audioChunkRunnable = object : Runnable {
        override fun run() {
            if (!sessionActive || mode != MODE_MIC || !audioLoopRunning) return
            rotateAudioChunk()
            mainHandler.postDelayed(this, 1200L)
        }
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private var cmdReg: ListenerRegistration? = null
    private var sessionActive = false
    private var webRtcLive: WebRtcLive? = null

    private val uploading = AtomicBoolean(false)
    private val lastUploadMs = AtomicLong(0)
    private val minUploadIntervalMs = 900L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_LISTEN) {
            ACTION_START_CAMERA -> startSession(MODE_CAMERA)
            ACTION_START_MIC -> startSession(MODE_MIC)
            ACTION_START_LIVE -> startSession(MODE_LIVE)
            ACTION_STOP -> stopSession("stopped", "أوقفت الجلسة يدوياً")
            else -> ensureListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cmdReg?.remove()
        cmdReg = null
        releaseHardware()
        releaseWakeLock()
        stopCamThread()
        super.onDestroy()
    }

    private fun ensureListening() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            stopSelf()
            return
        }
        if (cmdReg != null) return
        startAsListener()
        cmdReg = FirebaseFirestore.getInstance().collection("media").document(uid)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
                val cmd = snap.getString("command") ?: return@addSnapshotListener
                val status = snap.getString("status") ?: ""
                if (status != "pending") return@addSnapshotListener
                when (cmd) {
                    "camera" -> startSession(MODE_CAMERA)
                    "mic" -> startSession(MODE_MIC)
                    "live" -> startSession(MODE_LIVE)
                    "stop" -> stopSession("stopped", "أوقفت من الهاتف المتحكم")
                }
            }
    }

    /**
     * نوع الخدمة الأمامية أثناء الاستماع. أندرويد 14 يرفض النوع 0، ولا يسمح بترقية الخدمة إلى
     * camera/microphone من الخلفية؛ لذلك نبدأ الاستماع (والتطبيق ظاهر) بهذه الأنواع إن كانت
     * الأذونات ممنوحة، فتبقى صالحة عند وصول الأمر والتطبيق في الخلفية.
     */
    private fun listenerFgsType(): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (Build.VERSION.SDK_INT >= 30) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return t
    }

    private fun showListenerForeground() {
        ensureChannel()
        val n = baseNotif("في انتظار أوامر الوسائط", ongoing = true, stopAction = false)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(this, NOTIF_ID, n, listenerFgsType())
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (_: Exception) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIF_ID, n)
                }
            } catch (_: Exception) {}
        }
    }

    private fun startAsListener() = showListenerForeground()

    private fun startSession(newMode: String) {
        val store = Store(this)
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            updateStatus(null, "error", "لا يوجد حساب سحابي")
            stopSelf()
            return
        }

        when (newMode) {
            MODE_CAMERA, MODE_LIVE -> {
                if (!store.allowRemoteCamera) {
                    updateStatus(uid, "denied", "موافقة الكاميرا المحلية مغلقة")
                    return
                }
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                    updateStatus(uid, "denied", "إذن الكاميرا غير ممنوح في النظام")
                    return
                }
                if (newMode == MODE_LIVE) {
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

        releaseHardware()
        mode = newMode
        sessionActive = true
        acquireWakeLock()

        val title = when (newMode) {
            MODE_LIVE -> "بث مباشر فيديو+صوت"
            MODE_CAMERA -> "جلسة كاميرا + بث لقطات"
            else -> "جلسة ميكروفون نشطة"
        }
        ensureChannel()
        val notif = baseNotif(title, ongoing = true, stopAction = true)

        val fgsType = when {
            Build.VERSION.SDK_INT < 29 -> 0
            newMode == MODE_LIVE && Build.VERSION.SDK_INT >= 30 ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
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
            // الخدمة تعمل أصلاً بأنواعها منذ بدء الاستماع؛ جرّب المتابعة بدل الفشل الكامل
            if (Build.VERSION.SDK_INT >= 34 && e is SecurityException && cmdReg != null) {
                // لا شيء: نكمل بالأنواع الحالية
            } else {
                updateStatus(uid, "error", "تعذّر بدء الخدمة الأمامية (افتح التطبيق على الهاتف الهدف ثم أعد الطلب): ${e.message}")
                sessionActive = false
                return
            }
        }

        val ok = when (newMode) {
            MODE_LIVE -> openLive(uid)
            MODE_CAMERA -> openCamera(uid)
            MODE_MIC -> openMic()
            else -> false
        }

        if (ok) {
            updateStatus(
                uid, "active",
                when (newMode) {
                    MODE_LIVE -> "بث مباشر جارٍ"
                    MODE_CAMERA -> "الكاميرا تبث لقطات"
                    else -> "الميكروفون مفتوح"
                }
            )
        } else {
            updateStatus(uid, "error", "فشل فتح العتاد")
            stopSession("error", "فشل فتح العتاد")
        }
    }

    private fun ensureCamThread() {
        if (camThread != null) return
        camThread = HandlerThread("cam-bg").also { it.start() }
        camHandler = Handler(camThread!!.looper)
    }

    private fun stopCamThread() {
        try { camThread?.quitSafely() } catch (_: Exception) {}
        camThread = null
        camHandler = null
    }

    private fun openCamera(ownerUid: String): Boolean {
        return try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return false

            ensureCamThread()
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { camId ->
                val facing = cm.getCameraCharacteristics(camId)
                    .get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull() ?: return false

            val width = 640
            val height = 480
            imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2).also { reader ->
                reader.setOnImageAvailableListener({ r ->
                    val image = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                    try {
                        if (!sessionActive || mode != MODE_CAMERA) return@setOnImageAvailableListener
                        val now = System.currentTimeMillis()
                        if (now - lastUploadMs.get() < minUploadIntervalMs) return@setOnImageAvailableListener
                        if (!uploading.compareAndSet(false, true)) return@setOnImageAvailableListener
                        lastUploadMs.set(now)
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        uploadFrame(ownerUid, bytes)
                    } catch (_: Exception) {
                        uploading.set(false)
                    } finally {
                        try { image.close() } catch (_: Exception) {}
                    }
                }, camHandler)
            }

            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val surface = imageReader?.surface ?: return
                        camera.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    try {
                                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(surface)
                                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        }.build()
                                        session.setRepeatingRequest(req, null, camHandler)
                                    } catch (e: Exception) {
                                        mainHandler.post {
                                            updateStatus(ownerUid, "error", "فشل طلب الكاميرا: ${e.message}")
                                            stopSession("error", "فشل طلب الكاميرا")
                                        }
                                    }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    mainHandler.post {
                                        updateStatus(ownerUid, "error", "فشل تهيئة جلسة الكاميرا")
                                        stopSession("error", "فشل تهيئة جلسة الكاميرا")
                                    }
                                }
                            },
                            camHandler
                        )
                    } catch (e: Exception) {
                        mainHandler.post {
                            updateStatus(ownerUid, "error", "خطأ جلسة: ${e.message}")
                            stopSession("error", "خطأ جلسة")
                        }
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    mainHandler.post {
                        updateStatus(ownerUid, "error", "خطأ كاميرا: $error")
                        stopSession("error", "خطأ كاميرا")
                    }
                }
            }, camHandler)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun uploadFrame(ownerUid: String, jpeg: ByteArray) {
        try {
            val ref = FirebaseStorage.getInstance().reference
                .child("media_frames").child(ownerUid).child("latest.jpg")
            ref.putBytes(jpeg)
                .addOnSuccessListener {
                    ref.downloadUrl
                        .addOnSuccessListener { uri ->
                            try {
                                FirebaseFirestore.getInstance().collection("media").document(ownerUid)
                                    .set(
                                        hashMapOf(
                                            "frameUrl" to uri.toString(),
                                            "frameAt" to FieldValue.serverTimestamp(),
                                            "status" to "active",
                                            "mode" to MODE_CAMERA,
                                            "message" to "الكاميرا تبث لقطات",
                                            "updatedAt" to FieldValue.serverTimestamp()
                                        ),
                                        SetOptions.merge()
                                    )
                            } catch (_: Exception) {}
                            uploading.set(false)
                        }
                        .addOnFailureListener { uploading.set(false) }
                }
                .addOnFailureListener { uploading.set(false) }
        } catch (_: Exception) {
            uploading.set(false)
        }
    }

    private fun openLive(ownerUid: String): Boolean {
        return try {
            try { webRtcLive?.stop() } catch (_: Exception) {}
            webRtcLive = WebRtcLive(this).also { w ->
                w.onStatus = { msg -> updateStatus(ownerUid, "active", msg) }
                w.onError = { msg ->
                    updateStatus(ownerUid, "error", msg)
                    mainHandler.post { stopSession("error", msg) }
                }
                w.startPublisher(ownerUid)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun openMic(): Boolean {
        return try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return false
            audioLoopRunning = true
            // ابدأ أول مقطع فوراً ثم كل ~1.2 ثانية
            mainHandler.post {
                rotateAudioChunk()
                mainHandler.postDelayed(audioChunkRunnable, 1200L)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun rotateAudioChunk() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!sessionActive || mode != MODE_MIC) return
        try {
            // أوقف المقطع السابق وارفعه
            val prev = mediaRecorder
            val prevFile = audioChunkFile
            mediaRecorder = null
            audioChunkFile = null
            if (prev != null) {
                try { prev.stop() } catch (_: Exception) {}
                try { prev.release() } catch (_: Exception) {}
                if (prevFile != null && prevFile.exists() && prevFile.length() > 64) {
                    uploadAudioChunk(uid, prevFile)
                } else {
                    try { prevFile?.delete() } catch (_: Exception) {}
                }
            }
            if (!sessionActive || mode != MODE_MIC) return
            val file = File(cacheDir, "mic_${System.currentTimeMillis()}.m4a")
            audioChunkFile = file
            @Suppress("DEPRECATION")
            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            mediaRecorder = rec
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(16000)
            rec.setAudioEncodingBitRate(24000)
            rec.setAudioChannels(1)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
        } catch (e: Exception) {
            try { mediaRecorder?.release() } catch (_: Exception) {}
            mediaRecorder = null
            mainHandler.post {
                updateStatus(uid, "error", "فشل تسجيل الصوت: ${e.message}")
            }
        }
    }

    private fun uploadAudioChunk(ownerUid: String, file: File) {
        try {
            val ref = FirebaseStorage.getInstance().reference
                .child("media_frames").child(ownerUid).child("latest.m4a")
            ref.putFile(android.net.Uri.fromFile(file))
                .addOnSuccessListener {
                    ref.downloadUrl
                        .addOnSuccessListener { uri ->
                            try {
                                FirebaseFirestore.getInstance().collection("media").document(ownerUid)
                                    .set(
                                        hashMapOf(
                                            "audioUrl" to uri.toString(),
                                            "audioAt" to FieldValue.serverTimestamp(),
                                            "status" to "active",
                                            "mode" to MODE_MIC,
                                            "message" to "الميكروفون يبث مقاطع",
                                            "updatedAt" to FieldValue.serverTimestamp()
                                        ),
                                        SetOptions.merge()
                                    )
                            } catch (_: Exception) {}
                            try { file.delete() } catch (_: Exception) {}
                        }
                        .addOnFailureListener { try { file.delete() } catch (_: Exception) {} }
                }
                .addOnFailureListener { try { file.delete() } catch (_: Exception) {} }
        } catch (_: Exception) {
            try { file.delete() } catch (_: Exception) {}
        }
    }

    private fun stopSession(status: String, message: String) {
        sessionActive = false
        releaseHardware()
        releaseWakeLock()
        mode = MODE_IDLE
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        updateStatus(uid, status, message)
        if (uid != null && cmdReg != null) {
            showListenerForeground()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun releaseHardware() {
        try { webRtcLive?.stop() } catch (_: Exception) {}
        webRtcLive = null
        audioLoopRunning = false
        try { mainHandler.removeCallbacks(audioChunkRunnable) } catch (_: Exception) {}
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        try { audioChunkFile?.delete() } catch (_: Exception) {}
        audioChunkFile = null
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        audioThread = null
        uploading.set(false)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "findphone:media").also {
                it.acquire(30 * 60_000L)
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
        const val ACTION_START_LIVE = "com.findphone.app.START_LIVE"
        const val ACTION_STOP = "com.findphone.app.STOP_MEDIA"
        const val ACTION_LISTEN = "com.findphone.app.LISTEN_MEDIA"
        const val MODE_IDLE = "idle"
        const val MODE_CAMERA = "camera"
        const val MODE_MIC = "mic"
        const val MODE_LIVE = "live"

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
