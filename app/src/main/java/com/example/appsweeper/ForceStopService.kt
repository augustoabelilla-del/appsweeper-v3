package com.example.appsweeper

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Closes apps the way a person would: opens each app's info page in Settings,
 * taps "Force stop", then taps OK in the confirmation dialog.
 *
 * Safety: it only taps when the page on screen shows the name of the app it is
 * working on, so it can't force-stop the wrong app by mistake.
 */
class ForceStopService : AccessibilityService() {

    private enum class Stage { IDLE, OPEN, CONFIRM, WAIT }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private val failed = linkedSetOf<String>()
    private var current: String? = null
    private var currentLabel = ""
    private var stage = Stage.IDLE
    private var returnToApp = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlay: FrameLayout? = null
    private var overlayText: TextView? = null
    private var total = 0
    private var done = 0

    private val timeout = Runnable {
        current?.let { failed += it }
        next()
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        abort()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        abort()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ---------------------------------------------------------------- queue

    private fun begin(pkgs: List<String>, toApp: Boolean) {
        if (stage != Stage.IDLE) {
            pkgs.forEach { if (it != current && it !in queue) queue.addLast(it) }
            return
        }
        queue.clear()
        failed.clear()
        queue.addAll(pkgs)
        total = queue.size
        done = 0
        returnToApp = toApp
        Store(this).lastNote = ""
        if (queue.isEmpty()) return

        // Wake the screen for scheduled runs
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "appsweeper:forcestop"
        ).apply { acquire(queue.size * 8000L + 20000L) }

        stage = Stage.WAIT
        handler.postDelayed({
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (km.isKeyguardLocked) {
                Store(this).lastNote =
                    "Phone was locked, so nothing was closed. Accessibility sweeps need the screen unlocked."
                finish()
            } else {
                if (Store(this).hideScreens) showOverlay()
                next()
            }
        }, 1500)
    }

    private fun next() {
        handler.removeCallbacks(timeout)
        val pkg = queue.removeFirstOrNull()
        if (pkg == null) {
            finish()
            return
        }
        current = pkg
        currentLabel = Killer.labelOf(this, pkg)
        done += 1
        updateOverlay()
        stage = Stage.OPEN
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
            handler.postDelayed(timeout, 6000)
        } catch (e: Exception) {
            failed += pkg
            handler.post { next() }
        }
    }

    private fun finish() {
        handler.removeCallbacks(timeout)
        stage = Stage.IDLE
        current = null
        queue.clear()
        Store(this).setFailed(failed.toSet())
        releaseWake()
        hideOverlay()
        if (returnToApp) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } else {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun abort() {
        handler.removeCallbacksAndMessages(null)
        stage = Stage.IDLE
        queue.clear()
        releaseWake()
        hideOverlay()
    }

    // ----------------------------------------------------- cover screen

    /** A plain full-screen panel drawn above Settings so the app-info pages aren't visible. */
    private fun showOverlay() {
        if (overlay != null) return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val pad = (24 * resources.displayMetrics.density).toInt()

            val text = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
                this.text = "Cleaning apps…"
            }
            val cancel = Button(this).apply {
                this.text = "Cancel"
                setOnClickListener { cancelRun() }
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(pad, pad, pad, pad)
                addView(text)
                addView(cancel)
            }
            val root = FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#16171A"))
                addView(
                    box,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE
            )
            wm.addView(root, lp)
            overlay = root
            overlayText = text
            updateOverlay()
        } catch (e: Exception) {
            overlay = null
            overlayText = null
        }
    }

    private fun updateOverlay() {
        overlayText?.text = "Closing apps…\n$done / $total\n\n$currentLabel"
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
        }
        overlay = null
        overlayText = null
    }

    private fun cancelRun() {
        handler.removeCallbacks(timeout)
        queue.clear()
        performGlobalAction(GLOBAL_ACTION_BACK)
        Store(this).lastNote = "Sweep cancelled."
        finish()
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun backThenNext() {
        handler.removeCallbacks(timeout)
        stage = Stage.WAIT
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ next() }, 700)
    }

    // --------------------------------------------------------------- events

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (stage != Stage.OPEN && stage != Stage.CONFIRM) return
        val root = rootInActiveWindow ?: return

        when (stage) {
            Stage.OPEN -> {
                // Only act on the page that shows the app we are working on
                if (root.findAccessibilityNodeInfosByText(currentLabel).isNullOrEmpty()) return
                val btn = findByTexts(root, FORCE_STOP_IDS, FORCE_STOP_TEXTS) ?: return
                if (!btn.isEnabled) {
                    // Already stopped, or the app is protected by the phone. Skip it.
                    backThenNext()
                    return
                }
                if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    stage = Stage.CONFIRM
                    handler.removeCallbacks(timeout)
                    handler.postDelayed(timeout, 6000)
                }
            }
            Stage.CONFIRM -> {
                val ok = findByTexts(root, CONFIRM_IDS, CONFIRM_TEXTS) ?: return
                if (ok.performAction(AccessibilityNodeInfo.ACTION_CLICK)) backThenNext()
            }
            else -> {}
        }
    }

    // -------------------------------------------------------------- helpers

    private fun findByTexts(
        root: AccessibilityNodeInfo,
        ids: List<String>,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        for (id in ids) {
            root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()?.let { return clickableSelfOrParent(it) }
        }
        for (t in texts) {
            val hit = root.findAccessibilityNodeInfosByText(t)
                ?.firstOrNull { it.text?.toString()?.trim().equals(t, ignoreCase = true) }
            if (hit != null) return clickableSelfOrParent(hit)
        }
        return null
    }

    private fun clickableSelfOrParent(n: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var x: AccessibilityNodeInfo? = n
        while (x != null) {
            if (x.isClickable) return x
            x = x.parent
        }
        return n
    }

    companion object {
        @Volatile
        var instance: ForceStopService? = null

        fun isRunning(): Boolean = instance != null

        /** Queue packages for force-stopping. Returns false if the service is not switched on. */
        fun enqueue(pkgs: List<String>, returnToApp: Boolean): Boolean {
            val svc = instance ?: return false
            svc.handler.post { svc.begin(pkgs, returnToApp) }
            return true
        }

        // View ids are language-independent, so they are tried first.
        private val FORCE_STOP_IDS = listOf(
            "com.android.settings:id/force_stop_button",
            "com.android.settings:id/right_button",
            "com.android.settings:id/button_force_stop"
        )
        private val CONFIRM_IDS = listOf("android:id/button1")

        private val FORCE_STOP_TEXTS = listOf(
            "Force stop", "Force close",
            "强行停止", "强制停止", "強制停止", "強制終止",
            "Forzar detención", "Forzar parada",
            "Forcer l'arrêt", "Beenden erzwingen",
            "Forçar parada", "Forçar paragem", "Forza interruzione",
            "Принудительно остановить", "강제 종료", "강제 중지",
            "Paksa berhenti", "Buộc dừng", "บังคับหยุด", "Zorla durdur"
        )
        private val CONFIRM_TEXTS = listOf(
            "OK", "Force stop", "Yes",
            "确定", "確定", "强行停止", "强制停止", "強制停止",
            "Aceptar", "Forzar detención", "Forcer l'arrêt", "Beenden erzwingen",
            "Принудительно остановить", "확인", "강제 종료"
        )
    }
}
