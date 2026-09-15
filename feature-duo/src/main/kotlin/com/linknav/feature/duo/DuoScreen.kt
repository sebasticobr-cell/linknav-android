package com.linknav.feature.duo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.linknav.location.AndroidLocationEngine
import com.linknav.location.GeoPoint
import com.linknav.messaging.ChatMessage
import com.linknav.messaging.DuoApiClient
import com.linknav.messaging.DuoCredentials
import com.linknav.network.DuoRealtimeClient
import com.linknav.network.EventTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

private fun websocketBase(baseUrl:String):String = when {
    baseUrl.startsWith("https://") -> "wss://"+baseUrl.removePrefix("https://").trimEnd('/')
    baseUrl.startsWith("http://") -> "ws://"+baseUrl.removePrefix("http://").trimEnd('/')
    else -> baseUrl.trimEnd('/')
}

@Composable
fun DuoScreen(baseUrl:String,onBack:()->Unit){
    var joinCode by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("Desconectado") }
    var credentials by remember { mutableStateOf<DuoCredentials?>(null) }
    var messageText by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var partnerOnline by remember { mutableStateOf(false) }
    var sharingLocation by remember { mutableStateOf(false) }
    var partnerLocation by remember { mutableStateOf<GeoPoint?>(null) }
    val context=LocalContext.current
    val api=remember(baseUrl){DuoApiClient(baseUrl)}
    val realtime=remember { DuoRealtimeClient() }
    val scope=rememberCoroutineScope()
    val locationEngine=remember(context) { AndroidLocationEngine(context.applicationContext) }
    val wsBase=remember(baseUrl){websocketBase(baseUrl)}

    DisposableEffect(Unit){ onDispose { realtime.close() } }

    fun run(block:suspend()->DuoCredentials){
        scope.launch {
            state="Conectando…"
            runCatching { withContext(Dispatchers.IO){block()} }
                .onSuccess { credentials=it; state="DUO CONECTADO" }
                .onFailure { state="Falha: ${it.message}" }
        }
    }

    val c=credentials
    LaunchedEffect(c?.sessionId,c?.token){
        if(c==null) return@LaunchedEffect
        realtime.connect(wsBase,c.sessionId,c.token).collect { event ->
            when(event.type){
                EventTypes.PRESENCE -> {
                    val p=runCatching{JSONObject(event.payload)}.getOrNull()
                    partnerOnline=p?.optString("state")=="online"
                }
                EventTypes.LOCATION -> {
                    val p=runCatching{JSONObject(event.payload)}.getOrNull() ?: return@collect
                    if(event.senderId!=c.memberId && p.has("lat") && p.has("lon")) {
                        partnerLocation=GeoPoint(
                            latitude=p.getDouble("lat"),
                            longitude=p.getDouble("lon"),
                            accuracyM=p.optDouble("accuracy",Double.NaN).toFloat(),
                            speedMps=p.optDouble("speed",0.0).toFloat(),
                            bearingDeg=p.optDouble("bearing",0.0).toFloat(),
                            timestampMs=event.timestampMs
                        )
                    }
                }
                EventTypes.MESSAGE -> {
                    val p=runCatching{JSONObject(event.payload)}.getOrNull() ?: return@collect
                    val text=p.optString("text").take(2000)
                    if(text.isNotBlank()) messages=messages + ChatMessage(
                        id=p.optString("id",UUID.randomUUID().toString()),
                        senderId=event.senderId,
                        text=text,
                        sentAtMs=event.timestampMs
                    )
                }
            }
        }
    }


    LaunchedEffect(c?.sessionId,c?.token,sharingLocation){
        if(c==null || !sharingLocation) return@LaunchedEffect
        var lastSent=0L
        locationEngine.updates().collect { stateNow ->
            val p=stateNow.point ?: return@collect
            val interval=when { p.speedMps>8f -> 1_000L; p.speedMps>1f -> 2_000L; else -> 5_000L }
            val t=System.currentTimeMillis()
            if(t-lastSent>=interval){
                lastSent=t
                runCatching {
                    realtime.send(wsBase,c.sessionId,c.token,EventTypes.LOCATION,
                        JSONObject().put("lat",p.latitude).put("lon",p.longitude)
                            .put("accuracy",p.accuracyM).put("speed",p.speedMps).put("bearing",p.bearingDeg).toString())
                }
            }
        }
    }

    fun sendMessage(){
        val cred=credentials ?: return
        val text=messageText.trim().take(2000)
        if(text.isBlank()) return
        val id=UUID.randomUUID().toString()
        messageText=""
        messages=messages + ChatMessage(id,cred.memberId,text)
        scope.launch {
            runCatching {
                realtime.send(wsBase,cred.sessionId,cred.token,EventTypes.MESSAGE,
                    JSONObject().put("id",id).put("text",text).toString())
            }.onFailure { state="Falha ao enviar: ${it.message}" }
        }
    }

    fun leave(){
        val cred=credentials
        if(cred==null){ onBack(); return }
        scope.launch {
            runCatching { withContext(Dispatchers.IO){api.leave(cred)} }
            credentials=null; messages=emptyList(); partnerOnline=false; partnerLocation=null; sharingLocation=false; state="Desconectado"; onBack()
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("LINK DUO",style=MaterialTheme.typography.headlineMedium)
        Text(state)
        if(credentials==null){
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(onClick={run{api.create("Convidado")}}){Text("Criar sessão")}
                OutlinedButton(onClick=onBack){Text("Voltar")}
            }
            OutlinedTextField(value=joinCode,onValueChange={joinCode=it.uppercase().take(6)},label={Text("Código para entrar")},singleLine=true)
            Button(enabled=joinCode.length==6,onClick={run{api.join(joinCode,"Convidado")}}){Text("Entrar")}
        } else {
            credentials?.code?.let{Text("Código: $it",style=MaterialTheme.typography.headlineSmall)}
            Text(if(partnerOnline) "PARCEIRO ONLINE" else "Aguardando parceiro / sem presença confirmada")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(onClick={sharingLocation=!sharingLocation}){ Text(if(sharingLocation) "PARAR COMPARTILHAMENTO" else "Compartilhar localização") }
                if(sharingLocation) Text("LOCALIZAÇÃO COMPARTILHADA",style=MaterialTheme.typography.labelMedium)
            }
            partnerLocation?.let { p ->
                Text("Parceiro: ${"%.5f".format(p.latitude)}, ${"%.5f".format(p.longitude)} • atualização em tempo real",style=MaterialTheme.typography.bodySmall)
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)){
                if(messages.isEmpty()) item { Text("Chat conectado. As mensagens desta sessão aparecem aqui.",style=MaterialTheme.typography.bodySmall) }
                items(messages,key={it.id}) { m ->
                    Surface(tonalElevation=2.dp,shape=MaterialTheme.shapes.medium){
                        Text(if(m.senderId==credentials?.memberId) "Você: ${m.text}" else "Parceiro: ${m.text}",Modifier.padding(10.dp))
                    }
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedTextField(value=messageText,onValueChange={messageText=it},label={Text("Mensagem")},modifier=Modifier.weight(1f),singleLine=true)
                Button(enabled=messageText.isNotBlank(),onClick={sendMessage()}){Text("Enviar")}
            }
            Text("Voz e vídeo: engine WebRTC e signaling existem no núcleo, mas a tela de chamada ainda não está conectada; os botões ficam desativados em vez de simular chamada.",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                AssistChip(enabled=false,onClick={},label={Text("Ligar")})
                AssistChip(enabled=false,onClick={},label={Text("Vídeo")})
                OutlinedButton(onClick={leave()}){Text("Encerrar Duo")}
            }
        }
    }
}
