package com.linknav.ar

import android.content.Context
import android.hardware.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class OrientationState(
    val headingDeg:Float=Float.NaN,
    val pitchDeg:Float=0f,
    val rollDeg:Float=0f,
    val accuracy:Int=0
)

class SensorFusionManager(context:Context) {
    private val sm=context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var filteredHeading=Float.NaN
    private var filteredPitch=0f
    private var filteredRoll=0f

    fun orientations():Flow<OrientationState> = callbackFlow {
        val rotation=sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: run {
                close()
                return@callbackFlow
            }

        var accuracy=0
        val listener=object:SensorEventListener {
            override fun onAccuracyChanged(sensor:Sensor?,value:Int) {
                accuracy=value
            }

            override fun onSensorChanged(event:SensorEvent) {
                val matrix=FloatArray(9)
                SensorManager.getRotationMatrixFromVector(matrix,event.values)
                val orientation=FloatArray(3)
                SensorManager.getOrientation(matrix,orientation)

                val rawHeading=((Math.toDegrees(orientation[0].toDouble())+360.0)%360.0).toFloat()
                val rawPitch=Math.toDegrees(orientation[1].toDouble()).toFloat()
                val rawRoll=Math.toDegrees(orientation[2].toDouble()).toFloat()

                filteredHeading=if(filteredHeading.isNaN()) rawHeading
                else circularBlend(filteredHeading,rawHeading,.16f)
                filteredPitch=filteredPitch+(rawPitch-filteredPitch)*.18f
                filteredRoll=filteredRoll+(rawRoll-filteredRoll)*.18f

                trySend(
                    OrientationState(
                        headingDeg=filteredHeading,
                        pitchDeg=filteredPitch,
                        rollDeg=filteredRoll,
                        accuracy=accuracy
                    )
                )
            }
        }

        sm.registerListener(listener,rotation,SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sm.unregisterListener(listener) }
    }

    private fun circularBlend(a:Float,b:Float,t:Float):Float {
        val delta=((b-a+540f)%360f)-180f
        return (a+delta*t+360f)%360f
    }
}
