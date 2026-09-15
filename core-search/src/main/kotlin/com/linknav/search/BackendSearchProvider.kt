package com.linknav.search

import com.linknav.location.GeoPoint
import java.net.URLEncoder
import java.net.URL
import org.json.JSONArray

class BackendSearchProvider(private val baseUrl:String):SearchProvider {
    override suspend fun search(query:String,near:GeoPoint?,limit:Int):List<PlaceResult>{
        val q=URLEncoder.encode(query,"UTF-8")
        val nearQ=near?.let{"&lat=${it.latitude}&lon=${it.longitude}"}.orEmpty()
        val arr=JSONArray(URL("${baseUrl.trimEnd('/')}/search?q=$q&limit=$limit$nearQ").readText())
        return List(arr.length()){i-> val o=arr.getJSONObject(i); PlaceResult(o.optString("id","p$i"),o.getString("name"),o.optString("address"),GeoPoint(o.getDouble("lat"),o.getDouble("lon")),o.optString("category").ifBlank{null}) }
    }
}
