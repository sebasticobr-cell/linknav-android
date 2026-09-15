package com.linknav.map

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import android.util.Log
import org.maplibre.android.MapLibre
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.linknav.location.GeoPoint
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val ROUTE_SOURCE="linknav-route-source"
private const val ROUTE_LAYER="linknav-route-layer"

@Composable
fun LinkNavMap(
    modifier: Modifier = Modifier,
    point: GeoPoint? = null,
    route: List<GeoPoint> = emptyList(),
    styleUri: String = "https://tiles.openfreemap.org/styles/liberty"
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val mapLibreReady = remember(context) {
        runCatching {
            MapLibre.getInstance(context.applicationContext)
            true
        }.getOrElse {
            Log.e("LINKNAV", "MapLibre initialization failed", it)
            false
        }
    }
    if (!mapLibreReady) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Mapa temporariamente indisponível")
        }
        return
    }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            mapView?.let { v -> when(event) {
                Lifecycle.Event.ON_START -> v.onStart()
                Lifecycle.Event.ON_RESUME -> v.onResume()
                Lifecycle.Event.ON_PAUSE -> v.onPause()
                Lifecycle.Event.ON_STOP -> v.onStop()
                Lifecycle.Event.ON_DESTROY -> v.onDestroy()
                else -> Unit
            } }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); mapView?.onDestroy() }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx -> MapView(ctx).also { v ->
            mapView=v; v.onCreate(null)
            v.getMapAsync { map -> map.setStyle(Style.Builder().fromUri(styleUri)) { style ->
                style.addSource(GeoJsonSource(ROUTE_SOURCE))
                style.addLayer(LineLayer(ROUTE_LAYER,ROUTE_SOURCE).withProperties(lineWidth(7f),lineOpacity(.88f),lineColor("#6750A4")))
                styleReady=true
            } }
        } },
        update = { v ->
            point?.let { p -> v.getMapAsync { map -> map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude,p.longitude),16.0),500) } }
            if(styleReady && route.size>=2){ v.getMapAsync { map -> map.style?.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(LineString.fromLngLats(route.map { Point.fromLngLat(it.longitude,it.latitude) })) } }
        }
    )
}
