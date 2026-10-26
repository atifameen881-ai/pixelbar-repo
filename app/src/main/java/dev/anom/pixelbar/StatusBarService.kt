package dev.anom.pixelbar

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class StatusBarService : AccessibilityService() {
    private var wm: WindowManager? = null
    private var view: StatusBarView? = null
    private var monitor: StatusMonitor? = null
    private var scope: CoroutineScope? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        wm = getSystemService(WindowManager::class.java)
        val m = StatusMonitor(this).also { it.start() }
        monitor = m
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sc
        sc.launch {
            combine(Prefs.config, m.state) { c, s -> c to s }.collect { (c, s) -> render(c, s) }
        }
    }

    private fun render(c: Config, s: Status) {
        if (!c.enabled) { detach(); return }
        val v = view ?: StatusBarView(this).also { attach(it) }
        v.update(c, s)
    }

    private fun attach(v: StatusBarView) {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            statusBarHeightPx(),
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
        view?.let { try { wm?.removeView(it) } catch (_: Exception) { } }
        view = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // rotation / dark-mode switch: status bar height can change, rebuild the window
        detach()
        val m = monitor ?: return
        render(Prefs.config.value, m.state.value)
    }

    private fun cleanup() {
        detach()
        monitor?.stop(); monitor = null
        scope?.cancel(); scope = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { cleanup(); return super.onUnbind(intent) }
    override fun onDestroy() { cleanup(); super.onDestroy() }
}
