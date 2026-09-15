package com.linknav.location

import kotlin.math.*

class PositionSmoother(private val alpha:Double=0.35) {
    private var last:GeoPoint?=null
    fun accept(raw:GeoPoint):GeoPoint {
        val prev=last ?: return raw.also { last=it }
        val accuracyFactor=(1.0-(raw.accuracyM.coerceAtMost(80f)/100f)).coerceIn(.15,.9)
        val a=(alpha*accuracyFactor).coerceIn(.08,.75)
        val smoothed=raw.copy(latitude=prev.latitude+(raw.latitude-prev.latitude)*a,longitude=prev.longitude+(raw.longitude-prev.longitude)*a,bearingDeg=blendBearing(prev.bearingDeg,raw.bearingDeg,a.toFloat()))
        last=smoothed; return smoothed
    }
    fun predict(nowMs:Long=System.currentTimeMillis()):GeoPoint? {
        val p=last ?: return null
        val dt=((nowMs-p.timestampMs).coerceIn(0,5000))/1000.0
        val d=p.speedMps*dt
        if(d<.2) return p
        val R=6371000.0; val br=Math.toRadians(p.bearingDeg.toDouble()); val lat1=Math.toRadians(p.latitude); val lon1=Math.toRadians(p.longitude)
        val lat2=asin(sin(lat1)*cos(d/R)+cos(lat1)*sin(d/R)*cos(br))
        val lon2=lon1+atan2(sin(br)*sin(d/R)*cos(lat1),cos(d/R)-sin(lat1)*sin(lat2))
        return p.copy(latitude=Math.toDegrees(lat2),longitude=Math.toDegrees(lon2),timestampMs=nowMs)
    }
    private fun blendBearing(a:Float,b:Float,t:Float):Float { val delta=((b-a+540f)%360f)-180f; return (a+delta*t+360f)%360f }
}
