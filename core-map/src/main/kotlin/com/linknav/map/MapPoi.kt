package com.linknav.map

import com.linknav.location.GeoPoint

data class MapPoi(
    val id:String,
    val name:String,
    val category:String?,
    val point:GeoPoint
)
