package com.findphone.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * بث مباشر WebRTC (فيديو + صوت متصل).
 * الإشارة عبر Firestore، الاتصال عبر STUN (وTURN اختياري لاحقاً).
 */
class WebRtcLive(private val ctx: Context) {
    private val tag = "WebRtcLive"
    private val db = FirebaseFirestore.getInstance()
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var factory: PeerConnectionFactory? = null
    private var egl: EglBase? = null
    private var pc: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideo: VideoTrack? = null
    private var localAudio: AudioTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var signalReg: ListenerRegistration? = null
    private var candReg: ListenerRegistration? = null
    private var role = ""
    private var ownerUid = ""
    private var sessionId = ""
    private var remoteRenderer: SurfaceViewRenderer? = null
    private val pendingIce = mutableListOf<IceCandidate>()
    private val remoteReady = AtomicBoolean(false)
    private var lastOfferHash = ""
    private var disposed = false
    private var audioFocusReq: AudioFocusRequest? = null

    var onStatus: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null

    private fun postStatus(s: String) {
        Log.i(tag, s)
        main.post { onStatus?.invoke(s) }
    }
    private fun postError(s: String) {
        Log.e(tag, s)
        main.post { onError?.invoke(s) }
    }

    @Synchronized
    private fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        egl = EglBase.create()
        val enc = DefaultVideoEncoderFactory(egl!!.eglBaseContext, true /* hw */, true /* intel */)
        val dec = DefaultVideoDecoderFactory(egl!!.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(enc)
            .setVideoDecoderFactory(dec)
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()
    }

    private fun iceServers(): List<PeerConnection.IceServer> {
        val list = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        )
        // TURN اختياري من الإعدادات المحلية إن وُجد
        val prefs = ctx.getSharedPreferences("fp", Context.MODE_PRIVATE)
        val turnUrl = prefs.getString("turn_url", null)?.trim().orEmpty()
        val turnUser = prefs.getString("turn_user", null)?.trim().orEmpty()
        val turnPass = prefs.getString("turn_pass", null)?.trim().orEmpty()
        if (turnUrl.isNotEmpty()) {
            val b = PeerConnection.IceServer.builder(turnUrl)
            if (turnUser.isNotEmpty()) b.setUsername(turnUser).setPassword(turnPass)
            list.add(b.createIceServer())
            Log.i(tag, "Using custom TURN: $turnUrl")
        }
        return list
    }

    private fun audioConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
    }

    private fun requestAudioFocus() {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
            if (Build.VERSION.SDK_INT >= 26) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioFocusReq = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        } catch (e: Exception) {
            Log.w(tag, "audioFocus: ${e.message}")
        }
    }

    private fun abandonAudioFocus() {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= 26) {
                audioFocusReq?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
        audioFocusReq = null
    }

    private fun pcObserver() = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Log.d(tag, "signaling=$state")
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.i(tag, "ice=$state role=$role")
            when (state) {
                PeerConnection.IceConnectionState.CHECKING -> postStatus("جاري الاتصال…")
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    postStatus("البث متصل ✓")
                    main.post { onConnected?.invoke() }
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> postStatus("انقطع مؤقتاً…")
                PeerConnection.IceConnectionState.FAILED ->
                    postStatus("فشل الاتصال الشبكي — جرّب نفس الـ Wi‑Fi أو أضف TURN")
                PeerConnection.IceConnectionState.CLOSED -> postStatus("أُغلق الاتصال")
                else -> {}
            }
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            Log.i(tag, "pcState=$newState")
            if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                postStatus("البث متصل ✓")
                main.post { onConnected?.invoke() }
            }
        }
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.d(tag, "gathering=$state")
        }
        override fun onIceCandidate(c: IceCandidate?) {
            if (c == null || ownerUid.isEmpty() || sessionId.isEmpty()) return
            val data = hashMapOf(
                "sdpMid" to (c.sdpMid ?: "0"),
                "sdpMLineIndex" to c.sdpMLineIndex,
                "candidate" to c.sdp,
                "from" to role,
                "sessionId" to sessionId,
                "at" to FieldValue.serverTimestamp()
            )
            db.collection("media").document(ownerUid)
                .collection("candidates").document()
                .set(data)
                .addOnFailureListener { e -> Log.e(tag, "cand write fail: ${e.message}") }
        }
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track() ?: return
            track.setEnabled(true)
            when (track) {
                is VideoTrack -> {
                    main.post {
                        remoteRenderer?.let { track.addSink(it) }
                        postStatus("الفيديو يعمل")
                    }
                }
                is AudioTrack -> {
                    track.setEnabled(true)
                    postStatus("الصوت يعمل")
                }
            }
        }
        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track() ?: return
            track.setEnabled(true)
            if (track is VideoTrack) {
                main.post { remoteRenderer?.let { track.addSink(it) } }
            }
        }
    }

    private fun createPeer() {
        val cfg = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            // مرشّحات أكثر لزيادة فرص النجاح عبر NAT
            iceCandidatePoolSize = 4
        }
        pc = factory!!.createPeerConnection(cfg, pcObserver())
            ?: throw IllegalStateException("تعذّر إنشاء PeerConnection")
    }

    private fun mediaDoc() = db.collection("media").document(ownerUid)

    private fun clearOldCandidates(then: () -> Unit) {
        mediaDoc().collection("candidates").get()
            .addOnSuccessListener { qs ->
                if (qs.isEmpty) { then(); return@addOnSuccessListener }
                // Firestore batch limit 500
                val docs = qs.documents
                var i = 0
                fun next() {
                    if (i >= docs.size) { then(); return }
                    val batch = db.batch()
                    val end = minOf(i + 400, docs.size)
                    for (j in i until end) batch.delete(docs[j].reference)
                    i = end
                    batch.commit().addOnCompleteListener { next() }
                }
                next()
            }
            .addOnFailureListener { then() }
    }

    /** الهدف: ينشر كاميرا + ميكروفون */
    fun startPublisher(ownerUid: String) {
        this.ownerUid = ownerUid
        this.role = "pub"
        this.sessionId = UUID.randomUUID().toString().take(12)
        disposed = false
        remoteReady.set(false)
        pendingIce.clear()
        lastOfferHash = ""
        requestAudioFocus()
        postStatus("تجهيز الكاميرا والميكروفون…")
        exec.execute {
            try {
                if (disposed) return@execute
                ensureFactory()
                createPeer()

                // فيديو خلفي
                val enumr = Camera2Enumerator(ctx)
                val camId = enumr.deviceNames.firstOrNull { enumr.isBackFacing(it) }
                    ?: enumr.deviceNames.firstOrNull()
                    ?: throw IllegalStateException("لا توجد كاميرا")
                videoCapturer = enumr.createCapturer(camId, null)
                    ?: throw IllegalStateException("تعذّر فتح الكاميرا")
                surfaceHelper = SurfaceTextureHelper.create("CaptureThread", egl!!.eglBaseContext)
                videoSource = factory!!.createVideoSource(false)
                videoCapturer!!.initialize(surfaceHelper, ctx.applicationContext, videoSource!!.capturerObserver)
                // 640x480 @ 24fps توازن بين السلاسة والحجم
                videoCapturer!!.startCapture(640, 480, 24)
                localVideo = factory!!.createVideoTrack("v0", videoSource)
                localVideo!!.setEnabled(true)
                pc!!.addTrack(localVideo, listOf("media"))

                // صوت
                audioSource = factory!!.createAudioSource(audioConstraints())
                localAudio = factory!!.createAudioTrack("a0", audioSource)
                localAudio!!.setEnabled(true)
                pc!!.addTrack(localAudio, listOf("media"))

                clearOldCandidates {
                    if (disposed) return@clearOldCandidates
                    listenAnswer()
                    listenCandidates()
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                    }
                    pc!!.createOffer(object : SdpAdapter() {
                        override fun onCreateSuccess(sdp: SessionDescription?) {
                            if (sdp == null || disposed) return
                            pc!!.setLocalDescription(object : SdpAdapter() {
                                override fun onSetSuccess() {
                                    val offer = sdp.description
                                    lastOfferHash = offer.hashCode().toString()
                                    mediaDoc().set(
                                        hashMapOf(
                                            "command" to "live",
                                            "status" to "active",
                                            "mode" to "live",
                                            "message" to "جاهز للمشاهدة",
                                            "sessionId" to sessionId,
                                            "offer" to offer,
                                            "offerType" to "offer",
                                            "answer" to FieldValue.delete(),
                                            "answerType" to FieldValue.delete(),
                                            "updatedAt" to FieldValue.serverTimestamp()
                                        ),
                                        SetOptions.merge()
                                    ).addOnSuccessListener {
                                        postStatus("بانتظار المشاهد… اضغط مشاهدة مباشرة")
                                    }.addOnFailureListener { e ->
                                        postError("فشل رفع الإشارة: ${e.message}")
                                    }
                                }
                                override fun onSetFailure(err: String?) {
                                    postError("setLocalOffer: $err")
                                }
                            }, sdp)
                        }
                        override fun onCreateFailure(err: String?) {
                            postError("createOffer: $err")
                        }
                    }, constraints)
                }
            } catch (e: Exception) {
                postError("فشل البث: ${e.message}")
            }
        }
    }

    /** المتحكم: يشاهد ويستمع */
    fun startViewer(ownerUid: String, renderer: SurfaceViewRenderer) {
        this.ownerUid = ownerUid
        this.role = "view"
        this.remoteRenderer = renderer
        disposed = false
        remoteReady.set(false)
        pendingIce.clear()
        lastOfferHash = ""
        sessionId = ""
        requestAudioFocus()
        postStatus("بانتظار إشارة البث من الهدف…")
        exec.execute {
            try {
                if (disposed) return@execute
                ensureFactory()
                // تهيئة العارض على الخيط الرئيسي
                val latch = java.util.concurrent.CountDownLatch(1)
                main.post {
                    try {
                        if (egl == null) ensureFactory()
                        renderer.init(egl!!.eglBaseContext, null)
                        renderer.setMirror(false)
                        renderer.setEnableHardwareScaler(true)
                        renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    } catch (e: Exception) {
                        postError("تهيئة العرض: ${e.message}")
                    }
                    latch.countDown()
                }
                latch.await()
                createPeer()
                // نستقبل فيديو + صوت
                pc!!.addTransceiver(
                    org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                )
                pc!!.addTransceiver(
                    org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                )
                listenOffer()
                listenCandidates()
            } catch (e: Exception) {
                postError("فشل المشاهدة: ${e.message}")
            }
        }
    }

    private fun listenOffer() {
        signalReg?.remove()
        signalReg = mediaDoc().addSnapshotListener { snap, err ->
            if (err != null) {
                postError("إشارة: ${err.message}")
                return@addSnapshotListener
            }
            if (snap == null || !snap.exists() || disposed) return@addSnapshotListener
            val offer = snap.getString("offer") ?: return@addSnapshotListener
            val sid = snap.getString("sessionId") ?: ""
            val hash = offer.hashCode().toString()
            if (hash == lastOfferHash) return@addSnapshotListener
            lastOfferHash = hash
            sessionId = sid
            postStatus("وصلت إشارة البث — جاري الاتصال…")
            exec.execute {
                try {
                    // إعادة peer إذا كان هناك وصف سابق
                    if (pc?.remoteDescription != null || pc?.localDescription != null) {
                        try { pc?.close() } catch (_: Exception) {}
                        remoteReady.set(false)
                        pendingIce.clear()
                        createPeer()
                        pc!!.addTransceiver(
                            org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                        )
                        pc!!.addTransceiver(
                            org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                        )
                    }
                    val sdp = SessionDescription(SessionDescription.Type.OFFER, offer)
                    pc!!.setRemoteDescription(object : SdpAdapter() {
                        override fun onSetSuccess() {
                            remoteReady.set(true)
                            flushIce()
                            val constraints = MediaConstraints().apply {
                                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                            }
                            pc!!.createAnswer(object : SdpAdapter() {
                                override fun onCreateSuccess(ans: SessionDescription?) {
                                    if (ans == null) return
                                    pc!!.setLocalDescription(object : SdpAdapter() {
                                        override fun onSetSuccess() {
                                            mediaDoc().set(
                                                hashMapOf(
                                                    "answer" to ans.description,
                                                    "answerType" to "answer",
                                                    "viewerReady" to true,
                                                    "updatedAt" to FieldValue.serverTimestamp()
                                                ),
                                                SetOptions.merge()
                                            )
                                            postStatus("تم الرد — انتظار اكتمال الاتصال…")
                                        }
                                    }, ans)
                                }
                                override fun onCreateFailure(e: String?) {
                                    postError("createAnswer: $e")
                                }
                            }, constraints)
                        }
                        override fun onSetFailure(e: String?) {
                            postError("setRemoteOffer: $e")
                        }
                    }, sdp)
                } catch (e: Exception) {
                    postError("معالجة العرض: ${e.message}")
                }
            }
        }
    }

    private fun listenAnswer() {
        signalReg?.remove()
        signalReg = mediaDoc().addSnapshotListener { snap, err ->
            if (err != null || snap == null || !snap.exists() || disposed) return@addSnapshotListener
            if (role != "pub") return@addSnapshotListener
            val answer = snap.getString("answer") ?: return@addSnapshotListener
            val sid = snap.getString("sessionId") ?: ""
            if (sid.isNotEmpty() && sessionId.isNotEmpty() && sid != sessionId) return@addSnapshotListener
            if (pc?.remoteDescription != null) return@addSnapshotListener
            postStatus("المشاهد متصل — إكمال الربط…")
            exec.execute {
                try {
                    val sdp = SessionDescription(SessionDescription.Type.ANSWER, answer)
                    pc!!.setRemoteDescription(object : SdpAdapter() {
                        override fun onSetSuccess() {
                            remoteReady.set(true)
                            flushIce()
                            postStatus("البث يعمل من هذا الجهاز")
                        }
                        override fun onSetFailure(e: String?) {
                            postError("setRemoteAnswer: $e")
                        }
                    }, sdp)
                } catch (e: Exception) {
                    postError("answer: ${e.message}")
                }
            }
        }
    }

    private fun listenCandidates() {
        candReg?.remove()
        candReg = mediaDoc().collection("candidates")
            .orderBy("at", Query.Direction.ASCENDING)
            .addSnapshotListener { qs, err ->
                if (err != null) {
                    Log.e(tag, "cand listen: ${err.message}")
                    // قد تفشل orderBy بدون فهرس — جرّب بدون ترتيب
                    if (candReg != null) {
                        candReg?.remove()
                        candReg = mediaDoc().collection("candidates")
                            .addSnapshotListener { qs2, _ -> handleCandSnap(qs2) }
                    }
                    return@addSnapshotListener
                }
                handleCandSnap(qs)
            }
    }

    private fun handleCandSnap(qs: com.google.firebase.firestore.QuerySnapshot?) {
        if (qs == null || disposed) return
        for (dc in qs.documentChanges) {
            val d = dc.document
            val from = d.getString("from") ?: continue
            if (from == role) continue
            val sid = d.getString("sessionId") ?: ""
            // إن عرفنا sessionId نفلتر؛ وإلا نقبل
            if (sessionId.isNotEmpty() && sid.isNotEmpty() && sid != sessionId) continue
            val mid = d.getString("sdpMid") ?: "0"
            val idx = (d.getLong("sdpMLineIndex") ?: 0L).toInt()
            val cand = d.getString("candidate") ?: continue
            val ice = IceCandidate(mid, idx, cand)
            if (remoteReady.get()) {
                try { pc?.addIceCandidate(ice) } catch (e: Exception) {
                    Log.w(tag, "addIce: ${e.message}")
                }
            } else {
                synchronized(pendingIce) { pendingIce.add(ice) }
            }
        }
    }

    private fun flushIce() {
        val copy: List<IceCandidate>
        synchronized(pendingIce) {
            copy = pendingIce.toList()
            pendingIce.clear()
        }
        for (c in copy) {
            try { pc?.addIceCandidate(c) } catch (_: Exception) {}
        }
    }

    fun stop() {
        disposed = true
        try { signalReg?.remove() } catch (_: Exception) {}
        try { candReg?.remove() } catch (_: Exception) {}
        signalReg = null
        candReg = null
        exec.execute {
            try { videoCapturer?.stopCapture() } catch (_: Exception) {}
            try { videoCapturer?.dispose() } catch (_: Exception) {}
            videoCapturer = null
            try { localVideo?.dispose() } catch (_: Exception) {}
            try { localAudio?.dispose() } catch (_: Exception) {}
            try { videoSource?.dispose() } catch (_: Exception) {}
            try { audioSource?.dispose() } catch (_: Exception) {}
            try { surfaceHelper?.dispose() } catch (_: Exception) {}
            surfaceHelper = null
            try { pc?.close() } catch (_: Exception) {}
            pc = null
        }
        main.post {
            try { remoteRenderer?.release() } catch (_: Exception) {}
            remoteRenderer = null
        }
        abandonAudioFocus()
        remoteReady.set(false)
        pendingIce.clear()
        if (ownerUid.isNotEmpty() && role == "pub") {
            try {
                mediaDoc().set(
                    hashMapOf(
                        "status" to "stopped",
                        "command" to "none",
                        "mode" to "idle",
                        "message" to "توقف البث",
                        "offer" to FieldValue.delete(),
                        "answer" to FieldValue.delete(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            } catch (_: Exception) {}
        }
        ownerUid = ""
        role = ""
        sessionId = ""
    }

    open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}
