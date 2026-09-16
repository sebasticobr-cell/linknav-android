package com.linknav.ar

import android.content.Context
import android.hardware.*
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.*

data class OrientationState(
    val headingDeg:Float=Float.NaN,
    val pitchDeg:Float=0f,
    val rollDeg:Float=0f,
    val accuracy:Int=0,
    val forwardEast:Float=0f,
    val forwardNorth:Float=1f,
    val forwardUp:Float=0f,
    val rightEast:Float=1f,
    val rightNorth:Float=0f,
    val rightUp:Float=0f,
    val upEast:Float=0f,
    val upNorth:Float=0f,
    val upUp:Float=1f,
    val timestampNs:Long=0L
)

private data class Vec3(val x:Float,val y:Float,val z:Float) {
    fun normalized():Vec3 {
        val n=sqrt(x*x+y*y+z*z).coerceAtLeast(1e-6f)
        return Vec3(x/n,y/n,z/n)
    }
    fun blend(to:Vec3,t:Float)=Vec3(
        x+(to.x-x)*t,
        y+(to.y-y)*t,
        z+(to.z-z)*t
    ).normalized()
    fun cross(o:Vec3)=Vec3(
        y*o.z-z*o.y,
        z*o.x-x*o.z,
        x*o.y-y*o.x
    )
}

class SensorFusionManager(context:Context) {
    private val app=context.applicationContext
    private val sm=app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val dm=app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private var filteredForward:Vec3?=null
    private var filteredRight:Vec3?=null
    private var lastTimestampNs=0L
    private var lastHeading=Float.NaN

    fun orientations():Flow<OrientationState> = callbackFlow {
        val rotation=sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelerometer=sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer=sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if(rotation==null && (accelerometer==null || magnetometer==null)) {
            close()
            return@callbackFlow
        }

        var accuracy=0
        var gravity:FloatArray?=null
        var magnetic:FloatArray?=null

        fun lowPass(old:FloatArray?,fresh:FloatArray,alpha:Float=.18f):FloatArray {
            if(old==null) return fresh.clone()
            val out=old.clone()
            for(i in out.indices) {
                out[i]+=alpha*(fresh[i]-out[i])
            }
            return out
        }

        fun processRawMatrix(raw:FloatArray,timestampNs:Long) {
            val remapped=FloatArray(9)
            @Suppress("DEPRECATION")
            val displayRotation=dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                ?: Surface.ROTATION_0

            val axes=when(displayRotation) {
                Surface.ROTATION_90 ->
                    SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 ->
                    SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 ->
                    SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else ->
                    SensorManager.AXIS_X to SensorManager.AXIS_Y
            }

            if(!SensorManager.remapCoordinateSystem(raw,axes.first,axes.second,remapped)) {
                return
            }

            // Rotation matrix columns are display-device axes expressed in
            // world coordinates. Back camera looks along -Z.
            val rawRight=Vec3(remapped[0],remapped[3],remapped[6]).normalized()
            val rawForward=Vec3(-remapped[2],-remapped[5],-remapped[8]).normalized()

            val dt=if(lastTimestampNs==0L) .016
            else ((timestampNs-lastTimestampNs).coerceAtLeast(1L)/1_000_000_000.0)
                .coerceIn(.004,.080)
            lastTimestampNs=timestampNs

            // One low-latency smoothing stage. It converges to the real
            // orientation; it never scales down the final angle.
            val alpha=(1.0-exp(-dt/.022)).toFloat().coerceIn(.28f,.90f)

            val forward=filteredForward?.blend(rawForward,alpha) ?: rawForward
            var right=filteredRight?.blend(rawRight,alpha) ?: rawRight
            var up=right.cross(forward).normalized()
            right=forward.cross(up).normalized()
            up=right.cross(forward).normalized()

            filteredForward=forward
            filteredRight=right

            val horizontal=hypot(forward.x,forward.y)
            val heading=if(horizontal>.08f) {
                ((Math.toDegrees(atan2(forward.x,forward.y).toDouble())+360.0)%360.0).toFloat()
            } else lastHeading
            if(!heading.isNaN()) lastHeading=heading

            val pitch=Math.toDegrees(
                asin(forward.z.coerceIn(-1f,1f).toDouble())
            ).toFloat()
            val roll=Math.toDegrees(
                atan2(right.z,up.z).toDouble()
            ).toFloat()

            trySend(
                OrientationState(
                    headingDeg=heading,
                    pitchDeg=pitch,
                    rollDeg=roll,
                    accuracy=accuracy,
                    forwardEast=forward.x,
                    forwardNorth=forward.y,
                    forwardUp=forward.z,
                    rightEast=right.x,
                    rightNorth=right.y,
                    rightUp=right.z,
                    upEast=up.x,
                    upNorth=up.y,
                    upUp=up.z,
                    timestampNs=timestampNs
                )
            )
        }

        val listener=object:SensorEventListener {
            override fun onAccuracyChanged(sensor:Sensor?,value:Int) {
                if(
                    sensor?.type==Sensor.TYPE_ROTATION_VECTOR ||
                    sensor?.type==Sensor.TYPE_MAGNETIC_FIELD
                ) accuracy=value
            }

            override fun onSensorChanged(event:SensorEvent) {
                if(rotation!=null && event.sensor.type==Sensor.TYPE_ROTATION_VECTOR) {
                    val raw=FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(raw,event.values)
                    processRawMatrix(raw,event.timestamp)
                    return
                }

                if(rotation==null) {
                    when(event.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER ->
                            gravity=lowPass(gravity,event.values)
                        Sensor.TYPE_MAGNETIC_FIELD ->
                            magnetic=lowPass(magnetic,event.values)
                    }
                    val g=gravity
                    val m=magnetic
                    if(g!=null && m!=null) {
                        val raw=FloatArray(9)
                        if(SensorManager.getRotationMatrix(raw,null,g,m)) {
                            processRawMatrix(raw,event.timestamp)
                        }
                    }
                }
            }
        }

        if(rotation!=null) {
            sm.registerListener(listener,rotation,SensorManager.SENSOR_DELAY_GAME)
        } else {
            sm.registerListener(listener,accelerometer,SensorManager.SENSOR_DELAY_GAME)
            sm.registerListener(listener,magnetometer,SensorManager.SENSOR_DELAY_GAME)
        }

        awaitClose { sm.unregisterListener(listener) }
    }
}
