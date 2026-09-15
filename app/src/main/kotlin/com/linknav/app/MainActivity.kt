package com.linknav.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.linknav.feature.map.MapScreen
import com.linknav.feature.duo.DuoScreen
import com.linknav.feature.ar.navigation.ArNavigationScreen
import com.linknav.routing.Route

class MainActivity:ComponentActivity(){ override fun onCreate(savedInstanceState:Bundle?){ super.onCreate(savedInstanceState); setContent { MaterialTheme { LinkNavApp() } } } }

@Composable private fun LinkNavApp(){
    var screen by remember { mutableStateOf("map") }
    var activeRoute by remember { mutableStateOf<Route?>(null) }
    val locationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){}
    LaunchedEffect(Unit){ locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)) }
    when(screen){ "duo"->DuoScreen(BuildConfig.LINKNAV_BASE_URL){screen="map"}; "camera"->ArNavigationScreen(routePoints=activeRoute?.points.orEmpty(),onBack={screen="map"}); else->MapScreen(BuildConfig.LINKNAV_BASE_URL,onDuo={screen="duo"},onCamera={screen="camera"},onRouteChanged={activeRoute=it}) }
}
