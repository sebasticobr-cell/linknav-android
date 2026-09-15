package com.linknav.feature.map

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
import com.linknav.location.AndroidLocationEngine
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

@Composable
fun MapScreen(
    baseUrl:String,
    onDuo:()->Unit,
    onCamera:()->Unit,
    onRouteChanged:(com.linknav.routing.Route?)->Unit = {}
){
    val ctx=LocalContext.current
    val locationEngine=remember{AndroidLocationEngine(ctx.applicationContext)}
    val loc by locationEngine.updates().collectAsState(initial=LocationState())
    val search=remember(baseUrl){BackendSearchProvider(baseUrl)}
    val routing=remember(baseUrl){BackendRoutingProvider(baseUrl)}
    val scope=rememberCoroutineScope()
    var query by remember{mutableStateOf("")}
    var results by remember{mutableStateOf<List<PlaceResult>>(emptyList())}
    var route by remember{mutableStateOf<com.linknav.routing.Route?>(null)}
    var status by remember{mutableStateOf("")}
    var satellite by remember{mutableStateOf(false)}
    var currentAddress by remember{mutableStateOf("")}

    fun doSearch(text:String=query){
        if(text.isBlank()) return
        scope.launch {
            status="Pesquisando…"
            runCatching{withContext(Dispatchers.IO){search.search(text,loc.point,12)}}
                .onSuccess{results=it;status=if(it.isEmpty())"Nada encontrado" else ""}
                .onFailure{status="Busca indisponível: ${it.message}"}
        }
    }

    fun navigate(place:PlaceResult){
        val origin=loc.point
        if(origin==null){ status="Aguardando GPS"; return }
        scope.launch {
            results=emptyList(); status="Calculando rota…"
            runCatching { withContext(Dispatchers.IO){ routing.route(origin,place.location,TravelMode.CAR).firstOrNull() } }
                .onSuccess { r ->
                    route=r
                    onRouteChanged(r)
                    status=r?.let { "${(it.durationSec/60).toInt()} min • ${"%.1f".format(it.distanceM/1000)} km • ${place.name}" }
                        ?: "Rota não encontrada"
                }
                .onFailure { status="Rota indisponível: ${it.message}" }
        }
    }

    LaunchedEffect(query,loc.point){
        if(query.trim().length<2) return@LaunchedEffect
        delay(450)
        runCatching{withContext(Dispatchers.IO){search.search(query.trim(),loc.point,8)}}
            .onSuccess{results=it}
    }

    LaunchedEffect(loc.point?.latitude,loc.point?.longitude){
        val p=loc.point ?: return@LaunchedEffect
        runCatching{withContext(Dispatchers.IO){search.reverse(p)}}
            .onSuccess{place-> currentAddress=place?.address.orEmpty()}
    }

    val categories=listOf("Restaurantes","Postos","Hospitais","Farmácias","Mercados","Hotéis")

    Box(Modifier.fillMaxSize()){
        LinkNavMap(
            Modifier.fillMaxSize(),
            point=loc.point,
            route=route?.points.orEmpty(),
            satellite=satellite
        )

        Column(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalArrangement=Arrangement.spacedBy(8.dp)
        ){
            Surface(tonalElevation=8.dp,shape=MaterialTheme.shapes.large){
                Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
                        OutlinedTextField(
                            value=query,
                            onValueChange={query=it},
                            label={Text("Pesquise rua, bairro, comércio ou lugar")},
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

            if(query.isBlank() && results.isEmpty()){
                LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    items(categories){cat->
                        AssistChip(onClick={query=cat;doSearch(cat)},label={Text(cat)})
                    }
                }
            }

            if(results.isNotEmpty()){
                Surface(tonalElevation=10.dp,shape=MaterialTheme.shapes.large){
                    LazyColumn(Modifier.heightIn(max=330.dp)){
                        items(results){p->
                            Column(
                                Modifier.fillMaxWidth().clickable{navigate(p)}.padding(horizontal=14.dp,vertical=11.dp)
                            ){
                                Text(p.name,style=MaterialTheme.typography.titleSmall)
                                if(p.address.isNotBlank()) Text(p.address,style=MaterialTheme.typography.bodySmall)
                                p.category?.let { Text(it,style=MaterialTheme.typography.labelSmall) }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(14.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp),
            horizontalAlignment=Alignment.End
        ){
            FilledTonalButton(onClick=onCamera){Text(if(route==null)"Câmera" else "Câmera AR")}
            Button(onClick=onDuo){Text("Duo")}
        }
    }
}
