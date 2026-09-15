package com.linknav.search

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor

data class PoiViewport(
    val north:Double,
    val south:Double,
    val east:Double,
    val west:Double,
    val zoom:Double
)

data class PoiLoadResult(
    val places:List<PlaceResult>,
    val fromCache:Boolean,
    val stale:Boolean
)

class ViewportPoiRepository(
    context:Context,
    private val provider:BackendSearchProvider
) {
    private val prefs=context.applicationContext.getSharedPreferences(
        "linknav_poi_cache",
        Context.MODE_PRIVATE
    )

    suspend fun load(viewport:PoiViewport):PoiLoadResult {
        if(viewport.zoom < 9.0) return PoiLoadResult(emptyList(),true,false)

        val key=cacheKey(viewport)
        val now=System.currentTimeMillis()
        val cached=read(key)
        val ttl=when {
            viewport.zoom >= 16.0 -> 3L*60L*60L*1000L
            viewport.zoom >= 13.0 -> 6L*60L*60L*1000L
            else -> 12L*60L*60L*1000L
        }

        if(cached!=null && now-cached.first < ttl) {
            return PoiLoadResult(cached.second,true,false)
        }

        val fresh=provider.viewportPlaces(
            north=viewport.north,
            south=viewport.south,
            east=viewport.east,
            west=viewport.west,
            zoom=viewport.zoom
        )

        if(fresh.isNotEmpty()) {
            write(key,now,fresh)
            trimCache()
            return PoiLoadResult(fresh,false,false)
        }

        return if(cached!=null) {
            PoiLoadResult(cached.second,true,true)
        } else {
            PoiLoadResult(emptyList(),false,true)
        }
    }

    fun cached(viewport:PoiViewport):List<PlaceResult> =
        read(cacheKey(viewport))?.second.orEmpty()

    private fun cacheKey(v:PoiViewport):String {
        val band=when {
            v.zoom<12.0 -> 11
            v.zoom<14.0 -> 13
            v.zoom<16.0 -> 15
            else -> 17
        }
        val cell=when(band) {
            11 -> .08
            13 -> .04
            15 -> .02
            else -> .009
        }
        val lat=floor(((v.north+v.south)/2.0)/cell).toInt()
        val lon=floor(((v.east+v.west)/2.0)/cell).toInt()
        return "z$band:$lat:$lon"
    }

    private fun write(
        key:String,
        timestamp:Long,
        places:List<PlaceResult>
    ) {
        val array=JSONArray()
        places.take(420).forEach { p ->
            array.put(JSONObject().apply {
                put("id",p.id)
                put("name",p.name)
                put("address",p.address)
                put("lat",p.location.latitude)
                put("lon",p.location.longitude)
                put("category",p.category ?: "")
            })
        }
        val payload=JSONObject()
            .put("time",timestamp)
            .put("places",array)
            .toString()

        val keys=prefs.getStringSet("keys",emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        keys += key

        prefs.edit()
            .putString(key,payload)
            .putStringSet("keys",keys)
            .apply()
    }

    private fun read(key:String):Pair<Long,List<PlaceResult>>? =
        runCatching {
            val raw=prefs.getString(key,null) ?: return null
            val obj=JSONObject(raw)
            val array=obj.getJSONArray("places")
            val places=List(array.length()) { i ->
                val p=array.getJSONObject(i)
                PlaceResult(
                    id=p.getString("id"),
                    name=p.getString("name"),
                    address=p.optString("address"),
                    location=com.linknav.location.GeoPoint(
                        p.getDouble("lat"),
                        p.getDouble("lon")
                    ),
                    category=p.optString("category").ifBlank { null }
                )
            }
            obj.getLong("time") to places
        }.getOrNull()

    private fun trimCache() {
        val keys=prefs.getStringSet("keys",emptySet())
            ?.toMutableSet() ?: return
        if(keys.size<=28) return

        val ordered=keys.mapNotNull { key ->
            read(key)?.first?.let { it to key }
        }.sortedBy { it.first }

        val remove=ordered.take((keys.size-28).coerceAtLeast(0))
        val editor=prefs.edit()
        remove.forEach { (_,key) ->
            editor.remove(key)
            keys.remove(key)
        }
        editor.putStringSet("keys",keys).apply()
    }
}
