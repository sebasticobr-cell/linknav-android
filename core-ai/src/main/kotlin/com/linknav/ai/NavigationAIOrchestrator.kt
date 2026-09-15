package com.linknav.ai

import com.linknav.location.GeoPoint
import com.linknav.routing.*
import com.linknav.search.*

sealed interface ToolResult {
    data class Text(val value:String):ToolResult
    data class Routes(val value:List<Route>):ToolResult
    data class Places(val value:List<PlaceResult>):ToolResult
    data class Session(val code:String):ToolResult
    data class Action(val name:String,val message:String):ToolResult
}

interface NavigationTools {
    suspend fun getCurrentLocation():GeoPoint?
    suspend fun searchPlace(query:String):List<PlaceResult>
    suspend fun calculateRoute(destination:GeoPoint, preference:RoutePreference=RoutePreference()):List<Route>
    suspend fun recalculateRoute():List<Route>
    suspend fun avoidRoad(name:String):List<Route>
    suspend fun addStop(place:GeoPoint):List<Route>
    suspend fun changeDestination(place:GeoPoint):List<Route>
    suspend fun findFuel():List<PlaceResult>
    suspend fun findRestaurant():List<PlaceResult>
    suspend fun findHospital():List<PlaceResult>
    suspend fun downloadMap():Boolean
    suspend fun startDuoSession():String
    suspend fun joinDuoSession(code:String):Boolean
    suspend fun callPartner(video:Boolean=false):Boolean
    suspend fun sendMessage(text:String)
    suspend fun getPartnerLocation():GeoPoint?
    suspend fun calculateMeetingPoint():PlaceResult?
    suspend fun startCameraNavigation()
}

class NavigationAIOrchestrator(private val tools:NavigationTools) {
    suspend fun execute(raw:String):ToolResult {
        val q=raw.trim().lowercase()
        return when {
            q.contains("onde estou") -> ToolResult.Text(tools.getCurrentLocation()?.let { "Você está próximo de ${it.latitude}, ${it.longitude}." } ?: "Localização indisponível.")
            q.contains("onde podemos nos encontrar") || q.contains("lugar para nós dois") || q.contains("lugar para os dois") -> {
                val p=tools.calculateMeetingPoint(); if(p!=null) ToolResult.Places(listOf(p)) else ToolResult.Text("Ainda não consegui calcular um ponto de encontro acessível para os dois.")
            }
            q.contains("posto") -> ToolResult.Places(tools.findFuel())
            q.contains("restaurante") -> ToolResult.Places(tools.findRestaurant())
            q.contains("hospital") -> ToolResult.Places(tools.findHospital())
            q.contains("liga para") || q.contains("ligue para") -> ToolResult.Action("call",if(tools.callPartner(false))"Chamada iniciada." else "Não foi possível iniciar a chamada.")
            q.contains("vídeo") && (q.contains("liga") || q.contains("chamada")) -> ToolResult.Action("video_call",if(tools.callPartner(true))"Videochamada iniciada." else "Não foi possível iniciar a videochamada.")
            q.contains("abre a câmera") || q.contains("abrir câmera") -> { tools.startCameraNavigation(); ToolResult.Action("camera","LINKNAV VISION aberto.") }
            q.startsWith("mande ") || q.startsWith("envie ") -> { val msg=raw.substringAfter(' '); tools.sendMessage(msg); ToolResult.Action("message","Mensagem enviada.") }
            q.contains("onde ele está") || q.contains("onde ela está") || q.contains("onde meu parceiro") -> ToolResult.Text(tools.getPartnerLocation()?.let { "Parceiro em ${it.latitude}, ${it.longitude}." } ?: "Localização do parceiro indisponível.")
            q.startsWith("evite ") -> ToolResult.Routes(tools.avoidRoad(raw.substringAfter(' ')))
            q.startsWith("me leve para ") || q.startsWith("me leve até ") || q.startsWith("ir para ") -> {
                val target=raw.substringAfter("para ").substringAfter("até ")
                val place=tools.searchPlace(target).firstOrNull() ?: return ToolResult.Text("Não encontrei esse destino.")
                ToolResult.Routes(tools.changeDestination(place.location))
            }
            q.startsWith("procura ") || q.startsWith("procure ") || q.startsWith("encontre ") -> ToolResult.Places(tools.searchPlace(raw.substringAfter(' ')))
            q.contains("baixar mapa") || q.contains("mapa offline") -> ToolResult.Action("download_map",if(tools.downloadMap())"Download do mapa iniciado." else "Não consegui iniciar o mapa offline.")
            q.contains("criar sessão") || q.contains("iniciar duo") -> ToolResult.Session(tools.startDuoSession())
            else -> ToolResult.Text("Esse comando exige a IA online ou uma ferramenta que ainda não está disponível neste contexto. Não executei nenhuma ação sem confirmação do sistema.")
        }
    }
}
