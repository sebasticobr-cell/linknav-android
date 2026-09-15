package com.linknav.map

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.linknav.location.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val ROUTE_SOURCE="linknav-route-source"
private const val ROUTE_LAYER="linknav-route-layer"
private const val USER_SOURCE="linknav-user-source"
private const val USER_CIRCLE_LAYER="linknav-user-circle"
private const val USER_ARROW_LAYER="linknav-user-arrow"
private const val SAT_SOURCE="linknav-satellite-source"
private const val SAT_LAYER="linknav-satellite-layer"

@Composable
fun LinkNavMap(
    modifier: Modifier = Modifier,
    point: GeoPoint? = null,
    route: List<GeoPoint> = emptyList(),
    satellite: Boolean = false,
    styleUri: String = "https://tiles.openfreemap.org/styles/bright"
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
    var firstFix by remember { mutableStateOf(true) }

    fun configureStyle(style: Style) {
        if (style.getSource(ROUTE_SOURCE) == null) style.addSource(GeoJsonSource(ROUTE_SOURCE))
        if (style.getSource(USER_SOURCE) == null) style.addSource(GeoJsonSource(USER_SOURCE))

        if (style.getSource(SAT_SOURCE) == null) {
            val tiles = TileSet("2.2.0", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}")
            tiles.attribution = "Tiles © Esri — Source: Esri and the GIS User Community"
            style.addSource(RasterSource(SAT_SOURCE, tiles, 256))
            val raster = RasterLayer(SAT_LAYER, SAT_SOURCE).withProperties(rasterOpacity(if (satellite) 1f else 0f))
            val firstLabel = style.layers.firstOrNull { it is SymbolLayer }?.id
            if (firstLabel != null) style.addLayerBelow(raster, firstLabel) else style.addLayerAt(raster, 0)
        }

        if (style.getLayer(ROUTE_LAYER) == null) {
            style.addLayer(LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                lineWidth(7f), lineOpacity(.92f), lineColor("#2962FF")
            ))
        }
        if (style.getLayer(USER_CIRCLE_LAYER) == null) {
            style.addLayer(CircleLayer(USER_CIRCLE_LAYER, USER_SOURCE).withProperties(
                circleRadius(11f),
                circleColor("#1976D2"),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(3f)
            ))
        }
        if (style.getLayer(USER_ARROW_LAYER) == null) {
            style.addLayer(SymbolLayer(USER_ARROW_LAYER, USER_SOURCE).withProperties(
                textField("▲"),
                textSize(19f),
                textColor("#FFFFFF"),
                textAllowOverlap(true),
                textIgnorePlacement(true)
            ))
        }
        styleReady = true
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            mapView?.let { v ->
                when(event) {
                    Lifecycle.Event.ON_START -> v.onStart()
                    Lifecycle.Event.ON_RESUME -> v.onResume()
                    Lifecycle.Event.ON_PAUSE -> v.onPause()
                    Lifecycle.Event.ON_STOP -> v.onStop()
                    Lifecycle.Event.ON_DESTROY -> v.onDestroy()
                    else -> Unit
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); mapView?.onDestroy() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).also { v ->
                mapView = v
                v.onCreate(null)
                v.getMapAsync { map ->
                    map.uiSettings.isCompassEnabled = true
                    map.uiSettings.isAttributionEnabled = true
                    map.setStyle(Style.Builder().fromUri(styleUri)) { style -> configureStyle(style) }
                }
            }
        },
        update = { v ->
            v.getMapAsync { map ->
                val style = map.style ?: return@getMapAsync
                if (!styleReady) return@getMapAsync

                style.getLayerAs<RasterLayer>(SAT_LAYER)?.setProperties(rasterOpacity(if (satellite) 1f else 0f))

                point?.let { p ->
                    style.getSourceAs<GeoJsonSource>(USER_SOURCE)?.setGeoJson(Point.fromLngLat(p.longitude, p.latitude))
                    style.getLayerAs<SymbolLayer>(USER_ARROW_LAYER)?.setProperties(textRotate(p.bearingDeg))
                    if (firstFix) {
                        firstFix = false
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude, p.longitude), 16.5),
                            650
                        )
                    }
                }

                if (route.size >= 2) {
                    style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(
                        LineString.fromLngLats(route.map { Point.fromLngLat(it.longitude, it.latitude) })
                    )
                }
            }
        }
    )
}
