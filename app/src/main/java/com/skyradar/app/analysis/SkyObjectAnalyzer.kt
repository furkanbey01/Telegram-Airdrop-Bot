package com.skyradar.app.analysis

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Finds small objects against the sky in the camera's luminance plane.
 *
 * The sky is modelled with a heavily blurred copy of the frame; pixels that
 * differ strongly from that background are object candidates. A second
 * blurred map of the absolute residual ("clutter") separates open sky from
 * textured regions such as trees and buildings, so ground clutter is ignored
 * and only sky-borne blobs are reported.
 */
class SkyObjectAnalyzer(
    private val onResult: (AnalysisResult) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector = LumaBlobDetector()

    override fun analyze(image: ImageProxy) {
        try {
            onResult(process(image))
        } finally {
            image.close()
        }
    }

    private fun process(image: ImageProxy): AnalysisResult {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        return detector.process(
            sourceWidth = image.width,
            sourceHeight = image.height,
            rotationDegrees = image.imageInfo.rotationDegrees,
            timestampNanos = image.imageInfo.timestamp
        ) { x, y ->
            buffer.get(y * rowStride + x * pixelStride).toInt() and 0xFF
        }
    }
}
