package com.linknav.messaging

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class DuoCredentials(val sessionId:String,val code:String?=null,val memberId:String,val token:String,val expiresAt:Long)
class DuoApiClient(private val baseUrl:String){
    suspend fun create(name:String):DuoCredentials { val o=post("/duo/create",JSONObject().put("name",name)); return DuoCredentials(o.getString("sessionId"),o.getString("code"),o.getString("memberId"),o.getString("token"),o.getLong("expiresAt")) }
    suspend fun join(code:String,name:String):DuoCredentials { val o=post("/duo/join",JSONObject().put("code",code).put("name",name)); return DuoCredentials(o.getString("sessionId"),null,o.getString("memberId"),o.getString("token"),o.getLong("expiresAt")) }
    suspend fun leave(c:DuoCredentials){ post("/duo/leave",JSONObject().put("sessionId",c.sessionId).put("token",c.token)) }
    suspend fun setDestination(c:DuoCredentials,lat:Double,lon:Double){ post("/duo/destination",JSONObject().put("sessionId",c.sessionId).put("token",c.token).put("destination",JSONObject().put("lat",lat).put("lon",lon))) }
    private fun post(path:String,body:JSONObject):JSONObject {
        val c=(URL(baseUrl.trimEnd('/')+path).openConnection() as HttpURLConnection).apply{requestMethod="POST";doOutput=true;setRequestProperty("Content-Type","application/json");connectTimeout=7000;readTimeout=10000}
        c.outputStream.use{it.write(body.toString().toByteArray())}
        val stream=if(c.responseCode in 200..299)c.inputStream else c.errorStream
        val txt=stream?.bufferedReader()?.readText().orEmpty(); if(c.responseCode !in 200..299) error("HTTP ${c.responseCode}: $txt")
        return JSONObject(txt)
    }
}
