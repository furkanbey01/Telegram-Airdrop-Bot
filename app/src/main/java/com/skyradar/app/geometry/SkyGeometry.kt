package com.skyradar.app.geometry

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Everything the views need to project sky coordinates onto the screen for
 * one camera frame.
 *
 * @param rotationMatrix device-to-world rotation (row major 3x3) from the
 *        rotation vector sensor. World axes: X = east, Y = north, Z = up.
 * @param fovXDeg horizontal field of view of the upright analysis frame.
 * @param fovYDeg vertical field of view of the upright analysis frame.
 * @param imageAspect width/height of the upright analysis frame.
 */
data class ViewGeometry(
    val rotationMatrix: FloatArray,
    val fovXDeg: Float,
    val fovYDeg: Float,
    val imageAspect: Float
)

/**
 * Converts between camera pixels and sky directions.
 *
 * Sky directions are expressed as azimuth (degrees clockwise from north) and
 * elevation (degrees above the horizon). Working in this frame makes tracks
 * independent of how the phone itself moves.
 *
 * Conventions: the device coordinate system is the standard Android sensor
 * frame (X right, Y up, Z out of the screen); the back camera looks along -Z.
 * Image coordinates are normalized to the upright frame, x right and y down.
 */
object SkyGeometry {

    /** Direction (unit vector, world frame) seen at normalized pixel (nx, ny). */
    fun pixelToWorld(nx: Float, ny: Float, fovXDeg: Float, fovYDeg: Float, r: FloatArray): FloatArray {
        val tx = (2f * nx - 1f) * tanHalf(fovXDeg)
        val ty = (2f * ny - 1f) * tanHalf(fovYDeg)
        // Image x is device +X, image y points down which is device -Y,
        // and the back camera looks along device -Z.
        val dx = tx
        val dy = -ty
        val dz = -1f
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        val vx = dx / len
        val vy = dy / len
        val vz = dz / len
        return floatArrayOf(
            r[0] * vx + r[1] * vy + r[2] * vz,
            r[3] * vx + r[4] * vy + r[5] * vz,
            r[6] * vx + r[7] * vy + r[8] * vz
        )
    }

    /**
     * Normalized upright pixel coordinates where the (azDeg, elDeg) direction
     * appears, or null when it is behind the camera plane. Results may fall
     * outside [0,1] when the direction is in front but out of frame.
     */
    fun worldToPixel(azDeg: Float, elDeg: Float, geom: ViewGeometry): FloatArray? {
        val w = unitVector(azDeg, elDeg)
        val r = geom.rotationMatrix
        // device = transpose(R) * world
        val dx = r[0] * w[0] + r[3] * w[1] + r[6] * w[2]
        val dy = r[1] * w[0] + r[4] * w[1] + r[7] * w[2]
        val dz = r[2] * w[0] + r[5] * w[1] + r[8] * w[2]
        if (dz > -0.05f) return null
        val tx = dx / -dz
        val ty = -dy / -dz
        val nx = (tx / tanHalf(geom.fovXDeg) + 1f) / 2f
        val ny = (ty / tanHalf(geom.fovYDeg) + 1f) / 2f
        return floatArrayOf(nx, ny)
    }

    /** [azimuthDeg, elevationDeg] of a world-frame unit vector. */
    fun azElOf(world: FloatArray): FloatArray {
        val az = Math.toDegrees(atan2(world[0].toDouble(), world[1].toDouble())).toFloat()
        val el = Math.toDegrees(asin(world[2].coerceIn(-1f, 1f).toDouble())).toFloat()
        return floatArrayOf(az, el)
    }

    /** World-frame unit vector for the given azimuth/elevation. */
    fun unitVector(azDeg: Float, elDeg: Float): FloatArray {
        val az = Math.toRadians(azDeg.toDouble())
        val el = Math.toRadians(elDeg.toDouble())
        val cosEl = cos(el)
        return floatArrayOf(
            (sin(az) * cosEl).toFloat(),
            (cos(az) * cosEl).toFloat(),
            sin(el).toFloat()
        )
    }

    /** [azimuthDeg, elevationDeg] the back camera is currently pointing at. */
    fun pointingAzEl(r: FloatArray): FloatArray =
        azElOf(floatArrayOf(-r[2], -r[5], -r[8]))

    /** Wraps an angle difference into (-180, 180]. */
    fun wrapDeg(a: Float): Float {
        var x = a % 360f
        if (x > 180f) x -= 360f
        if (x < -180f) x += 360f
        return x
    }

    /** Small-angle distance between two sky positions in degrees. */
    fun angularDistanceDeg(az1: Float, el1: Float, az2: Float, el2: Float): Float {
        val dEl = el2 - el1
        val midEl = Math.toRadians(((el1 + el2) / 2f).toDouble())
        val dAz = wrapDeg(az2 - az1) * cos(midEl).toFloat()
        return sqrt(dAz * dAz + dEl * dEl)
    }

    private fun tanHalf(fovDeg: Float): Float =
        tan(Math.toRadians(fovDeg / 2.0)).toFloat()
}
