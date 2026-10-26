package dev.anom.pixelbar

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.RoundedCorner
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.max

class StatusBarService : AccessibilityService() {
    private var wm: WindowManager? = null
    private var view: StatusBarView? = null
    private var monitor: StatusMonitor? = null
    private var scope: CoroutineScope? = null
    private var env = Env()

    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private var lastShot = 0L
    private var lastAdaptive: Int? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        wm = getSystemService(WindowManager::class.java)
        val m = StatusMonitor(this).also { it.start() }
        monitor = m
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sc
        sc.launch {
            combine(Prefs.config, m.state, NotifStore.icons) { c, s, n -> Triple(c, s, n) }
                .collect { (c, s, n) -> render(c, s, n) }
        }
    }

    private fun render(c: Config, s: Status, n: List<Bitmap>) {
        if (!c.enabled) { detach(); return }
        var v = view
        if (v == null) {
            v = StatusBarView(this)
            attach(v)
            if (view == null) return
            if (c.bgMode == 0) schedule()
        }
        v.update(c, s, n, env)
        if (c.bgMode == 0) {
            v.setAdaptiveColor(lastAdaptive)
            if (lastAdaptive == null) schedule()
        } else {
            v.setAdaptiveColor(null)
        }
    }

    /** Read real bar height, rounded corners and notch from the device instead of guessing. */
    private fun computeEnv(): Env {
        val fallback = statusBarHeightPx()
        return try {
            val wi = wm!!.currentWindowMetrics.windowInsets
            val bar = wi.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            val cutout = wi.displayCutout
            val top = cutout?.boundingRectTop
            val rl = wi.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
            val rr = wi.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius ?: 0
            val sl = cutout?.safeInsetLeft ?: 0
            val sr = cutout?.safeInsetRight ?: 0
            val hasTop = top != null && !top.isEmpty
            Env(
                barH = if (bar > 0) bar else fallback,
                startInset = max(rl * 0.55f, sl.toFloat()),
                endInset = max(rr * 0.55f, sr.toFloat()),
                cutL = if (hasTop) top!!.left.toFloat() else -1f,
                cutR = if (hasTop) top!!.right.toFloat() else -1f,
            )
        } catch (e: Exception) {
            Log.e("PixelBar", "env failed", e)
            Env(barH = fallback)
        }
    }

    private fun attach(v: StatusBarView) {
        env = computeEnv()
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            env.barH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "PixelBar"
        }
        try {
            wm?.addView(v, lp)
            view = v
        } catch (e: Exception) {
            Log.e("PixelBar", "addView failed", e)
        }
    }

    private fun detach() {
        handler.removeCallbacksAndMessages(null)
        pending = false
        view?.let { try { wm?.removeView(it) } catch (_: Exception) { } }
        view = null
    }

    // ---- adaptive background: sample the row of pixels just below the bar ----

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val c = Prefs.config.value
        if (!c.enabled || c.bgMode != 0) return
        schedule()
    }

    private fun schedule() {
        if (pending) return
        pending = true
        val wait = (lastShot + 800 - SystemClock.uptimeMillis()).coerceAtLeast(250)
        handler.postDelayed({ pending = false; sample() }, wait)
    }

    private fun sample() {
        if (view == null) return
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isInteractive) return
        lastShot = SystemClock.uptimeMillis()
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) { handleShot(screenshot) }
                override fun onFailure(errorCode: Int) { Log.w("PixelBar", "screenshot failed $errorCode") }
            })
        } catch (e: Exception) {
            Log.e("PixelBar", "takeScreenshot", e)
        }
    }

    private fun handleShot(r: ScreenshotResult) {
        var color: Int? = null
        try {
            val hb = r.hardwareBuffer
            val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
            if (hw != null) {
                val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                hw.recycle()
                if (soft != null) {
                    color = averageStrip(soft, env.barH + 2)
                    soft.recycle()
                }
            }
            hb.close()
        } catch (e: Exception) {
            Log.e("PixelBar", "handleShot", e)
        }
        if (color != null) {
            lastAdaptive = color
            if (Prefs.config.value.bgMode == 0) view?.setAdaptiveColor(color)
        }
    }

    private fun averageStrip(b: Bitmap, y0: Int): Int {
        val y = y0.coerceIn(0, b.height - 1)
        val n = 12
        var r = 0L; var g = 0L; var bl = 0L
        for (i in 0 until n) {
            val x = ((i + 0.5f) / n * b.width).toInt().coerceIn(0, b.width - 1)
            val px = b.getPixel(x, y)
            r += Color.red(px); g += Color.green(px); bl += Color.blue(px)
        }
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    // ---- lifecycle ----

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // rotation / dark-mode / display-size change: bar height and notch move, rebuild the window
        detach()
        val m = monitor ?: return
        render(Prefs.config.value, m.state.value, NotifStore.icons.value)
    }

    private fun cleanup() {
        detach()
        monitor?.stop(); monitor = null
        scope?.cancel(); scope = null
    }

    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { cleanup(); return super.onUnbind(intent) }
    override fun onDestroy() { cleanup(); super.onDestroy() }
}
