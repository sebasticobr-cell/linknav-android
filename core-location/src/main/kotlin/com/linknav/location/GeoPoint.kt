package com.linknav.location

data class GeoPoint(val latitude: Double, val longitude: Double, val accuracyM: Float = Float.NaN, val speedMps: Float = 0f, val bearingDeg: Float = 0f, val timestampMs: Long = System.currentTimeMillis())

data class LocationState(val point: GeoPoint? = null, val gpsWeak: Boolean = true, val provider: String = "none")
