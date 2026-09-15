package com.linknav.ar

import com.linknav.location.GeoPoint
import kotlin.math.*

data class ArCue(val relativeBearingDeg:Float,val distanceM:Double,val text:String,val confidence:Float)
object ArDirectionMath {
    fun cue(from:GeoPoint,to:GeoPoint,deviceHeadingDeg:Float,label:String):ArCue {
        val y=sin(Math.toRadians(to.longitude-from.longitude))*cos(Math.toRadians(to.latitude))
        val x=cos(Math.toRadians(from.latitude))*sin(Math.toRadians(to.latitude))-sin(Math.toRadians(from.latitude))*cos(Math.toRadians(to.latitude))*cos(Math.toRadians(to.longitude-from.longitude))
        val bearing=((Math.toDegrees(atan2(y,x))+360.0)%360.0).toFloat()
        val rel=((bearing-deviceHeadingDeg+540f)%360f)-180f
        val d=haversine(from,to)
        return ArCue(rel,d,label, if(from.accuracyM.isFinite()) (1f-(from.accuracyM/80f)).coerceIn(.15f,.95f) else .5f)
    }
    private fun haversine(a:GeoPoint,b:GeoPoint):Double { val R=6371000.0; val p1=Math.toRadians(a.latitude); val p2=Math.toRadians(b.latitude); val dp=p2-p1; val dl=Math.toRadians(b.longitude-a.longitude); val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2); return 2*R*asin(sqrt(h)) }
}
