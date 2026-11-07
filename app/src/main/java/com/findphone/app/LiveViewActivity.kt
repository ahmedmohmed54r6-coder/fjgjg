package com.findphone.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.webrtc.SurfaceViewRenderer

/**
 * شاشة مشاهدة البث المباشر (فيديو + صوت متصل عبر WebRTC).
 * افتحها بعد إرسال «بث مباشر» للهدف بثوانٍ قليلة.
 */
class LiveViewActivity : Activity() {
    private var live: WebRtcLive? = null
    private var renderer: SurfaceViewRenderer? = null
    private var statusTv: TextView? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val ownerUid = intent.getStringExtra(EXTRA_OWNER_UID) ?: run {
            finish(); return
        }
        val name = intent.getStringExtra(EXTRA_NAME) ?: "بث مباشر"

        statusTv = TextView(this).apply {
            text = "جارٍ الاتصال بالبث…\nتأكد أن الهدف فعّل السحابي ومفاتيح الكاميرا/الميكروفون"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(32, 28, 32, 16)
            setLineSpacing(6f, 1f)
        }
        renderer = SurfaceViewRenderer(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val tip = TextView(this).apply {
            text = "للحصول على أفضل نتيجة: الهاتفان على نفس الـ Wi‑Fi"
            setTextColor(Color.parseColor("#AAD4FF"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(24, 8, 24, 8)
        }

        val close = TextView(this).apply {
            text = "إيقاف المشاهدة"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(24, 28, 24, 28)
            setBackgroundColor(Color.parseColor("#CC1A56A0"))
            setOnClickListener { finish() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(statusTv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(renderer, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(tip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(close, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        setContentView(root)
        title = name

        live = WebRtcLive(applicationContext).also { w ->
            w.onStatus = { msg -> runOnUiThread { statusTv?.text = msg } }
            w.onError = { msg -> runOnUiThread { statusTv?.text = "خطأ: $msg" } }
            w.onConnected = {
                runOnUiThread {
                    statusTv?.text = "البث متصل — فيديو وصوت مباشرين"
                    statusTv?.setTextColor(Color.parseColor("#4ADE80"))
                }
            }
            w.startViewer(ownerUid, renderer!!)
        }
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
