package com.linknav.feature.map

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

private const val SEARCH_PREFS="linknav_search"
private const val RECENTS_KEY="recent_places"
private const val FAVORITES_KEY="favorite_places"

private fun loadPlaces(context:Context,key:String):List<PlaceResult> = runCatching {
    val raw=context.getSharedPreferences(SEARCH_PREFS,Context.MODE_PRIVATE)
        .getString(key,"[]") ?: "[]"
    val arr=JSONArray(raw)
    List(arr.length()) { i ->
        val o=arr.getJSONObject(i)
        PlaceResult(
            o.getString("id"),
            o.getString("name"),
            o.optString("address"),
            GeoPoint(o.getDouble("lat"),o.getDouble("lon")),
            o.optString("category").ifBlank { null }
        )
    }
}.getOrDefault(emptyList())

private fun savePlaces(context:Context,key:String,items:List<PlaceResult>) {
    val arr=JSONArray()
    items.take(24).forEach { p ->
        arr.put(JSONObject().apply {
            put("id",p.id)
            put("name",p.name)
            put("address",p.address)
            put("lat",p.location.latitude)
            put("lon",p.location.longitude)
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
        "Hospitais" -> c in setOf("hospital","clinic","doctors")
        "Farmácias" -> c=="pharmacy"
        "Mercados" -> c in setOf("supermarket","convenience","grocery")
        "Hotéis" -> c in setOf("hotel","hostel","guest_house","motel")
        else -> true
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
    val poiRepository=remember {
        ViewportPoiRepository(ctx.applicationContext,search)
    }
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
    var recents by remember { mutableStateOf(loadPlaces(ctx,RECENTS_KEY)) }
    var favorites by remember { mutableStateOf(loadPlaces(ctx,FAVORITES_KEY)) }

    val currentPoint=nav.location.point?.let { p ->
        val h=nav.orientation.headingDeg
        if(!h.isNaN()) p.copy(bearingDeg=h) else p
    }

    val filteredPlaces=remember(visiblePlaces,selectedCategory) {
        visiblePlaces.filter { categoryMatch(it.category,selectedCategory) }
    }

    val mapPois=remember(filteredPlaces) {
        filteredPlaces.map {
            MapPoi(it.id,it.name,it.category,it.location)
        }
    }

    val selectedMapPoi=selected?.let {
        MapPoi(it.id,it.name,it.category,it.location)
    }

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
            if(place.address.isNotBlank()) {
                append("\n")
                append(place.address)
            }
            append("\n")
            append(place.location.latitude)
            append(", ")
            append(place.location.longitude)
        }
        val intent=Intent(Intent.ACTION_SEND).apply {
            type="text/plain"
            putExtra(Intent.EXTRA_TEXT,text)
        }
        ctx.startActivity(Intent.createChooser(intent,"Compartilhar local"))
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
                    routing.route(
                        origin,
                        place.location,
                        TravelMode.CAR,
                        alternatives=3
                    ).firstOrNull()
                }
            }.getOrNull()

            if(route!=null) {
                navigation.setRoute(route)
                status="${(route.durationSec/60).toInt()} min • ${"%.1f".format(route.distanceM/1000)} km"
                if(startCamera) onCamera()
            } else {
                status="Não foi possível calcular a rota"
            }
        }
    }

    LaunchedEffect(viewport) {
        val v=viewport ?: return@LaunchedEffect
        delay(450)
        val pv=PoiViewport(v.north,v.south,v.east,v.west,v.zoom)

        val cached=poiRepository.cached(pv)
        if(cached.isNotEmpty()) {
            visiblePlaces=(visiblePlaces+cached)
                .distinctBy { it.id }
                .takeLast(700)
        }

        val result=withContext(Dispatchers.IO) {
            poiRepository.load(pv)
        }

        if(result.places.isNotEmpty()) {
            visiblePlaces=(visiblePlaces+result.places)
                .distinctBy { it.id }
                .takeLast(700)
        }

        if(result.stale && result.fromCache) {
            status="Algumas informações podem estar desatualizadas"
        }
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
            withContext(Dispatchers.IO) {
                search.search(q,currentPoint,15)
            }
        }.getOrDefault(emptyList())

        suggestions=(local+remote)
            .distinctBy { it.id }
            .take(15)
    }

    LaunchedEffect(currentPoint?.latitude,currentPoint?.longitude) {
        val p=currentPoint ?: return@LaunchedEffect
        delay(800)
        val reversed=runCatching {
            withContext(Dispatchers.IO) { search.reverse(p) }
        }.getOrNull()

        if(reversed!=null) {
            currentAddress=if(reversed.address.isNotBlank()) reversed.address else reversed.name
        }
    }

    val categories=listOf(
        "Restaurantes" to Icons.Default.Restaurant,
        "Postos" to Icons.Default.LocalGasStation,
        "Hospitais" to Icons.Default.LocalHospital,
        "Farmácias" to Icons.Default.LocalPharmacy,
        "Mercados" to Icons.Default.ShoppingCart,
        "Hotéis" to Icons.Default.Hotel
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
            onViewportIdle={ viewport=it },
            onPoiClick={ id ->
                selected=visiblePlaces.firstOrNull { it.id==id }
            }
        )

        Column(
            modifier=Modifier.fillMaxWidth().padding(start=14.dp,end=14.dp,top=12.dp),
            verticalArrangement=Arrangement.spacedBy(9.dp)
        ) {
            Surface(
                color=MaterialTheme.colorScheme.surface.copy(alpha=.94f),
                shadowElevation=8.dp,
                shape=MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement=Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription=null,
                            tint=MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "LINKNAV",
                                style=MaterialTheme.typography.titleLarge,
                                fontWeight=FontWeight.ExtraBold
                            )
                            Text(
                                "Explore. Conecte. Chegue lá.",
                                style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value=query,
                            onValueChange={ query=it },
                            modifier=Modifier.weight(1f),
                            singleLine=true,
                            leadingIcon={
                                Icon(Icons.Default.Search,contentDescription=null)
                            },
                            placeholder={ Text("Lugares, endereços, categorias…") }
                        )
                        Button(onClick={ selected=suggestions.firstOrNull() }) {
                            Text("Ir")
                        }
                    }

                    Row(
                        verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected=!satellite,
                            onClick={ satellite=false },
                            label={ Text("Mapa") }
                        )
                        FilterChip(
                            selected=satellite,
                            onClick={ satellite=true },
                            label={ Text("Satélite") }
                        )
                    }

                    if(currentAddress.isNotBlank()) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Place,
                                contentDescription=null,
                                tint=MaterialTheme.colorScheme.primary,
                                modifier=Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Você está em: $currentAddress",
                                style=MaterialTheme.typography.bodyMedium,
                                maxLines=2
                            )
                        }
                    }
                }
            }

            LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                items(categories) { pair ->
                    val name=pair.first
                    val icon=pair.second
                    FilterChip(
                        selected=selectedCategory==name,
                        onClick={
                            selectedCategory=if(selectedCategory==name) null else name
                        },
                        label={ Text(name) },
                        leadingIcon={
                            Icon(icon,contentDescription=null,modifier=Modifier.size(18.dp))
                        }
                    )
                }
            }

            if(query.isBlank() && recents.isNotEmpty()) {
                Surface(
                    color=MaterialTheme.colorScheme.surface.copy(alpha=.92f),
                    shadowElevation=5.dp,
                    shape=MaterialTheme.shapes.extraLarge
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Recentes",fontWeight=FontWeight.Bold)
                        recents.take(3).forEach { p ->
                            Row(
                                modifier=Modifier.fillMaxWidth()
                                    .clickable { selected=p }
                                    .padding(vertical=8.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.History,contentDescription=null)
                                Spacer(Modifier.width(9.dp))
                                Column {
                                    Text(p.name)
                                    if(p.address.isNotBlank()) {
                                        Text(
                                            p.address,
                                            style=MaterialTheme.typography.bodySmall,
                                            color=MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if(query.isNotBlank() && suggestions.isNotEmpty()) {
                Surface(
                    color=MaterialTheme.colorScheme.surface.copy(alpha=.97f),
                    shadowElevation=7.dp,
                    shape=MaterialTheme.shapes.extraLarge
                ) {
                    LazyColumn(Modifier.heightIn(max=360.dp)) {
                        items(suggestions) { p ->
                            Row(
                                modifier=Modifier.fillMaxWidth()
                                    .clickable {
                                        selected=p
                                        query=p.name
                                        rememberRecent(p)
                                    }
                                    .padding(horizontal=14.dp,vertical=10.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Place,
                                    contentDescription=null,
                                    tint=MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name,fontWeight=FontWeight.SemiBold)
                                    if(p.address.isNotBlank()) {
                                        Text(
                                            p.address,
                                            style=MaterialTheme.typography.bodySmall,
                                            color=MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick={ recenterToken++ },
            modifier=Modifier.align(Alignment.CenterEnd).padding(end=16.dp),
            containerColor=MaterialTheme.colorScheme.surface
        ) {
            Icon(Icons.Default.MyLocation,contentDescription="Centralizar")
        }

        Column(
            modifier=Modifier.align(Alignment.BottomEnd).padding(
                end=16.dp,
                bottom=if(selected==null) 18.dp else 176.dp
            ),
            verticalArrangement=Arrangement.spacedBy(10.dp),
            horizontalAlignment=Alignment.End
        ) {
            FilledTonalButton(onClick=onCamera) {
                Icon(Icons.Default.CameraAlt,contentDescription=null)
                Spacer(Modifier.width(6.dp))
                Text("Câmera")
            }

            Button(onClick=onDuo) {
                Icon(Icons.Default.Groups,contentDescription=null)
                Spacer(Modifier.width(6.dp))
                Text("Duo")
            }
        }

        Surface(
            modifier=Modifier.align(Alignment.BottomStart).padding(
                start=14.dp,
                bottom=if(selected==null) 16.dp else 178.dp
            ),
            shape=MaterialTheme.shapes.large,
            color=MaterialTheme.colorScheme.surface.copy(alpha=.92f),
            shadowElevation=5.dp
        ) {
            Row(
                Modifier.padding(horizontal=12.dp,vertical=8.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                val accuracy=currentPoint?.accuracyM
                val gpsColor=when {
                    accuracy==null || !accuracy.isFinite() ->
                        MaterialTheme.colorScheme.onSurfaceVariant
                    accuracy<=12f -> Color(0xFF00A86B)
                    accuracy<=35f -> Color(0xFFE6A100)
                    else -> MaterialTheme.colorScheme.error
                }

                Icon(
                    Icons.Default.GpsFixed,
                    contentDescription=null,
                    tint=gpsColor,
                    modifier=Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    currentPoint?.let { "GPS ±${it.accuracyM.toInt()} m" }
                        ?: "Localizando…"
                )
            }
        }

        selected?.let { place ->
            Surface(
                modifier=Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                color=MaterialTheme.colorScheme.surface.copy(alpha=.97f),
                shadowElevation=12.dp,
                shape=MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement=Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Place,
                            contentDescription=null,
                            tint=MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                place.name,
                                style=MaterialTheme.typography.titleLarge,
                                fontWeight=FontWeight.Bold
                            )
                            if(place.address.isNotBlank()) {
                                Text(
                                    place.address,
                                    style=MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        IconButton(onClick={ selected=null }) {
                            Icon(Icons.Default.Close,contentDescription="Fechar")
                        }
                    }

                    if(status.isNotBlank()) {
                        Text(
                            status,
                            style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick={ routeTo(place,false) },
                            modifier=Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Directions,contentDescription=null)
                            Spacer(Modifier.width(4.dp))
                            Text("Rotas")
                        }

                        FilledTonalButton(
                            onClick={ routeTo(place,true) },
                            modifier=Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Navigation,contentDescription=null)
                            Spacer(Modifier.width(4.dp))
                            Text("Iniciar")
                        }

                        IconButton(onClick={ saveFavorite(place) }) {
                            Icon(Icons.Default.BookmarkBorder,contentDescription="Salvar")
                        }

                        IconButton(onClick={ sharePlace(place) }) {
                            Icon(Icons.Default.Share,contentDescription="Compartilhar")
                        }
                    }
                }
            }
        }
    }
}
