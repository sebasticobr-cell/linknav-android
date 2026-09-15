package com.linknav.feature.ar.navigation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linknav.ar.ArDirectionMath
import com.linknav.ar.DeviceOrientationEngine
import com.linknav.camera.CameraPreview
import com.linknav.location.AndroidLocationEngine
import com.linknav.location.GeoPoint
import com.linknav.location.LocationState
import com.linknav.navigation.MapMatcher

@Composable
fun ArNavigationScreen(routePoints:List<GeoPoint>,onBack:()->Unit){
    val ctx=LocalContext.current
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) }
    val ask=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){ granted=it }
    val orientation=remember { DeviceOrientationEngine(ctx.applicationContext) }
    val locationEngine=remember { AndroidLocationEngine(ctx.applicationContext) }
    val heading by orientation.headings().collectAsState(initial=Float.NaN)
    val location by locationEngine.updates().collectAsState(initial=LocationState())

    LaunchedEffect(Unit){ if(!granted) ask.launch(Manifest.permission.CAMERA) }

    val cue=remember(routePoints,location.point,heading){
        val here=location.point
        if(here==null || heading.isNaN() || routePoints.isEmpty()) null
        else {
            val match=MapMatcher.match(here,routePoints)
            val nextIndex=(match.segmentIndex+2).coerceIn(0,routePoints.lastIndex)
            val target=routePoints[nextIndex]
            ArDirectionMath.cue(here,target,heading,"Próximo ponto da rota")
        }
    }

    Box(Modifier.fillMaxSize()){
        if(granted) CameraPreview(Modifier.fillMaxSize())
        else Text("A câmera precisa de permissão para o LINKNAV VISION.",Modifier.align(Alignment.Center).padding(24.dp))

        Surface(
            modifier=Modifier.align(Alignment.TopCenter).padding(20.dp),
            tonalElevation=8.dp,
            shape=MaterialTheme.shapes.large
        ){
            Column(Modifier.padding(14.dp),horizontalAlignment=Alignment.CenterHorizontally){
                Text("LINKNAV VISION",style=MaterialTheme.typography.titleMedium)
                when {
                    routePoints.isEmpty() -> Text("Inicie uma rota no mapa para projetar a direção.")
                    location.point==null -> Text("Aguardando GPS…")
                    heading.isNaN() -> Text("Aguardando orientação do aparelho…")
                    cue==null -> Text("Direção indisponível.")
                    else -> {
                        Text("↑",fontSize=72.sp,modifier=Modifier.rotate(cue.relativeBearingDeg))
                        Text("${cue.distanceM.toInt()} m",style=MaterialTheme.typography.headlineMedium)
                        Text("Direção relativa: ${cue.relativeBearingDeg.toInt()}°")
                        Text("Confiança ${(cue.confidence*100).toInt()}% • precisão GPS ${location.point?.accuracyM?.toInt() ?: 0} m",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Button(onClick=onBack,modifier=Modifier.align(Alignment.BottomCenter).padding(24.dp)){ Text("Mapa") }
    }
}
