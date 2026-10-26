package dev.anom.pixelbar

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow

data class Config(
    val enabled: Boolean = false,
    val bgMode: Int = 0,         // 0 blend (looks transparent), 1 solid match, 2 system, 3 light, 4 dark
    val clockFormat: Int = 0,    // 0 system, 1 12h, 2 24h
    val showPercent: Boolean = true,
    val notifMode: Int = 0,      // 0 all, 1 priority only, 2 none
    val hideFullscreen: Boolean = false,
    val iconScale: Float = 1f,   // multiplier on top of the automatic size
    val spacing: Float = 1f,     // multiplier on the automatic gap
    val sidePadding: Float = 12f // in "bar units" (1 unit = barHeight/24)
)

data class Status(
    val now: Long = System.currentTimeMillis(),
    val battery: Int = 100,
    val charging: Boolean = false,
    val saver: Boolean = false,
    val wifi: Int = -1,   // -1 not connected, 0..4
    val cell: Int = -1,   // -1 no service / unknown, 0..4
    val alarm: Boolean = false,
    val dnd: Boolean = false,
    val locked: Boolean = false,
    val carrier: String = "",
)

/** Measured from the real device: bar height, rounded-corner / cutout insets, notch x-range. */
data class Env(
    val barH: Int = 0,
    val startInset: Float = 0f,
    val endInset: Float = 0f,
    val cutL: Float = -1f,   // left x of the top cutout, -1 = none
    val cutR: Float = -1f,
)

class NotifIcon(val bmp: Bitmap, val priority: Boolean)

object NotifStore {
    val icons = MutableStateFlow<List<NotifIcon>>(emptyList())
    val connected = MutableStateFlow(false)
}

object Prefs {
    private var sp: SharedPreferences? = null
    val config = MutableStateFlow(Config())

    fun init(ctx: Context) {
        if (sp != null) return
        val s = ctx.applicationContext.getSharedPreferences("pixelbar", Context.MODE_PRIVATE)
        sp = s
        config.value = Config(
            enabled = s.getBoolean("enabled", false),
            bgMode = s.getInt("bg2", 0),
            clockFormat = s.getInt("clock", 0),
            showPercent = s.getBoolean("pct", true),
            notifMode = s.getInt("notif_mode", 0),
            hideFullscreen = s.getBoolean("hide_fs", false),
            iconScale = s.getFloat("scale", 1f),
            spacing = s.getFloat("spacing_m", 1f),
            sidePadding = s.getFloat("pad_u", 12f),
        )
    }

    fun update(f: (Config) -> Config) {
        val n = f(config.value)
        config.value = n
        sp?.edit()
            ?.putBoolean("enabled", n.enabled)
            ?.putInt("bg2", n.bgMode)
            ?.putInt("clock", n.clockFormat)
            ?.putBoolean("pct", n.showPercent)
            ?.putInt("notif_mode", n.notifMode)
            ?.putBoolean("hide_fs", n.hideFullscreen)
            ?.putFloat("scale", n.iconScale)
            ?.putFloat("spacing_m", n.spacing)
            ?.putFloat("pad_u", n.sidePadding)
            ?.apply()
    }
}

fun Context.statusBarHeightPx(): Int {
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id)
    else (24 * resources.displayMetrics.density).toInt()
}
