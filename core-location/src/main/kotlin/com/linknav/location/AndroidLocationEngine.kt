package com.linknav.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class AndroidLocationEngine(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    fun updates(minTimeMs: Long = 1000L, minDistanceM: Float = 1.5f): Flow<LocationState> = callbackFlow {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { trySend(LocationState()); close(); return@callbackFlow }
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                val p = GeoPoint(l.latitude,l.longitude,l.accuracy,if(l.hasSpeed()) l.speed else 0f,if(l.hasBearing()) l.bearing else 0f,l.time)
                trySend(LocationState(p, l.accuracy > 35f, l.provider ?: "unknown"))
            }
            @Deprecated("legacy") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        providers.forEach { lm.requestLocationUpdates(it,minTimeMs,minDistanceM,listener) }
        awaitClose { lm.removeUpdates(listener) }
    }
}
