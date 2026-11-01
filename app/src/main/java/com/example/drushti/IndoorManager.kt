package com.example.drushti

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

class IndoorManager(
    private val context: Context,
    private val onKeepWalking: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private var stepCount = 0
    var isIndoorActive = false
        private set

    fun startIndoorGuidance() {
        isIndoorActive = true
        stepCount = 0
        if (stepSensor != null) {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d("Drishti", "Step detector sensor registered.")
        } else {
            Log.d("Drishti", "Step detector sensor not available on device.")
        }
    }

    fun stopIndoorGuidance() {
        isIndoorActive = false
        stepCount = 0
        try {
            sensorManager.unregisterListener(this)
            Log.d("Drishti", "Step detector sensor unregistered.")
        } catch (e: Exception) {
            Log.e("Drishti", "Unregister step detector error", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isIndoorActive) return
        if (event?.sensor?.type == Sensor.TYPE_STEP_DETECTOR) {
            stepCount++
            Log.d("Drishti", "Step count: $stepCount")
            if (stepCount > 0 && stepCount % 5 == 0) {
                onKeepWalking()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
