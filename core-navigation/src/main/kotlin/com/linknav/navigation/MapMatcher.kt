package com.linknav.navigation

import com.linknav.location.GeoPoint
import kotlin.math.*

data class MatchedPoint(val point:GeoPoint,val errorM:Double,val segmentIndex:Int)

object MapMatcher {
    fun match(raw:GeoPoint, route:List<GeoPoint>):MatchedPoint? {
        if(route.size<2) return null
        var best:MatchedPoint?=null
        for(i in 0 until route.lastIndex){
            val c=project(raw,route[i],route[i+1])
            if(best==null || c.second<best!!.errorM) best=MatchedPoint(c.first,c.second,i)
        }
        return best
    }
    private fun project(p:GeoPoint,a:GeoPoint,b:GeoPoint):Pair<GeoPoint,Double>{
        val lat0=Math.toRadians(p.latitude); val kx=111320.0*cos(lat0); val ky=110540.0
        val ax=(a.longitude-p.longitude)*kx; val ay=(a.latitude-p.latitude)*ky
        val bx=(b.longitude-p.longitude)*kx; val by=(b.latitude-p.latitude)*ky
        val dx=bx-ax; val dy=by-ay; val den=dx*dx+dy*dy
        val t=if(den<1e-6)0.0 else (-(ax)*dx+(-ay)*dy)/den
        val u=t.coerceIn(0.0,1.0); val x=ax+u*dx; val y=ay+u*dy
        return GeoPoint(p.latitude+y/ky,p.longitude+x/kx,p.accuracyM,p.speedMps,p.bearingDeg,p.timestampMs) to hypot(x,y)
    }
}
