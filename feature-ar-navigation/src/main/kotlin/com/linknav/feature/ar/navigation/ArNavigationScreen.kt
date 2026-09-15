package com.linknav.feature.ar.navigation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.linknav.ar.ArDirectionMath
import com.linknav.ar.DeviceOrientationEngine
import com.linknav.camera.CameraPreview
import com.linknav.location.AndroidLocationEngine
import com.linknav.location.GeoPoint
import com.linknav.location.LocationState
import com.linknav.navigation.MapMatcher

@Composable
private fun VisionArrow(rotation:Float,active:Boolean){
    val color=if(active) Color(0xFF2962FF) else Color.White
    Canvas(Modifier.size(150.dp).rotate(rotation)){
        val p=Path().apply{
            moveTo(size.width/2f,8f)
            lineTo(size.width*0.82f,size.height*0.82f)
            lineTo(size.width/2f,size.height*0.67f)
            lineTo(size.width*0.18f,size.height*0.82f)
            close()
        }
        drawPath(p,color)
        drawCircle(
            color=Color.White.copy(alpha=.9f),
            radius=size.minDimension*.055f,
            center=center
        )
    }
}

@Composable
fun ArNavigationScreen(routePoints:List<GeoPoint>,onBack:()->Unit){
    val ctx=LocalContext.current
    var granted by remember {
        mutableStateOf(
            ctx.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
        )
    }
    val ask=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){
        granted=it
    }

    val orientation=remember { DeviceOrientationEngine(ctx.applicationContext) }
    val locationEngine=remember { AndroidLocationEngine(ctx.applicationContext) }
    val heading by orientation.headings().collectAsState(initial=Float.NaN)
    val location by locationEngine.updates().collectAsState(initial=LocationState())

    LaunchedEffect(Unit){
        if(!granted) ask.launch(Manifest.permission.CAMERA)
    }

    val cue=remember(routePoints,location.point,heading){
        val here=location.point
        if(here==null || heading.isNaN() || routePoints.isEmpty()) null
        else {
            val match=MapMatcher.match(here,routePoints)
            if(match==null) null
            else {
                val nextIndex=(match.segmentIndex+2).coerceIn(0,routePoints.lastIndex)
                val target=routePoints[nextIndex]
                ArDirectionMath.cue(here,target,heading,"Próximo ponto da rota")
            }
        }
    }

    val arrowRotation=when {
        cue!=null -> cue.relativeBearingDeg
        !heading.isNaN() -> -heading
        else -> 0f
    }

    Box(Modifier.fillMaxSize()){
        if(granted) CameraPreview(Modifier.fillMaxSize())
        else Text(
            "A câmera precisa de permissão para o LINKNAV VISION.",
            Modifier.align(Alignment.Center).padding(24.dp)
        )

        Column(
            modifier=Modifier.align(Alignment.Center),
            horizontalAlignment=Alignment.CenterHorizontally
        ){
            VisionArrow(rotation=arrowRotation,active=cue!=null)
            Surface(tonalElevation=9.dp,shape=MaterialTheme.shapes.large){
                Column(
                    Modifier.padding(horizontal=16.dp,vertical=12.dp),
                    horizontalAlignment=Alignment.CenterHorizontally
                ){
                    Text("LINKNAV VISION",style=MaterialTheme.typography.titleMedium)

                    when{
                        location.point==null -> Text("Buscando sua posição…")
                        cue!=null -> {
                            Text(
                                "${cue.distanceM.toInt()} m",
                                style=MaterialTheme.typography.headlineMedium
                            )
                            Text("Siga a seta para o próximo ponto da rota")
                            Text(
                                "Confiança ${(cue.confidence*100).toInt()}%",
                                style=MaterialTheme.typography.bodySmall
                            )
                        }
                        routePoints.isEmpty() -> {
                            Text("Sem rota ativa • seta aponta para o norte")
                        }
                        heading.isNaN() -> Text("Aguardando bússola…")
                        else -> Text("Recalculando direção…")
                    }

                    location.point?.let {
                        Text(
                            "GPS ±${it.accuracyM.toInt()} m • ${"%.5f".format(it.latitude)}, ${"%.5f".format(it.longitude)}",
                            style=MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Button(
            onClick=onBack,
            modifier=Modifier.align(Alignment.BottomCenter).padding(24.dp)
        ){
            Text("Mapa")
        }
    }
}
