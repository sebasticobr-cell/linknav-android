package com.linknav.network

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject

class DuoRealtimeClient {
    private val client=HttpClient(OkHttp){ install(WebSockets) { pingIntervalMillis=20_000 } }
    private val outgoing=Channel<String>(capacity=64)

    fun connect(wsUrl:String,sessionId:String,token:String):Flow<RealtimeEvent> = callbackFlow {
        val job=launch(Dispatchers.IO){
            while(isActive){
                try {
                    client.webSocket(urlString="$wsUrl/realtime/duo/$sessionId?token=$token") {
                        val sender=launch {
                            for(text in outgoing) send(Frame.Text(text))
                        }
                        try {
                            for(frame in incoming){
                                if(frame is Frame.Text){
                                    val o=JSONObject(frame.readText())
                                    trySend(RealtimeEvent(
                                        o.optString("type"),
                                        sessionId,
                                        o.optString("senderId"),
                                        o.opt("payload")?.toString() ?: "{}",
                                        o.optLong("timestamp",System.currentTimeMillis())
                                    ))
                                }
                            }
                        } finally { sender.cancel() }
                    }
                } catch(_:Throwable){
                    delay(1500)
                }
            }
        }
        awaitClose { job.cancel() }
    }

    suspend fun send(wsUrl:String,sessionId:String,token:String,type:String,payloadJson:String){
        // Parameters stay in the API so callers cannot accidentally send an event without session context.
        // The active connection owns the authenticated URL; queued messages survive short reconnects.
        require(wsUrl.isNotBlank() && sessionId.isNotBlank() && token.isNotBlank())
        val payload=runCatching { JSONObject(payloadJson) }.getOrElse { JSONObject().put("value",payloadJson) }
        outgoing.send(JSONObject().put("type",type).put("payload",payload).toString())
    }

    fun close(){ outgoing.close(); client.close() }
}
