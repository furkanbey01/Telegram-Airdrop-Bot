package com.skyradar.app.analysis

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds small objects against the sky in the camera's luminance plane.
 *
 * The sky is modelled with a heavily blurred copy of the frame; pixels that
 * differ strongly from that background are object candidates. A second
 * blurred map of the absolute residual ("clutter") separates open sky from
 * textured regions such as trees and buildings, so ground clutter is ignored
 * and only sky-borne blobs are reported.
 *
 * Everything runs on a downsampled grid (~200 px wide) with reused buffers,
 * which keeps a frame well under a millisecond of work on modern phones.
 */
class SkyObjectAnalyzer(
    private val onResult: (AnalysisResult) -> Unit
) : ImageAnalysis.Analyzer {

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

    override fun analyze(image: ImageProxy) {
        try {
            onResult(process(image))
        } finally {
            image.close()
        }
    }

    private fun process(image: ImageProxy): AnalysisResult {
        downsample(image)
        val n = w * h
        val radius = max(6, w / 10)

        boxBlur(gray, bg, tmp, w, h, radius)
        for (i in 0 until n) absRes[i] = abs(gray[i] - bg[i])
        boxBlur(absRes, clutter, tmp, w, h, radius)

        var skyCount = 0
        for (i in 0 until n) if (clutter[i] <= CLUTTER_SKY_MAX) skyCount++
        val skyFraction = skyCount.toFloat() / n

        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val detections = findBlobs().map { rotateToUpright(it, rotation) }
        val uprightAspect =
            if (rotation % 180 == 0) w.toFloat() / h else h.toFloat() / w

        val now = image.imageInfo.timestamp
        if (lastNanos != 0L) {
            val instFps = 1e9f / max(1L, now - lastNanos).toFloat()
            emaFps = if (emaFps == 0f) instFps else emaFps * 0.9f + instFps * 0.1f
        }
        lastNanos = now

        return AnalysisResult(now, detections, rotation, uprightAspect, skyFraction, emaFps)
    }

    /** Averages 2x2 blocks of the Y plane onto the working grid. */
    private fun downsample(image: ImageProxy) {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val srcW = image.width
        val srcH = image.height
        val step = max(1, max(srcW, srcH) / TARGET_LONG_SIDE)
        val newW = srcW / step
        val newH = srcH / step
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
            val r0 = sy * rowStride
            val r1 = min(sy + 1, srcH - 1) * rowStride
            val out = y * w
            for (x in 0 until w) {
                val sx = x * step
                val c0 = sx * pixelStride
                val c1 = min(sx + 1, srcW - 1) * pixelStride
                val v = (buf.get(r0 + c0).toInt() and 0xFF) +
                        (buf.get(r0 + c1).toInt() and 0xFF) +
                        (buf.get(r1 + c0).toInt() and 0xFF) +
                        (buf.get(r1 + c1).toInt() and 0xFF)
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

                if (x > 0) {
                    val j = i - 1
                    if (!visited[j] && isCandidate(j)) {
                        visited[j] = true
                        stack[sp++] = j
                    }
                }
                if (x < w - 1) {
                    val j = i + 1
                    if (!visited[j] && isCandidate(j)) {
                        visited[j] = true
                        stack[sp++] = j
                    }
                }
                if (y > 0) {
                    val j = i - w
                    if (!visited[j] && isCandidate(j)) {
                        visited[j] = true
                        stack[sp++] = j
                    }
                }
                if (y < h - 1) {
                    val j = i + w
                    if (!visited[j] && isCandidate(j)) {
                        visited[j] = true
                        stack[sp++] = j
                    }
                }
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
