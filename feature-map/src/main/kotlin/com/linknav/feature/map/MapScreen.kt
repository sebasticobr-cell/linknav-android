package com.linknav.feature.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun MapScreen(baseUrl:String,onDuo:()->Unit,onCamera:()->Unit,onRouteChanged:(com.linknav.routing.Route?)->Unit = {}){
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

    fun doSearch(){ if(query.isBlank())return; scope.launch { status="Pesquisando…"; runCatching{withContext(Dispatchers.IO){search.search(query,loc.point)}}.onSuccess{results=it;status=if(it.isEmpty())"Nada encontrado" else ""}.onFailure{status="Busca indisponível: ${it.message}"} } }
    fun navigate(place:PlaceResult){
        val origin=loc.point
        if(origin==null){ status="Aguardando GPS"; return }
        scope.launch {
            results=emptyList(); status="Calculando rota…"
            runCatching { withContext(Dispatchers.IO){ routing.route(origin,place.location,TravelMode.CAR).firstOrNull() } }
                .onSuccess { r ->
                    route=r
                    onRouteChanged(r)
                    status=r?.let { "${(it.durationSec/60).toInt()} min • ${"%.1f".format(it.distanceM/1000)} km" } ?: "Rota não encontrada"
                }
                .onFailure { status="Rota indisponível: ${it.message}" }
        }
    }

    Box(Modifier.fillMaxSize()){
        LinkNavMap(Modifier.fillMaxSize(),point=loc.point,route=route?.points.orEmpty())
        Column(Modifier.padding(16.dp).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Surface(tonalElevation=6.dp,shape=MaterialTheme.shapes.large){
                Column(Modifier.padding(10.dp)){
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        OutlinedTextField(value=query,onValueChange={query=it},label={Text("Para onde vamos?")},singleLine=true,modifier=Modifier.weight(1f))
                        Button(onClick={doSearch()}){Text("Ir")}
                    }
                    if(status.isNotBlank()) Text(status,style=MaterialTheme.typography.bodySmall)
                }
            }
            if(results.isNotEmpty()) Surface(tonalElevation=8.dp){
                LazyColumn(Modifier.heightIn(max=260.dp)){ items(results){p-> Column(Modifier.fillMaxWidth().clickable{navigate(p)}.padding(12.dp)){Text(p.name);Text(p.address,style=MaterialTheme.typography.bodySmall)};HorizontalDivider()} }
            }
        }
        Row(Modifier.padding(16.dp).fillMaxWidth().align(androidx.compose.ui.Alignment.BottomCenter),horizontalArrangement=Arrangement.SpaceBetween){
            Button(onClick=onCamera){Text("Câmera")}; Button(onClick=onDuo){Text("Duo")}
        }
    }
}
