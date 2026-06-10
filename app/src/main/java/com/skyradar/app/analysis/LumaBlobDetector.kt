package com.skyradar.app.analysis

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shared small-object detector for luminance frames.
 *
 * CameraX and imported videos both feed this class with grayscale samples, so
 * both sources use the same background, clutter and connected-component logic.
 */
class LumaBlobDetector {

    private var w = 0
    private var h = 0
    private var gray = IntArray(0)
    private var bg = IntArray(0)
    private var tmp = IntArray(0)
    private var absRes = IntArray(0)
    private var clutter = IntArray(0)
    private var visited = BooleanArray(0)
    private var stack = IntArray(0)

    private var lastNanos = 0L
    private var emaFps = 0f

    fun process(
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        timestampNanos: Long,
        sample: (x: Int, y: Int) -> Int
    ): AnalysisResult {
        downsample(sourceWidth, sourceHeight, sample)
        val n = w * h
        val radius = max(6, w / 10)

        boxBlur(gray, bg, tmp, w, h, radius)
        for (i in 0 until n) absRes[i] = abs(gray[i] - bg[i])
        boxBlur(absRes, clutter, tmp, w, h, radius)

        var skyCount = 0
        for (i in 0 until n) if (clutter[i] <= CLUTTER_SKY_MAX) skyCount++
        val skyFraction = skyCount.toFloat() / n

        val rotation = ((rotationDegrees % 360) + 360) % 360
        val detections = findBlobs().map { rotateToUpright(it, rotation) }
        val uprightAspect =
            if (rotation % 180 == 0) w.toFloat() / h else h.toFloat() / w

        if (lastNanos != 0L) {
            val instFps = 1e9f / max(1L, timestampNanos - lastNanos).toFloat()
            emaFps = if (emaFps == 0f) instFps else emaFps * 0.9f + instFps * 0.1f
        }
        lastNanos = timestampNanos

        return AnalysisResult(timestampNanos, detections, rotation, uprightAspect, skyFraction, emaFps)
    }

    fun processBitmap(bitmap: Bitmap, timestampNanos: Long): AnalysisResult =
        process(bitmap.width, bitmap.height, 0, timestampNanos) { x, y ->
            val c = bitmap.getPixel(x, y)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            (r * 299 + g * 587 + b * 114) / 1000
        }

    fun resetTiming() {
        lastNanos = 0L
        emaFps = 0f
    }

    /** Averages 2x2 blocks of the luminance source onto the working grid. */
    private fun downsample(sourceWidth: Int, sourceHeight: Int, sample: (x: Int, y: Int) -> Int) {
        val step = max(1, max(sourceWidth, sourceHeight) / TARGET_LONG_SIDE)
        val newW = sourceWidth / step
        val newH = sourceHeight / step
        if (newW != w || newH != h) {
            w = newW
            h = newH
            val n = w * h
            gray = IntArray(n)
            bg = IntArray(n)
            tmp = IntArray(n)
            absRes = IntArray(n)
            clutter = IntArray(n)
            visited = BooleanArray(n)
            stack = IntArray(n)
        }
        for (y in 0 until h) {
            val sy = y * step
            val y1 = min(sy + 1, sourceHeight - 1)
            val out = y * w
            for (x in 0 until w) {
                val sx = x * step
                val x1 = min(sx + 1, sourceWidth - 1)
                val v = sample(sx, sy) + sample(x1, sy) + sample(sx, y1) + sample(x1, y1)
                gray[out + x] = v shr 2
            }
        }
    }

    /** Two-pass box blur with edge clamping; src and dst must not alias. */
    private fun boxBlur(src: IntArray, dst: IntArray, tmp: IntArray, w: Int, h: Int, radius: Int) {
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            var count = 0
            for (x in 0..min(radius, w - 1)) {
                sum += src[row + x]
                count++
            }
            for (x in 0 until w) {
                tmp[row + x] = sum / count
                val add = x + radius + 1
                if (add < w) {
                    sum += src[row + add]
                    count++
                }
                val rem = x - radius
                if (rem >= 0) {
                    sum -= src[row + rem]
                    count--
                }
            }
        }
        for (x in 0 until w) {
            var sum = 0
            var count = 0
            for (y in 0..min(radius, h - 1)) {
                sum += tmp[y * w + x]
                count++
            }
            for (y in 0 until h) {
                dst[y * w + x] = sum / count
                val add = y + radius + 1
                if (add < h) {
                    sum += tmp[add * w + x]
                    count++
                }
                val rem = y - radius
                if (rem >= 0) {
                    sum -= tmp[rem * w + x]
                    count--
                }
            }
        }
    }

    private fun isCandidate(i: Int): Boolean =
        clutter[i] <= CLUTTER_SKY_MAX &&
            absRes[i] >= max(MIN_CONTRAST, clutter[i] * 4)

    /** Connected-component pass over candidate pixels, in sensor-frame coords. */
    private fun findBlobs(): List<Detection> {
        visited.fill(false)
        val n = w * h
        val maxArea = max(16, n / 64)
        val found = ArrayList<Detection>()

        for (start in 0 until n) {
            if (visited[start] || !isCandidate(start)) continue
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var area = 0
            var sumX = 0L
            var sumY = 0L
            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0
            var peak = 0

            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                area++
                sumX += x
                sumY += y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (absRes[i] > peak) peak = absRes[i]

                if (x > 0) pushIfCandidate(i - 1, stack, sp).also { sp = it }
                if (x < w - 1) pushIfCandidate(i + 1, stack, sp).also { sp = it }
                if (y > 0) pushIfCandidate(i - w, stack, sp).also { sp = it }
                if (y < h - 1) pushIfCandidate(i + w, stack, sp).also { sp = it }
            }

            if (area < MIN_AREA || area > maxArea) continue
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            // Shape filters: reject cloud edges and other stringy artifacts.
            if (bw > w / 6 || bh > h / 6) continue
            if (area.toFloat() / (bw * bh) < 0.2f) continue
            if (max(bw, bh).toFloat() / min(bw, bh) > 8f) continue

            found.add(
                Detection(
                    cx = (sumX.toFloat() / area + 0.5f) / w,
                    cy = (sumY.toFloat() / area + 0.5f) / h,
                    width = bw.toFloat() / w,
                    height = bh.toFloat() / h,
                    contrast = peak.toFloat(),
                    areaPx = area
                )
            )
        }

        found.sortByDescending { it.contrast }
        return if (found.size > MAX_DETECTIONS) ArrayList(found.subList(0, MAX_DETECTIONS)) else found
    }

    private fun pushIfCandidate(index: Int, stack: IntArray, sp: Int): Int {
        if (visited[index] || !isCandidate(index)) return sp
        visited[index] = true
        stack[sp] = index
        return sp + 1
    }

    /** Rotates sensor-frame coordinates into the upright display frame. */
    private fun rotateToUpright(d: Detection, rotationDegrees: Int): Detection =
        when (rotationDegrees) {
            90 -> d.copy(cx = 1f - d.cy, cy = d.cx, width = d.height, height = d.width)
            180 -> d.copy(cx = 1f - d.cx, cy = 1f - d.cy)
            270 -> d.copy(cx = d.cy, cy = 1f - d.cx, width = d.height, height = d.width)
            else -> d
        }

    private companion object {
        const val TARGET_LONG_SIDE = 200
        const val MIN_AREA = 2
        const val MIN_CONTRAST = 14
        const val CLUTTER_SKY_MAX = 7
        const val MAX_DETECTIONS = 12
    }
}
