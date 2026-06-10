package com.skyradar.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LumaBlobDetectorTest {

    @Test
    fun processFindsSmallBrightBlobInFlatFrame() {
        val detector = LumaBlobDetector()
        val result = detector.process(
            sourceWidth = 80,
            sourceHeight = 60,
            rotationDegrees = 0,
            timestampNanos = 1_000_000_000L
        ) { x, y ->
            if (x in 39..41 && y in 29..31) 220 else 100
        }

        assertEquals(1, result.detections.size)
        val detection = result.detections.single()
        assertTrue(detection.cx in 0.45f..0.55f)
        assertTrue(detection.cy in 0.45f..0.55f)
        assertTrue(detection.contrast > 50f)
        assertTrue(result.skyFraction > 0.95f)
    }

    @Test
    fun processRotatesDetectionsToUprightCoordinates() {
        val detector = LumaBlobDetector()
        val result = detector.process(
            sourceWidth = 80,
            sourceHeight = 60,
            rotationDegrees = 90,
            timestampNanos = 1_000_000_000L
        ) { x, y ->
            if (x in 15..17 && y in 29..31) 220 else 100
        }

        assertEquals(1, result.detections.size)
        val detection = result.detections.single()
        assertTrue("x should rotate from source y", detection.cx in 0.45f..0.55f)
        assertTrue("y should rotate from source x", detection.cy in 0.15f..0.30f)
    }
}
