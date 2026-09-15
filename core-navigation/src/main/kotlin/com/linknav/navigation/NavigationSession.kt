package com.linknav.navigation

import android.content.Context
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
    val accuracy:Int=0
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
    val wrongDirection:Boolean=false
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

    init {
        scope.launch {
            locationEngine.updates().collect { loc ->
                updateLocation(loc)
            }
        }
    }

    fun setRoute(route:Route?) {
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
                wrongDirection=false
            )
            return
        }
        val waypoints=simplifyWaypoints(route.points)
        _state.value=_state.value.copy(
            route=route,
            waypoints=waypoints,
            waypointIndex=0,
            nextWaypoint=waypoints.firstOrNull(),
            remainingM=route.distanceM,
            phase=NavigationPhase.NAVIGATING,
            progress=0f
        )
        _state.value.location.point?.let { updateNavigation(it) }
    }

    fun updateOrientation(
        headingDeg:Float,
        pitchDeg:Float,
        rollDeg:Float,
        accuracy:Int
    ) {
        val h=if(headingDeg.isNaN()) lastHeading else headingDeg
        if(!h.isNaN()) lastHeading=h
        _state.value=_state.value.copy(
            orientation=DeviceOrientation(h,pitchDeg,rollDeg,accuracy)
        )
        _state.value.location.point?.let { point ->
            if(_state.value.route!=null) updateNavigation(point)
        }
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

        val matched=MapMatcher.match(point,route.points)
        val segment=matched?.segmentIndex ?: 0
        val error=matched?.errorM ?: Double.POSITIVE_INFINITY
        val currentOnRoute=matched?.point ?: point

        val waypoints=s.waypoints.ifEmpty { simplifyWaypoints(route.points) }
        var index=if(forceWaypoint) nearestForwardWaypoint(point,waypoints) else s.waypointIndex
        while(index < waypoints.lastIndex && distance(point,waypoints[index]) < 11.0) {
            index++
        }
        val target=waypoints.getOrNull(index) ?: route.points.last()
        val toTarget=distance(point,target)
        val remaining=remainingAlongRoute(currentOnRoute,route.points,segment)
        val progress=if(route.distanceM>1.0)
            (1.0-(remaining/route.distanceM)).coerceIn(0.0,1.0).toFloat()
        else 1f

        val orientation=s.orientation
        val routeBearing=bearing(point,target)
        val relative=if(orientation.headingDeg.isNaN()) 0f
        else normalizeSigned(routeBearing-orientation.headingDeg)
        val wrong=point.speedMps>1.2f && abs(relative)>125f
        val instruction=instructionFor(
            point=point,
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
        val confidence=(accuracyScore*.5f+sensorScore*.28f+routeScore*.22f).coerceIn(.12f,.98f)

        val now=System.currentTimeMillis()
        val isOffRoute=error>45.0
        if(isOffRoute) {
            if(offRouteSince==0L) offRouteSince=now
        } else {
            offRouteSince=0L
        }

        val arrived=distance(point,route.points.last())<14.0
        val arriving=!arrived && remaining<65.0
        val phase=when {
            arrived -> NavigationPhase.ARRIVED
            now<rerouteCooldownUntil && s.phase==NavigationPhase.RECALCULATING -> NavigationPhase.RECALCULATING
            isOffRoute && offRouteSince>0L && now-offRouteSince>3500L -> NavigationPhase.OFF_ROUTE
            point.accuracyM.isFinite() && point.accuracyM>55f -> NavigationPhase.LOW_GPS_ACCURACY
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
            wrongDirection=wrong
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

    private fun nearestForwardWaypoint(point:GeoPoint,waypoints:List<GeoPoint>):Int {
        if(waypoints.isEmpty()) return 0
        return waypoints.indices.minByOrNull { distance(point,waypoints[it]) } ?: 0
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

    private fun remainingAlongRoute(
        current:GeoPoint,
        points:List<GeoPoint>,
        segment:Int
    ):Double {
        var total=distance(current,points[(segment+1).coerceAtMost(points.lastIndex)])
        for(i in (segment+1) until points.lastIndex) {
            total+=distance(points[i],points[i+1])
        }
        return total
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
    }
}
