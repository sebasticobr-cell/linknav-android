package com.linknav.routing

import com.linknav.location.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class BackendRoutingProvider(private val baseUrl:String) : RoutingProvider {
    override suspend fun route(origin:GeoPoint,destination:GeoPoint,mode:TravelMode,preference:RoutePreference,alternatives:Int):List<Route> {
        if(!baseUrl.contains(".invalid")) {
            runCatching { return routeBackend(origin,destination,mode,preference,alternatives) }
        }
        return routeOsrm(origin,destination,mode,alternatives)
    }

    private fun routeBackend(origin:GeoPoint,destination:GeoPoint,mode:TravelMode,preference:RoutePreference,alternatives:Int):List<Route>{
        val body = JSONObject().apply {
            put("origin", JSONObject().put("lat",origin.latitude).put("lon",origin.longitude))
            put("destination", JSONObject().put("lat",destination.latitude).put("lon",destination.longitude))
            put("mode",mode.name.lowercase()); put("alternatives",alternatives)
            put("avoidTolls",preference.avoidTolls); put("avoidHighways",preference.avoidHighways)
        }.toString()
        val c=(URL("$baseUrl/route").openConnection() as HttpURLConnection).apply {
            requestMethod="POST"; doOutput=true
            setRequestProperty("Content-Type","application/json")
            connectTimeout=7000; readTimeout=12000
        }
        c.outputStream.use { it.write(body.toByteArray()) }
        if (c.responseCode !in 200..299) error("Routing HTTP ${c.responseCode}")
        val json=JSONObject(c.inputStream.bufferedReader().readText())
        val arr=json.getJSONArray("routes")
        return List(arr.length()) { i ->
            val r=arr.getJSONObject(i); val shape=r.getJSONArray("points")
            val pts=List(shape.length()){j-> val q=shape.getJSONObject(j); GeoPoint(q.getDouble("lat"),q.getDouble("lon")) }
            val ins=r.optJSONArray("instructions")
            val instructions=if(ins==null) emptyList() else List(ins.length()){j->
                val x=ins.getJSONObject(j)
                RouteInstruction(x.optString("text"),x.optDouble("distanceM"),x.optString("maneuver"))
            }
            Route(r.optString("id","route-$i"),pts,r.getDouble("distanceM"),r.getDouble("durationSec"),instructions,r.optString("label"))
        }
    }

    private fun routeOsrm(origin:GeoPoint,destination:GeoPoint,mode:TravelMode,alternatives:Int):List<Route>{
        val profile="driving"
        val coords="${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}"
        val alt=if(alternatives>1) "true" else "false"
        val url="https://router.project-osrm.org/route/v1/$profile/$coords?alternatives=$alt&steps=true&geometries=geojson&overview=full"
        val c=(URL(url).openConnection() as HttpURLConnection).apply{
            requestMethod="GET"; connectTimeout=7000; readTimeout=15000
            setRequestProperty("User-Agent","LINKNAV/0.2 Android")
        }
        if(c.responseCode !in 200..299) error("OSRM HTTP ${c.responseCode}")
        val root=JSONObject(c.inputStream.bufferedReader().use{it.readText()})
        if(root.optString("code")!="Ok") error(root.optString("message","Rota não encontrada"))
        val routes=root.getJSONArray("routes")
        return List(routes.length()){i->
            val r=routes.getJSONObject(i)
            val coordsArr=r.getJSONObject("geometry").getJSONArray("coordinates")
            val pts=List(coordsArr.length()){j->
                val xy=coordsArr.getJSONArray(j)
                GeoPoint(xy.getDouble(1),xy.getDouble(0))
            }
            val instructions=mutableListOf<RouteInstruction>()
            val legs=r.optJSONArray("legs")
            if(legs!=null){
                for(li in 0 until legs.length()){
                    val steps=legs.getJSONObject(li).optJSONArray("steps") ?: continue
                    for(si in 0 until steps.length()){
                        val s=steps.getJSONObject(si)
                        val man=s.optJSONObject("maneuver") ?: JSONObject()
                        val type=man.optString("type")
                        val modifier=man.optString("modifier")
                        val road=s.optString("name")
                        instructions += RouteInstruction(
                            instructionText(type,modifier,road),
                            s.optDouble("distance",0.0),
                            listOf(type,modifier).filter{it.isNotBlank()}.joinToString("-")
                        )
                    }
                }
            }
            Route(
                id="osrm-$i",
                points=pts,
                distanceM=r.optDouble("distance",0.0),
                durationSec=r.optDouble("duration",0.0),
                instructions=instructions,
                label=if(i==0) "Mais rápida" else "Alternativa ${i+1}"
            )
        }
    }

    private fun instructionText(type:String,modifier:String,road:String):String{
        val dir=when(modifier){
            "left","slight left","sharp left" -> "à esquerda"
            "right","slight right","sharp right" -> "à direita"
            "uturn" -> "e faça o retorno"
            "straight" -> "em frente"
            else -> ""
        }
        val target=if(road.isBlank()) "" else " em $road"
        return when(type){
            "depart" -> "Siga$target"
            "arrive" -> "Você chegou ao destino"
            "turn" -> "Vire $dir$target".replace("  "," ")
            "new name","continue" -> "Continue $dir$target".replace("  "," ")
            "roundabout","rotary" -> "Entre na rotatória$target"
            "merge" -> "Entre $dir$target".replace("  "," ")
            "fork" -> "Mantenha-se $dir$target".replace("  "," ")
            else -> "Siga $dir$target".replace("  "," ").trim()
        }
    }
}
