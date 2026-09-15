package com.linknav.search

import com.linknav.location.GeoPoint

data class PlaceResult(val id:String,val name:String,val address:String,val location:GeoPoint,val category:String?=null)
interface SearchProvider { suspend fun search(query:String,near:GeoPoint?=null,limit:Int=10):List<PlaceResult> }
