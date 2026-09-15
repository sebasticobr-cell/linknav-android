package com.linknav.search

import com.linknav.location.GeoPoint
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class BackendSearchProvider(private val baseUrl:String):SearchProvider {
    override suspend fun search(query:String,near:GeoPoint?,limit:Int):List<PlaceResult>{
        if(query.isBlank()) return emptyList()
        if(!baseUrl.contains(".invalid")) {
            runCatching { return searchBackend(query,near,limit) }
        }
        return searchPhoton(query,near,limit)
    }

    suspend fun reverse(point:GeoPoint):PlaceResult? = runCatching {
        val url="https://photon.komoot.io/reverse?lon=${point.longitude}&lat=${point.latitude}&lang=pt"
        val root=JSONObject(read(url))
        root.optJSONArray("features")?.let { features ->
            if(features.length()==0) null else featureToPlace(features.getJSONObject(0),0)
        }
    }.getOrNull()

    private fun searchBackend(query:String,near:GeoPoint?,limit:Int):List<PlaceResult>{
        val q=URLEncoder.encode(query,"UTF-8")
        val nearQ=near?.let{"&lat=${it.latitude}&lon=${it.longitude}"}.orEmpty()
        val arr=JSONArray(read("${baseUrl.trimEnd('/')}/search?q=$q&limit=$limit$nearQ"))
        return List(arr.length()){i->
            val o=arr.getJSONObject(i)
            PlaceResult(
                o.optString("id","p$i"),
                o.getString("name"),
                o.optString("address"),
                GeoPoint(o.getDouble("lat"),o.getDouble("lon")),
                o.optString("category").ifBlank{null}
            )
        }
    }

    private fun searchPhoton(query:String,near:GeoPoint?,limit:Int):List<PlaceResult>{
        val q=URLEncoder.encode(query,"UTF-8")
        val bias=near?.let{"&lat=${it.latitude}&lon=${it.longitude}&location_bias_scale=0.35"}.orEmpty()
        val root=JSONObject(read("https://photon.komoot.io/api/?q=$q&limit=$limit&lang=pt$bias"))
        val features=root.optJSONArray("features") ?: return emptyList()
        return List(features.length()){ i -> featureToPlace(features.getJSONObject(i),i) }
    }

    private fun featureToPlace(f:JSONObject,index:Int):PlaceResult{
        val props=f.optJSONObject("properties") ?: JSONObject()
        val coords=f.getJSONObject("geometry").getJSONArray("coordinates")
        val name=props.optString("name").ifBlank {
            props.optString("street").ifBlank {
                props.optString("city").ifBlank { "Local" }
            }
        }
        val parts=listOf(
            props.optString("street"),
            props.optString("housenumber"),
            props.optString("district").ifBlank { props.optString("locality") },
            props.optString("city"),
            props.optString("state"),
            props.optString("country")
        ).filter { it.isNotBlank() }.distinct()
        val id="${props.optString("osm_type")}-${props.optLong("osm_id",index.toLong())}"
        val category=props.optString("osm_value").ifBlank { props.optString("type") }.ifBlank { null }
        return PlaceResult(id,name,parts.joinToString(" • "),GeoPoint(coords.getDouble(1),coords.getDouble(0)),category)
    }

    private fun read(url:String):String{
        val c=(URL(url).openConnection() as HttpURLConnection).apply{
            requestMethod="GET"
            connectTimeout=6500
            readTimeout=9000
            setRequestProperty("Accept-Language","pt-BR,pt;q=0.9")
            setRequestProperty("User-Agent","LINKNAV/0.2 Android")
        }
        if(c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
