package com.skyradar.app.analysis

/**
 * One candidate object found in a single camera frame.
 * Coordinates are normalized (0..1) in the upright, display-oriented frame:
 * x grows to the right, y grows downwards.
 */
data class Detection(
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    /** Peak luminance difference from the sky background, 0..255. */
    val contrast: Float,
    /** Blob area in analysis-grid pixels. */
    val areaPx: Int
)

/** Output of [SkyObjectAnalyzer] for one frame. */
data class AnalysisResult(
    val timestampNanos: Long,
    val detections: List<Detection>,
    val rotationDegrees: Int,
    /** width/height of the upright analysis frame. */
    val uprightAspect: Float,
    /** Fraction of the frame that looks like open sky (0..1). */
    val skyFraction: Float,
    val fps: Float
)
