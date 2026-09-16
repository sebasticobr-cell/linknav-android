package com.linknav.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.linknav.ar.SensorFusionManager
import com.linknav.feature.map.MapScreen
import com.linknav.feature.duo.DuoScreen
import com.linknav.feature.ar.navigation.ArNavigationScreen
import com.linknav.navigation.NavigationSession
import com.linknav.routing.BackendRoutingProvider

class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LinkNavApp()
            }
        }
    }
}

@Composable
private fun LinkNavApp(){
    val context=LocalContext.current
    var screen by remember { mutableStateOf("map") }

    val routing=remember {
        BackendRoutingProvider(BuildConfig.LINKNAV_BASE_URL)
    }
    val navigation=remember {
        NavigationSession(context.applicationContext,routing)
    }
    val sensors=remember {
        SensorFusionManager(context.applicationContext)
    }

    DisposableEffect(Unit) {
        onDispose { navigation.close() }
    }

    LaunchedEffect(sensors) {
        sensors.orientations().collect { o ->
            navigation.updateOrientation(
                headingDeg=o.headingDeg,
                pitchDeg=o.pitchDeg,
                rollDeg=o.rollDeg,
                accuracy=o.accuracy,
                forwardEast=o.forwardEast,
                forwardNorth=o.forwardNorth,
                forwardUp=o.forwardUp,
                rightEast=o.rightEast,
                rightNorth=o.rightNorth,
                rightUp=o.rightUp,
                upEast=o.upEast,
                upNorth=o.upNorth,
                upUp=o.upUp,
                timestampNs=o.timestampNs
            )
        }
    }

    val locationPermission=rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ){}

    LaunchedEffect(Unit){
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    when(screen){
        "duo" -> DuoScreen(BuildConfig.LINKNAV_BASE_URL) {
            screen="map"
        }
        "camera" -> ArNavigationScreen(
            navigation=navigation,
            onBack={ screen="map" }
        )
        else -> MapScreen(
            baseUrl=BuildConfig.LINKNAV_BASE_URL,
            navigation=navigation,
            onDuo={ screen="duo" },
            onCamera={ screen="camera" }
        )
    }
}
