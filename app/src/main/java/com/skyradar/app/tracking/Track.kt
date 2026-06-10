package com.skyradar.app.tracking

import kotlin.math.cos
import kotlin.math.sqrt

/** Immutable view of one track, handed to the UI thread for drawing. */
data class TrackSnapshot(
    val id: Int,
    val azDeg: Float,
    val elDeg: Float,
    val velAzDegPerSec: Float,
    val velElDegPerSec: Float,
    /** True angular speed across the sky in deg/s. */
    val speedDegPerSec: Float,
    val confirmed: Boolean,
    val ageSeconds: Float,
    /** Recent (azDeg, elDeg) fixes, oldest first. */
    val history: List<FloatArray>,
    /** Predicted (azDeg, elDeg) at +1s, +2s, ... assuming constant velocity. */
    val predicted: List<FloatArray>
)

/** Mutable tracker state for one sky object. */
internal class Track(
    val id: Int,
    var azDeg: Float,
    var elDeg: Float,
    val bornNanos: Long
) {
    /** deg/s along the azimuth coordinate (not yet scaled by cos(el)). */
    var velAz = 0f
    var velEl = 0f
    var hits = 1
    var lastSeenNanos = bornNanos
    val history = ArrayDeque<FloatArray>()

    val speedDegPerSec: Float
        get() {
            val a = velAz * cos(Math.toRadians(elDeg.toDouble())).toFloat()
            return sqrt(a * a + velEl * velEl)
        }
}
