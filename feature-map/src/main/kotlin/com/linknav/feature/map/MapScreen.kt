package com.linknav.feature.map

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linknav.location.GeoPoint
import com.linknav.map.LinkNavMap
import com.linknav.map.MapPoi
import com.linknav.map.MapViewport
import com.linknav.navigation.NavigationSession
import com.linknav.routing.BackendRoutingProvider
import com.linknav.routing.TravelMode
import com.linknav.search.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.cos
import kotlin.math.pow

private const val SEARCH_PREFS="linknav_search"
private const val RECENTS_KEY="recent_places"
private const val FAVORITES_KEY="favorite_places"
private const val REF_W=709f
private const val REF_H=1536f
private const val REF_STATUS=52f
private const val REF_NAV=75f
private const val REF_CONTENT_H=REF_H-REF_STATUS-REF_NAV

private val Purple=Color(0xFF6E32E8)
private val Violet=Color(0xFF7A43F2)
private val Navy=Color(0xF2161D33)
private val Navy2=Color(0xF51D2742)
private val Border=Color(0xFF52617F)

private fun loadPlaces(context:Context,key:String):List<PlaceResult> = runCatching {
    val raw=context.getSharedPreferences(SEARCH_PREFS,Context.MODE_PRIVATE)
        .getString(key,"[]") ?: "[]"
    val arr=JSONArray(raw)
    List(arr.length()) { i ->
        val o=arr.getJSONObject(i)
        PlaceResult(
            o.getString("id"),o.getString("name"),o.optString("address"),
            GeoPoint(o.getDouble("lat"),o.getDouble("lon")),
            o.optString("category").ifBlank { null }
        )
    }
}.getOrDefault(emptyList())

private fun savePlaces(context:Context,key:String,items:List<PlaceResult>) {
    val arr=JSONArray()
    items.take(24).forEach { p ->
        arr.put(JSONObject().apply {
            put("id",p.id); put("name",p.name); put("address",p.address)
            put("lat",p.location.latitude); put("lon",p.location.longitude)
            put("category",p.category ?: "")
        })
    }
    context.getSharedPreferences(SEARCH_PREFS,Context.MODE_PRIVATE)
        .edit().putString(key,arr.toString()).apply()
}

private fun categoryMatch(category:String?,filter:String?):Boolean {
    if(filter==null) return true
    val c=category.orEmpty()
    return when(filter) {
        "Restaurantes" -> c in setOf("restaurant","fast_food","cafe","bar","pub","food_court")
        "Postos" -> c in setOf("fuel","charging_station")
        "Hospitais" -> c in setOf("hospital","clinic","doctors","dentist")
        "Mercados" -> c in setOf("supermarket","convenience","grocery","mall")
        "Hotéis" -> c in setOf("hotel","hostel","guest_house","motel")
        else -> true
    }
}

private fun poiPriority(category:String?):Int = when(category.orEmpty()) {
    "hospital" -> 100
    "university","school" -> 90
    "supermarket","mall" -> 86
    "fuel","charging_station" -> 82
    "pharmacy","clinic","doctors" -> 80
    "hotel" -> 76
    "park","stadium","place_of_worship" -> 72
    "bank","atm" -> 70
    "restaurant","cafe","bar","fast_food" -> 66
    else -> 52
}

private fun categoryLabel(category:String?):String = when(category.orEmpty()) {
    "restaurant" -> "Restaurante"
    "fast_food" -> "Lanchonete"
    "cafe" -> "Café"
    "bar","pub" -> "Bar"
    "hospital" -> "Hospital"
    "clinic","doctors" -> "Clínica"
    "pharmacy" -> "Farmácia"
    "supermarket" -> "Supermercado"
    "convenience","grocery" -> "Mercado"
    "electronics" -> "Loja de eletrônicos"
    "furniture" -> "Loja de móveis"
    "fuel" -> "Posto"
    "charging_station" -> "Carregador elétrico"
    "bank" -> "Banco"
    "atm" -> "Caixa eletrônico"
    "school" -> "Escola"
    "university","college" -> "Educação"
    "place_of_worship" -> "Igreja"
    "hotel","hostel","guest_house" -> "Hotel"
    "park","garden" -> "Parque"
    "parking" -> "Estacionamento"
    else -> "Local"
}

private fun categoryIcon(category:String?):ImageVector = when(category.orEmpty()) {
    "restaurant","fast_food","cafe","bar","pub" -> Icons.Default.Restaurant
    "hospital","clinic","doctors","pharmacy","dentist" -> Icons.Default.LocalHospital
    "fuel","charging_station" -> Icons.Default.LocalGasStation
    "supermarket","convenience","grocery","mall" -> Icons.Default.ShoppingCart
    "bank","atm" -> Icons.Default.AccountBalance
    "school","university","college" -> Icons.Default.School
    "place_of_worship" -> Icons.Default.Church
    "hotel","hostel","guest_house" -> Icons.Default.Hotel
    "park","garden" -> Icons.Default.Park
    "parking" -> Icons.Default.LocalParking
    else -> Icons.Default.Place
}

private fun categoryTint(category:String?):Color = when(category.orEmpty()) {
    "restaurant","fast_food","cafe","bar","pub" -> Color(0xFFF57C00)
    "hospital","clinic","doctors","pharmacy","dentist" -> Color(0xFFE93B4F)
    "fuel","charging_station" -> Color(0xFF147FDA)
    "supermarket","convenience","grocery","mall","electronics","furniture" -> Color(0xFF1676E8)
    "bank","atm" -> Color(0xFF7B4BD8)
    "park","garden" -> Color(0xFF139064)
    else -> Color(0xFF66758F)
}

private fun scaleDistanceMeters(point:GeoPoint?,zoom:Double):Int {
    val lat=point?.latitude ?: 0.0
    val metersPerPixel=cos(Math.toRadians(lat))*2.0*Math.PI*6378137.0/(256.0*2.0.pow(zoom))
    val target=metersPerPixel*85.0
    val choices=listOf(2,5,10,20,50,100,200,500,1000,2000,5000)
    return choices.minByOrNull { kotlin.math.abs(it-target) } ?: 10
}

@Composable
private fun Brand() {
    Column {
        Row(verticalAlignment=Alignment.Bottom) {
            Text("LINK",color=Color.White,fontWeight=FontWeight.ExtraBold,fontSize=23.sp,letterSpacing=(-.7).sp)
            Text("NAV",color=Color(0xFF5E7CFF),fontWeight=FontWeight.ExtraBold,fontSize=23.sp,letterSpacing=(-.7).sp)
        }
        Text("Explore. Conecte. Chegue lá.",color=Color(0xFFB5C0DF),fontSize=10.sp,lineHeight=11.sp)
    }
}

@Composable
private fun CompactSearch(
    query:String,
    onQuery:(String)->Unit,
    onMic:()->Unit,
    onGo:()->Unit
) {
    Surface(
        shape=RoundedCornerShape(18.dp),
        color=Color(0xF51A2440),
        border=BorderStroke(1.dp,Color(0xFF617293))
    ) {
        Row(
            Modifier.fillMaxSize().padding(start=13.dp,end=4.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search,null,tint=Color.White,modifier=Modifier.size(22.dp))
            Spacer(Modifier.width(9.dp))
            BasicTextField(
                value=query,
                onValueChange=onQuery,
                modifier=Modifier.weight(1f),
                singleLine=true,
                textStyle=TextStyle(color=Color.White,fontSize=13.sp,fontWeight=FontWeight.Medium),
                decorationBox={ inner ->
                    if(query.isBlank()) Text(
                        "Pesquise lugares, endereços, categorias...",
                        color=Color(0xFFC5CEE3),
                        fontSize=12.sp,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis
                    )
                    inner()
                }
            )
            IconButton(onClick=onMic,modifier=Modifier.size(34.dp)) {
                Icon(Icons.Default.Mic,"Voz",tint=Color(0xFFC9D4ED),modifier=Modifier.size(20.dp))
            }
            Box(
                modifier=Modifier
                    .fillMaxHeight()
                    .width(61.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(listOf(Violet,Purple)))
                    .clickable(onClick=onGo),
                contentAlignment=Alignment.Center
            ) {
                Text("Ir",color=Color.White,fontSize=13.sp,fontWeight=FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ModeChip(text:String,selected:Boolean,onClick:()->Unit) {
    Surface(
        modifier=Modifier.fillMaxSize().clickable(onClick=onClick),
        shape=RoundedCornerShape(18.dp),
        color=if(selected) Purple else Color(0xE91E2945),
        border=BorderStroke(1.dp,if(selected) Color(0xFF8D6CFF) else Color(0xFF65728D))
    ) {
        Box(contentAlignment=Alignment.Center) {
            Text(text,color=Color.White,fontSize=11.sp,fontWeight=FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MapControl(
    modifier:Modifier,
    background:Color,
    icon:ImageVector,
    tint:Color=Color.White,
    onClick:()->Unit
) {
    Surface(
        modifier=modifier.clickable(onClick=onClick),
        shape=CircleShape,
        color=background,
        border=BorderStroke(1.dp,Color(0x445B6A86)),
        shadowElevation=4.dp
    ) {
        Box(contentAlignment=Alignment.Center) {
            Icon(icon,null,tint=tint,modifier=Modifier.fillMaxSize(.48f))
        }
    }
}

@Composable
fun MapScreen(
    baseUrl:String,
    navigation:NavigationSession,
    onDuo:()->Unit,
    onCamera:()->Unit
) {
    val ctx=LocalContext.current
    val nav by navigation.state.collectAsState()
    val search=remember(baseUrl) { BackendSearchProvider(baseUrl) }
    val routing=remember(baseUrl) { BackendRoutingProvider(baseUrl) }
    val poiRepository=remember { ViewportPoiRepository(ctx.applicationContext,search) }
    val scope=rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<PlaceResult>>(emptyList()) }
    var visiblePlaces by remember { mutableStateOf<List<PlaceResult>>(emptyList()) }
    var viewport by remember { mutableStateOf<MapViewport?>(null) }
    var selected by remember { mutableStateOf<PlaceResult?>(null) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var satellite by remember { mutableStateOf(false) }
    var currentAddress by remember { mutableStateOf("") }
    var recenterToken by remember { mutableIntStateOf(0) }
    var northToken by remember { mutableIntStateOf(0) }
    var recents by remember { mutableStateOf(loadPlaces(ctx,RECENTS_KEY)) }
    var favorites by remember { mutableStateOf(loadPlaces(ctx,FAVORITES_KEY)) }
    var showProfile by remember { mutableStateOf(false) }

    val speechLauncher=rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if(result.resultCode==Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.let { query=it }
        }
    }

    val currentPoint=nav.location.point?.let { p ->
        val h=nav.orientation.headingDeg
        if(!h.isNaN()) p.copy(bearingDeg=h) else p
    }

    val bootstrapCell=currentPoint?.let {
        "${(it.latitude*100.0).toInt()}:${(it.longitude*100.0).toInt()}"
    }

    LaunchedEffect(bootstrapCell) {
        val p=currentPoint ?: return@LaunchedEffect
        val nearby=withContext(Dispatchers.IO) { search.nearbyIndex(p,radiusM=5000,limit=700) }
        if(nearby.isNotEmpty()) {
            visiblePlaces=(visiblePlaces+nearby).distinctBy { it.id }.takeLast(1600)
        }
    }

    val rankedPlaces=remember(visiblePlaces,selectedCategory) {
        visiblePlaces
            .filter { categoryMatch(it.category,selectedCategory) }
            .sortedByDescending { poiPriority(it.category) }
            .take(700)
    }
    val mapPois=remember(rankedPlaces) {
        rankedPlaces.map { MapPoi(it.id,it.name,it.category,it.location) }
    }
    val selectedMapPoi=selected?.let { MapPoi(it.id,it.name,it.category,it.location) }

    fun rememberRecent(place:PlaceResult) {
        recents=(listOf(place)+recents.filterNot { it.id==place.id }).take(18)
        savePlaces(ctx,RECENTS_KEY,recents)
    }
    fun saveFavorite(place:PlaceResult) {
        favorites=(listOf(place)+favorites.filterNot { it.id==place.id }).take(24)
        savePlaces(ctx,FAVORITES_KEY,favorites)
        status="Salvo"
    }
    fun sharePlace(place:PlaceResult) {
        val text=buildString {
            append(place.name)
            if(place.address.isNotBlank()) append("\n").append(place.address)
            append("\n").append(place.location.latitude).append(", ").append(place.location.longitude)
        }
        ctx.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type="text/plain"; putExtra(Intent.EXTRA_TEXT,text)
            },"Compartilhar local"
        ))
    }
    fun choosePlace(place:PlaceResult) {
        selected=place
        query=place.name
        rememberRecent(place)
    }
    fun routeTo(place:PlaceResult,startCamera:Boolean) {
        val origin=nav.location.point ?: run { status="Aguardando GPS"; return }
        rememberRecent(place)
        selected=place
        scope.launch {
            status="Calculando rota…"
            val route=runCatching {
                withContext(Dispatchers.IO) {
                    routing.route(origin,place.location,TravelMode.CAR,alternatives=3).firstOrNull()
                }
            }.getOrNull()
            if(route!=null) {
                navigation.setRoute(route)
                status="${(route.durationSec/60).toInt()} min • ${"%.1f".format(Locale.US,route.distanceM/1000)} km"
                if(startCamera) onCamera()
            } else status="Rota indisponível"
        }
    }
    fun submitSearch() {
        suggestions.firstOrNull()?.let { choosePlace(it); return }
        val q=query.trim()
        if(q.isBlank()) return
        scope.launch {
            val result=runCatching {
                withContext(Dispatchers.IO) { search.search(q,currentPoint,15) }
            }.getOrDefault(emptyList())
            suggestions=result
            result.firstOrNull()?.let { choosePlace(it) }
        }
    }

    LaunchedEffect(viewport) {
        val v=viewport ?: return@LaunchedEffect
        delay(450)
        val pv=PoiViewport(v.north,v.south,v.east,v.west,v.zoom)
        val cached=poiRepository.cached(pv)
        if(cached.isNotEmpty()) visiblePlaces=(visiblePlaces+cached).distinctBy { it.id }.takeLast(1600)
        val result=withContext(Dispatchers.IO) { poiRepository.load(pv) }
        if(result.places.isNotEmpty()) visiblePlaces=(visiblePlaces+result.places).distinctBy { it.id }.takeLast(1600)
        if(result.stale && result.fromCache) status="Dados em cache"
    }

    LaunchedEffect(query,visiblePlaces) {
        val q=query.trim()
        if(q.isBlank()) { suggestions=emptyList(); return@LaunchedEffect }
        val local=search.localSuggestions(q,visiblePlaces+recents+favorites,15)
        suggestions=local
        delay(if(q.length<3) 240 else 190)
        val remote=runCatching {
            withContext(Dispatchers.IO) { search.search(q,currentPoint,15) }
        }.getOrDefault(emptyList())
        suggestions=(local+remote).distinctBy { it.id }.take(15)
    }

    LaunchedEffect(currentPoint?.latitude,currentPoint?.longitude) {
        val p=currentPoint ?: return@LaunchedEffect
        delay(1000)
        val reversed=runCatching { withContext(Dispatchers.IO) { search.reverse(p) } }.getOrNull()
        if(reversed!=null) currentAddress=if(reversed.address.isNotBlank()) reversed.address else reversed.name
    }

    val categories=listOf(
        Triple("Restaurantes",Icons.Default.Restaurant,Color(0xFFF57C00)),
        Triple("Postos",Icons.Default.LocalGasStation,Color(0xFF1682E7)),
        Triple("Hospitais",Icons.Default.LocalHospital,Color(0xFFE93B4F)),
        Triple("Mercados",Icons.Default.ShoppingCart,Color(0xFF1676E8)),
        Triple("Hotéis",Icons.Default.Hotel,Color(0xFF7B4BD8))
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sx=maxWidth.value/REF_W
        val sy=maxHeight.value/REF_CONTENT_H
        val s=minOf(sx,sy)

        fun refModifier(cx:Float,cy:Float,w:Float,h:Float):Modifier {
            val fw=w*s
            val fh=h*s
            val x=cx*sx-fw/2f
            val y=(cy-REF_STATUS)*sy-fh/2f
            return Modifier.offset(x=x.dp,y=y.dp).size(fw.dp,fh.dp)
        }

        LinkNavMap(
            modifier=Modifier.fillMaxSize(),
            point=currentPoint,
            route=nav.route?.points.orEmpty(),
            places=mapPois,
            selectedPlace=selectedMapPoi,
            satellite=satellite,
            recenterToken=recenterToken,
            northToken=northToken,
            onViewportIdle={ viewport=it },
            onPoiClick={ id -> selected=visiblePlaces.firstOrNull { it.id==id } }
        )

        Surface(
            modifier=refModifier(354.5f,156f,709f,208f),
            shape=RoundedCornerShape(bottomStart=(20f*s).dp,bottomEnd=(20f*s).dp),
            color=Navy,
            shadowElevation=5.dp
        ) { Box(Modifier.fillMaxSize()) }

        Box(modifier=refModifier(115f,82f,190f,50f),contentAlignment=Alignment.CenterStart) {
            Brand()
        }

        Surface(
            modifier=refModifier(666f,78f,50f,50f).clickable { showProfile=true },
            shape=CircleShape,
            color=Color(0xFF252E4C),
            border=BorderStroke(1.dp,Border)
        ) {
            Box(contentAlignment=Alignment.Center) {
                Icon(Icons.Outlined.Person,"Perfil",tint=Color(0xFFDCE4F8),modifier=Modifier.size((24f*s).dp))
            }
        }

        Box(modifier=refModifier(354.5f,151f,667f,63f)) {
            CompactSearch(
                query=query,
                onQuery={ query=it },
                onMic={
                    speechLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE,"pt-BR")
                    })
                },
                onGo={ submitSearch() }
            )
        }

        Box(modifier=refModifier(78f,222f,112f,47f)) {
            ModeChip("Mapa",!satellite) { satellite=false }
        }
        Box(modifier=refModifier(192f,222f,102f,47f)) {
            ModeChip("Satélite",satellite) { satellite=true }
        }

        Surface(
            modifier=refModifier(472f,222f,424f,47f),
            shape=RoundedCornerShape((19f*s).dp),
            color=Color(0xE91E2945),
            border=BorderStroke(1.dp,Color(0xFF65728D))
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal=(12f*s).dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Place,null,tint=Color.White,modifier=Modifier.size((18f*s).dp))
                Spacer(Modifier.width((7f*s).dp))
                Text(
                    if(currentAddress.isBlank()) "Você está em: localizando…" else "Você está em: $currentAddress",
                    color=Color(0xFFE7ECF8),
                    fontSize=9.sp,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis
                )
            }
        }

        LazyRow(
            modifier=Modifier.offset(y=((265f-REF_STATUS)*sy).dp).fillMaxWidth().height((50f*s).dp),
            contentPadding=PaddingValues(horizontal=(8f*s).dp),
            horizontalArrangement=Arrangement.spacedBy((5f*s).dp)
        ) {
            items(categories) { (name,icon,tint) ->
                val w=when(name) {
                    "Restaurantes" -> 133f
                    "Hospitais" -> 116f
                    "Mercados" -> 118f
                    else -> 105f
                }
                Surface(
                    modifier=Modifier.width((w*s).dp).fillMaxHeight().clickable {
                        val next=if(selectedCategory==name) null else name
                        selectedCategory=next
                        if(next!=null && currentPoint!=null) {
                            scope.launch {
                                val extra=withContext(Dispatchers.IO) { search.search(next,currentPoint,60) }
                                if(extra.isNotEmpty()) {
                                    visiblePlaces=(visiblePlaces+extra).distinctBy { it.id }.takeLast(1600)
                                }
                            }
                        }
                    },
                    shape=RoundedCornerShape((18f*s).dp),
                    color=if(selectedCategory==name) Color(0xFF30395A) else Color(0xEB26314D),
                    border=BorderStroke(1.dp,if(selectedCategory==name) Purple else Color(0xFF566581))
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal=(8f*s).dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Icon(icon,null,tint=tint,modifier=Modifier.size((20f*s).dp))
                        Spacer(Modifier.width((6f*s).dp))
                        Text(name,color=Color.White,fontSize=9.sp,fontWeight=FontWeight.SemiBold,maxLines=1)
                    }
                }
            }
        }

        if(query.isNotBlank() && suggestions.isNotEmpty()) {
            Surface(
                modifier=Modifier
                    .offset(x=(18f*sx).dp,y=((185f-REF_STATUS)*sy).dp)
                    .width((672f*sx).dp)
                    .heightIn(max=(300f*s).dp),
                shape=RoundedCornerShape((16f*s).dp),
                color=Navy2,
                border=BorderStroke(1.dp,Border),
                shadowElevation=8.dp
            ) {
                LazyColumn {
                    items(suggestions) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { choosePlace(p) }
                                .padding(horizontal=(12f*s).dp,vertical=(8f*s).dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier=Modifier.size((34f*s).dp),
                                shape=CircleShape,
                                color=categoryTint(p.category).copy(alpha=.16f)
                            ) {
                                Box(contentAlignment=Alignment.Center) {
                                    Icon(categoryIcon(p.category),null,tint=categoryTint(p.category),modifier=Modifier.size((19f*s).dp))
                                }
                            }
                            Spacer(Modifier.width((9f*s).dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name,color=Color.White,fontSize=11.sp,fontWeight=FontWeight.SemiBold,maxLines=1)
                                Text(
                                    listOf(categoryLabel(p.category),p.address).filter { it.isNotBlank() }.joinToString(" • "),
                                    color=Color(0xFFB6C1DB),fontSize=8.sp,maxLines=1,overflow=TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        MapControl(
            modifier=refModifier(670f,365f,62f,62f),
            background=Color.White.copy(alpha=.96f),
            icon=Icons.Default.Explore,
            tint=Color(0xFF26314A)
        ) { northToken++ }

        MapControl(
            modifier=refModifier(670f,438f,52f,52f),
            background=Color(0xEB182139),
            icon=Icons.Outlined.Layers
        ) { satellite=!satellite }

        MapControl(
            modifier=refModifier(660f,905f,60f,60f),
            background=Color.White.copy(alpha=.97f),
            icon=Icons.Default.MyLocation,
            tint=Color(0xFF202943)
        ) { recenterToken++ }

        MapControl(
            modifier=refModifier(660f,994f,68f,68f),
            background=Purple,
            icon=Icons.Default.Navigation
        ) {
            if(nav.route!=null) onCamera() else recenterToken++
        }

        Surface(
            modifier=refModifier(626f,1111f,127f,52f).clickable(onClick=onCamera),
            shape=RoundedCornerShape((17f*s).dp),
            color=Color(0xF8F7F4FF),
            shadowElevation=4.dp
        ) {
            Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
                Icon(Icons.Default.CameraAlt,null,tint=Color(0xFF35176C),modifier=Modifier.size((20f*s).dp))
                Spacer(Modifier.width((6f*s).dp))
                Text("Câmera",color=Color(0xFF32194F),fontSize=10.sp,fontWeight=FontWeight.SemiBold)
            }
        }

        Surface(
            modifier=refModifier(626f,1170f,127f,58f).clickable(onClick=onDuo),
            shape=RoundedCornerShape((18f*s).dp),
            color=Purple,
            shadowElevation=5.dp
        ) {
            Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
                Icon(Icons.Default.Groups,null,tint=Color.White,modifier=Modifier.size((21f*s).dp))
                Spacer(Modifier.width((6f*s).dp))
                Text("Duo",color=Color.White,fontSize=11.sp,fontWeight=FontWeight.Bold)
            }
        }

        Surface(
            modifier=refModifier(95f,1134f,145f,92f).clickable(onClick=onCamera),
            shape=RoundedCornerShape((10f*s).dp),
            color=Color(0xEB182139),
            border=BorderStroke(1.dp,Color.White.copy(alpha=.55f)),
            shadowElevation=4.dp
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(Color(0xFF243553),Color(0xFF101729)))
                )
            ) {
                Icon(Icons.Default.CameraAlt,null,tint=Color(0xFF81A5FF),modifier=Modifier.align(Alignment.Center).size((29f*s).dp))
                Surface(
                    modifier=Modifier.align(Alignment.BottomCenter).fillMaxWidth().height((27f*s).dp),
                    color=Color(0xDC151B2D)
                ) {
                    Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
                        Icon(Icons.Default.Map,null,tint=Color.White,modifier=Modifier.size((13f*s).dp))
                        Spacer(Modifier.width((4f*s).dp))
                        Text("Live View",color=Color.White,fontSize=8.sp)
                    }
                }
            }
        }

        Surface(
            modifier=refModifier(96f,1232f,165f,40f),
            shape=RoundedCornerShape((15f*s).dp),
            color=Color(0xE71A233A),
            border=BorderStroke(1.dp,Color(0xFF53617E))
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal=(10f*s).dp),verticalAlignment=Alignment.CenterVertically) {
                val acc=currentPoint?.accuracyM
                val c=when {
                    acc==null || !acc.isFinite() -> Color(0xFF8590A8)
                    acc<=12f -> Color(0xFF11BC79)
                    acc<=35f -> Color(0xFFE3A42A)
                    else -> Color(0xFFE05762)
                }
                Box(Modifier.size((8f*s).dp).clip(CircleShape).background(c))
                Spacer(Modifier.width((6f*s).dp))
                Text(currentPoint?.let { "GPS ±${it.accuracyM.toInt()} m" } ?: "Localizando…",color=Color.White,fontSize=9.sp,modifier=Modifier.weight(1f))
                Icon(Icons.Default.Info,null,tint=Color(0xFF9DB0D5),modifier=Modifier.size((15f*s).dp))
            }
        }

        val scaleM=scaleDistanceMeters(currentPoint,viewport?.zoom ?: 15.0)
        Column(
            modifier=refModifier(559f,1228f,118f,42f),
            horizontalAlignment=Alignment.End,
            verticalArrangement=Arrangement.Center
        ) {
            Text(
                if(scaleM<1000) "$scaleM m" else "${"%.1f".format(Locale.US,scaleM/1000.0)} km",
                color=Color(0xFF1C2434),fontSize=8.sp,fontWeight=FontWeight.Medium
            )
            Box(Modifier.width((82f*s).dp).height(1.dp).background(Color(0xFF1C2434)))
            Text("${(scaleM*3.28084).toInt()} pés",color=Color(0xFF1C2434),fontSize=7.sp)
        }

        selected?.let { place ->
            val dist=currentPoint?.let { NavigationSession.distance(it,place.location) }
            Surface(
                modifier=refModifier(354.5f,1384f,709f,228f),
                shape=RoundedCornerShape(topStart=(24f*s).dp,topEnd=(24f*s).dp),
                color=Color(0xF51A2135),
                border=BorderStroke(1.dp,Color(0x334F5C78)),
                shadowElevation=8.dp
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal=(20f*s).dp,vertical=(12f*s).dp)) {
                    Row(Modifier.height((90f*s).dp),verticalAlignment=Alignment.CenterVertically) {
                        Surface(
                            modifier=Modifier.size((74f*s).dp),
                            shape=RoundedCornerShape((9f*s).dp),
                            color=categoryTint(place.category).copy(alpha=.18f)
                        ) {
                            Box(contentAlignment=Alignment.Center) {
                                Icon(categoryIcon(place.category),null,tint=categoryTint(place.category),modifier=Modifier.size((33f*s).dp))
                            }
                        }
                        Spacer(Modifier.width((12f*s).dp))
                        Column(Modifier.weight(1f)) {
                            Text(place.name,color=Color.White,fontSize=15.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(
                                buildString {
                                    append(categoryLabel(place.category))
                                    if(dist!=null) append("  •  ").append(if(dist<1000) "${dist.toInt()} m" else "${"%.1f".format(Locale.US,dist/1000)} km")
                                },
                                color=Color(0xFFC5CEE1),fontSize=9.sp
                            )
                            if(place.address.isNotBlank()) Text(place.address,color=Color(0xFF98A7C5),fontSize=8.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                            if(status.isNotBlank()) Text(status,color=Color(0xFF7DE0A8),fontSize=8.sp,maxLines=1)
                        }
                        IconButton(onClick={ selected=null },modifier=Modifier.size((34f*s).dp)) {
                            Icon(Icons.Default.ChevronRight,"Detalhes",tint=Color(0xFFC6D0E7))
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().height((47f*s).dp),
                        horizontalArrangement=Arrangement.spacedBy((7f*s).dp)
                    ) {
                        Button(
                            onClick={ routeTo(place,false) },
                            modifier=Modifier.weight(1.15f).fillMaxHeight(),
                            shape=RoundedCornerShape((16f*s).dp),
                            colors=ButtonDefaults.buttonColors(containerColor=Purple),
                            contentPadding=PaddingValues(horizontal=4.dp)
                        ) {
                            Icon(Icons.Default.Directions,null,modifier=Modifier.size((17f*s).dp))
                            Spacer(Modifier.width((4f*s).dp)); Text("Rotas",fontSize=9.sp)
                        }
                        listOf(
                            Triple("Iniciar",Icons.Default.Navigation,{ routeTo(place,true) }),
                            Triple("Salvar",Icons.Outlined.BookmarkBorder,{ saveFavorite(place) }),
                            Triple("Compart.",Icons.Default.Share,{ sharePlace(place) })
                        ).forEach { (label,icon,action) ->
                            OutlinedButton(
                                onClick=action,
                                modifier=Modifier.weight(1f).fillMaxHeight(),
                                shape=RoundedCornerShape((16f*s).dp),
                                border=BorderStroke(1.dp,Color(0xFF596784)),
                                colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White),
                                contentPadding=PaddingValues(horizontal=3.dp)
                            ) {
                                Icon(icon,null,modifier=Modifier.size((15f*s).dp))
                                Spacer(Modifier.width((3f*s).dp))
                                Text(label,fontSize=8.sp,maxLines=1)
                            }
                        }
                    }
                }
            }
        }
    }

    if(showProfile) {
        AlertDialog(
            onDismissRequest={ showProfile=false },
            confirmButton={ TextButton(onClick={ showProfile=false }) { Text("Fechar") } },
            title={ Text("LINKNAV") },
            text={
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Favoritos: ${favorites.size}")
                    Text("Recentes: ${recents.size}")
                    Text(currentAddress.ifBlank { "Localização ainda não resolvida." })
                }
            }
        )
    }
}
