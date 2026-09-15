package com.linknav.map

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
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
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val ROUTE_SOURCE="linknav-route-source"
private const val ROUTE_LAYER="linknav-route-layer"
private const val POI_SOURCE="linknav-poi-source"
private const val POI_DOT_LAYER="linknav-poi-dot-layer"
private const val POI_LABEL_LAYER="linknav-poi-label-layer"
private const val SAT_SOURCE="linknav-satellite-source"
private const val SAT_LAYER="linknav-satellite-layer"

@Composable
fun LinkNavMap(
    modifier: Modifier = Modifier,
    point: GeoPoint? = null,
    route: List<GeoPoint> = emptyList(),
    places: List<MapPoi> = emptyList(),
    satellite: Boolean = false,
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
    var firstFix by remember { mutableStateOf(true) }

    fun configureStyle(style: Style) {
        if (style.getSource(ROUTE_SOURCE) == null) style.addSource(GeoJsonSource(ROUTE_SOURCE))
        if (style.getSource(POI_SOURCE) == null) style.addSource(GeoJsonSource(POI_SOURCE))

        if (style.getSource(SAT_SOURCE) == null) {
            val tiles = TileSet(
                "2.2.0",
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
            )
            tiles.attribution = "Tiles © Esri — Source: Esri and the GIS User Community"
            tiles.setMinZoom(0f)
            // Intencionalmente 17: acima disso o MapLibre sobre-amplia o último
            // tile válido em vez de solicitar níveis que retornam 'Map data not yet available'.
            tiles.setMaxZoom(17f)

            style.addSource(RasterSource(SAT_SOURCE, tiles, 256))

            val raster = RasterLayer(SAT_LAYER, SAT_SOURCE).withProperties(
                rasterOpacity(if (satellite) 1f else 0f)
            )
            val firstLabel = style.layers.firstOrNull { it is SymbolLayer }?.id
            if (firstLabel != null) style.addLayerBelow(raster, firstLabel)
            else style.addLayerAt(raster, 0)
        }

        if (style.getLayer(ROUTE_LAYER) == null) {
            style.addLayer(
                LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                    lineWidth(7f),
                    lineOpacity(.94f),
                    lineColor("#2962FF")
                )
            )
        }

        if (style.getLayer(POI_DOT_LAYER) == null) {
            style.addLayer(
                CircleLayer(POI_DOT_LAYER, POI_SOURCE).withProperties(
                    circleRadius(4.5f),
                    circleColor("#1976D2"),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(1.5f)
                )
            )
        }

        if (style.getLayer(POI_LABEL_LAYER) == null) {
            style.addLayer(
                SymbolLayer(POI_LABEL_LAYER, POI_SOURCE).withProperties(
                    textField(Expression.get("name")),
                    textSize(12f),
                    textColor("#263238"),
                    textHaloColor("#FFFFFF"),
                    textHaloWidth(1.5f),
                    textOffset(arrayOf(0f, 1.1f)),
                    textAllowOverlap(false),
                    textIgnorePlacement(false)
                )
            )
        }

        styleReady = true
    }

    fun ensureLocation(map: org.maplibre.android.maps.MapLibreMap, style: Style, p:GeoPoint) {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if(!fine && !coarse) return

        val component = map.locationComponent
        if(!component.isLocationComponentActivated) {
            runCatching {
                component.activateLocationComponent(
                    LocationComponentActivationOptions.builder(context, style)
                        .useDefaultLocationEngine(false)
                        .build()
                )
                component.isLocationComponentEnabled = true
                component.renderMode = RenderMode.GPS
            }.onFailure {
                Log.e("LINKNAV","LocationComponent activation failed",it)
            }
        }

        if(component.isLocationComponentActivated) {
            val l=Location("linknav").apply {
                latitude=p.latitude
                longitude=p.longitude
                accuracy=p.accuracyM.coerceAtLeast(1f)
                bearing=p.bearingDeg
                speed=p.speedMps
                time=p.timestampMs.takeIf{it>0L} ?: System.currentTimeMillis()
            }
            runCatching {
                component.forceLocationUpdate(l)
                if(!component.isLocationComponentEnabled) component.isLocationComponentEnabled=true
                component.renderMode=RenderMode.GPS
            }
        }
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
        onDispose {
            lifecycle.removeObserver(observer)
            mapView?.onDestroy()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).also { v ->
                mapView=v
                v.onCreate(null)
                v.getMapAsync { map ->
                    map.uiSettings.isCompassEnabled=true
                    map.uiSettings.isAttributionEnabled=true
                    map.setMaxZoomPreference(22.0)
                    map.setStyle(Style.Builder().fromUri(styleUri)) { style ->
                        configureStyle(style)
                    }
                }
            }
        },
        update = { v ->
            v.getMapAsync { map ->
                val style=map.style ?: return@getMapAsync
                if(!styleReady) return@getMapAsync

                style.getLayerAs<RasterLayer>(SAT_LAYER)?.setProperties(
                    rasterOpacity(if(satellite) 1f else 0f)
                )

                point?.let { p ->
                    ensureLocation(map,style,p)
                    if(firstFix) {
                        firstFix=false
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(p.latitude,p.longitude),
                                17.2
                            ),
                            650
                        )
                    }
                }

                if(route.size>=2) {
                    style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(
                        LineString.fromLngLats(
                            route.map { Point.fromLngLat(it.longitude,it.latitude) }
                        )
                    )
                }

                val features=places.map { poi ->
                    Feature.fromGeometry(
                        Point.fromLngLat(poi.point.longitude,poi.point.latitude)
                    ).also { f ->
                        f.addStringProperty("name",poi.name)
                        f.addStringProperty("category",poi.category ?: "")
                    }
                }
                style.getSourceAs<GeoJsonSource>(POI_SOURCE)?.setGeoJson(
                    FeatureCollection.fromFeatures(features)
                )
            }
        }
    )
}
