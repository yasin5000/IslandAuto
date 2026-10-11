package com.example.islandauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
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
        const val PLAY = "Google Play Store"
        const val SEARCH_TEXT = "tigo"
        const val BIRTH_YEAR = 2000      // jei year chai
        const val DEFAULT_YEAR = 2001    // Tigo birthday picker er default
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var container: FrameLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var destroyBtn: Button
    private lateinit var lp: WindowManager.LayoutParams

    // 0 = idle
    // Run flow:     1/2 = ACCEPT / ACCEPT & CONTINUE, 3 = Play Store row, 4 = open arrow, 5 = Sign in,
    //               6 = user login dewar por "I understand"/"I agree" click, 8 = Search tab,
    //               9 = "tigo" type, 10 = search submit, 11 = Install click, 12 = download shesh hole Open click,
    //               13 = Tigo e "Google" button, 14 = Choose an account e Gmail select,
    //               15 = Gender: Male, 16 = Confirm,
    //               17 = Birthday year 2001 -> 2000, 18 = Birthday Confirm,
    //               30 = Home e profile (man) icon, 31 = "Daily Bonus"
    // Destroy flow: 20 = 3-dot, 21 = Settings, 22 = Scoped Settings, 23 = Island,
    //               24 = scroll + Destroy permanently, 25 = DESTROY (confirm)
    private var step = 0
        set(v) {
            val old = field
            field = v
            if (old != v) onStepChanged(old, v)
        }
    private var stepSince = 0L
    private var lastDone = ""
    private var inNotice = false          // "Welcome to your new account" notice screen e achi
    private var lastUnderstandAt = 0L
    private var swipeToggle = false
    private lateinit var statusView: TextView
    private lateinit var statusLp: WindowManager.LayoutParams
    private var startTime = 0L
    private var tries = 0
    private var destroyArmed = false
    private var lastInstallY = -1
    private var birthRowY = -1

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildOverlay()
        buildStatusOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        try { wm.removeView(container) } catch (_: Exception) {}
        try { wm.removeView(statusView) } catch (_: Exception) {}
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
            minWidth = 0; minHeight = 0; setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { startRun() }
        }
        destroyBtn = Button(this).apply {
            text = "\uD83D\uDDD1 Destroy"; isAllCaps = false; textSize = 13f
            minWidth = 0; minHeight = 0; setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        // Destroy ekdom chap dile jeno na hoy: 1st tap = "Sure?", 3 sec er moddhe 2nd tap = chalu
        destroyBtn.setOnClickListener {
            if (!destroyArmed) {
                destroyArmed = true
                destroyBtn.text = "\u26A0 Sure?"
                handler.postDelayed({
                    destroyArmed = false
                    destroyBtn.text = "\uD83D\uDDD1 Destroy"
                }, 3000)
            } else {
                destroyArmed = false
                destroyBtn.text = "\uD83D\uDDD1 Destroy"
                startDestroy()
            }
        }
        val hide = Button(this).apply {
            text = "\u2715"; textSize = 13f; minWidth = 0; minHeight = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { collapse() }
        }
        toolbar.addView(handle)
        toolbar.addView(run)
        toolbar.addView(destroyBtn)
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

    // ---------------- Flow control ----------------

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun launchIsland(firstStep: Int) {
        val i = packageManager.getLaunchIntentForPackage(ISLAND)
        if (i == null) { toast("Island app install kora nai"); return }
        step = firstStep
        tries = 0
        startTime = SystemClock.uptimeMillis()
        container.visibility = View.GONE          // automation cholar somoy toolbar lukiye thakbe
        lastDone = ""; inNotice = false
        stepSince = SystemClock.uptimeMillis()
        showStatus("\u25B6 Shuru: " + stepName(firstStep), 0xEE1565C0.toInt())
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 2000)
    }

    private fun startRun() = launchIsland(1)
    private fun startDestroy() = launchIsland(20)

    private fun finishFlow(msg: String) {
        val ok = msg.contains("\u2705") || msg.contains("done", true) || msg.contains("installed ache")
        val where = if (step != 0) "\n\uD83D\uDCCD Ei step e: ${stepName(step)}" else ""
        showStatus((if (ok) "\u2705 " else "\u274C ") + msg + where,
            if (ok) 0xEE2E7D32.toInt() else 0xEEC62828.toInt(), if (ok) 6000 else 20000)
        step = 0
        handler.removeCallbacks(tick)
        container.visibility = View.VISIBLE
        collapse()
        toast(msg)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (step == 0) return
            // step 6 e user nijer Gmail/password dey, tai beshi somoy (5 min)
            val limit = if (step == 6 || step == 12) 300_000L else 120_000L
            if (SystemClock.uptimeMillis() - startTime > limit) { finishFlow("Timeout, abar try korun"); return }
            try { process() } catch (_: Exception) {}
            if (step != 0) refreshStatus()
            handler.postDelayed(this, 800)
        }
    }

    private fun process() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: ""
        tries++
        when (step) {
            // ---------- Run flow ----------
            1, 2 -> {
                if (clickText(root, "ACCEPT & CONTINUE")) { step = 3; tries = 0; return }
                if (pkg == ISLAND) {
                    if (clickText(root, "ACCEPT")) { step = 2; return }
                    if (step == 1 && root.findAccessibilityNodeInfosByText("MAINLAND").isNotEmpty()) {
                        step = 3; tries = 0; return          // already setup
                    }
                    if (!clickBottomRight(root)) scroll(root)
                }
            }
            3 -> if (pkg == ISLAND && clickText(root, PLAY)) { step = 4; tries = 0 }
            4 -> if (pkg == ISLAND) {
                // Play Store row chap dile pashe arrow ashe, sheta chap
                if (clickRowRight(root, PLAY)) { step = 5; tries = 0; return }
                // arrow node na pele: row er dan prante coordinate tap
                if (tries >= 2 && tapRowRight(root, PLAY)) { step = 5; tries = 0 }
            }
            5 -> {
                // Play Store open howar por Sign in button khuje click korbe.
                if (pkg != ISLAND && pkg.isNotEmpty()) {
                    if (clickText(root, "Sign in")) {
                        // Sign in click hoyeche; ekhon user Gmail/pass dibe, tarpor "I understand" click hobe
                        step = 6; tries = 0
                        startTime = SystemClock.uptimeMillis()
                    } else if (tries >= 20) {
                        finishFlow("Play Store open, Sign in button paoa jayni")
                    }
                } else if (tries >= 4) {
                    step = 4; tries = 0   // Play Store open hoyni, abar try
                }
            }

            // User Gmail + password dewar por "I understand" / "I agree" click
            6 -> {
                if (handleUnderstand(root)) { tries = 0; return }
                if (clickText(root, "I agree")) { step = 8; tries = 0; startTime = SystemClock.uptimeMillis(); return }
                // login er age jeno step 9 e na jay: "Sign in" na thakle tobei Search tab
                val signInGone = root.findAccessibilityNodeInfosByText("Sign in").isEmpty()
                if (pkg == "com.android.vending" && signInGone && tries > 3 && clickExact(root, "Search")) {
                    step = 9; tries = 0; startTime = SystemClock.uptimeMillis()
                }
            }
            // I agree er por: (jodi ashe) Google services "Accept", tarpor Play Store home er Search tab
            8 -> {
                if (handleUnderstand(root)) return
                if (clickText(root, "Accept")) return
                if (clickText(root, "I agree")) return
                if (clickExact(root, "Search")) { step = 9; tries = 0 }
            }
            // Search box e "tigo" type
            9 -> {
                val box = findEditable(root)
                if (box == null) {
                    // Search tab e age non-editable "Search apps & games" bar thake, shetay click
                    if (clickText(root, "Search apps")) return
                    if (tries >= 20) finishFlow("Search box paoa jayni")
                    return
                }
                box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                box.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, SEARCH_TEXT)
                }
                if (box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) { step = 10; tries = 0 }
                else if (tries >= 12) finishFlow("Text type kora jayni")
            }
            // Search submit (keyboard enter), na hole typed suggestion click
            10 -> {
                val box = findEditable(root)
                var ok = false
                if (box != null && Build.VERSION.SDK_INT >= 30) {
                    ok = box.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
                if (!ok) ok = clickSuggestion(root, SEARCH_TEXT)
                if (ok) { step = 11; tries = 0; startTime = SystemClock.uptimeMillis() }
                else if (tries >= 10) finishFlow("Search submit kora jayni")
            }
            // Result ashle "tigo" er row er Install button click
            11 -> {
                if (clickInstall(root)) { step = 12; tries = 0; startTime = SystemClock.uptimeMillis(); return }
                // already install thakle Install er jaygay "Open" thake
                if (tries >= 6 && root.findAccessibilityNodeInfosByText("Open").isNotEmpty()) {
                    finishFlow("App ager theke installed ache"); return
                }
                if (tries >= 30) finishFlow("Install button paoa jayni")
            }
            // Download/install shesh hole je Install button chhilo shetai "Open" hoye jay, seta click
            12 -> {
                if (pkg == "com.android.vending") {
                    if (clickOpenNear(root, lastInstallY)) { step = 13; tries = 0; startTime = SystemClock.uptimeMillis(); return }
                    // layout sore gele (60 tick ~ 48 sec por) je kono Open
                    if (tries >= 60 && clickExact(root, "Open")) { step = 13; tries = 0; startTime = SystemClock.uptimeMillis() }
                }
            }
            // Tigo login page: "Google" button click
            13 -> {
                if (pkg == "com.android.vending" || pkg == ISLAND || pkg.isEmpty()) {
                    // Tigo ekhono khole ni; Open click na hoye thakle abar chesta
                    if (tries % 8 == 0) clickOpenNear(root, lastInstallY)
                    return
                }
                if (clickExact(root, "Google")) { step = 14; tries = 0; startTime = SystemClock.uptimeMillis(); return }
                // button er text accessibility te na dile: screen er nicher dike (~73%) tap
                if (tries >= 20) {
                    val m = resources.displayMetrics
                    if (tapAt(m.widthPixels * 0.5f, m.heightPixels * 0.73f)) {
                        step = 14; tries = 0; startTime = SystemClock.uptimeMillis()
                    }
                }
            }
            // "Choose an account": prothom Gmail account select
            14 -> {
                val chooser = pkg == "com.google.android.gms" ||
                    root.findAccessibilityNodeInfosByText("Choose an account").isNotEmpty()
                if (chooser && clickAccount(root)) { step = 15; tries = 0; startTime = SystemClock.uptimeMillis(); return }
                if (tries >= 20) finishFlow("Account list paoa jayni")
            }

            // Gender page: "Male" select
            15 -> {
                if (clickMale(root)) { step = 16; tries = 0; return }
                if (tries >= 40) finishFlow("Gender page / Male paoa jayni")
            }
            // Male select hole "Confirm" enable hoy, seta (ba "Continue") click
            16 -> {
                if (tries < 2) return
                if (clickEnabled(root, "Confirm") || clickEnabled(root, "Continue")) {
                    step = 17; tries = 0; birthRowY = -1; startTime = SystemClock.uptimeMillis()
                } else if (tries >= 15) finishFlow("Confirm click kora jayni")
            }

            // Birthday page: year wheel DEFAULT_YEAR (2001) theke BIRTH_YEAR (2000) e ana
            17 -> {
                if (tries < 2) return
                val cur = findExact(root, DEFAULT_YEAR.toString())
                val tgt = findExact(root, BIRTH_YEAR.toString())
                if (cur != null && tgt != null) {
                    val cr = Rect(); cur.getBoundsInScreen(cr)
                    val tr = Rect(); tgt.getBoundsInScreen(tr)
                    if (birthRowY < 0) birthRowY = cr.centerY()          // default year er row = selected row
                    if (abs(tr.centerY() - birthRowY) <= dp(20)) { step = 18; tries = 0; return }   // 2000 select hoyeche
                    swipe(cr.centerX().toFloat(), cr.centerY().toFloat(), tr.centerX().toFloat(), tr.centerY().toFloat(), 350)
                    return
                }
                // wheel er text accessibility te na dile: Year column e ek ghor niche swipe (andaj coordinate)
                if (tries >= 8) {
                    val w = resources.displayMetrics.widthPixels.toFloat()
                    swipe(w * 0.13f, w * 0.70f, w * 0.13f, w * 0.87f, 350)
                    step = 18; tries = 0
                }
            }
            // Wheel thamle "Confirm" click
            18 -> {
                if (tries < 2) return
                if (clickEnabled(root, "Confirm")) { step = 30; tries = 0; startTime = SystemClock.uptimeMillis(); return }
                // Confirm er text na pele andaj coordinate tap
                if (tries >= 6) {
                    val w = resources.displayMetrics.widthPixels.toFloat()
                    tapAt(w * 0.5f, w * 1.18f)
                    step = 30; tries = 0; startTime = SystemClock.uptimeMillis()
                }
            }

            // Home page: niche dan dike (man) profile icon click
            30 -> {
                if (tries < 3) return
                if (pkg == "com.android.vending" || pkg == ISLAND || pkg.isEmpty()) return
                if (clickBottomNavLast(root)) { step = 31; tries = 0; return }
                // icon node na pele andaj coordinate tap (screen er dan-niche)
                if (tries >= 10) {
                    val m = resources.displayMetrics
                    if (tapAt(m.widthPixels * 0.875f, m.heightPixels * 0.88f)) { step = 31; tries = 0 }
                }
            }
            // Profile page: "Daily Bonus" click
            31 -> {
                if (clickExact(root, "Daily Bonus")) { finishFlow("Daily Bonus click kora hoyeche \u2705"); return }
                if (tries >= 25) finishFlow("Daily Bonus paoa jayni")
            }

            // ---------- Destroy flow ----------
            20 -> if (pkg == ISLAND) {
                if (root.findAccessibilityNodeInfosByText("MAINLAND").isNotEmpty()) {
                    if (clickTopRight(root)) { step = 21; tries = 0 }       // 3 dot
                } else if (tries <= 3) {
                    performGlobalAction(GLOBAL_ACTION_BACK)                  // main screen e fera
                }
            }
            21 -> if (clickExact(root, "Settings")) { step = 22; tries = 0 }
            22 -> if (clickExact(root, "Scoped Settings")) { step = 23; tries = 0 }
            23 -> if (clickExact(root, "Island")) { step = 24; tries = 0 }
            24 -> {
                if (clickText(root, "Destroy permanently")) { step = 25; tries = 0 }
                else scroll(root)                                           // ekdom niche scroll
            }
            25 -> if (clickExact(root, "DESTROY")) finishFlow("Destroy done \u2705")
        }
    }

    // ---------------- Status notice (kon step shesh, kothay atke ase) ----------------

    private fun stepName(s: Int): String = when (s) {
        1, 2 -> "Island: ACCEPT"
        3 -> "Island: Play Store row"
        4 -> "Island: Play Store arrow"
        5 -> "Play Store: Sign in button"
        6 -> "Login + \"I understand\" notice"
        8 -> "I agree / Accept"
        9 -> "Search e \"tigo\" type"
        10 -> "Search submit"
        11 -> "Install button"
        12 -> "Download cholche (Open er opekkha)"
        13 -> "Tigo: Google button"
        14 -> "Choose an account"
        15 -> "Gender: Male"
        16 -> "Gender Confirm"
        17 -> "Birthday year"
        18 -> "Birthday Confirm"
        30 -> "Profile icon"
        31 -> "Daily Bonus"
        20 -> "Destroy: 3-dot menu"
        21 -> "Destroy: Settings"
        22 -> "Destroy: Scoped Settings"
        23 -> "Destroy: Island"
        24 -> "Destroy: Destroy permanently"
        25 -> "Destroy: DESTROY confirm"
        else -> "Step $s"
    }

    private fun buildStatusOverlay() {
        statusView = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(0xEE263238.toInt()); cornerRadius = dp(14).toFloat()
            }
            visibility = View.GONE
        }
        statusLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_TOUCHABLE: notice er pichone click/scroll jeno badha na pay
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(60)
        }
        wm.addView(statusView, statusLp)
    }

    private val hideStatus = Runnable { statusView.visibility = View.GONE }

    private fun showStatus(msg: String, color: Int, hideAfterMs: Long = 0) {
        if (!::statusView.isInitialized) return
        (statusView.background as GradientDrawable).setColor(color)
        statusView.text = msg
        statusView.visibility = View.VISIBLE
        handler.removeCallbacks(hideStatus)
        if (hideAfterMs > 0) handler.postDelayed(hideStatus, hideAfterMs)
    }

    private fun onStepChanged(old: Int, new: Int) {
        stepSince = SystemClock.uptimeMillis()
        if (old != 0 && new != 0) lastDone = stepName(old)
        if (new != 0) refreshStatus()
    }

    private fun refreshStatus() {
        val sec = (SystemClock.uptimeMillis() - stepSince) / 1000
        val sb = StringBuilder()
        if (lastDone.isNotEmpty()) sb.append("\u2705 Shesh: ").append(lastDone).append('\n')
        val stuck = sec >= 15
        sb.append(if (stuck) "\u26A0 Atke ase: " else "\u25B6 Cholche: ").append(stepName(step))
        sb.append(" (").append(sec).append("s)")
        showStatus(sb.toString(), if (stuck) 0xEEE65100.toInt() else 0xEE263238.toInt())
    }

    // ---------------- "I understand" notice (scroll lage kina auto bujhe) ----------------

    private fun isNoticeScreen(root: AccessibilityNodeInfo): Boolean {
        val keys = listOf("Welcome to your new account", "Your school manages", "Workspace for Education",
            "ask your school", "internal policies")
        return keys.any { root.findAccessibilityNodeInfosByText(it).isNotEmpty() }
    }

    // true = kichu ekta kora hoyeche (click ba scroll). Notice screen na hole false
    private fun handleUnderstand(root: AccessibilityNodeInfo): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastUnderstandAt < 2500) return inNotice      // click er por screen change er opekkha

        val m = resources.displayMetrics
        val r = Rect()
        // 1) Button screen e dekha gele (scroll lagle o, na lagle o) click
        val btn = root.findAccessibilityNodeInfosByText("I understand").firstOrNull {
            it.getBoundsInScreen(r)
            it.isVisibleToUser && r.height() > 0 && r.bottom <= m.heightPixels && r.top >= 0
        }
        if (btn != null) {
            inNotice = true
            btn.getBoundsInScreen(r)
            var x: AccessibilityNodeInfo? = btn
            while (x != null && !x.isClickable) x = x.parent
            val ok = (x != null && x.isEnabled && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ||
                tapAt(r.centerX().toFloat(), r.centerY().toFloat())
            if (ok) { lastUnderstandAt = now; inNotice = false; return true }
            return true
        }
        // 2) Notice screen kintu button dekha jacche na -> niche scroll
        if (isNoticeScreen(root)) inNotice = true
        if (!inNotice) return false
        swipeToggle = !swipeToggle
        // ekbar accessibility scroll, porer bar gesture swipe (WebView e duto-i kaj kore)
        if (swipeToggle && scroll(root)) return true
        swipe(m.widthPixels * 0.5f, m.heightPixels * 0.70f, m.widthPixels * 0.5f, m.heightPixels * 0.25f, 400)
        return true
    }

    // ---------------- Helpers ----------------

    private fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findEditable(c)?.let { return it }
        }
        return null
    }

    // Editable na emon node jar text e search word ache (suggestion row)
    private fun clickSuggestion(root: AccessibilityNodeInfo, t: String): Boolean {
        for (n in root.findAccessibilityNodeInfosByText(t)) {
            if (n.isEditable) continue
            var x: AccessibilityNodeInfo? = n
            while (x != null && !x.isClickable) x = x.parent
            if (x != null && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    // Sponsored (ad) bad diye prothom asol Install button click.
    // "Sponsored" label er thik niche je Install ta prothom ashe, seta ad.
    private fun clickInstall(root: AccessibilityNodeInfo): Boolean {
        val installs = mutableListOf<AccessibilityNodeInfo>()
        val sponsoredBottoms = mutableListOf<Int>()
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo) {
            val s = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (s != null) {
                if (s.equals("Install", ignoreCase = true)) installs.add(n)
                else if (s.startsWith("Sponsored", ignoreCase = true)) {
                    n.getBoundsInScreen(r); sponsoredBottoms.add(r.bottom)
                }
            }
            for (i in 0 until n.childCount) { n.getChild(i)?.let { walk(it) } }
        }
        walk(root)
        if (installs.isEmpty()) return false

        // uporer theke niche sajao
        val sorted = installs.map { n -> n.getBoundsInScreen(r); Pair(r.centerY(), n) }.sortedBy { it.first }
        val adIdx = HashSet<Int>()
        for (sy in sponsoredBottoms) {
            val idx = sorted.indexOfFirst { it.first >= sy }
            if (idx >= 0) adIdx.add(idx)
        }
        var target: AccessibilityNodeInfo? = null
        for ((i, p) in sorted.withIndex()) {
            if (i !in adIdx) { target = p.second; break }
        }
        if (target == null) { scroll(root); return false }   // shob ad, niche scroll kore dekhi

        target.getBoundsInScreen(r)
        lastInstallY = r.centerY()
        var x: AccessibilityNodeInfo? = target
        while (x != null && !x.isClickable) x = x.parent
        return x?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    // Install button er same row er "Open" button click
    private fun clickOpenNear(root: AccessibilityNodeInfo, y: Int): Boolean {
        val opens = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) {
            val s = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (s != null && s.equals("Open", ignoreCase = true)) opens.add(n)
            for (i in 0 until n.childCount) { n.getChild(i)?.let { walk(it) } }
        }
        walk(root)
        if (opens.isEmpty() || y < 0) return false
        val r = Rect()
        var target: AccessibilityNodeInfo? = null
        var best = Int.MAX_VALUE
        for (n in opens) {
            n.getBoundsInScreen(r)
            val d = abs(r.centerY() - y)
            if (d < best) { best = d; target = n }
        }
        if (target == null || best > dp(120)) return false
        var x: AccessibilityNodeInfo? = target
        while (x != null && !x.isClickable) x = x.parent
        return x?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    // "@" thaka prothom account row click ("Add another account" e @ nei, tai bad jabe)
    private fun clickAccount(root: AccessibilityNodeInfo): Boolean {
        fun walk(n: AccessibilityNodeInfo): Boolean {
            val s = (n.text ?: n.contentDescription)?.toString()
            if (s != null && s.contains("@") && !n.isEditable) {
                var x: AccessibilityNodeInfo? = n
                while (x != null && !x.isClickable) x = x.parent
                if (x != null && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                if (walk(c)) return true
            }
            return false
        }
        return walk(root)
    }

    // "Male" label click. Shabdhan: findAccessibilityNodeInfosByText("Male") "Female" o dhore, tai exact match
    private fun clickMale(root: AccessibilityNodeInfo): Boolean {
        val lab = root.findAccessibilityNodeInfosByText("Male").firstOrNull {
            (it.text ?: it.contentDescription)?.toString()?.trim().equals("Male", ignoreCase = true)
        } ?: return false
        var x: AccessibilityNodeInfo? = lab
        while (x != null && !x.isClickable) x = x.parent
        if (x != null && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        // clickable node na pele label er upor tap
        val r = Rect(); lab.getBoundsInScreen(r)
        return tapAt(r.centerX().toFloat(), r.centerY().toFloat())
    }

    // Enabled thakle tobei exact text e click (disabled "Confirm" skip)
    private fun clickEnabled(root: AccessibilityNodeInfo, t: String): Boolean {
        fun walk(n: AccessibilityNodeInfo): Boolean {
            val s = (n.text ?: n.contentDescription)?.toString()
            if (s != null && s.trim().equals(t, ignoreCase = true)) {
                var x: AccessibilityNodeInfo? = n
                while (x != null && !x.isClickable) x = x.parent
                if (x != null && x.isEnabled && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                if (walk(c)) return true
            }
            return false
        }
        return walk(root)
    }

    private fun findExact(root: AccessibilityNodeInfo, t: String): AccessibilityNodeInfo? {
        for (n in root.findAccessibilityNodeInfosByText(t)) {
            if ((n.text ?: n.contentDescription)?.toString()?.trim() == t) return n
        }
        return null
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long): Boolean {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, dur))
            .build()
        return dispatchGesture(g, null, null)
    }

    // Niche tab bar er sobcheye dan er clickable icon (profile)
    private fun clickBottomNavLast(root: AccessibilityNodeInfo): Boolean {
        val m = resources.displayMetrics
        val r = Rect()
        var best: AccessibilityNodeInfo? = null
        var bestX = -1
        fun walk(n: AccessibilityNodeInfo) {
            n.getBoundsInScreen(r)
            if (n.isClickable && r.centerY() > m.heightPixels * 0.80 && r.centerY() < m.heightPixels * 0.95 &&
                r.width() < m.widthPixels * 0.35 && r.centerX() > bestX) { best = n; bestX = r.centerX() }
            for (i in 0 until n.childCount) { n.getChild(i)?.let { walk(it) } }
        }
        walk(root)
        return best?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun canClick(n: AccessibilityNodeInfo): Boolean =
        n.isClickable || n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }

    private fun clickText(root: AccessibilityNodeInfo, t: String): Boolean {
        for (n in root.findAccessibilityNodeInfosByText(t)) {
            var x: AccessibilityNodeInfo? = n
            while (x != null && !x.isClickable) x = x.parent
            if (x != null && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    // Poro text ta hubohu mile gele click (substring na)
    private fun clickExact(root: AccessibilityNodeInfo, t: String): Boolean {
        fun walk(n: AccessibilityNodeInfo): Boolean {
            val s = (n.text ?: n.contentDescription)?.toString()
            if (s != null && s.trim().equals(t, ignoreCase = true)) {
                var x: AccessibilityNodeInfo? = n
                while (x != null && !x.isClickable) x = x.parent
                if (x != null && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                if (walk(c)) return true
            }
            return false
        }
        return walk(root)
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

    // Upore dan dik er sobcheye dan er clickable button (3 dot)
    private fun clickTopRight(root: AccessibilityNodeInfo): Boolean {
        val m = resources.displayMetrics
        val r = Rect()
        var best: AccessibilityNodeInfo? = null
        var bestX = -1
        fun walk(n: AccessibilityNodeInfo) {
            n.getBoundsInScreen(r)
            if (n.isClickable && r.centerY() < m.heightPixels * 0.2 && r.width() < m.widthPixels * 0.3 &&
                r.centerX() > bestX) { best = n; bestX = r.centerX() }
            for (i in 0 until n.childCount) { n.getChild(i)?.let { walk(it) } }
        }
        walk(root)
        return best?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    // label er same row er dan dik er clickable (open arrow)
    private fun clickRowRight(root: AccessibilityNodeInfo, label: String): Boolean {
        val m = resources.displayMetrics
        val lab = root.findAccessibilityNodeInfosByText(label).firstOrNull() ?: return false
        val lr = Rect(); lab.getBoundsInScreen(lr)
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo): Boolean {
            n.getBoundsInScreen(r)
            if (canClick(n) && r.centerX() > m.widthPixels * 0.7 && r.width() < m.widthPixels * 0.4 &&
                lr.centerY() >= r.top && lr.centerY() <= r.bottom) {
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

    // Row er dan prante (arrow er jayga) screen tap
    private fun tapRowRight(root: AccessibilityNodeInfo, label: String): Boolean {
        val lab = root.findAccessibilityNodeInfosByText(label).firstOrNull() ?: return false
        val lr = Rect(); lab.getBoundsInScreen(lr)
        val m = resources.displayMetrics
        return tapAt((m.widthPixels - dp(28)).toFloat(), lr.centerY().toFloat())
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 80))
            .build()
        return dispatchGesture(g, null, null)
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
