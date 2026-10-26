package dev.anom.pixelbar

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pixel-style status bar, drawn from scratch with Canvas. No assets, no emoji. */
class StatusBarView(context: Context) : View(context) {
    private var config = Config()
    private var status = Status()
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    fun update(c: Config, s: Status) {
        config = c
        status = s
        invalidate()
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

    override fun onDraw(c: Canvas) {
        val night = when (config.theme) {
            1 -> false
            2 -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        }
        val bg = if (night) Color.BLACK else Color.WHITE
        val fg = if (night) Color.WHITE else 0xFF1F1F1F.toInt()
        c.drawColor(bg)

        val u = d * config.iconScale
        val gap = config.spacing * d
        val pad = config.sidePadding * d
        val cy = height / 2f

        drawClock(c, pad, cy, u, fg)

        var x = width - pad
        x = drawBattery(c, x, cy, u, fg, bg)
        x -= gap
        x = drawSignal(c, x, cy, u, fg)
        if (status.wifi >= 0) { x -= gap; x = drawWifi(c, x, cy, u, fg, bg) }
        if (status.dnd) { x -= gap; x = drawDnd(c, x, cy, u, fg) }
        if (status.alarm) { x -= gap; x = drawAlarm(c, x, cy, u, fg) }
    }

    private fun drawClock(c: Canvas, x: Float, cy: Float, u: Float, fg: Int) {
        val is24 = when (config.clockFormat) {
            1 -> false
            2 -> true
            else -> DateFormat.is24HourFormat(context)
        }
        val txt = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault())
            .format(Date(status.now))
        paint(fg)
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        p.textSize = 15f * u
        val fm = p.fontMetrics
        c.drawText(txt, x, cy - (fm.ascent + fm.descent) / 2f, p)
    }

    private fun drawBattery(c: Canvas, right: Float, cy: Float, u: Float, fg: Int, bg: Int): Float {
        val showPct = config.showPercent
        val bw = (if (showPct) 27f else 22f) * u
        val bh = (if (showPct) 14f else 11.5f) * u
        val capW = 1.6f * u
        val capH = 4.5f * u
        val capGap = 0.8f * u
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
            paint(bg)
            p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            p.textSize = 9.5f * u
            p.textAlign = Paint.Align.CENTER
            val fm = p.fontMetrics
            val by = cy - (fm.ascent + fm.descent) / 2f
            val tx = left + bw / 2
            val s = pct.toString()
            c.save(); c.clipRect(left, top, fillRight, bottom)
            p.color = bg; c.drawText(s, tx, by, p); c.restore()
            c.save(); c.clipRect(fillRight, top, bodyRight, bottom)
            p.color = fg; c.drawText(s, tx, by, p); c.restore()
        }
        c.restore()

        var l = left
        if (status.charging) {
            if (showPct) {
                val bs = 10f * u
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
        if (halo) c.drawPath(path, paint(bg, 255, Paint.Style.FILL_AND_STROKE, 1.4f * d))
        c.drawPath(path, paint(fg))
    }

    private fun drawSignal(c: Canvas, right: Float, cy: Float, u: Float, fg: Int): Float {
        val w = 13f * u
        val h = 12f * u
        val sw = 1.6f * u
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
        val rad = 11f * u
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
        val r = 5f * u
        val sw = 1.5f * u
        val cx = right - r
        c.drawCircle(cx, cy, r - sw / 2, paint(fg, 255, Paint.Style.STROKE, sw))
        c.drawLine(cx - r * 0.5f, cy, cx + r * 0.5f, cy, paint(fg, 255, Paint.Style.STROKE, sw))
        return right - 2 * r
    }

    private fun drawAlarm(c: Canvas, right: Float, cy: Float, u: Float, fg: Int): Float {
        val r = 5f * u
        val sw = 1.5f * u
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
