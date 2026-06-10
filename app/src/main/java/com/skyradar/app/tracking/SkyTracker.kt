package com.skyradar.app.tracking

import com.skyradar.app.geometry.SkyGeometry

/**
 * Multi-object tracker working in sky (azimuth/elevation) coordinates, so
 * tracks stay valid while the phone itself moves around.
 *
 * Greedy nearest-neighbour association feeds a per-track alpha-beta filter:
 * each measurement nudges the position estimate and the angular velocity
 * estimate, and the velocity is what powers the "where will it be" prediction.
 * Tracks coast on their last velocity when detections drop out for a moment.
 *
 * Not thread-safe; call [update] from a single analysis thread.
 */
class SkyTracker {

    private val tracks = mutableListOf<Track>()
    private var nextId = 1
    private var lastFrameNanos = 0L

    /**
     * Feeds one frame of measurements ([azDeg, elDeg] pairs, frame timestamp
     * in nanoseconds) and returns snapshots of all live tracks.
     */
    fun update(measurements: List<FloatArray>, nowNanos: Long): List<TrackSnapshot> {
        val dtFrame =
            if (lastFrameNanos == 0L) 0f
            else ((nowNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.5f)
        lastFrameNanos = nowNanos

        // Coast every track to the current frame time.
        for (t in tracks) {
            t.azDeg = SkyGeometry.wrapDeg(t.azDeg + t.velAz * dtFrame)
            t.elDeg = (t.elDeg + t.velEl * dtFrame).coerceIn(-89f, 89f)
        }

        // Collect candidate pairs inside the association gate, best first.
        class Cand(val track: Track, val measIndex: Int, val dist: Float)

        val cands = ArrayList<Cand>()
        for (t in tracks) {
            val gate = GATE_BASE_DEG +
                t.speedDegPerSec * dtFrame * 2f +
                if (t.hits < CONFIRM_HITS) 1.5f else 0f
            for ((i, m) in measurements.withIndex()) {
                val d = SkyGeometry.angularDistanceDeg(t.azDeg, t.elDeg, m[0], m[1])
                if (d <= gate) cands.add(Cand(t, i, d))
            }
        }
        cands.sortBy { it.dist }

        val takenTracks = HashSet<Int>()
        val takenMeas = HashSet<Int>()
        for (c in cands) {
            if (c.track.id in takenTracks || c.measIndex in takenMeas) continue
            takenTracks.add(c.track.id)
            takenMeas.add(c.measIndex)
            correct(c.track, measurements[c.measIndex], nowNanos)
        }

        // Unexplained measurements spawn new (tentative) tracks.
        for ((i, m) in measurements.withIndex()) {
            if (i in takenMeas || tracks.size >= MAX_TRACKS) continue
            val nearExisting = tracks.any {
                SkyGeometry.angularDistanceDeg(it.azDeg, it.elDeg, m[0], m[1]) < SPAWN_MIN_SEPARATION_DEG
            }
            if (nearExisting) continue
            val t = Track(nextId++, m[0], m[1], nowNanos)
            t.history.addLast(floatArrayOf(m[0], m[1]))
            tracks.add(t)
        }

        // Drop tracks that have coasted for too long.
        tracks.removeAll { t ->
            val unseenSec = (nowNanos - t.lastSeenNanos) / 1e9f
            unseenSec > if (t.hits >= CONFIRM_HITS) COAST_CONFIRMED_SEC else COAST_TENTATIVE_SEC
        }

        return tracks.map { snapshot(it, nowNanos) }
    }

    fun reset() {
        tracks.clear()
        lastFrameNanos = 0L
    }

    private fun correct(t: Track, meas: FloatArray, nowNanos: Long) {
        val dtMeas = ((nowNanos - t.lastSeenNanos) / 1e9f).coerceAtLeast(0.02f)
        val resAz = SkyGeometry.wrapDeg(meas[0] - t.azDeg)
        val resEl = meas[1] - t.elDeg
        t.azDeg = SkyGeometry.wrapDeg(t.azDeg + ALPHA * resAz)
        t.elDeg = (t.elDeg + ALPHA * resEl).coerceIn(-89f, 89f)
        t.velAz = (t.velAz + BETA * resAz / dtMeas).coerceIn(-MAX_ANGULAR_SPEED, MAX_ANGULAR_SPEED)
        t.velEl = (t.velEl + BETA * resEl / dtMeas).coerceIn(-MAX_ANGULAR_SPEED, MAX_ANGULAR_SPEED)
        t.hits++
        t.lastSeenNanos = nowNanos
        t.history.addLast(floatArrayOf(t.azDeg, t.elDeg))
        while (t.history.size > HISTORY_MAX) t.history.removeFirst()
    }

    private fun snapshot(t: Track, nowNanos: Long): TrackSnapshot {
        val confirmed = t.hits >= CONFIRM_HITS
        val predicted =
            if (confirmed && t.speedDegPerSec >= MIN_PREDICT_SPEED_DEG_PER_SEC) {
                (1..PREDICT_SECONDS).map { k ->
                    floatArrayOf(
                        SkyGeometry.wrapDeg(t.azDeg + t.velAz * k),
                        (t.elDeg + t.velEl * k).coerceIn(-89f, 89f)
                    )
                }
            } else {
                emptyList()
            }
        return TrackSnapshot(
            id = t.id,
            azDeg = t.azDeg,
            elDeg = t.elDeg,
            velAzDegPerSec = t.velAz,
            velElDegPerSec = t.velEl,
            speedDegPerSec = t.speedDegPerSec,
            confirmed = confirmed,
            ageSeconds = (nowNanos - t.bornNanos) / 1e9f,
            history = t.history.toList(),
            predicted = predicted
        )
    }

    private companion object {
        // Alpha-beta gains tuned for ~30 fps input: velocity gain works out to
        // BETA/dt ≈ 2.4/s, low enough that detection jitter does not whip the
        // speed estimate around, while still settling in about a second.
        const val ALPHA = 0.35f
        const val BETA = 0.08f
        const val GATE_BASE_DEG = 2.5f
        const val CONFIRM_HITS = 4
        const val MAX_TRACKS = 16
        const val MAX_ANGULAR_SPEED = 60f // deg/s
        const val HISTORY_MAX = 90
        const val PREDICT_SECONDS = 4
        const val MIN_PREDICT_SPEED_DEG_PER_SEC = 0.3f
        const val SPAWN_MIN_SEPARATION_DEG = 1.5f
        const val COAST_CONFIRMED_SEC = 1.8f
        const val COAST_TENTATIVE_SEC = 0.4f
    }
}
