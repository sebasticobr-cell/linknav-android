package com.linknav.ar

import com.linknav.camera.CameraIntrinsics
import com.linknav.location.GeoPoint
import com.linknav.navigation.DeviceOrientation
import com.linknav.navigation.NavigationSession
import kotlin.math.*

data class RouteWorldSample(
    val distanceAheadM:Double,
    val eastM:Double,
    val northM:Double,
    val upM:Double,
    val tangentEast:Double,
    val tangentNorth:Double
)

data class ProjectedRouteSample(
    val distanceAheadM:Double,
    val screenX:Float,
    val screenY:Float,
    val depthM:Float,
    val pixelsPerMeter:Float,
    val rotationDeg:Float,
    val cameraX:Float,
    val cameraY:Float,
    val visible:Boolean
)

object RouteCameraProjection {
    private val defaultDistances=doubleArrayOf(
        3.5,5.0,6.8,9.0,11.8,15.2,19.5,24.5,30.5,38.0,47.0,58.0
    )

    fun resample(
        route:List<GeoPoint>,
        current:GeoPoint,
        progressM:Double,
        cameraHeightM:Double=1.55,
        distancesAhead:DoubleArray=defaultDistances
    ):List<RouteWorldSample> {
        if(route.size<2) return emptyList()
        val cumulative=cumulative(route)
        val total=cumulative.last()
        if(total<=0.1) return emptyList()

        val start=progressM.coerceIn(0.0,total)
        val requested=distancesAhead
            .map { start+it }
            .filter { it<=total+.001 }
            .toMutableList()

        if(requested.isEmpty() && total-start>.5) requested+=total

        return requested.mapNotNull { targetDistance ->
            val p=pointAt(route,cumulative,targetDistance) ?: return@mapNotNull null

            val tangentWindow=when {
                targetDistance-start<8.0 -> 1.2
                targetDistance-start<20.0 -> 2.0
                else -> 3.2
            }
            val before=pointAt(
                route,cumulative,(targetDistance-tangentWindow).coerceAtLeast(0.0)
            ) ?: p
            val after=pointAt(
                route,cumulative,(targetDistance+tangentWindow).coerceAtMost(total)
            ) ?: p

            val tangentEnu=enu(before,after)
            val tLen=hypot(tangentEnu.first,tangentEnu.second)
            if(tLen<1e-4) return@mapNotNull null

            // IMPORTANT: current is the real camera/GPS position, not the
            // map-matched point. Progress is still taken from map matching.
            // This preserves lateral offset instead of forcing the AR route
            // back to screen center.
            val local=enu(current,p)

            RouteWorldSample(
                distanceAheadM=(targetDistance-start).coerceAtLeast(0.0),
                eastM=local.first,
                northM=local.second,
                upM=-cameraHeightM,
                tangentEast=tangentEnu.first/tLen,
                tangentNorth=tangentEnu.second/tLen
            )
        }
    }

    fun project(
        samples:List<RouteWorldSample>,
        orientation:DeviceOrientation,
        intrinsics:CameraIntrinsics,
        viewportWidth:Float,
        viewportHeight:Float
    ):List<ProjectedRouteSample> {
        if(samples.isEmpty() || viewportWidth<=1f || viewportHeight<=1f) return emptyList()

        val hfov=Math.toRadians(
            intrinsics.horizontalFovDeg.toDouble().coerceIn(20.0,140.0)
        )
        val vfov=Math.toRadians(
            intrinsics.verticalFovDeg.toDouble().coerceIn(20.0,140.0)
        )
        val fx=(viewportWidth/2f/tan(hfov/2.0)).toFloat()
        val fy=(viewportHeight/2f/tan(vfov/2.0)).toFloat()
        val cx=viewportWidth/2f
        val cy=viewportHeight/2f

        fun camera(e:Double,n:Double,u:Double):Triple<Float,Float,Float> {
            val x=(
                e*orientation.rightEast+
                n*orientation.rightNorth+
                u*orientation.rightUp
            ).toFloat()
            val y=(
                e*orientation.upEast+
                n*orientation.upNorth+
                u*orientation.upUp
            ).toFloat()
            val z=(
                e*orientation.forwardEast+
                n*orientation.forwardNorth+
                u*orientation.forwardUp
            ).toFloat()
            return Triple(x,y,z)
        }

        return samples.map { sample ->
            val (x,y,z)=camera(sample.eastM,sample.northM,sample.upM)
            val inFront=z>.35f

            val sx=if(inFront) cx+fx*x/z else Float.NaN
            val sy=if(inFront) cy-fy*y/z else Float.NaN

            val tangentScale=when {
                sample.distanceAheadM<8.0 -> 1.2
                sample.distanceAheadM<20.0 -> 2.0
                else -> 3.0
            }
            val (tx,ty,tz)=camera(
                sample.eastM+sample.tangentEast*tangentScale,
                sample.northM+sample.tangentNorth*tangentScale,
                sample.upM
            )

            val angle=if(inFront && tz>.35f) {
                val sx2=cx+fx*tx/tz
                val sy2=cy-fy*ty/tz
                Math.toDegrees(
                    atan2(
                        (sx2-sx).toDouble(),
                        (sy-sy2).toDouble()
                    )
                ).toFloat()
            } else 0f

            ProjectedRouteSample(
                distanceAheadM=sample.distanceAheadM,
                screenX=sx,
                screenY=sy,
                depthM=z,
                pixelsPerMeter=if(inFront) fx/z else 0f,
                rotationDeg=angle,
                cameraX=x,
                cameraY=y,
                visible=inFront &&
                    sx.isFinite() && sy.isFinite() &&
                    sx>=-viewportWidth*.05f &&
                    sx<=viewportWidth*1.05f &&
                    sy>=-viewportHeight*.05f &&
                    sy<=viewportHeight*1.05f
            )
        }
    }

    private fun cumulative(route:List<GeoPoint>):DoubleArray {
        val out=DoubleArray(route.size)
        for(i in 1 until route.size) {
            out[i]=out[i-1]+NavigationSession.distance(route[i-1],route[i])
        }
        return out
    }

    private fun pointAt(
        route:List<GeoPoint>,
        cumulative:DoubleArray,
        distanceM:Double
    ):GeoPoint? {
        if(route.isEmpty()) return null
        if(distanceM<=0.0) return route.first()
        if(distanceM>=cumulative.last()) return route.last()

        var lo=0
        var hi=cumulative.lastIndex
        while(lo+1<hi) {
            val mid=(lo+hi)/2
            if(cumulative[mid]<=distanceM) lo=mid else hi=mid
        }

        val segmentLength=(cumulative[lo+1]-cumulative[lo]).coerceAtLeast(1e-6)
        val t=((distanceM-cumulative[lo])/segmentLength).coerceIn(0.0,1.0)
        val a=route[lo]
        val b=route[lo+1]

        return GeoPoint(
            latitude=a.latitude+(b.latitude-a.latitude)*t,
            longitude=a.longitude+(b.longitude-a.longitude)*t,
            accuracyM=a.accuracyM,
            speedMps=a.speedMps,
            bearingDeg=a.bearingDeg,
            timestampMs=a.timestampMs
        )
    }

    private fun enu(origin:GeoPoint,target:GeoPoint):Pair<Double,Double> {
        val meanLat=Math.toRadians((target.latitude+origin.latitude)/2.0)
        val north=(target.latitude-origin.latitude)*111132.92
        val east=(target.longitude-origin.longitude)*111412.84*cos(meanLat)
        return east to north
    }
}
