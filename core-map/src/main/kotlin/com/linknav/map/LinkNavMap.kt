package com.linknav.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
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
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
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
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.sin

private const val ROUTE_SOURCE="linknav-route-source"
private const val ROUTE_LAYER="linknav-route-layer"
private const val POI_SOURCE="linknav-poi-source"
private const val POI_ICON_LAYER="linknav-poi-icon-layer"
private const val POI_LABEL_LAYER="linknav-poi-label-layer"
private const val SELECTED_SOURCE="linknav-selected-source"
private const val SELECTED_LAYER="linknav-selected-layer"
private const val ACCURACY_SOURCE="linknav-accuracy-source"
private const val ACCURACY_LAYER="linknav-accuracy-layer"
private const val USER_SOURCE="linknav-user-source"
private const val USER_LAYER="linknav-user-layer"
private const val USER_IMAGE="linknav-user-arrow"
private const val SAT_SOURCE="linknav-satellite-source"
private const val SAT_LAYER="linknav-satellite-layer"

private fun userArrowBitmap():Bitmap {
    val size=144
    val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
    val canvas=Canvas(bitmap)
    val white=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=AndroidColor.WHITE
        style=Paint.Style.FILL
    }
    val halo=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=AndroidColor.argb(55,61,126,255)
        style=Paint.Style.FILL
    }
    val blue=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=AndroidColor.rgb(31,111,255)
        style=Paint.Style.FILL
    }
    canvas.drawCircle(72f,72f,67f,halo)
    canvas.drawCircle(72f,72f,49f,white)
    val p=Path().apply {
        moveTo(72f,23f)
        lineTo(108f,108f)
        lineTo(72f,91f)
        lineTo(36f,108f)
        close()
    }
    canvas.drawPath(p,blue)
    return bitmap
}

private fun poiIconBitmap(hex:String,kind:String):Bitmap {
    val size=96
    val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
    val canvas=Canvas(bitmap)
    val bg=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=AndroidColor.parseColor(hex)
        style=Paint.Style.FILL
    }
    val fg=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=AndroidColor.WHITE
        style=Paint.Style.STROKE
        strokeWidth=7f
        strokeCap=Paint.Cap.ROUND
        strokeJoin=Paint.Join.ROUND
    }
    canvas.drawCircle(48f,48f,38f,bg)
    when(kind) {
        "food" -> {
            canvas.drawLine(37f,26f,37f,69f,fg)
            canvas.drawLine(29f,26f,29f,43f,fg)
            canvas.drawLine(45f,26f,45f,43f,fg)
            canvas.drawLine(29f,43f,45f,43f,fg)
            canvas.drawLine(60f,26f,67f,69f,fg)
        }
        "health" -> {
            canvas.drawLine(48f,28f,48f,68f,fg)
            canvas.drawLine(28f,48f,68f,48f,fg)
        }
        "fuel" -> {
            canvas.drawRect(31f,29f,57f,67f,fg)
            canvas.drawLine(57f,36f,68f,43f,fg)
            canvas.drawLine(68f,43f,68f,65f,fg)
        }
        "shop" -> {
            val p=Path().apply {
                moveTo(30f,41f); lineTo(66f,41f)
                lineTo(61f,69f); lineTo(35f,69f); close()
            }
            canvas.drawPath(p,fg)
            canvas.drawArc(39f,25f,57f,47f,180f,180f,false,fg)
        }
        "bank" -> {
            val p=Path().apply {
                moveTo(24f,41f); lineTo(48f,27f); lineTo(72f,41f)
            }
            canvas.drawPath(p,fg)
            for(x in listOf(32f,48f,64f)) canvas.drawLine(x,42f,x,65f,fg)
            canvas.drawLine(25f,68f,71f,68f,fg)
        }
        "hotel" -> {
            canvas.drawRect(27f,48f,69f,66f,fg)
            canvas.drawLine(31f,35f,31f,70f,fg)
            canvas.drawCircle(43f,43f,7f,fg)
        }
        "park" -> {
            val p=Path().apply {
                moveTo(48f,24f); lineTo(67f,54f); lineTo(55f,54f)
                lineTo(66f,67f); lineTo(30f,67f); lineTo(41f,54f)
                lineTo(29f,54f); close()
            }
            canvas.drawPath(p,fg)
        }
        "worship" -> {
            canvas.drawLine(48f,27f,48f,70f,fg)
            canvas.drawLine(34f,41f,62f,41f,fg)
        }
        else -> canvas.drawCircle(48f,48f,13f,fg)
    }
    return bitmap
}

private fun accuracyPolygon(point:GeoPoint):Polygon {
    val radius=point.accuracyM.coerceIn(3f,120f).toDouble()
    val earth=6378137.0
    val latRad=Math.toRadians(point.latitude)
    val ring=mutableListOf<Point>()
    for(i in 0..36) {
        val a=2.0*Math.PI*i/36.0
        val dLat=radius/earth*sin(a)
        val dLon=radius/(earth*cos(latRad).coerceAtLeast(.1))*cos(a)
        ring += Point.fromLngLat(
            point.longitude+Math.toDegrees(dLon),
            point.latitude+Math.toDegrees(dLat)
        )
    }
    return Polygon.fromLngLats(listOf(ring))
}

private fun categoryMeta(category:String?):Triple<String,String,String> {
    return when(category.orEmpty()) {
        "restaurant","fast_food","cafe","bar","pub","food_court" ->
            Triple("poi-food","#F57C00","Alimentação")
        "hospital","clinic","doctors","pharmacy","dentist" ->
            Triple("poi-health","#E43C4A","Saúde")
        "fuel","charging_station","bus_station" ->
            Triple("poi-fuel","#168DDF","Transporte")
        "supermarket","convenience","mall","electronics","furniture","department_store" ->
            Triple("poi-shop","#1676E8","Compras")
        "bank","atm" ->
            Triple("poi-bank","#7A4BD7","Banco")
        "hotel","hostel","guest_house","motel" ->
            Triple("poi-hotel","#7A4BD7","Hospedagem")
        "park","garden","nature_reserve","playground" ->
            Triple("poi-park","#148A5B","Parque")
        "place_of_worship","church" ->
            Triple("poi-worship","#657690","Igreja")
        "school","college","university" ->
            Triple("poi-bank","#5C72A6","Educação")
        else -> Triple("poi-default","#607D8B","Local")
    }
}

@Composable
fun LinkNavMap(
    modifier:Modifier=Modifier,
    point:GeoPoint?=null,
    route:List<GeoPoint> = emptyList(),
    places:List<MapPoi> = emptyList(),
    selectedPlace:MapPoi?=null,
    satellite:Boolean=false,
    recenterToken:Int=0,
    northToken:Int=0,
    styleUri:String="https://tiles.openfreemap.org/styles/liberty",
    showAttribution:Boolean=true,
    showAccuracy:Boolean=true,
    followZoom:Double=15.7,
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
        Box(modifier=modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
            Text("Mapa temporariamente indisponível")
        }
        return
    }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var firstFix by remember { mutableStateOf(true) }
    var lastRecenter by remember { mutableIntStateOf(recenterToken) }
    var lastNorth by remember { mutableIntStateOf(northToken) }

    fun configureStyle(style:Style) {
        listOf(ROUTE_SOURCE,POI_SOURCE,SELECTED_SOURCE,ACCURACY_SOURCE,USER_SOURCE).forEach {
            if(style.getSource(it)==null) style.addSource(GeoJsonSource(it))
        }

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
            if(firstLabel!=null) style.addLayerBelow(raster,firstLabel) else style.addLayerAt(raster,0)
        }

        style.addImage(USER_IMAGE,userArrowBitmap())
        style.addImage("poi-food",poiIconBitmap("#F57C00","food"))
        style.addImage("poi-health",poiIconBitmap("#E43C4A","health"))
        style.addImage("poi-fuel",poiIconBitmap("#168DDF","fuel"))
        style.addImage("poi-shop",poiIconBitmap("#1676E8","shop"))
        style.addImage("poi-bank",poiIconBitmap("#7A4BD7","bank"))
        style.addImage("poi-hotel",poiIconBitmap("#7A4BD7","hotel"))
        style.addImage("poi-park",poiIconBitmap("#148A5B","park"))
        style.addImage("poi-worship",poiIconBitmap("#657690","worship"))
        style.addImage("poi-default",poiIconBitmap("#607D8B","default"))

        if(style.getLayer(ROUTE_LAYER)==null) {
            style.addLayer(LineLayer(ROUTE_LAYER,ROUTE_SOURCE).withProperties(
                lineWidth(7f),lineOpacity(.94f),lineColor("#4B7CFF")
            ))
        }

        if(style.getLayer(ACCURACY_LAYER)==null) {
            style.addLayer(FillLayer(ACCURACY_LAYER,ACCURACY_SOURCE).withProperties(
                fillColor("#3F7EFF"),fillOpacity(.14f),fillOutlineColor("#79A0FF")
            ))
        }

        if(style.getLayer(POI_ICON_LAYER)==null) {
            val icons=SymbolLayer(POI_ICON_LAYER,POI_SOURCE).withProperties(
                iconImage(Expression.get("icon")),
                iconSize(
                    Expression.interpolate(
                        Expression.linear(),Expression.zoom(),
                        Expression.stop(12,0.42f),
                        Expression.stop(15,0.58f),
                        Expression.stop(18,0.70f)
                    )
                ),
                iconAllowOverlap(false),iconIgnorePlacement(false)
            )
            icons.minZoom=11.5f
            style.addLayer(icons)
        }

        if(style.getLayer(POI_LABEL_LAYER)==null) {
            val labels=SymbolLayer(POI_LABEL_LAYER,POI_SOURCE).withProperties(
                textField(Expression.get("label")),
                textSize(
                    Expression.interpolate(
                        Expression.linear(),Expression.zoom(),
                        Expression.stop(13,10f),
                        Expression.stop(16,11.5f),
                        Expression.stop(19,13f)
                    )
                ),
                textColor(Expression.get("color")),
                textHaloColor("#FFFFFF"),
                textHaloWidth(1.8f),
                textOffset(arrayOf(0f,1.7f)),
                textAllowOverlap(false),
                textIgnorePlacement(false)
            )
            labels.minZoom=12.8f
            style.addLayer(labels)
        }

        if(style.getLayer(USER_LAYER)==null) {
            style.addLayer(SymbolLayer(USER_LAYER,USER_SOURCE).withProperties(
                iconImage(USER_IMAGE),
                iconSize(.72f),
                iconRotate(Expression.get("bearing")),
                iconAllowOverlap(true),
                iconIgnorePlacement(true)
            ))
        }

        if(style.getLayer(SELECTED_LAYER)==null) {
            style.addLayer(CircleLayer(SELECTED_LAYER,SELECTED_SOURCE).withProperties(
                circleRadius(12f),circleColor("#6F39F4"),circleOpacity(.22f),
                circleStrokeColor("#6F39F4"),circleStrokeWidth(3f)
            ))
        }

        styleReady=true
    }

    fun emitViewport(map:org.maplibre.android.maps.MapLibreMap) {
        val b=map.projection.visibleRegion.latLngBounds
        currentViewportCallback(
            MapViewport(
                north=b.latitudeNorth,south=b.latitudeSouth,
                east=b.longitudeEast,west=b.longitudeWest,
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
                    map.uiSettings.isCompassEnabled=false
                    map.uiSettings.isAttributionEnabled=showAttribution
                    map.setMaxZoomPreference(22.0)
                    map.addOnCameraIdleListener { if(styleReady) emitViewport(map) }
                    map.addOnMapClickListener { latLng ->
                        val screen=map.projection.toScreenLocation(latLng)
                        val hit=map.queryRenderedFeatures(screen,POI_ICON_LAYER,POI_LABEL_LAYER).firstOrNull()
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
                    style.getSourceAs<GeoJsonSource>(USER_SOURCE)?.setGeoJson(
                        Feature.fromGeometry(Point.fromLngLat(p.longitude,p.latitude)).also {
                            it.addNumberProperty("bearing",p.bearingDeg)
                        }
                    )
                    if(showAccuracy) {
                        style.getSourceAs<GeoJsonSource>(ACCURACY_SOURCE)?.setGeoJson(
                            Feature.fromGeometry(accuracyPolygon(p))
                        )
                    } else {
                        style.getSourceAs<GeoJsonSource>(ACCURACY_SOURCE)?.setGeoJson(
                            FeatureCollection.fromFeatures(emptyList<Feature>())
                        )
                    }

                    if(firstFix || recenterToken!=lastRecenter) {
                        firstFix=false
                        lastRecenter=recenterToken
                        val zoom=if(map.cameraPosition.zoom<14.0) followZoom else map.cameraPosition.zoom
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude,p.longitude),zoom),
                            600
                        )
                    }
                }

                if(northToken!=lastNorth) {
                    lastNorth=northToken
                    val cp=CameraPosition.Builder(map.cameraPosition).bearing(0.0).build()
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(cp),350)
                }

                if(route.size>=2) {
                    style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(
                        LineString.fromLngLats(route.map { Point.fromLngLat(it.longitude,it.latitude) })
                    )
                } else {
                    style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(
                        FeatureCollection.fromFeatures(emptyList<Feature>())
                    )
                }

                val features=places.map { poi ->
                    val meta=categoryMeta(poi.category)
                    Feature.fromGeometry(Point.fromLngLat(poi.point.longitude,poi.point.latitude)).also { f ->
                        f.addStringProperty("id",poi.id)
                        f.addStringProperty("name",poi.name)
                        f.addStringProperty("category",poi.category ?: "")
                        f.addStringProperty("icon",meta.first)
                        f.addStringProperty("color",meta.second)
                        f.addStringProperty("label",poi.name+"\n"+meta.third)
                    }
                }
                style.getSourceAs<GeoJsonSource>(POI_SOURCE)?.setGeoJson(
                    FeatureCollection.fromFeatures(features)
                )

                val selectedSource=style.getSourceAs<GeoJsonSource>(SELECTED_SOURCE)
                if(selectedPlace!=null) {
                    selectedSource?.setGeoJson(
                        Feature.fromGeometry(Point.fromLngLat(
                            selectedPlace.point.longitude,selectedPlace.point.latitude
                        ))
                    )
                } else {
                    selectedSource?.setGeoJson(
                        FeatureCollection.fromFeatures(emptyList<Feature>())
                    )
                }
            }
        }
    )
}
