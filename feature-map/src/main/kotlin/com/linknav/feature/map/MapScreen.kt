package com.linknav.feature.map

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.linknav.ar.DeviceOrientationEngine
import com.linknav.location.AndroidLocationEngine
import com.linknav.location.GeoPoint
import com.linknav.location.LocationState
import com.linknav.map.LinkNavMap
import com.linknav.routing.BackendRoutingProvider
import com.linknav.routing.TravelMode
import com.linknav.search.BackendSearchProvider
import com.linknav.search.PlaceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val SEARCH_PREFS="linknav_search"
private const val RECENTS_KEY="recent_places"

private fun loadRecents(context:Context):List<PlaceResult> = runCatching {
    val raw=context.getSharedPreferences(SEARCH_PREFS,Context.MODE_PRIVATE)
        .getString(RECENTS_KEY,"[]") ?: "[]"
    val arr=JSONArray(raw)
    List(arr.length()){i->
        val o=arr.getJSONObject(i)
        PlaceResult(
            o.getString("id"),
            o.getString("name"),
            o.optString("address"),
            GeoPoint(o.getDouble("lat"),o.getDouble("lon")),
            o.optString("category").ifBlank{null}
        )
    }
}.getOrDefault(emptyList())

private fun saveRecents(context:Context,items:List<PlaceResult>){
    val arr=JSONArray()
    items.take(15).forEach { p ->
        arr.put(JSONObject().apply{
            put("id",p.id)
            put("name",p.name)
            put("address",p.address)
            put("lat",p.location.latitude)
            put("lon",p.location.longitude)
            put("category",p.category ?: "")
        })
    }
    context.getSharedPreferences(SEARCH_PREFS,Context.MODE_PRIVATE)
        .edit().putString(RECENTS_KEY,arr.toString()).apply()
}

@Composable
fun MapScreen(
    baseUrl:String,
    onDuo:()->Unit,
    onCamera:()->Unit,
    onRouteChanged:(com.linknav.routing.Route?)->Unit = {}
){
    val ctx=LocalContext.current
    val locationEngine=remember{AndroidLocationEngine(ctx.applicationContext)}
    val orientation=remember{DeviceOrientationEngine(ctx.applicationContext)}
    val loc by locationEngine.updates().collectAsState(initial=LocationState())
    val heading by orientation.headings().collectAsState(initial=Float.NaN)

    val search=remember(baseUrl){BackendSearchProvider(baseUrl)}
    val routing=remember(baseUrl){BackendRoutingProvider(baseUrl)}
    val scope=rememberCoroutineScope()

    var query by remember{mutableStateOf("")}
    var results by remember{mutableStateOf<List<PlaceResult>>(emptyList())}
    var recents by remember{mutableStateOf(loadRecents(ctx))}
    var route by remember{mutableStateOf<com.linknav.routing.Route?>(null)}
    var status by remember{mutableStateOf("")}
    var satellite by remember{mutableStateOf(false)}
    var currentAddress by remember{mutableStateOf("")}

    val mapPoint=loc.point?.let { p ->
        if(!heading.isNaN()) p.copy(bearingDeg=heading) else p
    }

    fun rememberPlace(place:PlaceResult){
        recents=(listOf(place)+recents.filterNot{it.id==place.id}).take(15)
        saveRecents(ctx,recents)
    }

    fun doSearch(text:String=query){
        val q=text.trim()
        if(q.isBlank()) return
        scope.launch {
            status="Pesquisando…"
            val recentMatches=recents.filter {
                it.name.contains(q,true) || it.address.contains(q,true)
            }
            runCatching{withContext(Dispatchers.IO){search.search(q,loc.point,15)}}
                .onSuccess{
                    results=(recentMatches+it).distinctBy{p->p.id}.take(15)
                    status=if(results.isEmpty())"Nada encontrado" else ""
                }
                .onFailure{
                    results=recentMatches
                    status=if(recentMatches.isEmpty())"Busca indisponível: ${it.message}" else ""
                }
        }
    }

    fun navigate(place:PlaceResult){
        rememberPlace(place)
        val origin=loc.point
        if(origin==null){ status="Aguardando GPS"; return }
        scope.launch {
            results=emptyList()
            query=place.name
            status="Calculando rota…"
            runCatching {
                withContext(Dispatchers.IO){
                    routing.route(origin,place.location,TravelMode.CAR).firstOrNull()
                }
            }.onSuccess { r ->
                route=r
                onRouteChanged(r)
                status=r?.let {
                    "${(it.durationSec/60).toInt()} min • ${"%.1f".format(it.distanceM/1000)} km • ${place.name}"
                } ?: "Rota não encontrada"
            }.onFailure {
                status="Rota indisponível: ${it.message}"
            }
        }
    }

    LaunchedEffect(query){
        val q=query.trim()
        if(q.isBlank()){
            results=emptyList()
            status=""
            return@LaunchedEffect
        }
        delay(if(q.length==1) 520 else 260)

        val recentMatches=recents.filter {
            it.name.contains(q,true) || it.address.contains(q,true)
        }
        val remote=runCatching{
            withContext(Dispatchers.IO){search.search(q,loc.point,15)}
        }.getOrDefault(emptyList())

        results=(recentMatches+remote)
            .distinctBy{it.id}
            .take(15)
    }

    LaunchedEffect(loc.point?.latitude,loc.point?.longitude){
        val p=loc.point ?: return@LaunchedEffect
        delay(700)
        runCatching{withContext(Dispatchers.IO){search.reverse(p)}}
            .onSuccess{place-> currentAddress=place?.address.orEmpty()}
    }

    val categories=listOf("Restaurantes","Postos","Hospitais","Farmácias","Mercados","Hotéis")

    Box(Modifier.fillMaxSize()){
        LinkNavMap(
            Modifier.fillMaxSize(),
            point=mapPoint,
            route=route?.points.orEmpty(),
            satellite=satellite
        )

        Column(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalArrangement=Arrangement.spacedBy(8.dp)
        ){
            Surface(tonalElevation=8.dp,shape=MaterialTheme.shapes.large){
                Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Row(
                        horizontalArrangement=Arrangement.spacedBy(8.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ){
                        OutlinedTextField(
                            value=query,
                            onValueChange={query=it},
                            label={Text("Pesquise aqui")},
                            placeholder={Text("Rua, bairro, comércio, lugar…")},
                            singleLine=true,
                            modifier=Modifier.weight(1f)
                        )
                        Button(onClick={doSearch()}){Text("Ir")}
                    }

                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        FilterChip(selected=!satellite,onClick={satellite=false},label={Text("Mapa")})
                        FilterChip(selected=satellite,onClick={satellite=true},label={Text("Satélite")})
                    }

                    if(currentAddress.isNotBlank() && query.isBlank()) {
                        Text("Você está em: $currentAddress",style=MaterialTheme.typography.bodySmall)
                    }
                    if(status.isNotBlank()) Text(status,style=MaterialTheme.typography.bodySmall)
                }
            }

            if(query.isBlank()){
                LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    items(categories){cat->
                        AssistChip(onClick={query=cat},label={Text(cat)})
                    }
                }

                if(recents.isNotEmpty()){
                    Surface(tonalElevation=9.dp,shape=MaterialTheme.shapes.large){
                        Column{
                            Text(
                                "Recentes",
                                modifier=Modifier.padding(horizontal=14.dp,vertical=10.dp),
                                style=MaterialTheme.typography.titleSmall
                            )
                            recents.take(6).forEach { p ->
                                Column(
                                    Modifier.fillMaxWidth()
                                        .clickable{navigate(p)}
                                        .padding(horizontal=14.dp,vertical=9.dp)
                                ){
                                    Text(p.name)
                                    if(p.address.isNotBlank()) Text(
                                        p.address,
                                        style=MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if(results.isNotEmpty()){
                Surface(tonalElevation=10.dp,shape=MaterialTheme.shapes.large){
                    LazyColumn(Modifier.heightIn(max=380.dp)){
                        items(results){p->
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable{navigate(p)}
                                    .padding(horizontal=14.dp,vertical=11.dp)
                            ){
                                Text(p.name,style=MaterialTheme.typography.titleSmall)
                                if(p.address.isNotBlank()) Text(
                                    p.address,
                                    style=MaterialTheme.typography.bodySmall
                                )
                                p.category?.let {
                                    Text(it,style=MaterialTheme.typography.labelSmall)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        Surface(
            modifier=Modifier.align(Alignment.BottomStart).padding(14.dp),
            shape=MaterialTheme.shapes.large,
            tonalElevation=8.dp
        ){
            Text(
                text=loc.point?.let{
                    "GPS ±${it.accuracyM.toInt()} m${if(loc.gpsWeak) " • fraco" else ""}"
                } ?: "Buscando GPS…",
                modifier=Modifier.padding(horizontal=12.dp,vertical=8.dp),
                style=MaterialTheme.typography.labelMedium
            )
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(14.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp),
            horizontalAlignment=Alignment.End
        ){
            FilledTonalButton(onClick=onCamera){
                Text(if(route==null)"Câmera" else "Câmera AR")
            }
            Button(onClick=onDuo){Text("Duo")}
        }
    }
}
