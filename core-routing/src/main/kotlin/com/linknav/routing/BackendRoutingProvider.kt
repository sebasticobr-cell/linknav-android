package com.linknav.routing

import com.linknav.location.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class BackendRoutingProvider(private val baseUrl:String) : RoutingProvider {
    override suspend fun route(origin:GeoPoint,destination:GeoPoint,mode:TravelMode,preference:RoutePreference,alternatives:Int):List<Route> {
        val body = JSONObject().apply {
            put("origin", JSONObject().put("lat",origin.latitude).put("lon",origin.longitude))
            put("destination", JSONObject().put("lat",destination.latitude).put("lon",destination.longitude))
            put("mode",mode.name.lowercase()); put("alternatives",alternatives)
            put("avoidTolls",preference.avoidTolls); put("avoidHighways",preference.avoidHighways)
        }.toString()
        val c=(URL("$baseUrl/route").openConnection() as HttpURLConnection).apply { requestMethod="POST"; doOutput=true; setRequestProperty("Content-Type","application/json"); connectTimeout=7000; readTimeout=12000 }
        c.outputStream.use { it.write(body.toByteArray()) }
        if (c.responseCode !in 200..299) error("Routing HTTP ${c.responseCode}")
        val json=JSONObject(c.inputStream.bufferedReader().readText())
        val arr=json.getJSONArray("routes")
        return List(arr.length()) { i ->
            val r=arr.getJSONObject(i); val shape=r.getJSONArray("points")
            val pts=List(shape.length()){j-> val q=shape.getJSONObject(j); GeoPoint(q.getDouble("lat"),q.getDouble("lon")) }
            val ins=r.optJSONArray("instructions")
            val instructions=if(ins==null) emptyList() else List(ins.length()){j-> val x=ins.getJSONObject(j); RouteInstruction(x.optString("text"),x.optDouble("distanceM"),x.optString("maneuver")) }
            Route(r.optString("id","route-$i"),pts,r.getDouble("distanceM"),r.getDouble("durationSec"),instructions,r.optString("label"))
        }
    }
}
