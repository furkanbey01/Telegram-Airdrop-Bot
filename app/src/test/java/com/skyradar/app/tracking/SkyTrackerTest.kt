package com.skyradar.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyTrackerTest {

    @Test
    fun updateConfirmsTrackAndPredictsFuturePositions() {
        val tracker = SkyTracker()
        var snapshots: List<TrackSnapshot> = emptyList()

        for (frame in 0 until 6) {
            snapshots = tracker.update(
                measurements = listOf(floatArrayOf(frame.toFloat(), 30f)),
                nowNanos = frame * 100_000_000L
            )
        }

        val track = snapshots.single()
        assertTrue(track.confirmed)
        assertTrue(track.speedDegPerSec > 0.3f)
        assertEquals(4, track.predicted.size)
        assertTrue(track.predicted.first()[0] > track.azDeg)
    }

    @Test
    fun updateDropsTentativeTrackAfterCoastingTooLong() {
        val tracker = SkyTracker()
        tracker.update(listOf(floatArrayOf(10f, 20f)), 0L)
        val snapshots = tracker.update(emptyList(), 1_000_000_000L)

        assertTrue(snapshots.isEmpty())
    }
}
