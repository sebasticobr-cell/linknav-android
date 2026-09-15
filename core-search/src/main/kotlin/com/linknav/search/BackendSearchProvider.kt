package com.linknav.search

import com.linknav.location.GeoPoint
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONObject

class BackendSearchProvider(private val baseUrl:String):SearchProvider {
    override suspend fun search(query:String,near:GeoPoint?,limit:Int):List<PlaceResult>{
        val clean=query.trim()
        if(clean.isBlank()) return emptyList()

        if(!baseUrl.contains(".invalid")) {
            runCatching { return searchBackend(clean,near,limit) }
        }

        val nearbyPrefix = if(near != null && clean.length <= 2) {
            runCatching { nearbyPrefixPois(clean,near,8) }.getOrDefault(emptyList())
        } else emptyList()

        val nearResults = runCatching { photonRequest(clean,near,limit) }.getOrDefault(emptyList())
        val broadResults = if(clean.length <= 3) {
            runCatching { photonRequest(clean,null,(limit/2).coerceAtLeast(4)) }.getOrDefault(emptyList())
        } else emptyList()

        return (nearbyPrefix + nearResults + broadResults)
            .distinctBy { "${it.id}:${it.location.latitude}:${it.location.longitude}" }
            .take(limit)
    }

    suspend fun reverse(point:GeoPoint):PlaceResult? = runCatching {
        val url="https://photon.komoot.io/reverse?lon=${point.longitude}&lat=${point.latitude}"
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

    private fun photonRequest(query:String,near:GeoPoint?,limit:Int):List<PlaceResult>{
        val q=URLEncoder.encode(query,"UTF-8")
        val bias=near?.let{"&lat=${it.latitude}&lon=${it.longitude}&zoom=15&location_bias_scale=0.12"}.orEmpty()
        val root=JSONObject(read("https://photon.komoot.io/api/?q=$q&limit=$limit$bias"))
        val features=root.optJSONArray("features") ?: return emptyList()
        return List(features.length()){ i -> featureToPlace(features.getJSONObject(i),i) }
    }

    private fun nearbyPrefixPois(query:String,near:GeoPoint,limit:Int):List<PlaceResult>{
        val safe=query
            .filter { it.isLetterOrDigit() || it.isWhitespace() || it=='-' || it=='\'' || it=='.' }
            .trim()
            .replace("\"","")
        if(safe.isBlank()) return emptyList()

        val overpass = """
            [out:json][timeout:6];
            (
              nwr(around:12000,${near.latitude},${near.longitude})["amenity"]["name"~"^$safe",i];
              nwr(around:12000,${near.latitude},${near.longitude})["shop"]["name"~"^$safe",i];
              nwr(around:12000,${near.latitude},${near.longitude})["tourism"]["name"~"^$safe",i];
              nwr(around:12000,${near.latitude},${near.longitude})["office"]["name"~"^$safe",i];
            );
            out center $limit;
        """.trimIndent()

        val url="https://overpass-api.de/api/interpreter?data="+URLEncoder.encode(overpass,"UTF-8")
        val root=JSONObject(read(url))
        val elements=root.optJSONArray("elements") ?: return emptyList()
        val out=mutableListOf<PlaceResult>()
        for(i in 0 until elements.length()){
            val e=elements.getJSONObject(i)
            val tags=e.optJSONObject("tags") ?: continue
            val name=tags.optString("name")
            if(name.isBlank()) continue

            val lat:Double
            val lon:Double
            if(e.has("lat") && e.has("lon")){
                lat=e.getDouble("lat"); lon=e.getDouble("lon")
            }else{
                val center=e.optJSONObject("center") ?: continue
                lat=center.optDouble("lat",Double.NaN)
                lon=center.optDouble("lon",Double.NaN)
                if(!lat.isFinite() || !lon.isFinite()) continue
            }

            val address=listOf(
                tags.optString("addr:street"),
                tags.optString("addr:housenumber"),
                tags.optString("addr:suburb"),
                tags.optString("addr:city")
            ).filter { it.isNotBlank() }.joinToString(" • ")

            val category=listOf(
                tags.optString("amenity"),
                tags.optString("shop"),
                tags.optString("tourism"),
                tags.optString("office")
            ).firstOrNull { it.isNotBlank() }

            out += PlaceResult(
                id="osm-${e.optString("type")}-${e.optLong("id")}",
                name=name,
                address=address,
                location=GeoPoint(lat,lon),
                category=category
            )
        }
        return out.take(limit)
    }


    suspend fun nearbyIndex(near:GeoPoint,radiusM:Int=5000,limit:Int=320):List<PlaceResult> = runCatching {
        val overpass = """
            [out:json][timeout:12];
            (
              nwr(around:$radiusM,${near.latitude},${near.longitude})["name"]["amenity"];
              nwr(around:$radiusM,${near.latitude},${near.longitude})["name"]["shop"];
              nwr(around:$radiusM,${near.latitude},${near.longitude})["name"]["tourism"];
              nwr(around:$radiusM,${near.latitude},${near.longitude})["name"]["office"];
              nwr(around:$radiusM,${near.latitude},${near.longitude})["name"]["leisure"];
              nwr(around:$radiusM,${near.latitude},${near.longitude})["name"]["craft"];
              nwr(around:1600,${near.latitude},${near.longitude})["addr:housenumber"];
            );
            out center $limit;
        """.trimIndent().replace("$","$")

        val url="https://overpass-api.de/api/interpreter?data="+URLEncoder.encode(overpass,"UTF-8")
        val root=JSONObject(read(url))
        val elements=root.optJSONArray("elements") ?: return@runCatching emptyList()
        val out=mutableListOf<PlaceResult>()

        for(i in 0 until elements.length()){
            val e=elements.getJSONObject(i)
            val tags=e.optJSONObject("tags") ?: continue
            val name=tags.optString("name").ifBlank { tags.optString("addr:housenumber") }
            if(name.isBlank()) continue

            val lat:Double
            val lon:Double
            if(e.has("lat") && e.has("lon")){
                lat=e.getDouble("lat")
                lon=e.getDouble("lon")
            }else{
                val center=e.optJSONObject("center") ?: continue
                lat=center.optDouble("lat",Double.NaN)
                lon=center.optDouble("lon",Double.NaN)
                if(!lat.isFinite() || !lon.isFinite()) continue
            }

            val address=listOf(
                tags.optString("addr:street"),
                tags.optString("addr:housenumber"),
                tags.optString("addr:suburb"),
                tags.optString("addr:city")
            ).filter { it.isNotBlank() }.distinct().joinToString(" • ")

            val category=listOf(
                tags.optString("amenity"),
                tags.optString("shop"),
                tags.optString("tourism"),
                tags.optString("office"),
                tags.optString("leisure"),
                tags.optString("craft")
            ).firstOrNull { it.isNotBlank() }
                ?: if(tags.optString("addr:housenumber").isNotBlank()) "address" else null

            out += PlaceResult(
                id="osm-${e.optString("type")}-${e.optLong("id")}".replace("$","$"),
                name=name,
                address=address,
                location=GeoPoint(lat,lon),
                category=category
            )
        }
        out.distinctBy { it.id }.take(limit)
    }.getOrDefault(emptyList())

    fun localSuggestions(query:String,local:List<PlaceResult>,limit:Int=15):List<PlaceResult>{
        val q=normalize(query)
        if(q.isBlank()) return emptyList()

        return local.mapNotNull { place ->
            val n=normalize(place.name)
            val a=normalize(place.address)
            val score=when{
                n.startsWith(q) -> 0
                n.contains(q) -> 1
                a.startsWith(q) -> 2
                a.contains(q) -> 3
                fuzzyWords(n,q) -> 4
                else -> null
            }
            score?.let { it to place }
        }.sortedWith(
            compareBy<Pair<Int,PlaceResult>> { it.first }
                .thenBy { it.second.name.length }
        ).map { it.second }
            .distinctBy { it.id }
            .take(limit)
    }

    private fun normalize(value:String):String =
        Normalizer.normalize(value.lowercase(),Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"),"")
            .trim()

    private fun fuzzyWords(text:String,q:String):Boolean{
        if(q.length<3) return false
        val tolerance=when{
            q.length<=4 -> 1
            q.length<=7 -> 2
            else -> 3
        }
        return text.split(' ','-','/').any { token ->
            token.isNotBlank() && levenshtein(token.take(q.length+2),q) <= tolerance
        }
    }

    private fun levenshtein(a:String,b:String):Int{
        if(a==b) return 0
        if(a.isEmpty()) return b.length
        if(b.isEmpty()) return a.length
        var prev=IntArray(b.length+1){it}
        var curr=IntArray(b.length+1)
        for(i in a.indices){
            curr[0]=i+1
            for(j in b.indices){
                val cost=if(a[i]==b[j]) 0 else 1
                curr[j+1]=minOf(curr[j]+1,prev[j+1]+1,prev[j]+cost)
            }
            val t=prev
            prev=curr
            curr=t
        }
        return prev[b.length]
    }


    suspend fun viewportPlaces(
        north:Double,
        south:Double,
        east:Double,
        west:Double,
        zoom:Double,
        limit:Int=420
    ):List<PlaceResult> = runCatching {
        val selectors = when {
            zoom < 10.0 -> listOf(
                """nwr($south,$west,$north,$east)["aeroway"~"aerodrome|terminal"]["name"];""",
                """nwr($south,$west,$north,$east)["tourism"~"attraction|museum"]["name"];""",
                """nwr($south,$west,$north,$east)["amenity"~"hospital|university"]["name"];"""
            )
            zoom < 13.0 -> listOf(
                """nwr($south,$west,$north,$east)["amenity"~"hospital|university|school|fuel|bus_station"]["name"];""",
                """nwr($south,$west,$north,$east)["tourism"~"hotel|attraction|museum"]["name"];""",
                """nwr($south,$west,$north,$east)["leisure"~"park|stadium"]["name"];""",
                """nwr($south,$west,$north,$east)["shop"~"supermarket|mall"]["name"];"""
            )
            zoom < 15.0 -> listOf(
                """nwr($south,$west,$north,$east)["amenity"]["name"];""",
                """nwr($south,$west,$north,$east)["shop"~"supermarket|mall|department_store|convenience"]["name"];""",
                """nwr($south,$west,$north,$east)["tourism"]["name"];""",
                """nwr($south,$west,$north,$east)["leisure"]["name"];"""
            )
            else -> listOf(
                """nwr($south,$west,$north,$east)["amenity"]["name"];""",
                """nwr($south,$west,$north,$east)["shop"]["name"];""",
                """nwr($south,$west,$north,$east)["tourism"]["name"];""",
                """nwr($south,$west,$north,$east)["office"]["name"];""",
                """nwr($south,$west,$north,$east)["leisure"]["name"];""",
                """nwr($south,$west,$north,$east)["craft"]["name"];""",
                """nwr($south,$west,$north,$east)["historic"]["name"];""",
                """nwr($south,$west,$north,$east)["public_transport"]["name"];"""
            )
        }.map { it.replace("$south",south.toString())
                  .replace("$west",west.toString())
                  .replace("$north",north.toString())
                  .replace("$east",east.toString()) }

        val query = buildString {
            append("[out:json][timeout:12];(")
            selectors.forEach { append(it) }
            append(");out center ")
            append(limit)
            append(";")
        }

        val url="https://overpass-api.de/api/interpreter?data="+
            URLEncoder.encode(query,"UTF-8")
        val root=JSONObject(read(url))
        val elements=root.optJSONArray("elements") ?: return@runCatching emptyList()
        val out=mutableListOf<PlaceResult>()

        for(i in 0 until elements.length()){
            val e=elements.getJSONObject(i)
            val tags=e.optJSONObject("tags") ?: continue
            val name=tags.optString("name")
            if(name.isBlank()) continue

            val lat:Double
            val lon:Double
            if(e.has("lat") && e.has("lon")){
                lat=e.getDouble("lat")
                lon=e.getDouble("lon")
            }else{
                val center=e.optJSONObject("center") ?: continue
                lat=center.optDouble("lat",Double.NaN)
                lon=center.optDouble("lon",Double.NaN)
                if(!lat.isFinite() || !lon.isFinite()) continue
            }

            val address=listOf(
                tags.optString("addr:street"),
                tags.optString("addr:housenumber"),
                tags.optString("addr:suburb"),
                tags.optString("addr:city")
            ).filter { it.isNotBlank() }.distinct().joinToString(" • ")

            val category=listOf(
                tags.optString("amenity"),
                tags.optString("shop"),
                tags.optString("tourism"),
                tags.optString("office"),
                tags.optString("leisure"),
                tags.optString("craft"),
                tags.optString("historic"),
                tags.optString("public_transport")
            ).firstOrNull { it.isNotBlank() }

            out += PlaceResult(
                id="osm-${e.optString("type")}-${e.optLong("id")}",
                name=name,
                address=address,
                location=GeoPoint(lat,lon),
                category=category
            )
        }

        out.distinctBy { it.id }
            .sortedByDescending { viewportImportance(it,zoom) }
            .take(limit)
    }.getOrDefault(emptyList())

    private fun viewportImportance(place:PlaceResult,zoom:Double):Double {
        val category=place.category.orEmpty()
        val base=when(category) {
            "hospital" -> 100.0
            "university","school" -> 82.0
            "supermarket","mall" -> 78.0
            "fuel" -> 76.0
            "hotel" -> 70.0
            "park","stadium" -> 68.0
            "bank" -> 66.0
            "pharmacy" -> 65.0
            "restaurant","cafe","fast_food" -> 58.0
            "place_of_worship" -> 55.0
            else -> 42.0
        }
        return base + zoom
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
        return PlaceResult(
            id,
            name,
            parts.joinToString(" • "),
            GeoPoint(coords.getDouble(1),coords.getDouble(0)),
            category
        )
    }

    private fun read(url:String):String{
        val c=(URL(url).openConnection() as HttpURLConnection).apply{
            requestMethod="GET"
            connectTimeout=6500
            readTimeout=9000
            setRequestProperty("Accept-Language","pt-BR,pt;q=0.9")
            setRequestProperty("User-Agent","LINKNAV/0.3 Android")
        }
        if(c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
