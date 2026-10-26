package dev.anom.pixelbar

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.View
import androidx.core.graphics.ColorUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pixel-style status bar drawn with Canvas.
 * Every size is derived from the real status bar height (1 unit = barHeight / 24),
 * so it scales with the device instead of using hard-coded dp.
 */
class StatusBarView(context: Context) : View(context) {
    private var config = Config()
    private var status = Status()
    private var notifs: List<Bitmap> = emptyList()
    private var env = Env()
    private var adaptive: Int? = null
    private var anim: ValueAnimator? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val dst = RectF()
    private val tb = Rect()

    private val faceClock: Typeface = loadFace(520)
    private val faceBold: Typeface = loadFace(720)

    private fun loadFace(weight: Int): Typeface {
        val t = try {
            Typeface.Builder(context.assets, "fonts/Figtree.ttf")
                .setFontVariationSettings("'wght' $weight")
                .build()
        } catch (_: Exception) {
            null
        }
        return t ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun update(c: Config, s: Status, n: List<Bitmap>, e: Env) {
        config = c; status = s; notifs = n; env = e
        invalidate()
    }

    /** Colour sampled from the app below the bar. null = fall back to system light/dark. */
    fun setAdaptiveColor(target: Int?) {
        anim?.cancel()
        if (target == null) { adaptive = null; invalidate(); return }
        val from = adaptive ?: target
        if (from == target) { adaptive = target; invalidate(); return }
        anim = ValueAnimator.ofArgb(from, target).apply {
            duration = 180
            addUpdateListener { adaptive = it.animatedValue as Int; invalidate() }
            start()
        }
    }

    private fun paint(
        color: Int,
        alpha: Int = 255,
        style: Paint.Style = Paint.Style.FILL,
        sw: Float = 0f,
    ): Paint = p.apply {
        reset()
        isAntiAlias = true
        this.color = color
        this.alpha = alpha
        this.style = style
        strokeWidth = sw
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private fun onColor(bg: Int): Int =
        if (ColorUtils.calculateLuminance(bg) > 0.5) 0xFF202124.toInt() else Color.WHITE

    override fun onDraw(c: Canvas) {
        val sysNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val sysBg = if (sysNight) Color.BLACK else Color.WHITE
        val bg = when (config.bgMode) {
            2 -> Color.WHITE
            3 -> Color.BLACK
            1 -> sysBg
            else -> adaptive ?: sysBg
        }
        val fg = onColor(bg)
        c.drawColor(bg)

        val h = height.toFloat()
        val unit = h / 24f
        val u = unit * config.iconScale
        val gap = 6.5f * u * config.spacing
        val minPad = config.sidePadding * unit
        val startPad = max(minPad, env.startInset)
        val endPad = max(minPad, env.endInset)
        val cy = h / 2f

        // right group, drawn right-to-left
        var x = width - endPad
        x = drawBattery(c, x, cy, u, fg, bg)
        x -= gap
        x = drawSignal(c, x, cy, u, fg)
        if (status.wifi >= 0) { x -= gap; x = drawWifi(c, x, cy, u, fg, bg) }
        if (status.dnd) { x -= gap; x = drawDnd(c, x, cy, u, fg) }
        if (status.alarm) { x -= gap; x = drawAlarm(c, x, cy, u, fg) }

        // left group: clock + notification icons, never into the notch or the right group
        var limit = x - gap
        if (env.cutL >= 0f) limit = min(limit, env.cutL - 3f * u)
        val clockEnd = drawClock(c, startPad, cy, u, fg)
        if (config.showNotifs && notifs.isNotEmpty()) {
            drawNotifs(c, clockEnd + 5f * u, cy, u, fg, limit)
        }
    }

    private fun drawClock(c: Canvas, x: Float, cy: Float, u: Float, fg: Int): Float {
        val is24 = when (config.clockFormat) {
            1 -> false
            2 -> true
            else -> DateFormat.is24HourFormat(context)
        }
        val txt = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault())
            .format(Date(status.now))
        paint(fg)
        p.typeface = faceClock
        p.textSize = 14.5f * u
        p.getTextBounds("0", 0, 1, tb)
        val base = cy - tb.exactCenterY()   // centre the digits, not the font box
        c.drawText(txt, x, base, p)
        return x + p.measureText(txt)
    }

    private fun drawNotifs(c: Canvas, startX: Float, cy: Float, u: Float, fg: Int, limit: Float) {
        val sz = 14f * u
        val g = 3.5f * u
        val step = sz + g
        val n = notifs.size
        val maxFit = floor((limit - startX + g) / step).toInt()
        if (maxFit <= 0) return
        val show = if (n <= maxFit) n else max(maxFit - 1, 0)
        bp.colorFilter = PorterDuffColorFilter(fg, PorterDuff.Mode.SRC_IN)
        var x = startX
        for (i in 0 until show) {
            dst.set(x, cy - sz / 2, x + sz, cy + sz / 2)
            c.drawBitmap(notifs[i], null, dst, bp)
            x += step
        }
        if (n > show) c.drawCircle(x + 2.5f * u, cy, 2f * u, paint(fg))
    }

    private fun drawBattery(c: Canvas, right: Float, cy: Float, u: Float, fg: Int, bg: Int): Float {
        val showPct = config.showPercent
        val bh = (if (showPct) 15.5f else 12f) * u
        val capW = 1.8f * u
        val capH = 5f * u
        val capGap = 0.9f * u
        val txtSize = 11.5f * u
        var bw = 22f * u
        if (showPct) {
            paint(fg)
            p.typeface = faceBold
            p.textSize = txtSize
            bw = p.measureText("100") + 6f * u   // always sized for 3 digits so it never jumps
        }
        bw = max(bw, bh * 1.5f)

        val bodyRight = right - capW - capGap
        val left = bodyRight - bw
        val top = cy - bh / 2
        val bottom = cy + bh / 2
        val r = bh * 0.42f

        rect.set(bodyRight + capGap, cy - capH / 2, right, cy + capH / 2)
        c.drawRoundRect(rect, capW / 2, capW / 2, paint(fg, 77))

        rect.set(left, top, bodyRight, bottom)
        c.drawRoundRect(rect, r, r, paint(fg, 77))

        val pct = status.battery.coerceIn(0, 100)
        val fillColor = when {
            status.saver -> 0xFFF9AB00.toInt()
            pct <= 15 && !status.charging -> 0xFFEA4335.toInt()
            else -> fg
        }
        val fillRight = left + bw * pct / 100f

        c.save()
        path.reset()
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        c.clipPath(path)
        c.drawRect(left, top, fillRight, bottom, paint(fillColor))
        if (showPct) {
            paint(fg)
            p.typeface = faceBold
            p.textSize = txtSize
            p.textAlign = Paint.Align.CENTER
            p.getTextBounds("0", 0, 1, tb)
            val by = cy - tb.exactCenterY()
            val tx = left + bw / 2
            val s = pct.toString()
            c.save(); c.clipRect(left, top, fillRight, bottom)
            p.color = onColor(fillColor); c.drawText(s, tx, by, p); c.restore()
            c.save(); c.clipRect(fillRight, top, bodyRight, bottom)
            p.color = fg; c.drawText(s, tx, by, p); c.restore()
        }
        c.restore()

        var l = left
        if (status.charging) {
            if (showPct) {
                val bs = 11f * u
                drawBolt(c, left - 1.5f * u - bs * 0.3f, cy, bs, fg, bg, false)
                l = left - 1.5f * u - bs * 0.6f
            } else {
                drawBolt(c, left + bw / 2, cy, bh * 0.9f, fg, bg, true)
            }
        }
        return l
    }

    private fun drawBolt(c: Canvas, cx: Float, cy: Float, s: Float, fg: Int, bg: Int, halo: Boolean) {
        path.reset()
        path.moveTo(cx + 0.15f * s, cy - 0.5f * s)
        path.lineTo(cx - 0.30f * s, cy + 0.10f * s)
        path.lineTo(cx - 0.02f * s, cy + 0.10f * s)
        path.lineTo(cx - 0.15f * s, cy + 0.5f * s)
        path.lineTo(cx + 0.30f * s, cy - 0.10f * s)
        path.lineTo(cx + 0.02f * s, cy - 0.10f * s)
        path.close()
        if (halo) c.drawPath(path, paint(bg, 255, Paint.Style.FILL_AND_STROKE, 0.9f * s * 0.2f))
        c.drawPath(path, paint(fg))
    }

    private fun drawSignal(c: Canvas, right: Float, cy: Float, u: Float, fg: Int): Float {
        val w = 13.5f * u
        val h = 12.5f * u
        val sw = 1.7f * u
        val left = right - w
        val top = cy - h / 2
        val bottom = cy + h / 2
        path.reset()
        path.moveTo(left + sw / 2, bottom - sw / 2)
        path.lineTo(right - sw / 2, bottom - sw / 2)
        path.lineTo(right - sw / 2, top + sw / 2)
        path.close()
        c.drawPath(path, paint(fg, 77, Paint.Style.FILL_AND_STROKE, sw))
        val lvl = status.cell
        if (lvl > 0) {
            c.save()
            c.clipRect(left, top - 2f, left + w * lvl / 4f, bottom + 2f)
            c.drawPath(path, paint(fg, 255, Paint.Style.FILL_AND_STROKE, sw))
            c.restore()
        }
        return left
    }

    private fun drawWifi(c: Canvas, right: Float, cy: Float, u: Float, fg: Int, bg: Int): Float {
        val rad = 11.5f * u
        val halfW = rad * 0.7071f
        val left = right - 2 * halfW
        val cx = left + halfW
        val cyc = cy + rad / 2
        rect.set(cx - rad, cyc - rad, cx + rad, cyc + rad)
        path.reset()
        path.moveTo(cx, cyc)
        path.arcTo(rect, 225f, 90f)
        path.close()
        c.drawPath(path, paint(fg, 77))

        val b = floatArrayOf(0.30f, 0.52f, 0.76f, 1f)
        val lvl = status.wifi
        if (lvl > 0) {
            val clip = Path().apply { addCircle(cx, cyc, rad * b[lvl - 1], Path.Direction.CW) }
            c.save()
            c.clipPath(clip)
            c.drawPath(path, paint(fg))
            c.restore()
        }
        val gw = 1.3f * u
        for (i in 0..2) {
            val rr = rad * b[i]
            rect.set(cx - rr, cyc - rr, cx + rr, cyc + rr)
            val gp = paint(bg, 255, Paint.Style.STROKE, gw)
            gp.strokeCap = Paint.Cap.BUTT
            c.drawArc(rect, 225f, 90f, false, gp)
        }
        return left
    }

    private fun drawDnd(c: Canvas, right: Float, cy: Float, u: Float, fg: Int): Float {
        val r = 5.5f * u
        val sw = 1.6f * u
        val cx = right - r
        c.drawCircle(cx, cy, r - sw / 2, paint(fg, 255, Paint.Style.STROKE, sw))
        c.drawLine(cx - r * 0.5f, cy, cx + r * 0.5f, cy, paint(fg, 255, Paint.Style.STROKE, sw))
        return right - 2 * r
    }

    private fun drawAlarm(c: Canvas, right: Float, cy: Float, u: Float, fg: Int): Float {
        val r = 5.5f * u
        val sw = 1.6f * u
        val cx = right - r
        c.drawCircle(cx, cy, r - sw / 2, paint(fg, 255, Paint.Style.STROKE, sw))
        path.reset()
        path.moveTo(cx, cy - r * 0.5f)
        path.lineTo(cx, cy)
        path.lineTo(cx + r * 0.4f, cy + r * 0.3f)
        c.drawPath(path, paint(fg, 255, Paint.Style.STROKE, sw))
        return right - 2 * r
    }
}
