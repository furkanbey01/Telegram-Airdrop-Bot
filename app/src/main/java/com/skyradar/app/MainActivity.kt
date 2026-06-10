package com.skyradar.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.skyradar.app.analysis.AnalysisResult
import com.skyradar.app.analysis.LumaBlobDetector
import com.skyradar.app.analysis.SkyObjectAnalyzer
import com.skyradar.app.databinding.ActivityMainBinding
import com.skyradar.app.geometry.SkyGeometry
import com.skyradar.app.geometry.ViewGeometry
import com.skyradar.app.sensors.OrientationProvider
import com.skyradar.app.tracking.SkyTracker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var orientation: OrientationProvider
    private val tracker = SkyTracker()
    private val videoDetector = LumaBlobDetector()
    private var analysisExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var videoRunId = 0
    private var cameraStarted = false
    private var permissionRequested = false
    private var videoMode = false
    private var currentVideoUri: Uri? = null

    // Field of view of the sensor's native landscape frame; refined from the
    // camera characteristics once available.
    private var fovLongDeg = 66f
    private var fovShortDeg = 52f

    private lateinit var compassDirs: Array<String>

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionUi()
        }

    private val videoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { switchToVideo(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        orientation = OrientationProvider(this)
        compassDirs = resources.getStringArray(R.array.compass_dirs)
        binding.previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        binding.permissionButton.setOnClickListener { onPermissionButton() }
        binding.importVideoButton.setOnClickListener { videoLauncher.launch("video/*") }
        binding.liveCameraButton.setOnClickListener { switchToLiveCamera() }
        readFieldOfView()
    }

    override fun onResume() {
        super.onResume()
        orientation.start()
        when {
            videoMode -> {
                binding.videoView.start()
                currentVideoUri?.let { startVideoProcessing(it) }
            }
            hasCameraPermission() -> startCamera()
            !permissionRequested -> {
                permissionRequested = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> showPermissionUi()
        }
    }

    override fun onPause() {
        stopVideoProcessing()
        if (videoMode) binding.videoView.pause()
        orientation.stop()
        super.onPause()
    }

    override fun onDestroy() {
        stopVideoProcessing()
        analysisExecutor?.shutdown()
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun showPermissionUi() {
        binding.permissionGroup.visibility = View.VISIBLE
    }

    private fun onPermissionButton() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            // Permanently denied: the system dialog will not show again.
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        }
    }

    private fun startCamera() {
        binding.permissionGroup.visibility = View.GONE
        if (cameraStarted) return
        cameraStarted = true

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            val aspect = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build()
                )
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(aspect)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val executor = Executors.newSingleThreadExecutor()
            analysisExecutor = executor
            analysis.setAnalyzer(executor, SkyObjectAnalyzer(::onAnalysis))

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            binding.previewView.visibility = View.VISIBLE
            binding.videoView.visibility = View.GONE
            binding.liveCameraButton.visibility = View.GONE
        }, ContextCompat.getMainExecutor(this))
    }

    /** Runs on the analysis thread, once per camera frame. */
    private fun onAnalysis(result: AnalysisResult) {
        val rot = orientation.rotationMatrix
        val rotated = result.rotationDegrees % 180 != 0
        val fovX = if (rotated) fovShortDeg else fovLongDeg
        val fovY = if (rotated) fovLongDeg else fovShortDeg
        consumeAnalysis(result, rot, fovX, fovY, isVideo = false)
    }

    private fun consumeAnalysis(
        result: AnalysisResult,
        rot: FloatArray,
        fovX: Float,
        fovY: Float,
        isVideo: Boolean
    ) {
        val measurements = ArrayList<FloatArray>(result.detections.size)
        for (d in result.detections) {
            measurements.add(
                SkyGeometry.azElOf(SkyGeometry.pixelToWorld(d.cx, d.cy, fovX, fovY, rot))
            )
        }

        val snapshots = tracker.update(measurements, result.timestampNanos)
        val pointing = SkyGeometry.pointingAzEl(rot)
        val geometry = ViewGeometry(rot, fovX, fovY, result.uprightAspect)

        if (isDestroyed) return
        runOnUiThread {
            binding.overlayView.submit(snapshots, geometry)
            binding.radarView.submit(snapshots, pointing[0], pointing[1], fovX)
            binding.statusText.text = buildStatus(snapshots.count { it.confirmed }, pointing, result, isVideo)
        }
    }

    private fun buildStatus(targetCount: Int, pointing: FloatArray, result: AnalysisResult, isVideo: Boolean): String {
        val az = (pointing[0] + 360f) % 360f
        val el = pointing[1]
        val dir = compassDirs[(((az + 22.5f) / 45f).toInt()) % 8]
        val line1 = getString(
            R.string.status_line,
            dir, az.roundToInt() % 360, el.roundToInt(), targetCount, result.fps
        )
        val source = getString(if (isVideo) R.string.source_video else R.string.source_camera)
        val line2 = when {
            isVideo -> getString(R.string.hint_video_import)
            el < 15f -> getString(R.string.hint_point_sky)
            result.skyFraction < 0.35f -> getString(R.string.hint_view_blocked)
            !orientation.hasCompass -> getString(R.string.hint_no_compass)
            else -> getString(R.string.sky_quality, (result.skyFraction * 100).roundToInt())
        }
        return source + "  •  " + line1 + "\n" + line2
    }


    private fun switchToVideo(uri: Uri) {
        stopVideoProcessing()
        cameraProvider?.unbindAll()
        cameraStarted = false
        videoMode = true
        currentVideoUri = uri
        tracker.reset()
        videoDetector.resetTiming()

        binding.permissionGroup.visibility = View.GONE
        binding.previewView.visibility = View.GONE
        binding.videoView.visibility = View.VISIBLE
        binding.liveCameraButton.visibility = View.VISIBLE
        binding.videoView.setVideoURI(uri)
        binding.videoView.setOnPreparedListener { player ->
            player.isLooping = true
            binding.videoView.start()
        }
        startVideoProcessing(uri)
    }

    private fun switchToLiveCamera() {
        stopVideoProcessing()
        analysisExecutor?.shutdownNow()
        videoMode = false
        currentVideoUri = null
        binding.videoView.stopPlayback()
        binding.videoView.visibility = View.GONE
        binding.previewView.visibility = View.VISIBLE
        binding.liveCameraButton.visibility = View.GONE
        tracker.reset()
        if (hasCameraPermission()) startCamera() else showPermissionUi()
    }

    private fun startVideoProcessing(uri: Uri) {
        val runId = ++videoRunId
        val executor = Executors.newSingleThreadExecutor()
        analysisExecutor?.shutdown()
        analysisExecutor = executor
        executor.execute {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                val durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
                val frameStepUs = 1_000_000L / VIDEO_ANALYSIS_FPS
                val rot = virtualVideoRotationMatrix()
                var tUs = 0L
                while (videoRunId == runId && !isDestroyed) {
                    val frame = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (frame != null) {
                        val result = videoDetector.processBitmap(frame, tUs * 1000L + 1L)
                        frame.recycle()
                        val fovX = if (result.uprightAspect >= 1f) fovLongDeg else fovShortDeg
                        val fovY = if (result.uprightAspect >= 1f) fovShortDeg else fovLongDeg
                        consumeAnalysis(result, rot, fovX, fovY, isVideo = true)
                    }
                    tUs += frameStepUs
                    if (tUs / 1000L >= durationMs) {
                        tUs = 0L
                        tracker.reset()
                        videoDetector.resetTiming()
                    }
                    Thread.sleep(1000L / VIDEO_ANALYSIS_FPS)
                }
            } catch (_: Exception) {
                if (videoRunId == runId) {
                    runOnUiThread { binding.statusText.text = getString(R.string.video_import_failed) }
                }
            } finally {
                retriever.release()
            }
        }
    }

    private fun stopVideoProcessing() {
        videoRunId++
    }

    private fun virtualVideoRotationMatrix(): FloatArray {
        val el = Math.toRadians(VIDEO_VIRTUAL_ELEVATION_DEG.toDouble())
        val c = kotlin.math.cos(el).toFloat()
        val s = kotlin.math.sin(el).toFloat()
        return floatArrayOf(
            1f, 0f, 0f,
            0f, -s, -c,
            0f, c, -s
        )
    }

    /**
     * Estimates the back camera's field of view from its focal length and
     * physical sensor size; keeps the defaults when unavailable.
     */
    private fun readFieldOfView() {
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: break
                val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: break
                fovLongDeg = fovDeg(sensor.width, focal)
                fovShortDeg = fovDeg(sensor.height, focal)
                break
            }
        } catch (_: Exception) {
            // Defaults stay in place.
        }
    }

    private fun fovDeg(sensorSizeMm: Float, focalMm: Float): Float =
        Math.toDegrees(2.0 * atan((sensorSizeMm / (2f * focalMm)).toDouble())).toFloat()

    private companion object {
        const val VIDEO_ANALYSIS_FPS = 12L
        const val VIDEO_VIRTUAL_ELEVATION_DEG = 45f
    }
}
