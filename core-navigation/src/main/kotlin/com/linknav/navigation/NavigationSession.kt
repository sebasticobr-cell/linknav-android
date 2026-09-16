package com.linknav.navigation

import android.content.Context
import android.hardware.GeomagneticField
import com.linknav.location.AndroidLocationEngine
import com.linknav.location.GeoPoint
import com.linknav.location.LocationState
import com.linknav.routing.Route
import com.linknav.routing.RoutingProvider
import com.linknav.routing.TravelMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*

enum class NavigationPhase {
    IDLE, LOCATING, NAVIGATING, LOW_GPS_ACCURACY, OFF_ROUTE,
    RECALCULATING, ARRIVING, ARRIVED
}

data class DeviceOrientation(
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

data class NavigationSessionState(
    val phase:NavigationPhase=NavigationPhase.LOCATING,
    val location:LocationState=LocationState(),
    val orientation:DeviceOrientation=DeviceOrientation(),
    val route:Route?=null,
    val waypoints:List<GeoPoint> = emptyList(),
    val waypointIndex:Int=0,
    val nextWaypoint:GeoPoint?=null,
    val distanceToWaypointM:Double=0.0,
    val remainingM:Double=0.0,
    val offRouteErrorM:Double=0.0,
    val instruction:String="",
    val confidence:Float=0f,
    val progress:Float=0f,
    val wrongDirection:Boolean=false,
    val matchedPoint:GeoPoint?=null,
    val matchedSegmentIndex:Int=0,
    val routeProgressM:Double=0.0
)

class NavigationSession(
    context:Context,
    private val routingProvider:RoutingProvider
) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val locationEngine=AndroidLocationEngine(context.applicationContext)
    private val _state=MutableStateFlow(NavigationSessionState())
    val state:StateFlow<NavigationSessionState> = _state.asStateFlow()

    private var offRouteSince=0L
    private var rerouteCooldownUntil=0L
    private var lastHeading=Float.NaN
    private var lastMatchedSegment=-1
    private var lastRouteProgressM=0.0

    private var declinationDeg=0f
    private var declinationAtMs=0L
    private var declinationLat=Double.NaN
    private var declinationLon=Double.NaN

    init {
        scope.launch {
            locationEngine.updates().collect { loc ->
                updateLocation(loc)
            }
        }
    }

    fun setRoute(route:Route?) {
        lastMatchedSegment=-1
        lastRouteProgressM=0.0
        if(route==null){
            _state.value=_state.value.copy(
                phase=if(_state.value.location.point==null) NavigationPhase.LOCATING else NavigationPhase.IDLE,
                route=null,
                waypoints=emptyList(),
                waypointIndex=0,
                nextWaypoint=null,
                distanceToWaypointM=0.0,
                remainingM=0.0,
                instruction="",
                progress=0f,
                wrongDirection=false,
                matchedPoint=null,
                matchedSegmentIndex=0,
                routeProgressM=0.0
            )
            return
        }
        val waypoints=simplifyWaypoints(route.points)
        _state.value=_state.value.copy(
            route=route,
            waypoints=waypoints,
            waypointIndex=0,
            nextWaypoint=waypoints.firstOrNull(),
            remainingM=polylineLength(route.points),
            phase=NavigationPhase.NAVIGATING,
            progress=0f,
            matchedPoint=null,
            matchedSegmentIndex=0,
            routeProgressM=0.0
        )
        _state.value.location.point?.let { updateNavigation(it) }
    }

    fun updateOrientation(
        headingDeg:Float,
        pitchDeg:Float,
        rollDeg:Float,
        accuracy:Int,
        forwardEast:Float=0f,
        forwardNorth:Float=1f,
        forwardUp:Float=0f,
        rightEast:Float=1f,
        rightNorth:Float=0f,
        rightUp:Float=0f,
        upEast:Float=0f,
        upNorth:Float=0f,
        upUp:Float=1f,
        timestampNs:Long=0L
    ) {
        val declination=currentDeclination()
        val h=when {
            headingDeg.isNaN() -> lastHeading
            else -> normalize360(headingDeg+declination)
        }
        if(!h.isNaN()) lastHeading=h

        fun trueHorizontal(e:Float,n:Float):Pair<Float,Float> {
            val d=Math.toRadians(declination.toDouble())
            return (
                e*cos(d)+n*sin(d)
            ).toFloat() to (
                -e*sin(d)+n*cos(d)
            ).toFloat()
        }

        val f=trueHorizontal(forwardEast,forwardNorth)
        val r=trueHorizontal(rightEast,rightNorth)
        val u=trueHorizontal(upEast,upNorth)

        _state.value=_state.value.copy(
            orientation=DeviceOrientation(
                headingDeg=h,
                pitchDeg=pitchDeg,
                rollDeg=rollDeg,
                accuracy=accuracy,
                forwardEast=f.first,
                forwardNorth=f.second,
                forwardUp=forwardUp,
                rightEast=r.first,
                rightNorth=r.second,
                rightUp=rightUp,
                upEast=u.first,
                upNorth=u.second,
                upUp=upUp,
                timestampNs=timestampNs
            )
        )
    }

    fun recenterNavigation() {
        val point=_state.value.location.point ?: return
        offRouteSince=0L
        updateNavigation(point,forceWaypoint=true)
    }

    fun clearRoute()=setRoute(null)

    fun close() {
        scope.cancel()
    }

    private fun updateLocation(loc:LocationState) {
        val current=_state.value
        _state.value=current.copy(
            location=loc,
            phase=when {
                loc.point==null -> NavigationPhase.LOCATING
                current.route==null -> NavigationPhase.IDLE
                loc.gpsWeak -> NavigationPhase.LOW_GPS_ACCURACY
                else -> current.phase
            }
        )
        loc.point?.let { updateNavigation(it) }
    }

    private fun updateNavigation(point:GeoPoint,forceWaypoint:Boolean=false) {
        val s=_state.value
        val route=s.route ?: return
        if(route.points.size<2) return

        val matched=MapMatcher.match(
            point,
            route.points,
            lastMatchedSegment.takeIf { it>=0 }
        )
        val segment=matched?.segmentIndex ?: lastMatchedSegment.coerceAtLeast(0)
        val error=matched?.errorM ?: Double.POSITIVE_INFINITY
        val currentOnRoute=matched?.point ?: point
        val total=polylineLength(route.points).coerceAtLeast(1.0)

        val rawProgress=matched?.distanceAlongRouteM ?: lastRouteProgressM
        val allowedBacktrack=if(point.speedMps<.8f) 3.0 else 7.0
        val stabilized=if(lastMatchedSegment>=0)
            rawProgress.coerceAtLeast(lastRouteProgressM-allowedBacktrack)
        else rawProgress

        if(lastMatchedSegment<0 || stabilized>=lastRouteProgressM-2.0) {
            lastRouteProgressM=max(lastRouteProgressM,stabilized)
        } else {
            lastRouteProgressM=stabilized
        }
        lastRouteProgressM=lastRouteProgressM.coerceIn(0.0,total)
        if(matched!=null) lastMatchedSegment=matched.segmentIndex

        val waypoints=s.waypoints.ifEmpty { simplifyWaypoints(route.points) }
        var index=if(forceWaypoint) {
            nearestForwardWaypointByRoute(lastRouteProgressM,waypoints,route.points)
        } else s.waypointIndex.coerceIn(0,waypoints.lastIndex.coerceAtLeast(0))

        while(index<waypoints.lastIndex) {
            val waypointProgress=routeDistanceOfPoint(waypoints[index],route.points)
            if(waypointProgress-lastRouteProgressM>=11.0) break
            index++
        }

        val target=waypoints.getOrNull(index) ?: route.points.last()
        val targetProgress=routeDistanceOfPoint(target,route.points)
        val toTarget=(targetProgress-lastRouteProgressM).coerceAtLeast(0.0)
        val remaining=(total-lastRouteProgressM).coerceAtLeast(0.0)
        val progress=(lastRouteProgressM/total).coerceIn(0.0,1.0).toFloat()

        val orientation=s.orientation
        val routeBearing=bearingAlongRoute(route.points,segment)
        val relative=if(orientation.headingDeg.isNaN()) 0f
        else normalizeSigned(routeBearing-orientation.headingDeg)
        val wrong=point.speedMps>1.2f && abs(relative)>125f

        val instruction=instructionFor(
            point=currentOnRoute,
            target=target,
            next=waypoints.getOrNull(index+1),
            distanceM=toTarget
        )

        val accuracyScore=if(point.accuracyM.isFinite())
            (1f-point.accuracyM.coerceAtMost(100f)/110f).coerceIn(.12f,.97f)
        else .4f
        val sensorScore=when {
            orientation.headingDeg.isNaN() -> .35f
            orientation.accuracy>=3 -> .98f
            orientation.accuracy==2 -> .82f
            orientation.accuracy==1 -> .62f
            else -> .45f
        }
        val routeScore=(1f-(error.coerceAtMost(80.0)/90.0).toFloat()).coerceIn(.2f,1f)
        val confidence=(accuracyScore*.46f+sensorScore*.30f+routeScore*.24f).coerceIn(.12f,.98f)

        val now=System.currentTimeMillis()
        val offRouteThreshold=max(32.0,(point.accuracyM.takeIf { it.isFinite() } ?: 12f)*2.2)
        val isOffRoute=error>offRouteThreshold
        if(isOffRoute) {
            if(offRouteSince==0L) offRouteSince=now
        } else {
            offRouteSince=0L
        }

        val arrived=distance(point,route.points.last())<14.0
        val arriving=!arrived && remaining<65.0
        val phase=when {
            arrived -> NavigationPhase.ARRIVED
            now<rerouteCooldownUntil && s.phase==NavigationPhase.RECALCULATING ->
                NavigationPhase.RECALCULATING
            isOffRoute && offRouteSince>0L && now-offRouteSince>3500L ->
                NavigationPhase.OFF_ROUTE
            point.accuracyM.isFinite() && point.accuracyM>55f ->
                NavigationPhase.LOW_GPS_ACCURACY
            arriving -> NavigationPhase.ARRIVING
            else -> NavigationPhase.NAVIGATING
        }

        _state.value=s.copy(
            phase=phase,
            location=s.location.copy(point=point),
            waypoints=waypoints,
            waypointIndex=index,
            nextWaypoint=target,
            distanceToWaypointM=toTarget,
            remainingM=remaining,
            offRouteErrorM=error,
            instruction=instruction,
            confidence=confidence,
            progress=progress,
            wrongDirection=wrong,
            matchedPoint=currentOnRoute,
            matchedSegmentIndex=segment,
            routeProgressM=lastRouteProgressM
        )

        if(phase==NavigationPhase.OFF_ROUTE && now>=rerouteCooldownUntil) {
            reroute(point,route.points.last())
        }
    }

    private fun reroute(from:GeoPoint,destination:GeoPoint) {
        rerouteCooldownUntil=System.currentTimeMillis()+15_000L
        _state.value=_state.value.copy(phase=NavigationPhase.RECALCULATING)
        scope.launch(Dispatchers.IO) {
            val newRoute=runCatching {
                routingProvider.route(from,destination,TravelMode.CAR,alternatives=1).firstOrNull()
            }.getOrNull()

            withContext(Dispatchers.Default) {
                if(newRoute!=null) {
                    offRouteSince=0L
                    setRoute(newRoute)
                } else {
                    _state.value=_state.value.copy(phase=NavigationPhase.OFF_ROUTE)
                }
            }
        }
    }

    private fun simplifyWaypoints(points:List<GeoPoint>):List<GeoPoint> {
        if(points.size<=2) return points
        val out=mutableListOf(points.first())
        var last=points.first()
        var accumulated=0.0
        for(i in 1 until points.lastIndex) {
            val d=distance(last,points[i])
            accumulated+=d
            if(accumulated>=34.0 || turnMagnitude(points,i)>=28.0) {
                out+=points[i]
                last=points[i]
                accumulated=0.0
            }
        }
        if(out.last()!=points.last()) out+=points.last()
        return out
    }

    private fun nearestForwardWaypointByRoute(
        progressM:Double,
        waypoints:List<GeoPoint>,
        route:List<GeoPoint>
    ):Int {
        if(waypoints.isEmpty()) return 0
        return waypoints.indices.firstOrNull {
            routeDistanceOfPoint(waypoints[it],route)>=progressM-3.0
        } ?: waypoints.lastIndex
    }

    private fun routeDistanceOfPoint(point:GeoPoint,route:List<GeoPoint>):Double {
        if(route.isEmpty()) return 0.0
        val exact=route.indexOf(point)
        val index=if(exact>=0) exact else route.indices.minByOrNull {
            distance(route[it],point)
        } ?: 0
        var total=0.0
        for(i in 0 until index) total+=distance(route[i],route[i+1])
        return total
    }

    private fun bearingAlongRoute(points:List<GeoPoint>,segment:Int):Float {
        if(points.size<2) return 0f
        val i=segment.coerceIn(0,points.lastIndex-1)
        return bearing(points[i],points[i+1])
    }

    private fun instructionFor(
        point:GeoPoint,
        target:GeoPoint,
        next:GeoPoint?,
        distanceM:Double
    ):String {
        if(next==null) {
            return if(distanceM<18) "Você está chegando ao destino"
            else "Continue por ${distanceM.toInt()} m"
        }
        val incoming=bearing(point,target)
        val outgoing=bearing(target,next)
        val delta=normalizeSigned(outgoing-incoming)
        val prefix=when {
            delta>55f -> "Vire à direita"
            delta>25f -> "Mantenha-se à direita"
            delta< -55f -> "Vire à esquerda"
            delta< -25f -> "Mantenha-se à esquerda"
            else -> "Siga em frente"
        }
        return "$prefix em ${distanceM.toInt()} m"
    }

    private fun turnMagnitude(points:List<GeoPoint>,i:Int):Double {
        if(i<=0 || i>=points.lastIndex) return 0.0
        val a=bearing(points[i-1],points[i])
        val b=bearing(points[i],points[i+1])
        return abs(normalizeSigned(b-a).toDouble())
    }

    private fun currentDeclination():Float {
        val p=_state.value.location.point ?: return 0f
        val now=System.currentTimeMillis()
        val moved=if(declinationLat.isFinite() && declinationLon.isFinite()) {
            distance(
                GeoPoint(declinationLat,declinationLon),
                GeoPoint(p.latitude,p.longitude)
            )
        } else Double.POSITIVE_INFINITY

        if(now-declinationAtMs>600_000L || moved>1000.0 || !declinationLat.isFinite()) {
            declinationDeg=runCatching {
                GeomagneticField(
                    p.latitude.toFloat(),
                    p.longitude.toFloat(),
                    0f,
                    now
                ).declination
            }.getOrDefault(0f)
            declinationAtMs=now
            declinationLat=p.latitude
            declinationLon=p.longitude
        }
        return declinationDeg
    }

    companion object {
        fun distance(a:GeoPoint,b:GeoPoint):Double {
            val r=6371000.0
            val p1=Math.toRadians(a.latitude)
            val p2=Math.toRadians(b.latitude)
            val dp=p2-p1
            val dl=Math.toRadians(b.longitude-a.longitude)
            val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2)
            return 2*r*asin(sqrt(h))
        }

        fun bearing(a:GeoPoint,b:GeoPoint):Float {
            val y=sin(Math.toRadians(b.longitude-a.longitude))*cos(Math.toRadians(b.latitude))
            val x=cos(Math.toRadians(a.latitude))*sin(Math.toRadians(b.latitude))-
                sin(Math.toRadians(a.latitude))*cos(Math.toRadians(b.latitude))*
                cos(Math.toRadians(b.longitude-a.longitude))
            return ((Math.toDegrees(atan2(y,x))+360.0)%360.0).toFloat()
        }

        fun normalizeSigned(value:Float):Float =
            ((value+540f)%360f)-180f

        fun polylineLength(points:List<GeoPoint>):Double {
            var total=0.0
            for(i in 0 until points.lastIndex) total+=distance(points[i],points[i+1])
            return total
        }

        private fun normalize360(value:Float):Float=((value%360f)+360f)%360f
    }
}
