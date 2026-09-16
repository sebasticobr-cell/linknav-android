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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linknav.camera.CameraIntrinsics
import com.linknav.camera.CameraPreview
import com.linknav.ar.RouteCameraProjection
import com.linknav.ar.RouteWorldSample
import com.linknav.location.GeoPoint
import com.linknav.map.LinkNavMap
import com.linknav.map.MapPoi
import com.linknav.navigation.NavigationPhase
import com.linknav.navigation.NavigationSession
import com.linknav.voice.NavigationVoice
import java.util.Locale
import kotlin.math.abs

private val HudNavy=Color(0xE91A2034)
private val HudBorder=Color(0xFF455270)
private val AccentPurple=Color(0xFF7042F1)
private val NavBlue=Color(0xFF1689FF)

private const val REF_W=864f
private const val REF_H=1536f
private const val REF_STATUS=50f
private const val REF_NAV=70f
private const val REF_CONTENT_H=REF_H-REF_STATUS-REF_NAV

private fun navigationArrowPath(
    cx:Float,
    cy:Float,
    width:Float,
    height:Float
):Path = Path().apply {
    moveTo(cx,cy-height*.50f)
    lineTo(cx+width*.50f,cy+height*.42f)
    lineTo(cx,cy+height*.25f)
    lineTo(cx-width*.50f,cy+height*.42f)
    close()
}

private fun chevronPath(
    cx:Float,
    cy:Float,
    width:Float,
    height:Float
):Path = Path().apply {
    moveTo(cx-width*.50f,cy+height*.28f)
    lineTo(cx,cy-height*.50f)
    lineTo(cx+width*.50f,cy+height*.28f)
    lineTo(cx+width*.30f,cy+height*.50f)
    lineTo(cx,cy-height*.15f)
    lineTo(cx-width*.30f,cy+height*.50f)
    close()
}

@Composable
private fun WorldRouteOverlay(
    current:GeoPoint?,
    routeSamples:List<RouteWorldSample>,
    orientation:com.linknav.navigation.DeviceOrientation,
    intrinsics:CameraIntrinsics,
    confidence:Float,
    visible:Boolean
) {
    if(
        !visible ||
        current==null ||
        routeSamples.isEmpty() ||
        orientation.headingDeg.isNaN()
    ) return

    Canvas(Modifier.fillMaxSize()) {
        val projected=RouteCameraProjection.project(
            samples=routeSamples,
            orientation=orientation,
            intrinsics=intrinsics,
            viewportWidth=size.width,
            viewportHeight=size.height
        )
        if(projected.isEmpty()) return@Canvas

        val alphaBase=(.46f+confidence.coerceIn(0f,1f)*.54f)
            .coerceIn(.46f,1f)

        val track=projected.filter {
            it.depthM>.45f &&
            it.screenX>-size.width*.15f &&
            it.screenX<size.width*1.15f &&
            it.screenY>-size.height*.15f &&
            it.screenY<size.height*1.15f
        }

        for(i in 0 until track.lastIndex) {
            val a=track[i]
            val b=track[i+1]
            val stroke=(
                minOf(a.pixelsPerMeter,b.pixelsPerMeter)*.18f
            ).coerceIn(2f,size.width*.022f)
            drawLine(
                color=Color(0xFF3D74FF).copy(alpha=.17f*alphaBase),
                start=Offset(a.screenX,a.screenY),
                end=Offset(b.screenX,b.screenY),
                strokeWidth=stroke
            )
        }

        for(i in projected.lastIndex downTo 1) {
            val sample=projected[i]
            if(!sample.visible) continue
            val w=(sample.pixelsPerMeter*1.05f)
                .coerceIn(size.width*.035f,size.width*.18f)
            val h=w*.44f
            val path=chevronPath(sample.screenX,sample.screenY,w,h)

            rotate(sample.rotationDeg,pivot=Offset(sample.screenX,sample.screenY)) {
                drawPath(
                    path=chevronPath(
                        sample.screenX,
                        sample.screenY,
                        w*1.30f,
                        h*1.30f
                    ),
                    color=Color(0xFF3F62FF).copy(alpha=.08f*alphaBase)
                )
                drawPath(
                    path=path,
                    brush=Brush.verticalGradient(
                        colors=listOf(
                            Color(0xFF7378FF).copy(alpha=.70f*alphaBase),
                            Color(0xFF326EFF).copy(alpha=.88f*alphaBase)
                        ),
                        startY=sample.screenY-h,
                        endY=sample.screenY+h
                    )
                )
            }
        }

        val main=projected.firstOrNull()
        if(main!=null && main.visible) {
            val mainW=(main.pixelsPerMeter*1.35f)
                .coerceIn(size.width*.13f,size.width*.31f)
            val mainH=mainW*.96f
            val x=main.screenX
            val y=main.screenY

            rotate(main.rotationDeg,pivot=Offset(x,y)) {
                drawPath(
                    navigationArrowPath(x,y,mainW*1.35f,mainH*1.30f),
                    Color(0x103E86FF).copy(alpha=alphaBase)
                )
                drawPath(
                    navigationArrowPath(x,y,mainW*1.18f,mainH*1.15f),
                    Color(0x28437EFF).copy(alpha=alphaBase)
                )
                drawPath(
                    navigationArrowPath(x,y,mainW,mainH),
                    Brush.verticalGradient(
                        colors=listOf(
                            Color(0xFF148EFF).copy(alpha=alphaBase),
                            Color(0xFF216BFF).copy(alpha=alphaBase),
                            Color(0xFF4A4FF1).copy(alpha=alphaBase)
                        ),
                        startY=y-mainH*.50f,
                        endY=y+mainH*.42f
                    )
                )
                drawCircle(
                    color=Color.White.copy(alpha=alphaBase),
                    radius=mainW*.075f,
                    center=Offset(x,y+mainH*.14f)
                )
            }
        }

        if(projected.none { it.visible }) {
            val cue=projected.firstOrNull()
            if(cue!=null) {
                val right=cue.cameraX>=0f
                val x=if(right) size.width*.91f else size.width*.09f
                val y=size.height*.53f
                val w=size.width*.075f
                val h=w*.82f
                rotate(
                    degrees=if(right) 90f else -90f,
                    pivot=Offset(x,y)
                ) {
                    drawPath(
                        navigationArrowPath(x,y,w,h),
                        Color(0xCC357BFF)
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallRoundControl(
    modifier:Modifier,
    label:String,
    icon:@Composable ()->Unit,
    onClick:()->Unit,
    enabled:Boolean=true
) {
    Surface(
        modifier=modifier.clickable(enabled=enabled,onClick=onClick),
        shape=CircleShape,
        color=HudNavy,
        border=BorderStroke(1.dp,HudBorder.copy(alpha=.92f)),
        shadowElevation=5.dp
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.Center
        ) {
            icon()
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                color=if(enabled) Color.White else Color.White.copy(alpha=.42f),
                fontSize=8.sp,
                lineHeight=9.sp,
                textAlign=TextAlign.Center,
                maxLines=1
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
    var cameraIntrinsics by remember {
        mutableStateOf(CameraIntrinsics.fallback())
    }

    val voice=remember { NavigationVoice(ctx.applicationContext) }
    DisposableEffect(Unit) { onDispose { voice.close() } }

    val ask=rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted=it }

    LaunchedEffect(Unit) {
        if(!granted) ask.launch(Manifest.permission.CAMERA)
    }

    val current=nav.location.point
    val target=nav.nextWaypoint
    val heading=nav.orientation.headingDeg

    val routeSamples=remember(
        current,
        nav.route?.points,
        nav.routeProgressM
    ) {
        val route=nav.route?.points.orEmpty()
        if(current==null || route.size<2) emptyList()
        else RouteCameraProjection.resample(
            route=route,
            current=current,
            progressM=nav.routeProgressM
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sx=maxWidth.value/REF_W
        val sy=maxHeight.value/REF_CONTENT_H
        val sizeScale=minOf(sx,sy)

        fun refModifier(
            centerX:Float,
            centerY:Float,
            widthRef:Float,
            heightRef:Float
        ):Modifier {
            val finalW=widthRef*sizeScale
            val finalH=heightRef*sizeScale
            val centerXDp=centerX*sx
            val centerYDp=(centerY-REF_STATUS)*sy
            return Modifier
                .offset(
                    x=(centerXDp-finalW/2f).dp,
                    y=(centerYDp-finalH/2f).dp
                )
                .size(finalW.dp,finalH.dp)
        }

        if(granted) {
            CameraPreview(
                modifier=Modifier.fillMaxSize(),
                torchEnabled=torch,
                onTorchAvailable={ torchAvailable=it },
                onIntrinsics={ cameraIntrinsics=it }
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

        WorldRouteOverlay(
            current=current,
            routeSamples=routeSamples,
            orientation=nav.orientation,
            intrinsics=cameraIntrinsics,
            confidence=nav.confidence,
            visible=nav.route!=null && target!=null
        )

        Surface(
            modifier=refModifier(432.5f,116f,805f,112f),
            shape=RoundedCornerShape((34f*sizeScale).dp),
            color=HudNavy,
            border=BorderStroke(1.dp,HudBorder),
            shadowElevation=7.dp
        ) {
            Row(
                Modifier.fillMaxSize().padding(
                    horizontal=(17f*sizeScale).dp,
                    vertical=(10f*sizeScale).dp
                ),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Navigation,
                    null,
                    tint=Color(0xFF6E5BFF),
                    modifier=Modifier.size((50f*sizeScale).dp)
                )
                Spacer(Modifier.width((11f*sizeScale).dp))

                Column(
                    modifier=Modifier.weight(.40f),
                    verticalArrangement=Arrangement.Center
                ) {
                    Text(
                        "LINKNAV",
                        color=Color.White,
                        fontSize=17.sp,
                        lineHeight=18.sp,
                        fontWeight=FontWeight.ExtraBold,
                        maxLines=1
                    )
                    Text(
                        "Modo Câmera",
                        color=Color(0xFFB7C0DC),
                        fontSize=10.sp,
                        lineHeight=11.sp,
                        maxLines=1
                    )
                }

                Column(
                    modifier=Modifier.weight(.34f),
                    verticalArrangement=Arrangement.Center
                ) {
                    Text(
                        when(nav.phase) {
                            NavigationPhase.ARRIVED -> "Destino alcançado"
                            NavigationPhase.RECALCULATING -> "Recalculando rota"
                            else -> "Rota para destino"
                        },
                        color=Color(0xFFDDE2F2),
                        fontSize=9.sp,
                        lineHeight=10.sp,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height((5f*sizeScale).dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((7f*sizeScale).dp)
                            .background(
                                Color(0xFF35405D),
                                RoundedCornerShape(100.dp)
                            )
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(nav.progress.coerceIn(0f,1f))
                                .fillMaxHeight()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0xFF714EFF),Color(0xFF5F72FF))
                                    ),
                                    RoundedCornerShape(100.dp)
                                )
                        )
                    }
                    Spacer(Modifier.height((5f*sizeScale).dp))
                    Text(
                        if(nav.waypoints.isNotEmpty())
                            "${(nav.waypointIndex+1).coerceAtMost(nav.waypoints.size)} de ${nav.waypoints.size} pontos"
                        else
                            "Aguardando rota",
                        color=Color(0xFFB5C0DE),
                        fontSize=9.sp,
                        lineHeight=10.sp,
                        maxLines=1
                    )
                }

                Spacer(Modifier.width((12f*sizeScale).dp))

                Surface(
                    modifier=Modifier
                        .size((86f*sizeScale).dp)
                        .clickable(onClick=onBack),
                    shape=CircleShape,
                    color=Color(0xFF242C47),
                    border=BorderStroke(1.dp,HudBorder)
                ) {
                    Box(contentAlignment=Alignment.Center) {
                        Icon(
                            Icons.Default.Close,
                            "Fechar",
                            tint=Color.White,
                            modifier=Modifier.size((36f*sizeScale).dp)
                        )
                    }
                }
            }
        }

        SmallRoundControl(
            modifier=refModifier(92f,270f,120f,120f),
            label=if(torch) "Lanterna ON" else "Lanterna",
            icon={
                Icon(
                    Icons.Default.FlashlightOn,
                    null,
                    tint=if(torch) Color(0xFFFFE27A) else Color.White,
                    modifier=Modifier.size((31f*sizeScale).dp)
                )
            },
            onClick={ torch=!torch },
            enabled=torchAvailable
        )

        SmallRoundControl(
            modifier=refModifier(92f,420f,120f,120f),
            label=if(muted) "Som" else "Silenciar",
            icon={
                Icon(
                    if(muted) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    null,
                    tint=Color.White,
                    modifier=Modifier.size((31f*sizeScale).dp)
                )
            },
            onClick={ muted=!muted }
        )

        if(miniMapVisible) {
            Surface(
                modifier=refModifier(725f,323f,220f,248f),
                shape=RoundedCornerShape((25f*sizeScale).dp),
                color=HudNavy,
                border=BorderStroke(1.dp,HudBorder),
                shadowElevation=8.dp
            ) {
                Box {
                    LinkNavMap(
                        modifier=Modifier.fillMaxSize(),
                        point=current?.let {
                            if(!heading.isNaN()) it.copy(bearingDeg=heading) else it
                        },
                        route=nav.route?.points.orEmpty(),
                        places=emptyList(),
                        selectedPlace=nav.route?.points?.lastOrNull()?.let {
                            MapPoi("camera-destination","Destino",null,it)
                        },
                        satellite=false,
                        recenterToken=miniRecenter,
                        styleUri="https://tiles.openfreemap.org/styles/dark",
                        showAttribution=false,
                        showAccuracy=false,
                        followZoom=17.2
                    )
                    Surface(
                        modifier=Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(.25f)
                            .clickable { miniMapVisible=false },
                        color=Color(0xE31A2034)
                    ) {
                        Row(
                            Modifier.fillMaxSize(),
                            horizontalArrangement=Arrangement.Center,
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Map,
                                null,
                                tint=Color.White,
                                modifier=Modifier.size((22f*sizeScale).dp)
                            )
                            Spacer(Modifier.width((7f*sizeScale).dp))
                            Text(
                                "Mapa mini",
                                color=Color.White,
                                fontSize=9.sp,
                                maxLines=1
                            )
                        }
                    }
                }
            }
        } else {
            SmallRoundControl(
                modifier=refModifier(779f,323f,120f,120f),
                label="Mapa mini",
                icon={
                    Icon(
                        Icons.Default.Map,
                        null,
                        tint=Color.White,
                        modifier=Modifier.size((31f*sizeScale).dp)
                    )
                },
                onClick={ miniMapVisible=true }
            )
        }

        SmallRoundControl(
            modifier=refModifier(779f,534f,130f,130f),
            label="Centralizar",
            icon={
                Icon(
                    Icons.Default.MyLocation,
                    null,
                    tint=Color.White,
                    modifier=Modifier.size((37f*sizeScale).dp)
                )
            },
            onClick={
                navigation.recenterNavigation()
                miniRecenter++
            }
        )

        Surface(
            modifier=refModifier(432.5f,1127.5f,807f,309f),
            shape=RoundedCornerShape((31f*sizeScale).dp),
            color=HudNavy,
            border=BorderStroke(1.dp,HudBorder),
            shadowElevation=9.dp
        ) {
            Column(
                Modifier.fillMaxSize().padding(
                    horizontal=(18f*sizeScale).dp,
                    vertical=(15f*sizeScale).dp
                )
            ) {
                Row(
                    modifier=Modifier.weight(1f),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Box(
                        modifier=Modifier
                            .width((150f*sizeScale).dp)
                            .fillMaxHeight(),
                        contentAlignment=Alignment.Center
                    ) {
                        Icon(
                            when {
                                nav.wrongDirection -> Icons.Default.Undo
                                nav.phase==NavigationPhase.ARRIVED -> Icons.Default.Flag
                                nav.instruction.contains("direita",true) -> Icons.Default.ArrowForward
                                nav.instruction.contains("esquerda",true) -> Icons.Default.ArrowBack
                                else -> Icons.Default.ArrowUpward
                            },
                            null,
                            tint=NavBlue,
                            modifier=Modifier.size((68f*sizeScale).dp)
                        )
                    }

                    VerticalDivider(
                        modifier=Modifier
                            .fillMaxHeight(.80f)
                            .width(1.dp),
                        color=Color.White.copy(alpha=.13f)
                    )

                    Spacer(Modifier.width((20f*sizeScale).dp))

                    Column(
                        Modifier.weight(1f),
                        verticalArrangement=Arrangement.Center
                    ) {
                        Text(
                            when(nav.phase) {
                                NavigationPhase.ARRIVED -> "Destino alcançado"
                                NavigationPhase.ARRIVING -> "Chegando"
                                NavigationPhase.OFF_ROUTE -> "Fora da rota"
                                NavigationPhase.RECALCULATING -> "Recalculando"
                                else -> "Próximo ponto"
                            },
                            color=Color(0xFFC9D2E9),
                            fontSize=12.sp,
                            lineHeight=13.sp
                        )
                        Text(
                            if(target!=null) "${nav.distanceToWaypointM.toInt()} m" else "—",
                            color=Color.White,
                            fontSize=34.sp,
                            lineHeight=36.sp,
                            fontWeight=FontWeight.ExtraBold
                        )
                        Text(
                            when {
                                nav.wrongDirection ->
                                    "Você está seguindo na direção contrária."
                                nav.instruction.isNotBlank() -> nav.instruction
                                nav.route==null -> "Inicie uma rota no mapa."
                                else -> "Aguardando orientação da rota…"
                            },
                            color=Color(0xFFE1E7F7),
                            fontSize=11.sp,
                            lineHeight=13.sp,
                            maxLines=2,
                            overflow=TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(
                    color=Color.White.copy(alpha=.12f),
                    thickness=1.dp
                )

                Row(
                    modifier=Modifier
                        .fillMaxWidth()
                        .height((64f*sizeScale).dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Row(
                        modifier=Modifier.weight(1f),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Verified,
                            null,
                            tint=Color(0xFF69E1BE),
                            modifier=Modifier.size((24f*sizeScale).dp)
                        )
                        Spacer(Modifier.width((8f*sizeScale).dp))
                        Text(
                            "Confiança ",
                            color=Color(0xFFC9D2E6),
                            fontSize=9.sp
                        )
                        Text(
                            "${(nav.confidence*100).toInt()}%",
                            color=Color.White,
                            fontSize=10.sp,
                            fontWeight=FontWeight.Bold
                        )
                    }

                    VerticalDivider(
                        modifier=Modifier
                            .height((38f*sizeScale).dp)
                            .width(1.dp),
                        color=Color.White.copy(alpha=.11f)
                    )

                    current?.let {
                        Column(
                            modifier=Modifier
                                .weight(1f)
                                .padding(start=(17f*sizeScale).dp),
                            horizontalAlignment=Alignment.Start
                        ) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Place,
                                    null,
                                    tint=Color(0xFF8C6BFF),
                                    modifier=Modifier.size((23f*sizeScale).dp)
                                )
                                Spacer(Modifier.width((7f*sizeScale).dp))
                                Text(
                                    "GPS ±${it.accuracyM.toInt()} m",
                                    color=Color.White,
                                    fontSize=10.sp,
                                    fontWeight=FontWeight.Medium
                                )
                            }
                            Text(
                                "${"%.5f".format(Locale.US,it.latitude)}, ${"%.5f".format(Locale.US,it.longitude)}",
                                color=Color(0xFF9EABC8),
                                fontSize=8.sp,
                                maxLines=1
                            )
                        }
                    }
                }
            }
        }

        Surface(
            modifier=refModifier(432.5f,1396f,325f,96f).clickable(onClick=onBack),
            shape=RoundedCornerShape((48f*sizeScale).dp),
            color=Color.Transparent,
            shadowElevation=8.dp
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF5547E9),Color(0xFF7A35F3))
                        ),
                        RoundedCornerShape((48f*sizeScale).dp)
                    ),
                contentAlignment=Alignment.Center
            ) {
                Row(
                    verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Map,
                        null,
                        tint=Color.White,
                        modifier=Modifier.size((28f*sizeScale).dp)
                    )
                    Spacer(Modifier.width((10f*sizeScale).dp))
                    Text(
                        "Ver mapa",
                        color=Color.White,
                        fontSize=13.sp,
                        fontWeight=FontWeight.Bold
                    )
                }
            }
        }

        if(abs(nav.orientation.pitchDeg)>68f && nav.route!=null) {
            Surface(
                modifier=Modifier
                    .align(Alignment.Center)
                    .padding(horizontal=90.dp),
                shape=RoundedCornerShape(18.dp),
                color=HudNavy.copy(alpha=.88f),
                border=BorderStroke(1.dp,HudBorder.copy(alpha=.7f))
            ) {
                Text(
                    "Levante o celular para visualizar a rota",
                    color=Color.White,
                    fontSize=10.sp,
                    textAlign=TextAlign.Center,
                    modifier=Modifier.padding(horizontal=14.dp,vertical=9.dp)
                )
            }
        }

        if(!heading.isNaN() && nav.orientation.accuracy<2) {
            Text(
                "Precisão da bússola baixa",
                color=Color(0xFFFFD06D),
                fontSize=9.sp,
                modifier=Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom=4.dp)
            )
        }
    }
}
