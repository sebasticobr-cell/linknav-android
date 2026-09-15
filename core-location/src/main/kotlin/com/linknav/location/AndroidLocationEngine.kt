package com.linknav.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class AndroidLocationEngine(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun hasPermission(): Boolean {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun updates(minTimeMs: Long = 650L, minDistanceM: Float = 0.5f): Flow<LocationState> = callbackFlow {
        val smoother = PositionSmoother(alpha = 0.48)
        var best: Location? = null

        trySend(LocationState())
        while (!hasPermission()) {
            delay(300)
        }

        fun acceptLocation(l: Location) {
            if (!l.latitude.isFinite() || !l.longitude.isFinite()) return
            val now = System.currentTimeMillis()
            val prev = best
            val accuracy = if (l.hasAccuracy()) l.accuracy else 120f
            val prevAccuracy = prev?.let { if (it.hasAccuracy()) it.accuracy else 120f } ?: Float.MAX_VALUE
            val prevFresh = prev != null && now - prev.time < 8_000L

            if (prevFresh && l.time + 2_000L < (prev?.time ?: 0L)) return
            if (prevFresh && l.provider == LocationManager.NETWORK_PROVIDER &&
                prev?.provider == LocationManager.GPS_PROVIDER &&
                accuracy > prevAccuracy + 18f) return

            if (!prevFresh || prev == null || accuracy <= prevAccuracy + 28f || l.provider == LocationManager.GPS_PROVIDER) {
                best = l
                val raw = GeoPoint(
                    latitude = l.latitude,
                    longitude = l.longitude,
                    accuracyM = accuracy,
                    speedMps = if (l.hasSpeed()) l.speed else 0f,
                    bearingDeg = if (l.hasBearing()) l.bearing else (prev?.bearing ?: 0f),
                    timestampMs = if (l.time > 0L) l.time else now
                )
                val stable = smoother.accept(raw)
                trySend(LocationState(stable, stable.accuracyM > 45f, l.provider ?: "unknown"))
            }
        }

        val enabled = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        enabled.mapNotNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }.sortedBy { if (it.hasAccuracy()) it.accuracy else 999f }
            .firstOrNull()
            ?.let(::acceptLocation)

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = acceptLocation(location)
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("legacy")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        enabled.forEach { provider ->
            runCatching {
                lm.requestLocationUpdates(provider, minTimeMs, minDistanceM, listener)
            }
        }

        awaitClose { runCatching { lm.removeUpdates(listener) } }
    }
}
