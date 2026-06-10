package com.skyradar.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
    private var analysisExecutor: ExecutorService? = null
    private var cameraStarted = false
    private var permissionRequested = false

    // Field of view of the sensor's native landscape frame; refined from the
    // camera characteristics once available.
    private var fovLongDeg = 66f
    private var fovShortDeg = 52f

    private lateinit var compassDirs: Array<String>

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionUi()
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
        readFieldOfView()
    }

    override fun onResume() {
        super.onResume()
        orientation.start()
        when {
            hasCameraPermission() -> startCamera()
            !permissionRequested -> {
                permissionRequested = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> showPermissionUi()
        }
    }

    override fun onPause() {
        orientation.stop()
        super.onPause()
    }

    override fun onDestroy() {
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
        }, ContextCompat.getMainExecutor(this))
    }

    /** Runs on the analysis thread, once per camera frame. */
    private fun onAnalysis(result: AnalysisResult) {
        val rot = orientation.rotationMatrix
        val rotated = result.rotationDegrees % 180 != 0
        val fovX = if (rotated) fovShortDeg else fovLongDeg
        val fovY = if (rotated) fovLongDeg else fovShortDeg

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
            binding.statusText.text = buildStatus(snapshots.count { it.confirmed }, pointing, result)
        }
    }

    private fun buildStatus(targetCount: Int, pointing: FloatArray, result: AnalysisResult): String {
        val az = (pointing[0] + 360f) % 360f
        val el = pointing[1]
        val dir = compassDirs[(((az + 22.5f) / 45f).toInt()) % 8]
        val line1 = getString(
            R.string.status_line,
            dir, az.roundToInt() % 360, el.roundToInt(), targetCount, result.fps
        )
        val line2 = when {
            el < 15f -> getString(R.string.hint_point_sky)
            result.skyFraction < 0.35f -> getString(R.string.hint_view_blocked)
            !orientation.hasCompass -> getString(R.string.hint_no_compass)
            else -> getString(R.string.sky_quality, (result.skyFraction * 100).roundToInt())
        }
        return line1 + "\n" + line2
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
}
