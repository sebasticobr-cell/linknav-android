package com.linknav.navigation

import com.linknav.location.GeoPoint
import kotlin.math.*

data class MatchedPoint(
    val point:GeoPoint,
    val errorM:Double,
    val segmentIndex:Int,
    val segmentFraction:Double=0.0,
    val distanceAlongRouteM:Double=0.0
)

object MapMatcher {
    fun match(
        raw:GeoPoint,
        route:List<GeoPoint>,
        preferredSegment:Int?=null
    ):MatchedPoint? {
        if(route.size<2) return null
        val cumulative=DoubleArray(route.size)
        for(i in 1 until route.size) {
            cumulative[i]=cumulative[i-1]+distance(route[i-1],route[i])
        }

        fun evaluate(indices:IntRange):MatchedPoint? {
            var best:MatchedPoint?=null
            var bestScore=Double.POSITIVE_INFINITY
            for(i in indices) {
                if(i !in 0 until route.lastIndex) continue
                val p=project(raw,route[i],route[i+1])
                var score=p.errorM

                if(preferredSegment!=null && i<preferredSegment-2) {
                    score+=(preferredSegment-i-2)*8.0
                }

                if(raw.speedMps>1.2f && raw.bearingDeg.isFinite()) {
                    val segBearing=bearing(route[i],route[i+1])
                    val delta=abs(normalizeSigned(segBearing-raw.bearingDeg))
                    score+=delta.coerceAtMost(90f)/90f*8.0
                }

                if(score<bestScore) {
                    bestScore=score
                    val segLength=(cumulative[i+1]-cumulative[i]).coerceAtLeast(0.0)
                    best=MatchedPoint(
                        point=p.point,
                        errorM=p.errorM,
                        segmentIndex=i,
                        segmentFraction=p.fraction,
                        distanceAlongRouteM=cumulative[i]+segLength*p.fraction
                    )
                }
            }
            return best
        }

        val local=preferredSegment?.let {
            evaluate((it-4).coerceAtLeast(0)..(it+24).coerceAtMost(route.lastIndex-1))
        }
        if(local!=null && local.errorM<=80.0) return local

        return evaluate(0 until route.lastIndex)
    }

    private data class Projection(
        val point:GeoPoint,
        val errorM:Double,
        val fraction:Double
    )

    private fun project(p:GeoPoint,a:GeoPoint,b:GeoPoint):Projection {
        val lat0=Math.toRadians((a.latitude+b.latitude+p.latitude)/3.0)
        val kx=111320.0*cos(lat0)
        val ky=110540.0
        val ax=(a.longitude-p.longitude)*kx
        val ay=(a.latitude-p.latitude)*ky
        val bx=(b.longitude-p.longitude)*kx
        val by=(b.latitude-p.latitude)*ky
        val dx=bx-ax
        val dy=by-ay
        val den=dx*dx+dy*dy
        val t=if(den<1e-9) 0.0 else ((-ax)*dx+(-ay)*dy)/den
        val u=t.coerceIn(0.0,1.0)
        val x=ax+u*dx
        val y=ay+u*dy
        return Projection(
            point=GeoPoint(
                p.latitude+y/ky,
                p.longitude+x/kx,
                p.accuracyM,
                p.speedMps,
                p.bearingDeg,
                p.timestampMs
            ),
            errorM=hypot(x,y),
            fraction=u
        )
    }

    private fun distance(a:GeoPoint,b:GeoPoint):Double {
        val r=6371000.0
        val p1=Math.toRadians(a.latitude)
        val p2=Math.toRadians(b.latitude)
        val dp=p2-p1
        val dl=Math.toRadians(b.longitude-a.longitude)
        val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2)
        return 2*r*asin(sqrt(h))
    }

    private fun bearing(a:GeoPoint,b:GeoPoint):Float {
        val y=sin(Math.toRadians(b.longitude-a.longitude))*cos(Math.toRadians(b.latitude))
        val x=cos(Math.toRadians(a.latitude))*sin(Math.toRadians(b.latitude))-
            sin(Math.toRadians(a.latitude))*cos(Math.toRadians(b.latitude))*
            cos(Math.toRadians(b.longitude-a.longitude))
        return ((Math.toDegrees(atan2(y,x))+360.0)%360.0).toFloat()
    }

    private fun normalizeSigned(v:Float):Float=((v+540f)%360f)-180f
}
