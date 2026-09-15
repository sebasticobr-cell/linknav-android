package com.linknav.ar

import android.content.Context
import android.hardware.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class DeviceOrientationEngine(context:Context) {
    private val sm=context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    fun headings():Flow<Float> = callbackFlow {
        val sensor=sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: run { close(); return@callbackFlow }
        val listener=object:SensorEventListener{
            override fun onSensorChanged(e:SensorEvent){ val r=FloatArray(9); SensorManager.getRotationMatrixFromVector(r,e.values); val o=FloatArray(3); SensorManager.getOrientation(r,o); val deg=((Math.toDegrees(o[0].toDouble())+360.0)%360.0).toFloat(); trySend(deg) }
            override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int)=Unit
        }
        sm.registerListener(listener,sensor,SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sm.unregisterListener(listener) }
    }
}
