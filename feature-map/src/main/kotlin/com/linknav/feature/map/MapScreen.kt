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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.shadow
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

private const val SEARCH_PREFS="linknav_search"
private const val RECENTS_KEY="recent_places"
private const val FAVORITES_KEY="favorite_places"

private val Purple=Color(0xFF6F39F4)
private val Violet=Color(0xFF7B4DF4)
private val ElectricBlue=Color(0xFF2D80FF)
private val Navy=Color(0xFF171E35)
private val Navy2=Color(0xFF202944)
private val HomeCard=Color(0xFFF8F6FF)
private val HomeText=Color(0xFF16182D)
private val SoftBorder=Color(0xFFDAD7E9)

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
        "Farmácias" -> c=="pharmacy"
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
    "restaurant","cafe","bar","fast_food" -> 62
    else -> 48
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

@Composable
private fun LinkNavBrand(dark:Boolean,compact:Boolean=false) {
    val base=if(dark) Color.White else HomeText
    Row(verticalAlignment=Alignment.Bottom) {
        Text(
            "LINK",
            color=base,
            fontWeight=FontWeight.ExtraBold,
            fontSize=if(compact) 23.sp else 25.sp,
            letterSpacing=(-.7).sp
        )
        Text(
            "NAV",
            color=if(dark) Color(0xFF5B7CFF) else Purple,
            fontWeight=FontWeight.ExtraBold,
            fontSize=if(compact) 23.sp else 25.sp,
            letterSpacing=(-.7).sp
        )
    }
}

@Composable
private fun SearchBar(
    query:String,
    onQueryChange:(String)->Unit,
    dark:Boolean,
    onMic:()->Unit,
    onGo:()->Unit
) {
    val bg=if(dark) Color(0xFF16213A).copy(alpha=.93f) else Color.White.copy(alpha=.96f)
    val fg=if(dark) Color.White else HomeText
    val hint=if(dark) Color(0xFFCBD4E9) else Color(0xFF6B6D7C)
    Surface(
        modifier=Modifier.fillMaxWidth().height(58.dp),
        shape=RoundedCornerShape(20.dp),
        color=bg,
        border=BorderStroke(1.dp,if(dark) Color(0xFF566789) else Color(0xFFE2DEEF)),
        shadowElevation=if(dark) 0.dp else 7.dp
    ) {
        Row(
            Modifier.fillMaxSize().padding(start=16.dp,end=5.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search,null,tint=fg,modifier=Modifier.size(27.dp))
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value=query,
                onValueChange=onQueryChange,
                modifier=Modifier.weight(1f),
                singleLine=true,
                textStyle=TextStyle(color=fg,fontSize=17.sp,fontWeight=FontWeight.Medium),
                decorationBox={ inner ->
                    if(query.isBlank()) Text(
                        if(dark) "Pesquise lugares, endereços, categorias…" else "Pesquise aqui",
                        color=hint,
                        fontSize=if(dark) 14.sp else 18.sp,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis
                    )
                    inner()
                }
            )
            IconButton(onClick=onMic,modifier=Modifier.size(42.dp)) {
                Icon(Icons.Default.Mic,"Voz",tint=if(dark) Color(0xFFC9D3EC) else Color(0xFF2D3148))
            }
            Box(
                modifier=Modifier
                    .height(48.dp)
                    .width(if(dark) 72.dp else 76.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(Brush.horizontalGradient(listOf(Violet,Purple)))
                    .clickable(onClick=onGo),
                contentAlignment=Alignment.Center
            ) {
                Text("Ir",color=Color.White,fontSize=18.sp,fontWeight=FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MapToggle(
    satellite:Boolean,
    dark:Boolean,
    onChange:(Boolean)->Unit
) {
    Surface(
        shape=RoundedCornerShape(24.dp),
        color=if(dark) Color.Transparent else Color.White.copy(alpha=.74f),
        border=BorderStroke(1.dp,if(dark) Color(0xFF63708D) else Color(0xFFCAC5DE))
    ) {
        Row(Modifier.height(44.dp)) {
            listOf(false to "Mapa",true to "Satélite").forEach { (value,label) ->
                val selected=satellite==value
                Box(
                    modifier=Modifier
                        .width(if(dark) 82.dp else 94.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(21.dp))
                        .background(
                            if(selected) Brush.horizontalGradient(listOf(Color(0xFF7850F8),Color(0xFF6032E6)))
                            else Brush.linearGradient(listOf(Color.Transparent,Color.Transparent))
                        )
                        .clickable { onChange(value) },
                    contentAlignment=Alignment.Center
                ) {
                    Text(
                        label,
                        color=if(selected) Color.White else if(dark) Color(0xFFE1E7F5) else HomeText,
                        fontWeight=FontWeight.SemiBold,
                        fontSize=15.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickCategory(
    name:String,
    icon:ImageVector,
    tint:Color,
    selected:Boolean,
    dark:Boolean,
    onClick:()->Unit
) {
    Surface(
        modifier=Modifier.height(48.dp).clickable(onClick=onClick),
        shape=RoundedCornerShape(17.dp),
        color=if(dark) Color(0xE627314C) else Color.White.copy(alpha=.95f),
        border=BorderStroke(1.dp,if(selected) Purple else if(dark) Color(0xFF53617C) else Color(0xFFE2E0EA)),
        shadowElevation=if(dark) 0.dp else 5.dp
    ) {
        Row(
            Modifier.padding(horizontal=14.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Icon(icon,null,tint=tint,modifier=Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                color=if(dark) Color.White else HomeText,
                fontWeight=FontWeight.SemiBold,
                fontSize=15.sp
            )
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
    var showAllRecents by remember { mutableStateOf(false) }
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

    val advancedMode=
        selected!=null ||
        selectedCategory!=null ||
        query.isNotBlank() ||
        (viewport?.zoom ?: 0.0)>=16.8

    val rankedPlaces=remember(visiblePlaces,selectedCategory,advancedMode) {
        visiblePlaces
            .filter { categoryMatch(it.category,selectedCategory) }
            .sortedByDescending { poiPriority(it.category) }
            .let { list ->
                if(advancedMode) list.take(220)
                else list.filter { poiPriority(it.category)>=70 }.take(45)
            }
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
        status="Salvo em favoritos"
    }

    fun sharePlace(place:PlaceResult) {
        val text=buildString {
            append(place.name)
            if(place.address.isNotBlank()) append("\n").append(place.address)
            append("\n").append(place.location.latitude).append(", ").append(place.location.longitude)
        }
        ctx.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type="text/plain"
                putExtra(Intent.EXTRA_TEXT,text)
            },"Compartilhar local"
        ))
    }

    fun routeTo(place:PlaceResult,startCamera:Boolean) {
        val origin=nav.location.point
        if(origin==null) {
            status="Aguardando GPS"
            return
        }
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
                status="${(route.durationSec/60).toInt()} min • ${"%.1f".format(route.distanceM/1000)} km"
                if(startCamera) onCamera()
            } else status="Não foi possível calcular a rota"
        }
    }

    fun choosePlace(place:PlaceResult) {
        selected=place
        query=place.name
        rememberRecent(place)
    }

    fun submitSearch() {
        suggestions.firstOrNull()?.let {
            choosePlace(it)
            return
        }
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
        if(cached.isNotEmpty()) {
            visiblePlaces=(visiblePlaces+cached).distinctBy { it.id }.takeLast(700)
        }
        val result=withContext(Dispatchers.IO) { poiRepository.load(pv) }
        if(result.places.isNotEmpty()) {
            visiblePlaces=(visiblePlaces+result.places).distinctBy { it.id }.takeLast(700)
        }
        if(result.stale && result.fromCache) status="Algumas informações podem estar desatualizadas"
    }

    LaunchedEffect(query,visiblePlaces) {
        val q=query.trim()
        if(q.isBlank()) {
            suggestions=emptyList()
            return@LaunchedEffect
        }
        val local=search.localSuggestions(q,visiblePlaces+recents+favorites,15)
        suggestions=local
        delay(if(q.length==1) 300 else if(q.length==2) 240 else 210)
        val remote=runCatching {
            withContext(Dispatchers.IO) { search.search(q,currentPoint,15) }
        }.getOrDefault(emptyList())
        suggestions=(local+remote).distinctBy { it.id }.take(15)
    }

    LaunchedEffect(currentPoint?.latitude,currentPoint?.longitude) {
        val p=currentPoint ?: return@LaunchedEffect
        delay(900)
        val reversed=runCatching {
            withContext(Dispatchers.IO) { search.reverse(p) }
        }.getOrNull()
        if(reversed!=null) currentAddress=
            if(reversed.address.isNotBlank()) reversed.address else reversed.name
    }

    val categories=listOf(
        Triple("Restaurantes",Icons.Default.Restaurant,Color(0xFFF57C00)),
        Triple("Postos",Icons.Default.LocalGasStation,Color(0xFF1682E7)),
        Triple("Hospitais",Icons.Default.LocalHospital,Color(0xFFE93B4F)),
        Triple("Farmácias",Icons.Default.LocalPharmacy,Color(0xFF21A567)),
        Triple("Mercados",Icons.Default.ShoppingCart,Color(0xFF1676E8)),
        Triple("Hotéis",Icons.Default.Hotel,Color(0xFF7B4BD8))
    )

    Box(Modifier.fillMaxSize()) {
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

        if(advancedMode) {
            Column(
                Modifier.fillMaxWidth().align(Alignment.TopCenter)
            ) {
                Surface(
                    modifier=Modifier.fillMaxWidth(),
                    color=Navy.copy(alpha=.96f),
                    shape=RoundedCornerShape(bottomStart=22.dp,bottomEnd=22.dp),
                    shadowElevation=9.dp
                ) {
                    Column(
                        Modifier.padding(start=14.dp,end=14.dp,top=11.dp,bottom=10.dp),
                        verticalArrangement=Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                LinkNavBrand(dark=true,compact=true)
                                Text(
                                    "Explore. Conecte. Chegue lá.",
                                    color=Color(0xFFB6C2E3),
                                    fontSize=12.sp
                                )
                            }
                            Surface(
                                modifier=Modifier.size(43.dp).clickable { showProfile=true },
                                shape=CircleShape,
                                color=Color(0xFF27324E),
                                border=BorderStroke(1.dp,Color(0xFF455575))
                            ) {
                                Box(contentAlignment=Alignment.Center) {
                                    Icon(Icons.Outlined.Person,"Perfil",tint=Color(0xFFDCE4FA))
                                }
                            }
                        }
                        SearchBar(
                            query=query,onQueryChange={ query=it },dark=true,
                            onMic={
                                speechLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE,"pt-BR")
                                })
                            },
                            onGo={ submitSearch() }
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(8.dp)
                        ) {
                            MapToggle(satellite=satellite,dark=true,onChange={ satellite=it })
                            Surface(
                                modifier=Modifier.weight(1f).height(44.dp),
                                shape=RoundedCornerShape(22.dp),
                                color=Color(0xFF202B45),
                                border=BorderStroke(1.dp,Color(0xFF5B6B89))
                            ) {
                                Row(
                                    Modifier.padding(horizontal=12.dp),
                                    verticalAlignment=Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Place,null,tint=Color.White,modifier=Modifier.size(18.dp))
                                    Spacer(Modifier.width(7.dp))
                                    Text(
                                        if(currentAddress.isBlank()) "Localizando…" else "Você está em: $currentAddress",
                                        color=Color(0xFFE5EAF8),fontSize=12.sp,
                                        maxLines=1,overflow=TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                LazyRow(
                    modifier=Modifier.fillMaxWidth().padding(top=6.dp,start=10.dp),
                    horizontalArrangement=Arrangement.spacedBy(7.dp),
                    contentPadding=PaddingValues(end=12.dp)
                ) {
                    items(categories) { (name,icon,tint) ->
                        QuickCategory(
                            name=name,icon=icon,tint=tint,
                            selected=selectedCategory==name,dark=true
                        ) {
                            selectedCategory=if(selectedCategory==name) null else name
                        }
                    }
                }
            }
        } else {
            Column(
                modifier=Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(start=14.dp,end=14.dp,top=14.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier=Modifier.fillMaxWidth(),
                    shape=RoundedCornerShape(28.dp),
                    color=HomeCard.copy(alpha=.95f),
                    border=BorderStroke(1.dp,Color.White.copy(alpha=.72f)),
                    shadowElevation=10.dp
                ) {
                    Column(
                        Modifier.padding(start=18.dp,end=18.dp,top=16.dp,bottom=15.dp),
                        verticalArrangement=Arrangement.spacedBy(11.dp)
                    ) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.LocationOn,null,tint=Purple,
                                modifier=Modifier.size(42.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                LinkNavBrand(dark=false)
                                Text(
                                    "Explore. Conecte. Chegue lá.",
                                    color=Color(0xFF77758A),fontSize=12.sp
                                )
                            }
                            Surface(
                                modifier=Modifier.size(46.dp).clickable { recenterToken++ },
                                shape=CircleShape,
                                color=Color(0xFFEDE8FF)
                            ) {
                                Box(contentAlignment=Alignment.Center) {
                                    Icon(Icons.Default.MyLocation,"Centralizar",tint=Color(0xFF21195B))
                                }
                            }
                        }
                        SearchBar(
                            query=query,onQueryChange={ query=it },dark=false,
                            onMic={
                                speechLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE,"pt-BR")
                                })
                            },
                            onGo={ submitSearch() }
                        )
                        MapToggle(satellite=satellite,dark=false,onChange={ satellite=it })
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Default.Place,null,tint=Purple,modifier=Modifier.size(21.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if(currentAddress.isBlank()) "Você está em: localizando…" else "Você está em: $currentAddress",
                                color=HomeText,fontSize=14.sp,fontWeight=FontWeight.SemiBold,
                                maxLines=1,overflow=TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                LazyRow(
                    horizontalArrangement=Arrangement.spacedBy(8.dp),
                    contentPadding=PaddingValues(horizontal=1.dp)
                ) {
                    items(categories) { (name,icon,tint) ->
                        QuickCategory(
                            name=name,icon=icon,tint=tint,
                            selected=selectedCategory==name,dark=false
                        ) { selectedCategory=if(selectedCategory==name) null else name }
                    }
                }

                if(query.isBlank() && recents.isNotEmpty()) {
                    Surface(
                        modifier=Modifier.fillMaxWidth(),
                        shape=RoundedCornerShape(25.dp),
                        color=HomeCard.copy(alpha=.94f),
                        shadowElevation=7.dp
                    ) {
                        Column(Modifier.padding(horizontal=17.dp,vertical=13.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text(
                                    "Recentes",color=HomeText,fontSize=18.sp,
                                    fontWeight=FontWeight.ExtraBold,modifier=Modifier.weight(1f)
                                )
                                Text(
                                    "Ver tudo  ›",color=Color(0xFF5E2FB3),
                                    fontSize=14.sp,fontWeight=FontWeight.SemiBold,
                                    modifier=Modifier.clickable { showAllRecents=true }
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            recents.firstOrNull()?.let { p ->
                                Surface(
                                    modifier=Modifier.fillMaxWidth().height(54.dp).clickable { choosePlace(p) },
                                    shape=RoundedCornerShape(18.dp),
                                    color=Color(0xFFEDE9FF)
                                ) {
                                    Row(
                                        Modifier.padding(horizontal=13.dp),
                                        verticalAlignment=Alignment.CenterVertically
                                    ) {
                                        Surface(shape=CircleShape,color=Color(0xFFE2DCFF),modifier=Modifier.size(36.dp)) {
                                            Box(contentAlignment=Alignment.Center) {
                                                Icon(Icons.Default.History,null,tint=Color(0xFF4D2BA8),modifier=Modifier.size(22.dp))
                                            }
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            p.name,modifier=Modifier.weight(1f),
                                            color=HomeText,fontSize=16.sp
                                        )
                                        Icon(Icons.Default.ChevronRight,null,tint=Color(0xFF51486D))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if(query.isNotBlank() && suggestions.isNotEmpty()) {
            Surface(
                modifier=Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(
                        start=14.dp,end=14.dp,
                        top=if(advancedMode) 124.dp else 150.dp
                    ),
                shape=RoundedCornerShape(22.dp),
                color=if(advancedMode) Navy2.copy(alpha=.98f) else Color.White.copy(alpha=.98f),
                shadowElevation=12.dp
            ) {
                LazyColumn(Modifier.heightIn(max=330.dp)) {
                    items(suggestions) { p ->
                        Row(
                            modifier=Modifier.fillMaxWidth().clickable { choosePlace(p) }
                                .padding(horizontal=14.dp,vertical=10.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier=Modifier.size(38.dp),
                                shape=CircleShape,
                                color=categoryTint(p.category).copy(alpha=.14f)
                            ) {
                                Box(contentAlignment=Alignment.Center) {
                                    Icon(
                                        categoryIcon(p.category),null,
                                        tint=categoryTint(p.category),modifier=Modifier.size(21.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    p.name,
                                    color=if(advancedMode) Color.White else HomeText,
                                    fontWeight=FontWeight.SemiBold,fontSize=15.sp
                                )
                                Text(
                                    listOf(categoryLabel(p.category),p.address)
                                        .filter { it.isNotBlank() }.joinToString(" • "),
                                    color=if(advancedMode) Color(0xFFB9C4E1) else Color(0xFF737483),
                                    fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis
                                )
                            }
                        }
                        HorizontalDivider(color=if(advancedMode) Color.White.copy(alpha=.07f) else Color.Black.copy(alpha=.06f))
                    }
                }
            }
        }

        if(advancedMode) {
            Column(
                modifier=Modifier.align(Alignment.CenterEnd).padding(end=12.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp),
                horizontalAlignment=Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier=Modifier.size(50.dp).clickable { northToken++ },
                    shape=CircleShape,color=Color.White.copy(alpha=.95f),shadowElevation=6.dp
                ) {
                    Box(contentAlignment=Alignment.Center) {
                        Icon(Icons.Default.Explore,"Norte",tint=Color(0xFF273149),modifier=Modifier.size(28.dp))
                    }
                }
                Surface(
                    modifier=Modifier.size(47.dp).clickable { satellite=!satellite },
                    shape=CircleShape,color=Navy.copy(alpha=.93f),shadowElevation=6.dp
                ) {
                    Box(contentAlignment=Alignment.Center) {
                        Icon(Icons.Outlined.Layers,"Camadas",tint=Color.White)
                    }
                }
                Spacer(Modifier.height(120.dp))
                Surface(
                    modifier=Modifier.size(56.dp).clickable { recenterToken++ },
                    shape=CircleShape,color=Color.White.copy(alpha=.97f),shadowElevation=7.dp
                ) {
                    Box(contentAlignment=Alignment.Center) {
                        Icon(Icons.Default.MyLocation,"Centralizar",tint=Color(0xFF1F2B47),modifier=Modifier.size(29.dp))
                    }
                }
                Surface(
                    modifier=Modifier.size(58.dp).clickable {
                        if(nav.route!=null) onCamera() else recenterToken++
                    },
                    shape=CircleShape,
                    color=Brush.linearGradient(listOf(Violet,Purple)).let { Color(0xFF6733E4) },
                    shadowElevation=8.dp
                ) {
                    Box(contentAlignment=Alignment.Center) {
                        Icon(Icons.Default.Navigation,"Navegar",tint=Color.White,modifier=Modifier.size(29.dp))
                    }
                }
            }
        } else {
            Surface(
                modifier=Modifier.align(Alignment.CenterEnd).padding(end=15.dp).size(54.dp).clickable { recenterToken++ },
                shape=CircleShape,color=Color.White.copy(alpha=.96f),shadowElevation=7.dp
            ) {
                Box(contentAlignment=Alignment.Center) {
                    Icon(Icons.Default.MyLocation,"Centralizar",tint=Color(0xFF25265E),modifier=Modifier.size(29.dp))
                }
            }
        }

        Column(
            modifier=Modifier.align(Alignment.BottomEnd).padding(
                end=14.dp,
                bottom=if(selected==null) 20.dp else 146.dp
            ),
            verticalArrangement=Arrangement.spacedBy(9.dp),
            horizontalAlignment=Alignment.End
        ) {
            Surface(
                modifier=Modifier.width(112.dp).height(48.dp).clickable(onClick=onCamera),
                shape=RoundedCornerShape(20.dp),
                color=if(advancedMode) Color(0xFFF7F5FF).copy(alpha=.96f) else Color(0xFFF8F4FF).copy(alpha=.96f),
                shadowElevation=7.dp
            ) {
                Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
                    Icon(Icons.Default.CameraAlt,null,tint=Color(0xFF3E176F),modifier=Modifier.size(23.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Câmera",color=Color(0xFF30184E),fontWeight=FontWeight.SemiBold)
                }
            }
            Surface(
                modifier=Modifier.width(112.dp).height(50.dp).clickable(onClick=onDuo),
                shape=RoundedCornerShape(20.dp),
                color=Purple,shadowElevation=8.dp
            ) {
                Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
                    Icon(Icons.Default.Groups,null,tint=Color.White,modifier=Modifier.size(24.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Duo",color=Color.White,fontWeight=FontWeight.Bold,fontSize=17.sp)
                }
            }
        }

        if(advancedMode && selected==null) {
            Surface(
                modifier=Modifier.align(Alignment.BottomStart).padding(start=14.dp,bottom=72.dp)
                    .width(92.dp).height(72.dp).clickable(onClick=onCamera),
                shape=RoundedCornerShape(13.dp),
                color=Navy.copy(alpha=.92f),
                border=BorderStroke(1.dp,Color.White.copy(alpha=.72f)),
                shadowElevation=7.dp
            ) {
                Box {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.linearGradient(listOf(Color(0xFF21304F),Color(0xFF11182A)))
                        )
                    )
                    Icon(
                        Icons.Default.CameraAlt,null,tint=Color(0xFF86A9FF),
                        modifier=Modifier.align(Alignment.Center).size(28.dp)
                    )
                    Surface(
                        modifier=Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                        color=Color(0xD9151A2B)
                    ) {
                        Row(Modifier.padding(horizontal=7.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Default.Map,null,tint=Color.White,modifier=Modifier.size(13.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Live View",color=Color.White,fontSize=11.sp)
                        }
                    }
                }
            }
        }

        Surface(
            modifier=Modifier.align(Alignment.BottomStart).padding(
                start=14.dp,bottom=if(selected==null) 16.dp else 146.dp
            ),
            shape=RoundedCornerShape(19.dp),
            color=if(advancedMode) Navy.copy(alpha=.92f) else Color(0xFFF8F6FF).copy(alpha=.96f),
            border=BorderStroke(1.dp,if(advancedMode) Color(0xFF52617D) else Color(0xFFD9D5E8)),
            shadowElevation=5.dp
        ) {
            Row(
                Modifier.padding(horizontal=12.dp,vertical=8.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                val accuracy=currentPoint?.accuracyM
                val gpsColor=when {
                    accuracy==null || !accuracy.isFinite() -> Color(0xFF8790A8)
                    accuracy<=12f -> Color(0xFF11BC79)
                    accuracy<=35f -> Color(0xFFE3A42A)
                    else -> Color(0xFFE05762)
                }
                Box(Modifier.size(9.dp).clip(CircleShape).background(gpsColor))
                Spacer(Modifier.width(7.dp))
                Text(
                    currentPoint?.let { "GPS ±${it.accuracyM.toInt()} m" } ?: "Localizando…",
                    color=if(advancedMode) Color.White else HomeText,
                    fontSize=13.sp,fontWeight=FontWeight.Medium
                )
                Spacer(Modifier.width(7.dp))
                Icon(Icons.Default.Info,null,tint=if(advancedMode) Color(0xFF9FB0D7) else Color(0xFF4277A9),modifier=Modifier.size(17.dp))
            }
        }

        selected?.let { place ->
            val dist=currentPoint?.let { NavigationSession.distance(it,place.location) }
            Surface(
                modifier=Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color=Color(0xF3151C31),
                shape=RoundedCornerShape(topStart=24.dp,topEnd=24.dp),
                shadowElevation=15.dp
            ) {
                Column(Modifier.padding(start=14.dp,end=14.dp,top=13.dp,bottom=10.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Surface(
                            modifier=Modifier.size(70.dp),
                            shape=RoundedCornerShape(10.dp),
                            color=categoryTint(place.category).copy(alpha=.18f),
                            border=BorderStroke(1.dp,Color.White.copy(alpha=.16f))
                        ) {
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.linearGradient(listOf(Color(0xFF243451),Color(0xFF151B2D)))
                                ),
                                contentAlignment=Alignment.Center
                            ) {
                                Icon(categoryIcon(place.category),null,tint=categoryTint(place.category),modifier=Modifier.size(34.dp))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                place.name,color=Color.White,fontSize=20.sp,
                                fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis
                            )
                            Text(
                                buildString {
                                    append(categoryLabel(place.category))
                                    if(dist!=null) append("  •  ").append(
                                        if(dist<1000) "${dist.toInt()} m"
                                        else "${"%.1f".format(Locale.US,dist/1000)} km"
                                    )
                                },
                                color=Color(0xFFC3CCDF),fontSize=13.sp
                            )
                            if(place.address.isNotBlank()) {
                                Text(
                                    place.address,color=Color(0xFF9FAECC),
                                    fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis
                                )
                            }
                            if(status.isNotBlank()) {
                                Text(status,color=Color(0xFF7DE2A8),fontSize=11.sp,maxLines=1)
                            }
                        }
                        IconButton(onClick={ selected=null }) {
                            Icon(Icons.Default.ChevronRight,"Fechar",tint=Color(0xFFCDD7ED))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        val actionShape=RoundedCornerShape(18.dp)
                        Button(
                            onClick={ routeTo(place,false) },
                            modifier=Modifier.weight(1f).height(43.dp),
                            shape=actionShape,
                            colors=ButtonDefaults.buttonColors(containerColor=Purple)
                        ) {
                            Icon(Icons.Default.Directions,null,modifier=Modifier.size(19.dp))
                            Spacer(Modifier.width(5.dp)); Text("Rotas")
                        }
                        OutlinedButton(
                            onClick={ routeTo(place,true) },
                            modifier=Modifier.weight(1f).height(43.dp),
                            shape=actionShape,
                            border=BorderStroke(1.dp,Color(0xFF5B6887)),
                            colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White)
                        ) {
                            Icon(Icons.Default.Navigation,null,modifier=Modifier.size(18.dp))
                            Spacer(Modifier.width(5.dp)); Text("Iniciar")
                        }
                        OutlinedButton(
                            onClick={ saveFavorite(place) },
                            modifier=Modifier.weight(1f).height(43.dp),
                            shape=actionShape,
                            border=BorderStroke(1.dp,Color(0xFF5B6887)),
                            contentPadding=PaddingValues(horizontal=5.dp),
                            colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White)
                        ) {
                            Icon(Icons.Outlined.BookmarkBorder,null,modifier=Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp)); Text("Salvar",fontSize=12.sp)
                        }
                        OutlinedButton(
                            onClick={ sharePlace(place) },
                            modifier=Modifier.weight(1f).height(43.dp),
                            shape=actionShape,
                            border=BorderStroke(1.dp,Color(0xFF5B6887)),
                            contentPadding=PaddingValues(horizontal=5.dp),
                            colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White)
                        ) {
                            Icon(Icons.Default.Share,null,modifier=Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp)); Text("Compart.",fontSize=11.sp)
                        }
                    }
                }
            }
        }
    }

    if(showAllRecents) {
        AlertDialog(
            onDismissRequest={ showAllRecents=false },
            confirmButton={
                TextButton(onClick={
                    recents=emptyList()
                    savePlaces(ctx,RECENTS_KEY,emptyList())
                    showAllRecents=false
                }) { Text("Limpar histórico") }
            },
            dismissButton={ TextButton(onClick={ showAllRecents=false }) { Text("Fechar") } },
            title={ Text("Recentes") },
            text={
                LazyColumn(Modifier.heightIn(max=360.dp)) {
                    items(recents) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                choosePlace(p); showAllRecents=false
                            }.padding(vertical=9.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.History,null)
                            Spacer(Modifier.width(9.dp))
                            Text(p.name,modifier=Modifier.weight(1f))
                            Icon(Icons.Default.ChevronRight,null)
                        }
                    }
                }
            }
        )
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
