package app.lawnchair.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// [trebuchet-port] Live clock / calendar icons for the default (system) icon set.
//
// Trebuchet on LineageOS renders the clock app icon as an analog face showing the current time and
// the calendar app icon with today's date, using the framework's hidden DynamicIcons factories.
// Those APIs are blocklisted for third-party apps, so we draw equivalent faces ourselves as adaptive
// icons: the app's own background layer is kept, and the foreground is replaced by the live face.
// The icons are bitmaps in Lawnchair's icon cache, so they are regenerated (and redrawn with the
// then-current time/date) by the TIME_TICK / DATE_CHANGED refresh wired in LawnchairIconProvider.

/** Wraps [base]'s background layer with a live analog clock face foreground. */
class LiveClockIconDrawable(base: Drawable?) :
        CustomAdaptiveIconDrawable(liveBackgroundOf(base, "#5F6368"), ClockFaceDrawable()) {

    private class ClockFaceDrawable : Drawable() {
        private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val dial = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1F1F1F")
            style = Paint.Style.STROKE
        }
        private val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1F1F1F")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val second = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D93025")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1F1F1F") }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            // AdaptiveIconDrawable inflates child bounds by 25% on each side, so the visible
            // viewport half-size is a quarter of the child bounds width (36 of 144 in icon space).
            val unit = b.width() / 4f
            val r = unit * 0.86f

            // Dial
            canvas.drawCircle(cx, cy, r, white)
            dial.strokeWidth = unit * 0.06f
            canvas.drawCircle(cx, cy, r, dial)

            // Hour ticks
            dial.strokeWidth = unit * 0.07f
            val now = Calendar.getInstance()
            for (i in 0 until 12) {
                val a = Math.toRadians(i * 30.0 - 90.0)
                canvas.drawLine(
                        cx + (r * 0.82f) * cos(a).toFloat(), cy + (r * 0.82f) * sin(a).toFloat(),
                        cx + (r * 0.96f) * cos(a).toFloat(), cy + (r * 0.96f) * sin(a).toFloat(),
                        dial,
                )
            }

            val hour = now.get(Calendar.HOUR) + now.get(Calendar.MINUTE) / 60f
            val minute = now.get(Calendar.MINUTE) + now.get(Calendar.SECOND) / 60f

            // Hour hand
            hand.strokeWidth = unit * 0.16f
            val ha = Math.toRadians(hour * 30.0 - 90.0)
            canvas.drawLine(cx, cy, cx + (r * 0.5f) * cos(ha).toFloat(),
                    cy + (r * 0.5f) * sin(ha).toFloat(), hand)

            // Minute hand
            hand.strokeWidth = unit * 0.11f
            val ma = Math.toRadians(minute * 6.0 - 90.0)
            canvas.drawLine(cx, cy, cx + (r * 0.78f) * cos(ma).toFloat(),
                    cy + (r * 0.78f) * sin(ma).toFloat(), hand)

            // Center pivot
            canvas.drawCircle(cx, cy, unit * 0.09f, dot)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

/** Wraps [base]'s background layer with today's weekday + date, calendar-icon style. */
class LiveCalendarIconDrawable(base: Drawable?) :
        CustomAdaptiveIconDrawable(liveBackgroundOf(base, "#4285F4"), DateDrawable(base)) {

    private class DateDrawable(base: Drawable?) : Drawable() {
        private val weekday = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val day = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val format = SimpleDateFormat("EEE", Locale.getDefault())
        private val textColor = contrastColor(base)

        init {
            weekday.color = textColor
            day.color = textColor
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val unit = min(b.width(), b.height()) / 4f
            val now = Calendar.getInstance()
            weekday.textSize = unit * 0.62f
            day.textSize = unit * 1.55f
            val label = format.format(now.time).uppercase(Locale.getDefault())
            canvas.drawText(label, cx, cy - unit * 0.45f, weekday)
            canvas.drawText(now.get(Calendar.DAY_OF_MONTH).toString(), cx, cy + unit * 1.0f, day)
        }

        private fun contrastColor(base: Drawable?): Int {
            val c = averageColor(base) ?: return Color.parseColor("#1F1F1F")
            val luminance = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
            return if (luminance < 140) Color.WHITE else Color.parseColor("#1F1F1F")
        }

        private fun averageColor(base: Drawable?): Int? {
            base ?: return null
            return try {
                val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                try {
                    val c = Canvas(bmp)
                    base.mutate()
                    base.setBounds(0, 0, 16, 16)
                    base.draw(c)
                    var r = 0
                    var g = 0
                    var bl = 0
                    for (x in 0 until 16) {
                        for (y in 0 until 16) {
                            val p = bmp.getPixel(x, y)
                            r += Color.red(p)
                            g += Color.green(p)
                            bl += Color.blue(p)
                        }
                    }
                    val n = 16 * 16
                    Color.rgb(r / n, g / n, bl / n)
                } finally {
                    bmp.recycle()
                }
            } catch (e: Exception) {
                null
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

/**
 * Reuses the app's own adaptive-icon background layer for the live faces, falling back to the
 * base drawable (or a neutral color) when it is not an adaptive icon. Kept top-level so it can be
 * referenced from the superclass constructor delegation calls above.
 */
private fun liveBackgroundOf(base: Drawable?, fallbackColor: String): Drawable {
    val bg = (base as? AdaptiveIconDrawable)?.mutate()?.let {
        try {
            it.getDrawable(0)
        } catch (e: Exception) {
            null
        }
    }
    return bg ?: base ?: ColorDrawable(Color.parseColor(fallbackColor))
}
