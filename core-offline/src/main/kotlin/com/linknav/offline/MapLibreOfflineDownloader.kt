package com.linknav.offline

import android.content.Context
import com.linknav.location.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.*

data class OfflineProgress(
    val completedResources: Long = 0,
    val requiredResources: Long = 0,
    val completedBytes: Long = 0,
    val complete: Boolean = false,
    val error: String? = null
)

class MapLibreOfflineDownloader(private val context: Context) {
    suspend fun download(
        id: String,
        name: String,
        bounds: OfflineBounds,
        minZoom: Double,
        maxZoom: Double,
        styleUrl: String = "https://tiles.openfreemap.org/styles/liberty"
    ): Flow<OfflineProgress> = withContext(Dispatchers.Main.immediate) {
        MapLibre.getInstance(context.applicationContext)
        val manager = OfflineManager.getInstance(context.applicationContext)
        val b = LatLngBounds.Builder()
            .include(LatLng(bounds.north, bounds.west))
            .include(LatLng(bounds.south, bounds.east))
            .build()
        val definition = OfflineTilePyramidRegionDefinition(styleUrl,b,minZoom,maxZoom,context.resources.displayMetrics.density,false)
        val metadata = JSONObject().put("id",id).put("name",name).put("createdAt",System.currentTimeMillis()).toString().toByteArray()
        callbackFlow {
            manager.createOfflineRegion(definition,metadata,object:OfflineManager.CreateOfflineRegionCallback{
                override fun onCreate(region: OfflineRegion) {
                    region.setObserver(object:OfflineRegion.OfflineRegionObserver{
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            trySend(OfflineProgress(status.completedResourceCount,status.requiredResourceCount,status.completedResourceSize,status.isComplete))
                            if(status.isComplete) close()
                        }
                        override fun onError(error: OfflineRegionError) { trySend(OfflineProgress(error="${error.reason}: ${error.message}")); close() }
                        override fun mapboxTileCountLimitExceeded(limit: Long) { trySend(OfflineProgress(error="Limite de tiles excedido: $limit")); close() }
                    })
                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }
                override fun onError(error: String) { trySend(OfflineProgress(error=error)); close() }
            })
            awaitClose { }
        }
    }
}
