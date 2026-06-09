package com.skyradar.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Publishes the device-to-world rotation matrix from the rotation vector
 * sensor. World axes: X = east, Y = (magnetic) north, Z = up.
 *
 * Falls back to the game rotation vector when no magnetometer-backed sensor
 * exists; tracking still works then, but azimuth is relative, not compass north.
 */
class OrientationProvider(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    /** True when azimuth is referenced to compass north. */
    val hasCompass: Boolean =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null

    @Volatile
    var rotationMatrix: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        private set

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Some devices deliver 5 values; getRotationMatrixFromVector expects <= 4.
        val values = if (event.values.size > 4) event.values.copyOf(4) else event.values
        val m = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(m, values)
        rotationMatrix = m
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
