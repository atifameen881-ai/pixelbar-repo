package dev.anom.pixelbar

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

data class Config(
    val enabled: Boolean = false,
    val theme: Int = 0,          // 0 auto, 1 light, 2 dark
    val clockFormat: Int = 0,    // 0 system, 1 12h, 2 24h
    val showPercent: Boolean = true,
    val iconScale: Float = 1f,
    val spacing: Float = 8f,
    val sidePadding: Float = 16f,
)

data class Status(
    val now: Long = System.currentTimeMillis(),
    val battery: Int = 100,
    val charging: Boolean = false,
    val saver: Boolean = false,
    val wifi: Int = -1,   // -1 = not connected, 0..4 = level
    val cell: Int = -1,   // -1 = no service / unknown, 0..4 = level
    val alarm: Boolean = false,
    val dnd: Boolean = false,
)

object Prefs {
    private var sp: SharedPreferences? = null
    val config = MutableStateFlow(Config())

    fun init(ctx: Context) {
        if (sp != null) return
        val s = ctx.applicationContext.getSharedPreferences("pixelbar", Context.MODE_PRIVATE)
        sp = s
        config.value = Config(
            enabled = s.getBoolean("enabled", false),
            theme = s.getInt("theme", 0),
            clockFormat = s.getInt("clock", 0),
            showPercent = s.getBoolean("pct", true),
            iconScale = s.getFloat("scale", 1f),
            spacing = s.getFloat("spacing", 8f),
            sidePadding = s.getFloat("pad", 16f),
        )
    }

    fun update(f: (Config) -> Config) {
        val n = f(config.value)
        config.value = n
        sp?.edit()
            ?.putBoolean("enabled", n.enabled)
            ?.putInt("theme", n.theme)
            ?.putInt("clock", n.clockFormat)
            ?.putBoolean("pct", n.showPercent)
            ?.putFloat("scale", n.iconScale)
            ?.putFloat("spacing", n.spacing)
            ?.putFloat("pad", n.sidePadding)
            ?.apply()
    }
}

fun Context.statusBarHeightPx(): Int {
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id)
    else (24 * resources.displayMetrics.density).toInt()
}
