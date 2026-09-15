package com.linknav.navigation

import com.linknav.location.GeoPoint
import com.linknav.routing.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.*

data class NavigationState(val route:Route?=null,val current:GeoPoint?=null,val remainingM:Double=0.0,val offRoute:Boolean=false,val instruction:String="")

class NavigationEngine {
    private val _state=MutableStateFlow(NavigationState()); val state:StateFlow<NavigationState> = _state
    fun start(route:Route){ _state.value=NavigationState(route=route,remainingM=route.distanceM,instruction=route.instructions.firstOrNull()?.text.orEmpty()) }
    fun stop(){ _state.value=NavigationState() }
    fun update(point:GeoPoint){
        val r=_state.value.route ?: return
        if(r.points.isEmpty()) return
        val matched=MapMatcher.match(point,r.points)
        val current=if(matched!=null && matched.errorM<60.0) matched.point else point
        val error=matched?.errorM ?: Double.POSITIVE_INFINITY
        val dest=r.points.last(); val remaining=distance(current,dest)
        _state.value=_state.value.copy(current=current,remainingM=remaining,offRoute=error>45.0)
    }
    private fun distance(a:GeoPoint,b:GeoPoint):Double { val R=6371000.0; val p1=Math.toRadians(a.latitude); val p2=Math.toRadians(b.latitude); val dp=Math.toRadians(b.latitude-a.latitude); val dl=Math.toRadians(b.longitude-a.longitude); val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2); return 2*R*asin(sqrt(h)) }
}
