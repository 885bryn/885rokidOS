package com.rokidos885.phone.map

import com.rokidos885.protocol.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Street geometry and names from OpenStreetMap, via OpenFreeMap's prebuilt vector tiles
 * (https://openfreemap.org, OpenMapTiles schema, no key). Tiles come from a CDN, so they are far more
 * dependable than live Overpass queries, which timed out or failed on 2026-10-18. Requests disclose
 * only the z14 tiles (~2 km squares) around the driver and the next 2 km of route, never the destination.
 * Decoded roads are cached per tile; offline or failed fetches fall back to the cache.
 */
class RoadContextProvider(private val directory:File,private val http:OkHttpClient=OkHttpClient.Builder()
    .callTimeout(20,TimeUnit.SECONDS).build()) {

    /** [ahead] is the next stretch of the route; streets along it (and the next turn) are kept first. */
    suspend fun roadsNear(center:LatLng,ahead:List<LatLng> = emptyList()):List<ContextRoad> = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val roads=tilesFor(center,ahead).flatMap(::tileRoads)
        if(roads.isEmpty())log("no street data for this area yet")
        select(roads,center,ahead)
    }

    /** The tile under the driver first, then tiles under the route ahead, at most [MAX_TILES]. */
    private fun tilesFor(center:LatLng,ahead:List<LatLng>):List<TileId> {
        val tiles=LinkedHashSet<TileId>()
        tiles+=TileId.containing(center,ZOOM)
        for(p in ahead){if(tiles.size>=MAX_TILES)break;tiles+=TileId.containing(p,ZOOM)}
        return tiles.toList()
    }

    private fun tileRoads(tile:TileId):List<TileRoad> {
        val cache=File(directory,"${tile.z}-${tile.x}-${tile.y}.json")
        val cached=cache.takeIf {it.exists()}?.let {file->runCatching {readCache(file.readText())}.getOrNull()}
        if(cached!=null && System.currentTimeMillis()-cache.lastModified()<TILE_MAX_AGE_MS)return cached
        return try {
            val url=tileUrl(tile) ?: return cached.orEmpty()
            val bytes=http.newCall(Request.Builder().url(url).header("User-Agent",USER_AGENT).get().build()).execute().use {response->
                require(response.isSuccessful) {"HTTP ${response.code}"}
                val body=requireNotNull(response.body)
                require(body.contentLength()<=MAX_TILE_BYTES) {"tile too large"}
                body.bytes().also {require(it.size<=MAX_TILE_BYTES) {"tile too large"}}
            }
            val roads=VectorTile.roads(bytes,tile).map {TileRoad(simplify(it.points),it.name.filterNot(Char::isISOControl).take(40),it.major)}
            val temporary=File(directory,"${tile.z}-${tile.x}-${tile.y}.tmp")
            temporary.writeText(writeCache(roads))
            java.nio.file.Files.move(temporary.toPath(),cache.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            directory.listFiles {file->file.extension=="json"}?.sortedByDescending {it.lastModified()}?.drop(MAX_CACHED_TILES)?.forEach {it.delete()}
            roads
        } catch(e:java.io.IOException){log("tile $tile offline, keeping cache: ${e.javaClass.simpleName}");cached.orEmpty()}
        catch(e:IllegalArgumentException){log("tile $tile rejected: ${e.message}");cached.orEmpty()}
    }

    /** Tile URL template from OpenFreeMap's TileJSON (it names the weekly build); refreshed daily. */
    private fun tileUrl(tile:TileId):String? {
        val file=File(directory,"tilejson.txt")
        var template=file.takeIf {it.exists() && System.currentTimeMillis()-it.lastModified()<86_400_000}?.readText()
        if(template==null) {
            template=runCatching {
                http.newCall(Request.Builder().url(TILEJSON_URL).header("User-Agent",USER_AGENT).get().build()).execute().use {response->
                    require(response.isSuccessful) {"HTTP ${response.code}"}
                    Json.parseToJsonElement(requireNotNull(response.body).string()).jsonObject.getValue("tiles").jsonArray[0].jsonPrimitive.content
                }
            }.onSuccess {file.writeText(it)}.onFailure {log("tile index unavailable: $it")}.getOrNull()
                ?: file.takeIf {it.exists()}?.readText()   // stale template still works for the weekly build it names
        }
        return template?.takeIf {it.startsWith("https://")}?.replace("{z}","${tile.z}")?.replace("{x}","${tile.x}")?.replace("{y}","${tile.y}")
    }

    private fun log(message:String)=android.util.Log.w("885rokidOSMap",message)

    companion object {
        /** Total street payload per snapshot; [RoadContextChunker] splits it into ≤6 KB frames. */
        const val SNAPSHOT_BUDGET_BYTES=30_000
        private const val ZOOM=14
        private const val MAX_TILES=6
        private const val MAX_CACHED_TILES=60
        private const val MAX_TILE_BYTES=4_000_000L
        private const val TILE_MAX_AGE_MS=7*86_400_000L
        private const val TILEJSON_URL="https://tiles.openfreemap.org/planet"
        private const val USER_AGENT="885rokidOS/0.2 (personal navigation HUD)"
        private val cacheFormat=ListSerializer(ContextRoad.serializer())

        private fun writeCache(roads:List<TileRoad>)=Json.encodeToString(cacheFormat,roads.map {ContextRoad(PolylineCodec.encode(it.points),it.name,it.major)})
        private fun readCache(text:String)=Json.decodeFromString(cacheFormat,text).map {TileRoad(PolylineCodec.decode(it.encodedPolyline),it.name,it.major)}

        /**
         * Returns streets in priority order within [SNAPSHOT_BUDGET_BYTES]. Priority is distance to
         * whichever is nearer: the driver, or the route ahead, so the cross streets before a turn and the
         * turn street itself always make the cut. Major roads rank as if 150 m closer.
         */
        fun select(roads:List<TileRoad>,center:LatLng,ahead:List<LatLng> = emptyList()):List<ContextRoad> {
            val ranked=roads.filter {road->road.points.size>=2 && road.points.all {it.lat.isFinite() && it.lat in -90.0..90.0 && it.lng.isFinite() && it.lng in -180.0..180.0}}
                .map {road->
                    var distance=Geo.projectOntoPolyline(road.points,center)?.distanceMeters ?: Double.MAX_VALUE
                    if(ahead.size>=2)for(p in road.points)distance=minOf(distance,Geo.projectOntoPolyline(ahead,p)!!.distanceMeters)
                    ContextRoad(PolylineCodec.encode(road.points),road.name,road.major) to distance-(if(road.major)150 else 0)
                }.sortedBy {it.second}
            val selected=ArrayList<ContextRoad>()
            var bytes=0
            for((road,_) in ranked){
                // JSON per road ≈ polyline + name + field names and punctuation.
                val size=road.encodedPolyline.length*2+road.name.length+48
                if(bytes+size>SNAPSHOT_BUDGET_BYTES)continue
                selected+=road;bytes+=size
                if(selected.size>=400)break
            }
            return selected
        }

        /** Douglas–Peucker to five-meter tolerance. */
        internal fun simplify(points:List<LatLng>):List<LatLng> {
            if(points.size<3)return points
            val keep=BooleanArray(points.size);keep[0]=true;keep[points.lastIndex]=true
            val ranges=java.util.ArrayDeque<Pair<Int,Int>>();ranges.add(0 to points.lastIndex)
            while(ranges.isNotEmpty()){
                val (first,last)=ranges.removeLast();var farthest=5.0;var split=-1
                val ends=listOf(points[first],points[last])
                for(i in first+1 until last){val d=Geo.projectOntoPolyline(ends,points[i])!!.distanceMeters;if(d>farthest){farthest=d;split=i}}
                if(split>=0){keep[split]=true;ranges.add(first to split);ranges.add(split to last)}
            }
            return points.filterIndexed {i,_->keep[i]}
        }
    }
}
