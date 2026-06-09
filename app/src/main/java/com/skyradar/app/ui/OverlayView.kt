package com.skyradar.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.skyradar.app.R
import com.skyradar.app.geometry.SkyGeometry
import com.skyradar.app.geometry.ViewGeometry
import com.skyradar.app.tracking.TrackSnapshot
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2

/**
 * AR overlay drawn on top of the camera preview: target brackets with id and
 * angular speed, velocity vectors, dashed predicted paths, fading trails,
 * plus the projected horizon line with compass marks and a center crosshair.
 *
 * The camera preview underneath uses FIT_CENTER with the same aspect ratio as
 * the analysis stream, so projecting into [contentRect] keeps markers aligned
 * with what the camera actually sees.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var tracks: List<TrackSnapshot> = emptyList()
    private var geometry: ViewGeometry? = null

    private val green = context.getColor(R.color.radar_green)
    private val greenDim = context.getColor(R.color.radar_green_dim)
    private val amber = context.getColor(R.color.radar_amber)
    private val cardinals = resources.getStringArray(R.array.compass_cardinals)

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(6f)), 0f)
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = greenDim
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = sp(11f)
        setShadowLayer(dp(2f), 0f, 0f, 0xCC000000.toInt())
    }

    private val contentRect = RectF()
    private val path = Path()
    private val point = PointF()
    private val point2 = PointF()

    /** Called once per analyzed frame with fresh tracks and camera geometry. */
    fun submit(tracks: List<TrackSnapshot>, geometry: ViewGeometry) {
        this.tracks = tracks
        this.geometry = geometry
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val geom = geometry ?: return
        computeContentRect(geom.imageAspect)
        drawHorizon(canvas, geom)
        drawCrosshair(canvas)
        for (t in tracks) drawTrack(canvas, t, geom)
    }

    /** Letterboxed area matching the FIT_CENTER camera preview. */
    private fun computeContentRect(aspect: Float) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        var cw = vw
        var ch = if (aspect > 0f) vw / aspect else vh
        if (ch > vh) {
            ch = vh
            cw = vh * aspect
        }
        val left = (vw - cw) / 2f
        val top = (vh - ch) / 2f
        contentRect.set(left, top, left + cw, top + ch)
    }

    /** Projects a sky direction into view pixels; false when not drawable. */
    private fun projectTo(
        azDeg: Float,
        elDeg: Float,
        geom: ViewGeometry,
        out: PointF,
        slack: Float = 0.25f
    ): Boolean {
        val p = SkyGeometry.worldToPixel(azDeg, elDeg, geom) ?: return false
        if (p[0] < -slack || p[0] > 1f + slack || p[1] < -slack || p[1] > 1f + slack) return false
        out.set(
            contentRect.left + p[0] * contentRect.width(),
            contentRect.top + p[1] * contentRect.height()
        )
        return true
    }

    private fun drawHorizon(canvas: Canvas, geom: ViewGeometry) {
        val centerAz = SkyGeometry.pointingAzEl(geom.rotationMatrix)[0]

        path.rewind()
        var started = false
        var off = -80
        while (off <= 80) {
            if (projectTo(centerAz + off, 0f, geom, point, slack = 0.6f)) {
                if (started) {
                    path.lineTo(point.x, point.y)
                } else {
                    path.moveTo(point.x, point.y)
                    started = true
                }
            } else {
                started = false
            }
            off += 4
        }
        canvas.drawPath(path, dimPaint)

        // Compass ticks sitting on the horizon, letters on the cardinals.
        var az = 0
        while (az < 360) {
            val rel = SkyGeometry.wrapDeg(az - centerAz)
            if (abs(rel) <= 80f && projectTo(centerAz + rel, 0f, geom, point, slack = 0.3f)) {
                if (az % 90 == 0) {
                    canvas.drawLine(point.x, point.y, point.x, point.y - dp(14f), dimPaint)
                    textPaint.color = greenDim
                    canvas.drawText(cardinals[az / 90], point.x - dp(4f), point.y - dp(18f), textPaint)
                } else {
                    canvas.drawLine(point.x, point.y, point.x, point.y - dp(7f), dimPaint)
                }
            }
            az += 30
        }
    }

    private fun drawCrosshair(canvas: Canvas) {
        val cx = contentRect.centerX()
        val cy = contentRect.centerY()
        val r = dp(26f)
        canvas.drawCircle(cx, cy, r, dimPaint)
        canvas.drawLine(cx - r - dp(10f), cy, cx - r + dp(6f), cy, dimPaint)
        canvas.drawLine(cx + r - dp(6f), cy, cx + r + dp(10f), cy, dimPaint)
        canvas.drawLine(cx, cy - r - dp(10f), cx, cy - r + dp(6f), dimPaint)
        canvas.drawLine(cx, cy + r - dp(6f), cx, cy + r + dp(10f), dimPaint)
    }

    private fun drawTrack(canvas: Canvas, t: TrackSnapshot, geom: ViewGeometry) {
        val p = SkyGeometry.worldToPixel(t.azDeg, t.elDeg, geom) ?: return
        val x = contentRect.left + p[0] * contentRect.width()
        val y = contentRect.top + p[1] * contentRect.height()
        val color = if (t.confirmed) green else amber

        val inside = p[0] in 0f..1f && p[1] in 0f..1f
        if (!inside) {
            if (t.confirmed) drawEdgeIndicator(canvas, x, y, t.id, color)
            return
        }

        if (!t.confirmed) {
            fillPaint.color = amber
            canvas.drawCircle(x, y, dp(3f), fillPaint)
            return
        }

        // Fading trail of recent fixes.
        fillPaint.color = color
        val hist = t.history
        var i = hist.size - 3
        var fade = 150
        while (i >= 0 && fade > 25) {
            if (projectTo(hist[i][0], hist[i][1], geom, point)) {
                fillPaint.alpha = fade
                canvas.drawCircle(point.x, point.y, dp(1.6f), fillPaint)
            }
            i -= 3
            fade -= 12
        }
        fillPaint.alpha = 255

        // Corner-bracket target marker.
        markerPaint.color = color
        val s = dp(18f)
        val g = dp(7f)
        canvas.drawLines(
            floatArrayOf(
                x - s, y - s, x - s + g, y - s, x - s, y - s, x - s, y - s + g,
                x + s, y - s, x + s - g, y - s, x + s, y - s, x + s, y - s + g,
                x - s, y + s, x - s + g, y + s, x - s, y + s, x - s, y + s - g,
                x + s, y + s, x + s - g, y + s, x + s, y + s, x + s, y + s - g
            ),
            markerPaint
        )

        // Velocity vector: where the object will be ~0.7 s from now.
        if (t.speedDegPerSec >= 0.3f) {
            val nAz = SkyGeometry.wrapDeg(t.azDeg + t.velAzDegPerSec * 0.7f)
            val nEl = t.elDeg + t.velElDegPerSec * 0.7f
            if (projectTo(nAz, nEl, geom, point2)) {
                linePaint.color = color
                canvas.drawLine(x, y, point2.x, point2.y, linePaint)
                drawArrowHead(canvas, x, y, point2.x, point2.y, color)
            }
        }

        // Dashed predicted path with a dot per second.
        if (t.predicted.isNotEmpty()) {
            pathPaint.color = color
            path.rewind()
            path.moveTo(x, y)
            var any = false
            for (pt in t.predicted) {
                if (projectTo(pt[0], pt[1], geom, point)) {
                    path.lineTo(point.x, point.y)
                    any = true
                } else {
                    break
                }
            }
            if (any) canvas.drawPath(path, pathPaint)
            fillPaint.color = color
            for (pt in t.predicted) {
                if (projectTo(pt[0], pt[1], geom, point)) {
                    canvas.drawCircle(point.x, point.y, dp(2.2f), fillPaint)
                }
            }
        }

        textPaint.color = color
        canvas.drawText(
            String.format(Locale.US, "#%02d %.1f°/s", t.id, t.speedDegPerSec),
            x + s + dp(6f), y - dp(2f), textPaint
        )
        canvas.drawText(
            String.format(Locale.US, "AZ %03.0f EL %+03.0f", (t.azDeg + 360f) % 360f, t.elDeg),
            x + s + dp(6f), y + dp(12f), textPaint
        )
    }

    /** Chevron at the frame edge pointing toward an off-screen target. */
    private fun drawEdgeIndicator(canvas: Canvas, tx: Float, ty: Float, id: Int, color: Int) {
        val inset = dp(18f)
        val cx = tx.coerceIn(contentRect.left + inset, contentRect.right - inset)
        val cy = ty.coerceIn(contentRect.top + inset, contentRect.bottom - inset)
        val angle = Math.toDegrees(atan2((ty - cy).toDouble(), (tx - cx).toDouble())).toFloat()
        canvas.save()
        canvas.rotate(angle, cx, cy)
        fillPaint.color = color
        path.rewind()
        path.moveTo(cx + dp(9f), cy)
        path.lineTo(cx - dp(4f), cy - dp(6f))
        path.lineTo(cx - dp(4f), cy + dp(6f))
        path.close()
        canvas.drawPath(path, fillPaint)
        canvas.restore()
        textPaint.color = color
        canvas.drawText(String.format(Locale.US, "#%02d", id), cx - dp(12f), cy + dp(20f), textPaint)
    }

    private fun drawArrowHead(canvas: Canvas, fromX: Float, fromY: Float, toX: Float, toY: Float, color: Int) {
        val angle = Math.toDegrees(atan2((toY - fromY).toDouble(), (toX - fromX).toDouble())).toFloat()
        canvas.save()
        canvas.rotate(angle, toX, toY)
        fillPaint.color = color
        path.rewind()
        path.moveTo(toX + dp(7f), toY)
        path.lineTo(toX - dp(3f), toY - dp(5f))
        path.lineTo(toX - dp(3f), toY + dp(5f))
        path.close()
        canvas.drawPath(path, fillPaint)
        canvas.restore()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
