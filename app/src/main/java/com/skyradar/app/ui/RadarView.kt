package com.skyradar.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.skyradar.app.R
import com.skyradar.app.geometry.SkyGeometry
import com.skyradar.app.tracking.TrackSnapshot
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Heading-up sky-dome radar: the zenith sits at the centre, the horizon at
 * the rim, and whatever direction the camera faces points to the top of the
 * dial. Shows every track with a fading tail, the camera's field-of-view
 * wedge, rotating sweep and the cardinal directions.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var tracks: List<TrackSnapshot> = emptyList()
    private var deviceAzDeg = 0f
    private var deviceElDeg = 45f
    private var fovDeg = 55f

    private val green = context.getColor(R.color.radar_green)
    private val greenDim = context.getColor(R.color.radar_green_dim)
    private val amber = context.getColor(R.color.radar_amber)
    private val north = context.getColor(R.color.radar_red)
    private val cardinals = resources.getStringArray(R.array.compass_cardinals)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.radar_bg)
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = green
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = greenDim
    }
    private val wedgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = greenDim
        alpha = 50
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = green
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = green
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = sp(9f)
    }

    private val arcRect = RectF()

    /** Called once per analyzed frame. */
    fun submit(tracks: List<TrackSnapshot>, deviceAzDeg: Float, deviceElDeg: Float, fovDeg: Float) {
        this.tracks = tracks
        this.deviceAzDeg = deviceAzDeg
        this.deviceElDeg = deviceElDeg
        this.fovDeg = fovDeg.coerceIn(20f, 120f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - dp(6f)
        if (radius <= 0f) return

        canvas.drawCircle(cx, cy, radius, bgPaint)
        canvas.drawCircle(cx, cy, radius, rimPaint)
        canvas.drawCircle(cx, cy, radius / 3f, ringPaint)
        canvas.drawCircle(cx, cy, radius * 2f / 3f, ringPaint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, ringPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, ringPaint)

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Camera field-of-view wedge always opens to the top (heading-up).
        canvas.drawArc(arcRect, -90f - fovDeg / 2f, fovDeg, true, wedgePaint)

        // Rotating sweep with a fading tail.
        val sweep = (SystemClock.uptimeMillis() % SWEEP_PERIOD_MS) /
            SWEEP_PERIOD_MS.toFloat() * 360f - 90f
        for (i in 0 until 10) {
            sweepPaint.alpha = 70 - i * 7
            canvas.drawArc(arcRect, sweep - i * 3f, 3f, true, sweepPaint)
        }

        // Cardinal letters rotate so the dial stays heading-up.
        for (k in 0 until 4) {
            val rel = SkyGeometry.wrapDeg(k * 90f - deviceAzDeg)
            val rad = Math.toRadians((rel - 90f).toDouble())
            val tx = cx + (radius - dp(9f)) * cos(rad).toFloat()
            val ty = cy + (radius - dp(9f)) * sin(rad).toFloat() + dp(3.5f)
            textPaint.color = if (k == 0) north else greenDim
            canvas.drawText(cardinals[k], tx - dp(3.5f), ty, textPaint)
        }

        // Where the camera centre currently points on the dome.
        run {
            val r = ((90f - deviceElDeg) / 90f).coerceIn(0f, 1f) * radius
            canvas.drawCircle(cx, cy - r, dp(3.5f), pointerPaint)
        }

        for (t in tracks) drawTrack(canvas, t, cx, cy, radius)

        if (isAttachedToWindow) postInvalidateOnAnimation()
    }

    private fun drawTrack(canvas: Canvas, t: TrackSnapshot, cx: Float, cy: Float, radius: Float) {
        dotPaint.color = if (t.confirmed) green else amber

        // Short fading tail from the track history.
        val hist = t.history
        var i = hist.size - 4
        var alpha = 120
        while (i >= 0 && alpha > 20) {
            val hx = domeX(hist[i][0], hist[i][1], cx, radius)
            val hy = domeY(hist[i][0], hist[i][1], cy, radius)
            dotPaint.alpha = alpha
            canvas.drawCircle(hx, hy, dp(1.2f), dotPaint)
            i -= 4
            alpha -= 18
        }
        dotPaint.alpha = 255

        val x = domeX(t.azDeg, t.elDeg, cx, radius)
        val y = domeY(t.azDeg, t.elDeg, cy, radius)
        canvas.drawCircle(x, y, if (t.confirmed) dp(3f) else dp(2f), dotPaint)
        if (t.confirmed) {
            textPaint.color = green
            canvas.drawText(String.format(Locale.US, "%02d", t.id), x + dp(4f), y - dp(4f), textPaint)
        }
    }

    private fun domeX(azDeg: Float, elDeg: Float, cx: Float, radius: Float): Float {
        val rel = SkyGeometry.wrapDeg(azDeg - deviceAzDeg)
        val r = ((90f - elDeg) / 90f).coerceIn(0f, 1f) * radius
        return cx + r * cos(Math.toRadians((rel - 90f).toDouble())).toFloat()
    }

    private fun domeY(azDeg: Float, elDeg: Float, cy: Float, radius: Float): Float {
        val rel = SkyGeometry.wrapDeg(azDeg - deviceAzDeg)
        val r = ((90f - elDeg) / 90f).coerceIn(0f, 1f) * radius
        return cy + r * sin(Math.toRadians((rel - 90f).toDouble())).toFloat()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private companion object {
        const val SWEEP_PERIOD_MS = 3000L
    }
}
