package com.findphone.app

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
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
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.Executors

/**
 * بث مباشر WebRTC عبر إشارة Firestore.
 * الناشر = الهاتف الهدف (كاميرا+ميكروفون)، المشاهد = الهاتف المتحكم.
 * STUN عام مجاني؛ إن فشل الاتصال على بعض الشبكات يلزم TURN خاص.
 */
class WebRtcLive(private val ctx: Context) {
    private val tag = "WebRtcLive"
    private val db = FirebaseFirestore.getInstance()
    private val exec = Executors.newSingleThreadExecutor()

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
    private var role: String = ""
    private var ownerUid: String = ""
    private var remoteRenderer: SurfaceViewRenderer? = null
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private var remoteDescSet = false
    private var lastOffer: String? = null

    var onStatus: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun initFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        egl = EglBase.create()
        val encoder = DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(egl!!.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
    }

    private fun iceServers(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        // TURN عام تجريبي: استبدله بخادم TURN خاص بك (Metered/Twilio/coturn) لضمان الاتصال عبر شبكات الجوال
        PeerConnection.IceServer.builder(listOf(
            "turn:openrelay.metered.ca:80",
            "turn:openrelay.metered.ca:443",
            "turn:openrelay.metered.ca:443?transport=tcp"
        )).setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
    )

    private fun pcObserver() = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d(tag, "iceConnection $state")
            if (state == PeerConnection.IceConnectionState.FAILED) {
                onStatus?.invoke("فشل الاتصال المباشر (غالباً الشبكة تحتاج خادم TURN)")
            } else {
                onStatus?.invoke("ICE: $state")
            }
        }
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(c: IceCandidate?) {
            if (c == null || ownerUid.isEmpty()) return
            val data = hashMapOf(
                "sdpMid" to c.sdpMid,
                "sdpMLineIndex" to c.sdpMLineIndex,
                "candidate" to c.sdp,
                "from" to role,
                "at" to FieldValue.serverTimestamp()
            )
            db.collection("media").document(ownerUid)
                .collection("candidates").document()
                .set(data)
        }
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {
            // legacy
        }
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track() ?: return
            if (track.kind() == "video" && track is VideoTrack) {
                remoteRenderer?.let { track.addSink(it) }
            }
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            onStatus?.invoke("الاتصال: $newState")
        }
    }

    private fun createPeer() {
        val cfg = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        pc = factory!!.createPeerConnection(cfg, pcObserver())
    }

    /** الهاتف الهدف: يبدأ البث (فيديو + صوت) ويكتب Offer */
    fun startPublisher(ownerUid: String) {
        this.ownerUid = ownerUid
        this.role = "pub"
        exec.execute {
            try {
                initFactory()
                createPeer()
                // فيديو
                val enumerator = Camera2Enumerator(ctx)
                val device = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
                    ?: enumerator.deviceNames.firstOrNull()
                    ?: throw IllegalStateException("لا توجد كاميرا")
                videoCapturer = enumerator.createCapturer(device, null)
                surfaceHelper = SurfaceTextureHelper.create("cap", egl!!.eglBaseContext)
                videoSource = factory!!.createVideoSource(false)
                videoCapturer?.initialize(surfaceHelper, ctx, videoSource!!.capturerObserver)
                videoCapturer?.startCapture(640, 480, 24)
                localVideo = factory!!.createVideoTrack("video0", videoSource)
                localVideo?.setEnabled(true)
                pc?.addTrack(localVideo, listOf("stream0"))
                // صوت
                audioSource = factory!!.createAudioSource(MediaConstraints())
                localAudio = factory!!.createAudioTrack("audio0", audioSource)
                localAudio?.setEnabled(true)
                pc?.addTrack(localAudio, listOf("stream0"))

                clearSignaling(ownerUid) {
                    listenForAnswerAndCandidates()
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                    }
                    pc?.createOffer(object : SdpAdapter() {
                        override fun onCreateSuccess(sdp: SessionDescription?) {
                            if (sdp == null) return
                            pc?.setLocalDescription(SdpAdapter(), sdp)
                            db.collection("media").document(ownerUid).set(
                                hashMapOf(
                                    "command" to "live",
                                    "status" to "active",
                                    "mode" to "live",
                                    "message" to "بث مباشر جارٍ",
                                    "offer" to sdp.description,
                                    "offerType" to sdp.type.canonicalForm(),
                                    "answer" to FieldValue.delete(),
                                    "updatedAt" to FieldValue.serverTimestamp()
                                ),
                                SetOptions.merge()
                            )
                            onStatus?.invoke("تم إنشاء العرض — بانتظار المشاهد")
                        }
                        override fun onCreateFailure(p0: String?) {
                            onError?.invoke("فشل إنشاء العرض: $p0")
                        }
                    }, constraints)
                }
            } catch (e: Exception) {
                onError?.invoke("فشل بدء البث: ${e.message}")
            }
        }
    }

    /** الهاتف المتحكم: يشاهد ويفتح Answer */
    fun startViewer(ownerUid: String, renderer: SurfaceViewRenderer) {
        this.ownerUid = ownerUid
        this.role = "view"
        this.remoteRenderer = renderer
        exec.execute {
            try {
                initFactory()
                val initLatch = java.util.concurrent.CountDownLatch(1)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        renderer.init(egl!!.eglBaseContext, null)
                        renderer.setMirror(false)
                        renderer.setEnableHardwareScaler(true)
                    } catch (e: Exception) {
                        onError?.invoke("فشل تهيئة العرض: ${e.message}")
                    }
                    initLatch.countDown()
                }
                initLatch.await()
                createPeer()
                // نستقبل فيديو وصوت
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }
                listenForOffer(constraints)
                listenCandidates()
            } catch (e: Exception) {
                onError?.invoke("فشل بدء المشاهدة: ${e.message}")
            }
        }
    }

    private fun listenForOffer(constraints: MediaConstraints) {
        signalReg?.remove()
        signalReg = db.collection("media").document(ownerUid)
            .addSnapshotListener { snap, _ ->
                if (snap == null || !snap.exists()) return@addSnapshotListener
                val offer = snap.getString("offer") ?: return@addSnapshotListener
                val type = snap.getString("offerType") ?: "offer"
                if (offer == lastOffer) return@addSnapshotListener
                if (pc?.remoteDescription != null) {
                    // عرض جديد بعد قديم (جلسة سابقة): أعد بناء الاتصال
                    try { pc?.close() } catch (_: Exception) {}
                    remoteDescSet = false
                    createPeer()
                }
                lastOffer = offer
                val sdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(type), offer
                )
                pc?.setRemoteDescription(object : SdpAdapter() {
                    override fun onSetSuccess() {
                        remoteDescSet = true
                        flushRemoteIce()
                        pc?.createAnswer(object : SdpAdapter() {
                            override fun onCreateSuccess(ans: SessionDescription?) {
                                if (ans == null) return
                                pc?.setLocalDescription(SdpAdapter(), ans)
                                db.collection("media").document(ownerUid).set(
                                    hashMapOf(
                                        "answer" to ans.description,
                                        "answerType" to ans.type.canonicalForm(),
                                        "updatedAt" to FieldValue.serverTimestamp()
                                    ),
                                    SetOptions.merge()
                                )
                                onStatus?.invoke("تم الاتصال — البث المباشر")
                            }
                            override fun onCreateFailure(p0: String?) {
                                onError?.invoke("فشل Answer: $p0")
                            }
                        }, constraints)
                    }
                }, sdp)
            }
    }

    private fun listenForAnswerAndCandidates() {
        signalReg?.remove()
        signalReg = db.collection("media").document(ownerUid)
            .addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                val answer = snap.getString("answer") ?: return@addSnapshotListener
                if (pc?.remoteDescription != null) return@addSnapshotListener
                val type = snap.getString("answerType") ?: "answer"
                val sdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(type), answer
                )
                pc?.setRemoteDescription(object : SdpAdapter() {
                    override fun onSetSuccess() {
                        remoteDescSet = true
                        flushRemoteIce()
                        onStatus?.invoke("المشاهد متصل")
                    }
                }, sdp)
            }
        listenCandidates()
    }

    private fun listenCandidates() {
        candReg?.remove()
        candReg = db.collection("media").document(ownerUid)
            .collection("candidates")
            .addSnapshotListener { qs, _ ->
                if (qs == null) return@addSnapshotListener
                for (doc in qs.documentChanges) {
                    if (doc.type != com.google.firebase.firestore.DocumentChange.Type.ADDED) continue
                    val d = doc.document
                    val from = d.getString("from") ?: continue
                    // تجاهل مرشحاتنا نحن
                    if (from == role) continue
                    val mid = d.getString("sdpMid") ?: continue
                    val idx = (d.getLong("sdpMLineIndex") ?: 0L).toInt()
                    val cand = d.getString("candidate") ?: continue
                    val ice = IceCandidate(mid, idx, cand)
                    if (remoteDescSet) {
                        pc?.addIceCandidate(ice)
                    } else {
                        pendingRemoteIce.add(ice)
                    }
                }
            }
    }

    private fun flushRemoteIce() {
        for (c in pendingRemoteIce) {
            try { pc?.addIceCandidate(c) } catch (_: Exception) {}
        }
        pendingRemoteIce.clear()
    }

    private fun clearSignaling(ownerUid: String, then: () -> Unit) {
        db.collection("media").document(ownerUid).collection("candidates")
            .get()
            .addOnSuccessListener { qs ->
                val batch = db.batch()
                qs.documents.forEach { batch.delete(it.reference) }
                batch.commit().addOnCompleteListener { then() }
            }
            .addOnFailureListener { then() }
    }

    fun stop() {
        try { signalReg?.remove() } catch (_: Exception) {}
        try { candReg?.remove() } catch (_: Exception) {}
        signalReg = null
        candReg = null
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
        try { remoteRenderer?.release() } catch (_: Exception) {}
        remoteRenderer = null
        remoteDescSet = false
        lastOffer = null
        pendingRemoteIce.clear()
        if (ownerUid.isNotEmpty() && role == "pub") {
            try {
                db.collection("media").document(ownerUid).set(
                    hashMapOf(
                        "status" to "stopped",
                        "command" to "none",
                        "mode" to "idle",
                        "message" to "توقف البث المباشر",
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
    }

    open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}
