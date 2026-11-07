package com.findphone.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import org.webrtc.SurfaceViewRenderer

class LiveViewActivity : Activity() {
    private var live: WebRtcLive? = null
    private var renderer: SurfaceViewRenderer? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val ownerUid = intent.getStringExtra(EXTRA_OWNER_UID) ?: run {
            finish(); return
        }
        val name = intent.getStringExtra(EXTRA_NAME) ?: "بث مباشر"

        val status = TextView(this).apply {
            text = "جارٍ الاتصال…\nتأكد أن الهدف ضغط «بدء استماع الأوامر» وأن البث المباشر أُرسل"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(24, 24, 24, 12)
            gravity = Gravity.CENTER
        }
        renderer = SurfaceViewRenderer(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(status, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(renderer, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            val close = TextView(this@LiveViewActivity).apply {
                text = "إيقاف المشاهدة"
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(24, 28, 24, 28)
                setBackgroundColor(Color.parseColor("#CC222222"))
                setOnClickListener { finish() }
            }
            addView(close, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        setContentView(root)

        live = WebRtcLive(this).also { w ->
            w.onStatus = { msg -> runOnUiThread { status.text = msg } }
            w.onError = { msg -> runOnUiThread { status.text = "خطأ: $msg" } }
            w.startViewer(ownerUid, renderer!!)
        }
        title = name
    }

    override fun onDestroy() {
        try { live?.stop() } catch (_: Exception) {}
        live = null
        renderer = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OWNER_UID = "ownerUid"
        const val EXTRA_NAME = "name"
    }
}
