package com.linknav.map

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
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
private const val SELECTED_SOURCE="linknav-selected-source"
private const val SELECTED_LAYER="linknav-selected-layer"
private const val SAT_SOURCE="linknav-satellite-source"
private const val SAT_LAYER="linknav-satellite-layer"

@Composable
fun LinkNavMap(
    modifier:Modifier=Modifier,
    point:GeoPoint?=null,
    route:List<GeoPoint> = emptyList(),
    places:List<MapPoi> = emptyList(),
    selectedPlace:MapPoi?=null,
    satellite:Boolean=false,
    recenterToken:Int=0,
    styleUri:String="https://tiles.openfreemap.org/styles/liberty",
    onViewportIdle:(MapViewport)->Unit={},
    onPoiClick:(String)->Unit={}
) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val context=LocalContext.current
    val currentViewportCallback by rememberUpdatedState(onViewportIdle)
    val currentPoiClick by rememberUpdatedState(onPoiClick)

    val mapLibreReady=remember(context) {
        runCatching {
            MapLibre.getInstance(context.applicationContext)
            true
        }.getOrElse {
            Log.e("LINKNAV","MapLibre initialization failed",it)
            false
        }
    }

    if(!mapLibreReady) {
        Box(
            modifier=modifier.fillMaxSize(),
            contentAlignment=Alignment.Center
        ) {
            Text("Mapa temporariamente indisponível")
        }
        return
    }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var firstFix by remember { mutableStateOf(true) }
    var lastRecenter by remember { mutableIntStateOf(recenterToken) }

    fun categoryColor(category:String?):String = when(category.orEmpty()) {
        "restaurant","fast_food","cafe","bar","pub" -> "#F57C00"
        "hospital","clinic","doctors","pharmacy" -> "#E53935"
        "supermarket","convenience","mall","electronics","furniture" -> "#1976D2"
        "park","garden","nature_reserve" -> "#2E7D32"
        "fuel","charging_station","bus_station" -> "#00897B"
        "school","university","college" -> "#5E35B1"
        "bank","atm" -> "#6D4C41"
        "place_of_worship" -> "#546E7A"
        "hotel","hostel","guest_house" -> "#8E24AA"
        else -> "#546E7A"
    }

    fun configureStyle(style:Style) {
        if(style.getSource(ROUTE_SOURCE)==null) style.addSource(GeoJsonSource(ROUTE_SOURCE))
        if(style.getSource(POI_SOURCE)==null) style.addSource(GeoJsonSource(POI_SOURCE))
        if(style.getSource(SELECTED_SOURCE)==null) style.addSource(GeoJsonSource(SELECTED_SOURCE))

        if(style.getSource(SAT_SOURCE)==null) {
            val tiles=TileSet(
                "2.2.0",
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
            )
            tiles.attribution="Tiles © Esri — Source: Esri and the GIS User Community"
            tiles.setMinZoom(0f)
            tiles.setMaxZoom(17f)
            style.addSource(RasterSource(SAT_SOURCE,tiles,256))

            val raster=RasterLayer(SAT_LAYER,SAT_SOURCE).withProperties(
                rasterOpacity(if(satellite) 1f else 0f),
                rasterFadeDuration(0f)
            )
            val firstLabel=style.layers.firstOrNull { it is SymbolLayer }?.id
            if(firstLabel!=null) style.addLayerBelow(raster,firstLabel)
            else style.addLayerAt(raster,0)
        }

        if(style.getLayer(ROUTE_LAYER)==null) {
            style.addLayer(
                LineLayer(ROUTE_LAYER,ROUTE_SOURCE).withProperties(
                    lineWidth(7f),
                    lineOpacity(.94f),
                    lineColor("#2962FF")
                )
            )
        }

        if(style.getLayer(POI_DOT_LAYER)==null) {
            val dots=CircleLayer(POI_DOT_LAYER,POI_SOURCE).withProperties(
                circleRadius(
                    Expression.interpolate(
                        Expression.exponential(1.25f),
                        Expression.zoom(),
                        Expression.stop(12,2.5f),
                        Expression.stop(15,4.2f),
                        Expression.stop(18,5.5f)
                    )
                ),
                circleColor(Expression.get("color")),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(1.4f),
                circleOpacity(.96f)
            )
            dots.minZoom=11f
            style.addLayer(dots)
        }

        if(style.getLayer(POI_LABEL_LAYER)==null) {
            val labels=SymbolLayer(POI_LABEL_LAYER,POI_SOURCE).withProperties(
                textField(Expression.get("name")),
                textSize(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(13,10f),
                        Expression.stop(16,12f),
                        Expression.stop(19,13.5f)
                    )
                ),
                textColor("#263238"),
                textHaloColor("#FFFFFF"),
                textHaloWidth(1.6f),
                textOffset(arrayOf(0f,1.1f)),
                textAllowOverlap(false),
                textIgnorePlacement(false)
            )
            labels.minZoom=12.5f
            style.addLayer(labels)
        }

        if(style.getLayer(SELECTED_LAYER)==null) {
            style.addLayer(
                CircleLayer(SELECTED_LAYER,SELECTED_SOURCE).withProperties(
                    circleRadius(10f),
                    circleColor("#6C3BFF"),
                    circleOpacity(.95f),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(3f)
                )
            )
        }

        styleReady=true
    }

    fun ensureLocation(
        map:org.maplibre.android.maps.MapLibreMap,
        style:Style,
        p:GeoPoint
    ) {
        val fine=context.checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        )==PackageManager.PERMISSION_GRANTED
        val coarse=context.checkSelfPermission(
            Manifest.permission.ACCESS_COARSE_LOCATION
        )==PackageManager.PERMISSION_GRANTED
        if(!fine && !coarse) return

        val component=map.locationComponent
        if(!component.isLocationComponentActivated) {
            runCatching {
                component.activateLocationComponent(
                    LocationComponentActivationOptions.builder(context,style)
                        .useDefaultLocationEngine(false)
                        .build()
                )
                component.isLocationComponentEnabled=true
                component.renderMode=RenderMode.GPS
            }.onFailure {
                Log.e("LINKNAV","LocationComponent activation failed",it)
            }
        }

        if(component.isLocationComponentActivated) {
            val l=Location("linknav").apply {
                latitude=p.latitude
                longitude=p.longitude
                accuracy=p.accuracyM.coerceIn(1f,120f)
                bearing=p.bearingDeg
                speed=p.speedMps
                time=p.timestampMs.takeIf { it>0L } ?: System.currentTimeMillis()
            }
            runCatching {
                component.forceLocationUpdate(l)
                if(!component.isLocationComponentEnabled) component.isLocationComponentEnabled=true
                component.renderMode=RenderMode.GPS
            }
        }
    }

    fun emitViewport(map:org.maplibre.android.maps.MapLibreMap) {
        val bounds=map.projection.visibleRegion.latLngBounds
        currentViewportCallback(
            MapViewport(
                north=bounds.latitudeNorth,
                south=bounds.latitudeSouth,
                east=bounds.longitudeEast,
                west=bounds.longitudeWest,
                zoom=map.cameraPosition.zoom
            )
        )
    }

    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event ->
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
        modifier=modifier,
        factory={ ctx ->
            MapView(ctx).also { v ->
                mapView=v
                v.onCreate(null)
                v.getMapAsync { map ->
                    map.uiSettings.isCompassEnabled=true
                    map.uiSettings.isAttributionEnabled=true
                    map.setMaxZoomPreference(22.0)

                    map.addOnCameraIdleListener {
                        if(styleReady) emitViewport(map)
                    }

                    map.addOnMapClickListener { latLng ->
                        val screen=map.projection.toScreenLocation(latLng)
                        val hit=map.queryRenderedFeatures(
                            screen,
                            POI_LABEL_LAYER,
                            POI_DOT_LAYER
                        ).firstOrNull()
                        val id=hit?.getStringProperty("id")
                        if(!id.isNullOrBlank()) {
                            currentPoiClick(id)
                            true
                        } else false
                    }

                    map.setStyle(Style.Builder().fromUri(styleUri)) { style ->
                        configureStyle(style)
                        emitViewport(map)
                    }
                }
            }
        },
        update={ v ->
            v.getMapAsync { map ->
                val style=map.style ?: return@getMapAsync
                if(!styleReady) return@getMapAsync

                style.getLayerAs<RasterLayer>(SAT_LAYER)?.setProperties(
                    rasterOpacity(if(satellite) 1f else 0f)
                )

                point?.let { p ->
                    ensureLocation(map,style,p)
                    if(firstFix || recenterToken!=lastRecenter) {
                        firstFix=false
                        lastRecenter=recenterToken
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(p.latitude,p.longitude),
                                if(map.cameraPosition.zoom<15.5) 17.1 else map.cameraPosition.zoom
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
                } else {
                    style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(
                        FeatureCollection.fromFeatures(emptyList<Feature>())
                    )
                }

                val features=places.map { poi ->
                    Feature.fromGeometry(
                        Point.fromLngLat(poi.point.longitude,poi.point.latitude)
                    ).also { f ->
                        f.addStringProperty("id",poi.id)
                        f.addStringProperty("name",poi.name)
                        f.addStringProperty("category",poi.category ?: "")
                        f.addStringProperty("color",categoryColor(poi.category))
                    }
                }

                style.getSourceAs<GeoJsonSource>(POI_SOURCE)?.setGeoJson(
                    FeatureCollection.fromFeatures(features)
                )

                val selectedFeature=selectedPlace?.let { poi ->
                    Feature.fromGeometry(
                        Point.fromLngLat(poi.point.longitude,poi.point.latitude)
                    )
                }
                style.getSourceAs<GeoJsonSource>(SELECTED_SOURCE)?.setGeoJson(
                    selectedFeature ?: FeatureCollection.fromFeatures(emptyList<Feature>())
                )
            }
        }
    )
}
