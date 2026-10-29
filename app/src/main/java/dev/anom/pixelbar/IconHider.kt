package dev.anom.pixelbar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Transparent mode: hides the stock system icons through the public-but-protected
 * `icon_blacklist` secure setting, so the overlay can be fully transparent like the real bar.
 * Needs a one-time:  adb shell pm grant dev.anom.pixelbar android.permission.WRITE_SECURE_SETTINGS
 * The user's original blacklist is saved and restored whenever this is switched off.
 */
class IconHider(context: Context) {
    private val ctx = context.applicationContext
    private val sp = ctx.getSharedPreferences("pixelbar", Context.MODE_PRIVATE)
    private var applied = false
    private var first = true

    companion object {
        private const val KEY = "icon_blacklist"
        val OURS = listOf(
            "wifi", "mobile", "phone_signal", "data_connection", "battery", "alarm_clock", "zen",
            "bluetooth", "volume", "mute", "nfc", "rotate", "location", "hotspot", "airplane",
            "headset", "cast", "vpn"
        )

        fun canWrite(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }

    /** Idempotent. The first call always syncs, so a restarted service never leaves icons hidden by mistake. */
    fun sync(hide: Boolean) {
        if (!first && hide == applied) return
        first = false
        if (!canWrite(ctx)) return
        val cr = ctx.contentResolver
        try {
            val cur = Settings.Secure.getString(cr, KEY) ?: ""
            if (hide) {
                if (!sp.contains("orig_bl")) {
                    val orig = cur.split(',').filter { it.isNotBlank() && it !in OURS }.joinToString(",")
                    sp.edit().putString("orig_bl", orig).apply()
                }
                val base = (sp.getString("orig_bl", "") ?: "").split(',').filter { it.isNotBlank() }
                val merged = (base + OURS.filter { it !in base }).joinToString(",")
                if (merged != cur) Settings.Secure.putString(cr, KEY, merged)
            } else if (sp.contains("orig_bl")) {
                val orig = sp.getString("orig_bl", "") ?: ""
                if (orig != cur) Settings.Secure.putString(cr, KEY, orig)
                sp.edit().remove("orig_bl").apply()
            }
            applied = hide
        } catch (e: Exception) {
            Log.e("PixelBar", "icon_blacklist", e)
        }
    }
}
