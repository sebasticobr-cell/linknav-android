package com.linknav.feature.ar.navigation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linknav.camera.CameraPreview
import com.linknav.map.LinkNavMap
import com.linknav.navigation.NavigationPhase
import com.linknav.navigation.NavigationSession
import com.linknav.voice.NavigationVoice
import kotlin.math.abs
import kotlin.math.sin

private fun DrawScope.chevron(
    centerX:Float,
    centerY:Float,
    sizePx:Float,
    rotationDeg:Float,
    color:Color
) {
    val path=Path().apply {
        moveTo(centerX,centerY-sizePx)
        lineTo(centerX+sizePx*.72f,centerY+sizePx*.55f)
        lineTo(centerX,centerY+sizePx*.25f)
        lineTo(centerX-sizePx*.72f,centerY+sizePx*.55f)
        close()
    }
    drawPath(
        path=path,
        color=color
    )
}

@Composable
private fun WorldRouteOverlay(
    relativeBearing:Float,
    pitch:Float,
    roll:Float,
    active:Boolean
) {
    Canvas(
        Modifier.fillMaxSize()
    ) {
        val clamped=(relativeBearing/70f).coerceIn(-1f,1f)
        val rollShift=(roll/90f).coerceIn(-.3f,.3f)
        val pitchShift=(pitch/90f).coerceIn(-.22f,.22f)

        for(i in 0 until 6) {
            val depth=i/5f
            val y=size.height*(
                .77f-depth*.34f-pitchShift*.16f
            )
            val perspective=1f-depth*.62f
            val x=size.width*.5f+
                clamped*size.width*(.30f-depth*.12f)+
                rollShift*size.width*.08f

            val alpha=if(active)
                (.86f-depth*.40f)
            else
                (.34f-depth*.18f)

            chevron(
                centerX=x,
                centerY=y,
                sizePx=size.minDimension*.12f*perspective,
                rotationDeg=relativeBearing,
                color=Color(0xFF2E7BFF).copy(alpha=alpha)
            )
        }
    }
}

@Composable
private fun RoundControl(
    label:String,
    icon:@Composable ()->Unit,
    onClick:()->Unit,
    enabled:Boolean=true
) {
    FilledTonalButton(
        onClick=onClick,
        enabled=enabled,
        modifier=Modifier.size(width=94.dp,height=68.dp),
        contentPadding=PaddingValues(4.dp)
    ) {
        Column(
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            icon()
            Text(
                label,
                style=MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
fun ArNavigationScreen(
    navigation:NavigationSession,
    onBack:()->Unit
) {
    val ctx=LocalContext.current
    val nav by navigation.state.collectAsState()

    var granted by remember {
        mutableStateOf(
            ctx.checkSelfPermission(Manifest.permission.CAMERA)==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var torch by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var miniMap by remember { mutableStateOf(true) }
    var miniRecenter by remember { mutableIntStateOf(0) }
    var lastSpoken by remember { mutableStateOf("") }

    val voice=remember { NavigationVoice(ctx.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { voice.close() }
    }

    val ask=rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted=it }

    LaunchedEffect(Unit) {
        if(!granted) ask.launch(Manifest.permission.CAMERA)
    }

    val current=nav.location.point
    val target=nav.nextWaypoint
    val heading=nav.orientation.headingDeg

    val relativeBearing=remember(current,target,heading) {
        if(current==null || target==null || heading.isNaN()) 0f
        else NavigationSession.normalizeSigned(
            NavigationSession.bearing(current,target)-heading
        )
    }

    LaunchedEffect(
        nav.instruction,
        nav.phase,
        muted
    ) {
        if(muted) return@LaunchedEffect

        val phrase=when(nav.phase) {
            NavigationPhase.RECALCULATING ->
                "Recalculando rota."
            NavigationPhase.OFF_ROUTE ->
                "Você saiu da rota."
            NavigationPhase.ARRIVED ->
                "Destino alcançado."
            else -> nav.instruction
        }.trim()

        if(
            phrase.isNotBlank() &&
            phrase!=lastSpoken
        ) {
            lastSpoken=phrase
            voice.speak(phrase)
        }
    }

    Box(
        Modifier.fillMaxSize()
    ) {
        if(granted) {
            CameraPreview(
                modifier=Modifier.fillMaxSize(),
                torchEnabled=torch,
                onTorchAvailable={ torchAvailable=it }
            )
        } else {
            Surface(
                modifier=Modifier.align(Alignment.Center),
                color=Color(0xDD101426),
                shape=MaterialTheme.shapes.extraLarge
            ) {
                Text(
                    "Permita o uso da câmera para iniciar o LINKNAV VISION.",
                    Modifier.padding(24.dp),
                    color=Color.White
                )
            }
        }

        WorldRouteOverlay(
            relativeBearing=relativeBearing,
            pitch=nav.orientation.pitchDeg,
            roll=nav.orientation.rollDeg,
            active=nav.route!=null && target!=null
        )

        Surface(
            modifier=Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .align(Alignment.TopCenter),
            color=Color(0xDD101426),
            shape=MaterialTheme.shapes.extraLarge,
            shadowElevation=10.dp
        ) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Navigation,
                    contentDescription=null,
                    tint=Color(0xFF7657FF),
                    modifier=Modifier.size(38.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(
                    Modifier.weight(.42f)
                ) {
                    Text(
                        "LINKNAV",
                        color=Color.White,
                        fontWeight=FontWeight.ExtraBold,
                        style=MaterialTheme.typography.titleLarge
                    )
                    Text(
                        "Modo Câmera",
                        color=Color(0xFFB7C4E8),
                        style=MaterialTheme.typography.bodyMedium
                    )
                }

                Column(
                    Modifier.weight(.46f)
                ) {
                    Text(
                        when(nav.phase) {
                            NavigationPhase.ARRIVED -> "Destino alcançado"
                            NavigationPhase.RECALCULATING -> "Recalculando rota"
                            else -> "Rota para destino"
                        },
                        color=Color.White,
                        style=MaterialTheme.typography.bodySmall
                    )
                    LinearProgressIndicator(
                        progress={ nav.progress },
                        modifier=Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                    )
                    Text(
                        if(nav.waypoints.isNotEmpty())
                            "${(nav.waypointIndex+1).coerceAtMost(nav.waypoints.size)} de ${nav.waypoints.size} pontos"
                        else "Aguardando rota",
                        color=Color(0xFFB7C4E8),
                        style=MaterialTheme.typography.labelSmall
                    )
                }

                IconButton(
                    onClick=onBack
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription="Fechar",
                        tint=Color.White
                    )
                }
            }
        }

        Column(
            modifier=Modifier
                .align(Alignment.CenterStart)
                .padding(start=14.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)
        ) {
            RoundControl(
                label=if(torch) "Lanterna ON" else "Lanterna",
                icon={
                    Icon(
                        Icons.Default.FlashOn,
                        contentDescription=null
                    )
                },
                onClick={ torch=!torch },
                enabled=torchAvailable
            )

            RoundControl(
                label=if(muted) "Som" else "Silenciar",
                icon={
                    Icon(
                        if(muted) Icons.Default.VolumeUp
                        else Icons.Default.VolumeOff,
                        contentDescription=null
                    )
                },
                onClick={ muted=!muted }
            )
        }

        Column(
            modifier=Modifier
                .align(Alignment.CenterEnd)
                .padding(end=14.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp),
            horizontalAlignment=Alignment.End
        ) {
            if(miniMap) {
                Surface(
                    modifier=Modifier.size(
                        width=174.dp,
                        height=152.dp
                    ),
                    shape=MaterialTheme.shapes.extraLarge,
                    color=Color(0xCC101426),
                    shadowElevation=10.dp
                ) {
                    Box {
                        LinkNavMap(
                            modifier=Modifier.fillMaxSize(),
                            point=current?.let {
                                val h=nav.orientation.headingDeg
                                if(!h.isNaN()) it.copy(bearingDeg=h) else it
                            },
                            route=nav.route?.points.orEmpty(),
                            places=emptyList(),
                            satellite=false,
                            recenterToken=miniRecenter
                        )

                        FilledTonalButton(
                            onClick={ miniMap=false },
                            modifier=Modifier
                                .align(Alignment.BottomCenter)
                                .padding(6.dp),
                            contentPadding=PaddingValues(
                                horizontal=10.dp,
                                vertical=5.dp
                            )
                        ) {
                            Icon(
                                Icons.Default.Map,
                                contentDescription=null,
                                modifier=Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Mapa mini")
                        }
                    }
                }
            } else {
                RoundControl(
                    label="Mapa mini",
                    icon={
                        Icon(
                            Icons.Default.Map,
                            contentDescription=null
                        )
                    },
                    onClick={ miniMap=true }
                )
            }

            RoundControl(
                label="Centralizar",
                icon={
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription=null
                    )
                },
                onClick={
                    navigation.recenterNavigation()
                    miniRecenter++
                }
            )
        }

        Surface(
            modifier=Modifier
                .fillMaxWidth()
                .padding(
                    start=18.dp,
                    end=18.dp,
                    bottom=86.dp
                )
                .align(Alignment.BottomCenter),
            color=Color(0xE611162B),
            shape=MaterialTheme.shapes.extraLarge,
            shadowElevation=12.dp
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Navigation,
                        contentDescription=null,
                        tint=Color(0xFF3A86FF),
                        modifier=Modifier.size(56.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(
                        Modifier.weight(1f)
                    ) {
                        Text(
                            when(nav.phase) {
                                NavigationPhase.ARRIVED -> "Destino alcançado"
                                NavigationPhase.ARRIVING -> "Chegando"
                                NavigationPhase.OFF_ROUTE -> "Fora da rota"
                                NavigationPhase.RECALCULATING -> "Recalculando"
                                else -> "Próximo ponto"
                            },
                            color=Color(0xFFCAD4F4)
                        )
                        Text(
                            if(target!=null)
                                "${nav.distanceToWaypointM.toInt()} m"
                            else "—",
                            color=Color.White,
                            style=MaterialTheme.typography.displaySmall,
                            fontWeight=FontWeight.ExtraBold
                        )
                        Text(
                            when {
                                nav.wrongDirection ->
                                    "Você está seguindo na direção contrária."
                                nav.instruction.isNotBlank() ->
                                    nav.instruction
                                nav.route==null ->
                                    "Inicie uma rota no mapa."
                                else ->
                                    "Aguardando orientação da rota…"
                            },
                            color=Color(0xFFE3E8FA)
                        )
                    }
                }

                if(
                    !heading.isNaN() &&
                    nav.orientation.accuracy<2
                ) {
                    Text(
                        "Precisão da bússola baixa • mova o aparelho em forma de 8 para calibrar.",
                        color=Color(0xFFFFC857),
                        style=MaterialTheme.typography.bodySmall
                    )
                }

                HorizontalDivider(
                    color=Color.White.copy(alpha=.14f)
                )

                Row(
                    horizontalArrangement=Arrangement.SpaceBetween,
                    modifier=Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription=null,
                            tint=Color(0xFF6FE8C1),
                            modifier=Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Confiança ${(nav.confidence*100).toInt()}%",
                            color=Color.White,
                            style=MaterialTheme.typography.bodySmall
                        )
                    }

                    current?.let {
                        Row(
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.GpsFixed,
                                contentDescription=null,
                                tint=if(it.accuracyM<=35f)
                                    Color(0xFF8C6CFF)
                                else Color(0xFFFFC857),
                                modifier=Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "GPS ±${it.accuracyM.toInt()} m",
                                color=Color.White,
                                style=MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                current?.let {
                    Text(
                        "${"%.5f".format(it.latitude)}, ${"%.5f".format(it.longitude)}",
                        color=Color(0xFF9EABD2),
                        style=MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        Button(
            onClick=onBack,
            modifier=Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom=18.dp),
            contentPadding=PaddingValues(
                horizontal=28.dp,
                vertical=12.dp
            )
        ) {
            Icon(
                Icons.Default.Map,
                contentDescription=null
            )
            Spacer(Modifier.width(7.dp))
            Text("Ver mapa")
        }
    }
}
