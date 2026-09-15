package com.linknav.routing

import com.linknav.location.GeoPoint

enum class TravelMode { CAR, MOTORCYCLE, BICYCLE, WALK }
data class RoutePreference(val avoidTolls:Boolean=false,val avoidHighways:Boolean=false,val avoidFerries:Boolean=false,val avoidUnpaved:Boolean=false,val blockedRoads:List<String> = emptyList())
data class RouteInstruction(val text:String,val distanceM:Double,val maneuver:String?=null)
data class Route(val id:String,val points:List<GeoPoint>,val distanceM:Double,val durationSec:Double,val instructions:List<RouteInstruction>,val label:String="")
interface RoutingProvider { suspend fun route(origin:GeoPoint,destination:GeoPoint,mode:TravelMode,preference:RoutePreference=RoutePreference(),alternatives:Int=3):List<Route> }
