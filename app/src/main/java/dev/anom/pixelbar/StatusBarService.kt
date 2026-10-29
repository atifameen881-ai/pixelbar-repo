package dev.anom.pixelbar

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
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
import kotlin.math.min

class StatusBarService : AccessibilityService() {
    private var wm: WindowManager? = null
    private var dm: DisplayManager? = null
    private var view: StatusBarView? = null
    private var monitor: StatusMonitor? = null
    private var hider: IconHider? = null
    private var scope: CoroutineScope? = null
    private var env = Env()

    private val handler = Handler(Looper.getMainLooper())
    private var lastShot = 0L

    // last sampled backdrop, kept so a rebuilt window (rotation) is never blank
    private var lastBlur: Bitmap? = null
    private var lastAvg: Int? = null

    private var screenOn = true
    private var sysBarVisible = true
    private var lastRotation = -1

    private val sampleTask = Runnable { sample() }
    private val settleTask = Runnable { sample() }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) onDisplayStateChanged()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        wm = getSystemService(WindowManager::class.java)
        dm = getSystemService(DisplayManager::class.java)
        dm?.registerDisplayListener(displayListener, handler)
        dm?.getDisplay(Display.DEFAULT_DISPLAY)?.let {
            screenOn = it.state == Display.STATE_ON
            lastRotation = it.rotation
        }
        hider = IconHider(this)
        val m = StatusMonitor(this).also { it.start() }
        monitor = m
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sc
        sc.launch {
            combine(Prefs.config, m.state, NotifStore.icons) { c, s, n -> Triple(c, s, n) }
                .collect { (c, s, n) -> render(c, s, n) }
        }
    }

    private fun render(c0: Config, s: Status, n: List<NotifIcon>) {
        val canHide = IconHider.canWrite(this)
        hider?.sync(c0.enabled && c0.bgMode == 1 && canHide)
        // transparent mode without the ADB grant would double-draw icons, so fall back to Auto
        val c = if (c0.bgMode == 1 && !canHide) c0.copy(bgMode = 0) else c0
        if (!c.enabled) { detach(); return }
        var v = view
        if (v == null) {
            v = StatusBarView(this)
            attach(v)
            if (view == null) return
            requestSample()
        }
        v.update(c, s, n, env)
        if (c.bgMode <= 1) {
            if (lastAvg != null) v.setBackdrop(if (c.bgMode == 0) lastBlur else null, lastAvg)
        } else {
            v.setBackdrop(null, null)
        }
        applyVisibility()
    }

    /** Hidden on AOD / screen off, and (optionally) in fullscreen apps where the real bar is hidden. */
    private fun applyVisibility() {
        val v = view ?: return
        val hideFs = Prefs.config.value.hideFullscreen
        val show = screenOn && (!hideFs || sysBarVisible)
        v.visibility = if (show) View.VISIBLE else View.INVISIBLE
    }

    private fun onDisplayStateChanged() {
        val d = dm?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        screenOn = d.state == Display.STATE_ON
        if (d.rotation != lastRotation) {
            lastRotation = d.rotation
            rebuild()
        }
        applyVisibility()
        if (screenOn) { monitor?.refresh(); requestSample() }
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
        v.onStatusBarVisible = { vis ->
            if (vis != sysBarVisible) { sysBarVisible = vis; applyVisibility() }
        }
        try {
            wm?.addView(v, lp)
            view = v
        } catch (e: Exception) {
            Log.e("PixelBar", "addView failed", e)
        }
    }

    private fun detach() {
        handler.removeCallbacks(sampleTask)
        handler.removeCallbacks(settleTask)
        view?.let { try { wm?.removeView(it) } catch (_: Exception) { } }
        view = null
    }

    private fun rebuild() {
        detach()
        val m = monitor ?: return
        render(Prefs.config.value, m.state.value, NotifStore.icons.value)
    }

    // ---- backdrop: screenshot, mirror the strip just below the bar, blur it ----

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val c = Prefs.config.value
        if (!c.enabled) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) monitor?.refresh()  // lockscreen / unlock
        if (c.bgMode <= 1) requestSample()
    }

    /** Leading sample as soon as the 350 ms screenshot limit allows, plus one trailing sample after things settle. */
    private fun requestSample() {
        val now = SystemClock.uptimeMillis()
        handler.removeCallbacks(sampleTask)
        val at = max(now + 40, lastShot + 350)
        handler.postDelayed(sampleTask, at - now)
        handler.removeCallbacks(settleTask)
        handler.postDelayed(settleTask, 450)
    }

    private fun sample() {
        if (view == null || !screenOn) return
        val now = SystemClock.uptimeMillis()
        if (now - lastShot < 340) { handler.postDelayed(sampleTask, 340 - (now - lastShot)); return }
        lastShot = now
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
        var blur: Bitmap? = null
        var avg: Int? = null
        try {
            val hb = r.hardwareBuffer
            val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
            val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
            hw?.recycle()
            hb.close()
            if (soft != null) {
                val h = env.barH.coerceAtLeast(1)
                val y0 = min(h, soft.height - 1)
                val sh = min(h, soft.height - y0).coerceAtLeast(1)
                val strip = Bitmap.createBitmap(soft, 0, y0, soft.width, sh)
                val flip = Matrix().apply { preScale(1f, -1f) }
                val mirrored = Bitmap.createBitmap(strip, 0, 0, strip.width, strip.height, flip, false)
                val small = Bitmap.createScaledBitmap(
                    mirrored, max(soft.width / 12, 8), max(sh / 4, 2), true
                )
                avg = averageOf(small)
                blur = small
            }
        } catch (e: Exception) {
            Log.e("PixelBar", "handleShot", e)
        }
        val a = avg ?: return
        lastAvg = a
        lastBlur = blur
        val c = Prefs.config.value
        if (c.bgMode <= 1) view?.setBackdrop(if (c.bgMode == 0) blur else null, a)
    }

    private fun averageOf(b: Bitmap): Int {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var r = 0L; var g = 0L; var bl = 0L
        for (p in px) { r += Color.red(p); g += Color.green(p); bl += Color.blue(p) }
        val n = px.size.coerceAtLeast(1)
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    // ---- lifecycle ----

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuild()   // rotation / dark mode / display size: bar height and notch move
    }

    private fun cleanup() {
        detach()
        hider?.sync(false); hider = null
        try { dm?.unregisterDisplayListener(displayListener) } catch (_: Exception) { }
        monitor?.stop(); monitor = null
        scope?.cancel(); scope = null
    }

    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { cleanup(); return super.onUnbind(intent) }
    override fun onDestroy() { cleanup(); super.onDestroy() }
}
