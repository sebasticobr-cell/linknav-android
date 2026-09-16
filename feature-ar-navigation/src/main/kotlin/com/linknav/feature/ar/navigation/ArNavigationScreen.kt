package com.linknav.feature.ar.navigation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linknav.camera.CameraPreview
import com.linknav.map.LinkNavMap
import com.linknav.navigation.NavigationPhase
import com.linknav.navigation.NavigationSession
import com.linknav.voice.NavigationVoice
import java.util.Locale

private val CamNavy=Color(0xE61A2034)
private val CamBorder=Color(0xFF465474)
private val CamPurple=Color(0xFF7042F1)
private val CamBlue=Color(0xFF2380FF)

private fun Path.navigationArrow(cx:Float,cy:Float,size:Float) {
    moveTo(cx,cy-size)
    lineTo(cx+size*.72f,cy+size*.76f)
    lineTo(cx,cy+size*.44f)
    lineTo(cx-size*.72f,cy+size*.76f)
    close()
}

@Composable
private fun GroundRouteOverlay(
    relativeBearing:Float,
    pitch:Float,
    roll:Float,
    visible:Boolean
) {
    if(!visible) return

    Canvas(Modifier.fillMaxSize()) {
        val bearingShift=(relativeBearing/80f).coerceIn(-1f,1f)
        val rollShift=(roll/75f).coerceIn(-.35f,.35f)
        val pitchShift=(pitch/80f).coerceIn(-.25f,.25f)

        val baseX=size.width*.50f+
            bearingShift*size.width*.22f+
            rollShift*size.width*.08f
        val baseY=size.height*(.58f-pitchShift*.10f)

        for(i in 0 until 5) {
            val depth=i/4f
            val y=baseY-depth*size.height*.17f
            val x=baseX-bearingShift*depth*size.width*.06f
            val s=size.minDimension*(.055f-depth*.022f)

            val p=Path().apply { navigationArrow(x,y,s) }
            rotate(relativeBearing*.32f,pivot=Offset(x,y)) {
                drawPath(
                    path=p,
                    brush=Brush.verticalGradient(
                        colors=listOf(
                            Color(0xAA6F78FF),
                            Color(0x885A52F4)
                        ),
                        startY=y-s,
                        endY=y+s
                    )
                )
            }
        }

        val mainY=size.height*(.70f-pitchShift*.07f)
        val mainSize=size.minDimension*.145f
        val glow=Path().apply {
            navigationArrow(baseX,mainY,mainSize*1.08f)
        }
        rotate(relativeBearing*.42f,pivot=Offset(baseX,mainY)) {
            drawPath(glow,Color(0x33479BFF))
        }

        val main=Path().apply {
            navigationArrow(baseX,mainY,mainSize)
        }
        rotate(relativeBearing*.42f,pivot=Offset(baseX,mainY)) {
            drawPath(
                main,
                brush=Brush.verticalGradient(
                    listOf(Color(0xFF128BFF),Color(0xFF2857F8))
                )
            )
            drawCircle(
                color=Color.White,
                radius=mainSize*.11f,
                center=Offset(baseX,mainY+mainSize*.10f)
            )
        }
    }
}

@Composable
private fun CircleControl(
    label:String,
    icon:@Composable ()->Unit,
    onClick:()->Unit,
    enabled:Boolean=true
) {
    Surface(
        modifier=Modifier.size(82.dp).clickable(enabled=enabled,onClick=onClick),
        shape=CircleShape,
        color=CamNavy,
        border=BorderStroke(1.dp,CamBorder.copy(alpha=.9f)),
        shadowElevation=7.dp
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.Center
        ) {
            icon()
            Spacer(Modifier.height(5.dp))
            Text(
                label,
                color=if(enabled) Color.White else Color.White.copy(alpha=.45f),
                fontSize=11.sp,
                textAlign=TextAlign.Center
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
    var miniMapVisible by remember { mutableStateOf(true) }
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

    LaunchedEffect(nav.instruction,nav.phase,muted) {
        if(muted) return@LaunchedEffect
        val phrase=when(nav.phase) {
            NavigationPhase.RECALCULATING -> "Rota recalculada."
            NavigationPhase.OFF_ROUTE -> "Você saiu da rota."
            NavigationPhase.ARRIVED -> "Destino alcançado."
            else -> nav.instruction
        }.trim()
        if(phrase.isNotBlank() && phrase!=lastSpoken) {
            lastSpoken=phrase
            voice.speak(phrase)
        }
    }

    Box(Modifier.fillMaxSize()) {
        if(granted) {
            CameraPreview(
                modifier=Modifier.fillMaxSize(),
                torchEnabled=torch,
                onTorchAvailable={ torchAvailable=it }
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(Color.Black),
                contentAlignment=Alignment.Center
            ) {
                Text(
                    "Permita o uso da câmera para iniciar o LINKNAV VISION.",
                    color=Color.White,
                    modifier=Modifier.padding(26.dp),
                    textAlign=TextAlign.Center
                )
            }
        }

        GroundRouteOverlay(
            relativeBearing=relativeBearing,
            pitch=nav.orientation.pitchDeg,
            roll=nav.orientation.rollDeg,
            visible=nav.route!=null && target!=null
        )

        Surface(
            modifier=Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(start=14.dp,end=14.dp,top=14.dp),
            shape=RoundedCornerShape(27.dp),
            color=CamNavy,
            border=BorderStroke(1.dp,CamBorder),
            shadowElevation=10.dp
        ) {
            Row(
                Modifier.padding(horizontal=16.dp,vertical=13.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Navigation,
                    null,
                    tint=Color(0xFF7160FF),
                    modifier=Modifier.size(42.dp)
                )
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(.44f)) {
                    Text(
                        "LINKNAV",
                        color=Color.White,
                        fontSize=23.sp,
                        fontWeight=FontWeight.ExtraBold
                    )
                    Text(
                        "Modo Câmera",
                        color=Color(0xFFB5C0DE),
                        fontSize=14.sp
                    )
                }
                Column(Modifier.weight(.48f)) {
                    Text(
                        when(nav.phase) {
                            NavigationPhase.ARRIVED -> "Destino alcançado"
                            NavigationPhase.RECALCULATING -> "Recalculando rota"
                            else -> "Rota para destino"
                        },
                        color=Color(0xFFD8DDF0),
                        fontSize=11.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress={ nav.progress },
                        modifier=Modifier.fillMaxWidth().height(7.dp),
                        color=Color(0xFF7159FF),
                        trackColor=Color(0xFF35405E)
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        if(nav.waypoints.isNotEmpty())
                            "${(nav.waypointIndex+1).coerceAtMost(nav.waypoints.size)} de ${nav.waypoints.size} pontos"
                        else
                            "Aguardando rota",
                        color=Color(0xFFB5C0DE),
                        fontSize=11.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    modifier=Modifier.size(48.dp).clickable(onClick=onBack),
                    shape=CircleShape,
                    color=Color(0xFF242C47),
                    border=BorderStroke(1.dp,CamBorder)
                ) {
                    Box(contentAlignment=Alignment.Center) {
                        Icon(Icons.Default.Close,"Fechar",tint=Color.White,modifier=Modifier.size(28.dp))
                    }
                }
            }
        }

        Column(
            modifier=Modifier
                .align(Alignment.CenterStart)
                .padding(start=14.dp)
                .offset(y=(-88).dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)
        ) {
            CircleControl(
                label=if(torch) "Lanterna ON" else "Lanterna",
                icon={
                    Icon(
                        Icons.Default.FlashlightOn,
                        null,
                        tint=if(torch) Color(0xFFFFE27A) else Color.White,
                        modifier=Modifier.size(28.dp)
                    )
                },
                onClick={ torch=!torch },
                enabled=torchAvailable
            )
            CircleControl(
                label=if(muted) "Som" else "Silenciar",
                icon={
                    Icon(
                        if(muted) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        null,
                        tint=Color.White,
                        modifier=Modifier.size(28.dp)
                    )
                },
                onClick={ muted=!muted }
            )
        }

        Column(
            modifier=Modifier
                .align(Alignment.CenterEnd)
                .padding(end=14.dp)
                .offset(y=(-62).dp),
            verticalArrangement=Arrangement.spacedBy(15.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            if(miniMapVisible) {
                Surface(
                    modifier=Modifier
                        .width(166.dp)
                        .height(136.dp),
                    shape=RoundedCornerShape(22.dp),
                    color=CamNavy,
                    border=BorderStroke(1.dp,CamBorder),
                    shadowElevation=9.dp
                ) {
                    Box {
                        LinkNavMap(
                            modifier=Modifier.fillMaxSize(),
                            point=current?.let {
                                if(!heading.isNaN()) it.copy(bearingDeg=heading) else it
                            },
                            route=nav.route?.points.orEmpty(),
                            places=emptyList(),
                            satellite=false,
                            recenterToken=miniRecenter
                        )
                        Surface(
                            modifier=Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(37.dp)
                                .clickable { miniMapVisible=false },
                            color=Color(0xDD1A2034)
                        ) {
                            Row(
                                Modifier.fillMaxSize(),
                                horizontalArrangement=Arrangement.Center,
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Map,null,tint=Color.White,modifier=Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Mapa mini",color=Color.White,fontSize=12.sp)
                            }
                        }
                    }
                }
            } else {
                CircleControl(
                    label="Mapa mini",
                    icon={
                        Icon(Icons.Default.Map,null,tint=Color.White,modifier=Modifier.size(28.dp))
                    },
                    onClick={ miniMapVisible=true }
                )
            }

            CircleControl(
                label="Centralizar",
                icon={
                    Icon(Icons.Default.MyLocation,null,tint=Color.White,modifier=Modifier.size(30.dp))
                },
                onClick={
                    navigation.recenterNavigation()
                    miniRecenter++
                }
            )
        }

        Surface(
            modifier=Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start=15.dp,end=15.dp,bottom=85.dp),
            shape=RoundedCornerShape(29.dp),
            color=CamNavy,
            border=BorderStroke(1.dp,CamBorder),
            shadowElevation=11.dp
        ) {
            Column(
                Modifier.padding(horizontal=18.dp,vertical=15.dp),
                verticalArrangement=Arrangement.spacedBy(9.dp)
            ) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(
                        when {
                            nav.wrongDirection -> Icons.Default.Undo
                            nav.phase==NavigationPhase.ARRIVED -> Icons.Default.Flag
                            else -> Icons.Default.ArrowUpward
                        },
                        null,
                        tint=CamBlue,
                        modifier=Modifier.size(58.dp)
                    )
                    Spacer(Modifier.width(15.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when(nav.phase) {
                                NavigationPhase.ARRIVED -> "Destino alcançado"
                                NavigationPhase.ARRIVING -> "Chegando"
                                NavigationPhase.OFF_ROUTE -> "Fora da rota"
                                NavigationPhase.RECALCULATING -> "Recalculando"
                                else -> "Próximo ponto"
                            },
                            color=Color(0xFFCBD3EB),
                            fontSize=16.sp
                        )
                        Text(
                            if(target!=null) "${nav.distanceToWaypointM.toInt()} m" else "—",
                            color=Color.White,
                            fontSize=46.sp,
                            lineHeight=48.sp,
                            fontWeight=FontWeight.ExtraBold
                        )
                        Text(
                            when {
                                nav.wrongDirection -> "Você está seguindo na direção contrária."
                                nav.instruction.isNotBlank() -> nav.instruction
                                nav.route==null -> "Inicie uma rota no mapa."
                                else -> "Aguardando orientação da rota…"
                            },
                            color=Color(0xFFE1E7F8),
                            fontSize=14.sp
                        )
                    }
                }

                if(!heading.isNaN() && nav.orientation.accuracy<2) {
                    Text(
                        "Precisão da bússola baixa.",
                        color=Color(0xFFFFCF67),
                        fontSize=11.sp
                    )
                }

                HorizontalDivider(color=Color.White.copy(alpha=.12f))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Row(
                        modifier=Modifier.weight(1f),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Verified,
                            null,
                            tint=Color(0xFF6CE6C2),
                            modifier=Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "Confiança ${(nav.confidence*100).toInt()}%",
                            color=Color.White,
                            fontSize=13.sp,
                            fontWeight=FontWeight.SemiBold
                        )
                    }

                    current?.let {
                        Column(horizontalAlignment=Alignment.End) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Place,
                                    null,
                                    tint=Color(0xFF8D6BFF),
                                    modifier=Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    "GPS ±${it.accuracyM.toInt()} m",
                                    color=Color.White,
                                    fontSize=13.sp
                                )
                            }
                            Text(
                                "${"%.5f".format(Locale.US,it.latitude)}, ${"%.5f".format(Locale.US,it.longitude)}",
                                color=Color(0xFFA9B4D0),
                                fontSize=10.sp
                            )
                        }
                    }
                }
            }
        }

        Surface(
            modifier=Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom=18.dp)
                .width(176.dp)
                .height(54.dp)
                .clickable(onClick=onBack),
            shape=RoundedCornerShape(27.dp),
            color=CamPurple,
            shadowElevation=9.dp
        ) {
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.Center
            ) {
                Icon(Icons.Default.Map,null,tint=Color.White,modifier=Modifier.size(25.dp))
                Spacer(Modifier.width(8.dp))
                Text("Ver mapa",color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold)
            }
        }
    }
}
