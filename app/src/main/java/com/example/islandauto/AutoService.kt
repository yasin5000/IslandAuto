package com.example.islandauto

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class AutoService : AccessibilityService() {

    companion object {
        @Volatile var instance: AutoService? = null
        const val ISLAND = "com.oasisfeng.island"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var container: FrameLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var lp: WindowManager.LayoutParams

    // 0 = idle, 1 = Island welcome (arrow/ACCEPT), 2 = ACCEPT & CONTINUE, 3 = Play Store
    private var step = 0
    private var startTime = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        try { wm.removeView(container) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------------- Floating toolbar ----------------

    private fun buildOverlay() {
        container = FrameLayout(this)

        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xEE263238.toInt()); cornerRadius = dp(24).toFloat()
            }
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        val handle = TextView(this).apply {
            text = "\u22EE"; textSize = 22f; setTextColor(Color.WHITE)
            setPadding(dp(8), 0, dp(8), 0)
        }
        val run = Button(this).apply {
            text = "\u25B6 Run Island"; isAllCaps = false; textSize = 13f
            minWidth = 0; minHeight = 0; setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { startFlow() }
        }
        val hide = Button(this).apply {
            text = "\u2715"; textSize = 13f; minWidth = 0; minHeight = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { collapse() }
        }
        toolbar.addView(handle)
        toolbar.addView(run)
        toolbar.addView(hide)

        bubble = TextView(this).apply {
            text = "\uD83C\uDFDD"; textSize = 22f; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0xEE3F51B5.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48))
            visibility = View.GONE
        }

        container.addView(toolbar)
        container.addView(bubble)

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16); y = dp(200)
        }

        // Screen er je kono jayga tap korle toolbar hide (bubble) hoye jabe
        container.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) { collapse(); true } else false
        }
        makeDraggable(handle, null)
        makeDraggable(toolbar, null)
        makeDraggable(bubble) { expand() }

        wm.addView(container, lp)
    }

    private fun makeDraggable(v: View, onTap: (() -> Unit)?) {
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) { lp.x = sx + dx; lp.y = sy + dy; wm.updateViewLayout(container, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) onTap?.invoke(); true }
                else -> false
            }
        }
    }

    private fun collapse() {
        if (toolbar.visibility == View.VISIBLE) {
            toolbar.visibility = View.GONE; bubble.visibility = View.VISIBLE
        }
    }

    private fun expand() {
        toolbar.visibility = View.VISIBLE; bubble.visibility = View.GONE
    }

    fun showToolbar() {
        container.visibility = View.VISIBLE; expand()
    }

    // ---------------- Automation flow ----------------

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun startFlow() {
        val i = packageManager.getLaunchIntentForPackage(ISLAND)
        if (i == null) { toast("Island app install kora nai"); return }
        step = 1
        startTime = SystemClock.uptimeMillis()
        container.visibility = View.GONE          // automation cholar somoy toolbar lukiye thakbe
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 2000)
    }

    private fun finishFlow(msg: String) {
        step = 0
        handler.removeCallbacks(tick)
        container.visibility = View.VISIBLE
        collapse()
        toast(msg)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (step == 0) return
            if (SystemClock.uptimeMillis() - startTime > 90_000) { finishFlow("Timeout, abar try korun"); return }
            try { process() } catch (_: Exception) {}
            handler.postDelayed(this, 800)
        }
    }

    private fun process() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: ""
        when (step) {
            1, 2 -> {
                // System work-profile screen: ACCEPT & CONTINUE
                if (clickText(root, "ACCEPT & CONTINUE")) { step = 3; return }
                if (pkg == ISLAND) {
                    // Island welcome: ACCEPT
                    if (clickText(root, "ACCEPT")) { step = 2; return }
                    // already setup hole
                    if (step == 1 && root.findAccessibilityNodeInfosByText("MAINLAND").isNotEmpty()) {
                        step = 3; return
                    }
                    // arrow (niche dan dik) click, na hole scroll
                    if (!clickBottomRight(root)) scroll(root)
                }
            }
            3 -> if (pkg == ISLAND && clickText(root, "Google Play Store")) {
                finishFlow("Done \u2705 Play Store open")
            }
        }
    }

    private fun clickText(root: AccessibilityNodeInfo, t: String): Boolean {
        for (n in root.findAccessibilityNodeInfosByText(t)) {
            var x: AccessibilityNodeInfo? = n
            while (x != null && !x.isClickable) x = x.parent
            if (x != null && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    private fun clickBottomRight(root: AccessibilityNodeInfo): Boolean {
        val m = resources.displayMetrics
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo): Boolean {
            n.getBoundsInScreen(r)
            if (n.isClickable && r.centerX() > m.widthPixels * 0.6 && r.centerY() > m.heightPixels * 0.8) {
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                if (walk(c)) return true
            }
            return false
        }
        return walk(root)
    }

    private fun scroll(root: AccessibilityNodeInfo): Boolean {
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                find(c)?.let { return it }
            }
            return null
        }
        return find(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
    }
}
