package com.infinitum.aether.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import uniffi.aether_core.CoordFormat
import uniffi.aether_core.GpxDocument
import uniffi.aether_core.Route
import uniffi.aether_core.Track
import uniffi.aether_core.TrackPoint
import uniffi.aether_core.TrackSegment
import uniffi.aether_core.Vault
import uniffi.aether_core.Waypoint
import uniffi.aether_core.simplifyTrack
import uniffi.aether_core.tmsRow
import uniffi.aether_core.TileAddress
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A waypoint ("landmark") with a stable id. */
data class MapWaypoint(val id: String, val w: Waypoint)

/** A recorded or imported track. Each inner list is one segment (recording resumed = new segment). */
data class MapTrack(val id: String, val name: String, val createdAt: Long, val segments: List<List<TrackPoint>>) {
    val points: List<TrackPoint> get() = segments.flatten()
}

data class MapRoute(val id: String, val name: String, val points: List<Waypoint>)

/** An offline raster map pack (MBTiles) copied into app-private storage. */
data class MapPack(
    val id: String,
    val name: String,
    val path: String,
    val minZoom: Int,
    val maxZoom: Int,
    /** west, south, east, north, or null when the pack does not say. */
    val bounds: List<Double>?,
)

/**
 * ÆTHER Maps™ storage: waypoints, tracks, routes, map packs and display settings, kept as
 * JSON events in an encrypted ÆTHER Vault™ (append-only; edits and deletions are later events).
 * Map packs are MBTiles files under `noBackupFilesDir/aether/maps`; tiles are read on demand.
 */
class MapsStore(private val context: Context, private val vault: Vault) {
    private val packDir = File(context.noBackupFilesDir, "aether/maps").apply { mkdirs() }

    private val _waypoints = MutableStateFlow<List<MapWaypoint>>(emptyList())
    val waypoints: StateFlow<List<MapWaypoint>> = _waypoints.asStateFlow()
    private val _tracks = MutableStateFlow<List<MapTrack>>(emptyList())
    val tracks: StateFlow<List<MapTrack>> = _tracks.asStateFlow()
    private val _routes = MutableStateFlow<List<MapRoute>>(emptyList())
    val routes: StateFlow<List<MapRoute>> = _routes.asStateFlow()
    private val _packs = MutableStateFlow<List<MapPack>>(emptyList())
    val packs: StateFlow<List<MapPack>> = _packs.asStateFlow()
    private val _activePack = MutableStateFlow<String?>(null)
    val activePack: StateFlow<String?> = _activePack.asStateFlow()
    private val _format = MutableStateFlow(CoordFormat.MGRS)
    val format: StateFlow<CoordFormat> = _format.asStateFlow()
    /** Id of the track being recorded, or null. */
    private val _recording = MutableStateFlow<String?>(null)
    val recording: StateFlow<String?> = _recording.asStateFlow()

    init {
        load()
    }

    @Synchronized
    private fun put(o: JSONObject) {
        vault.append(o.toString().toByteArray())
    }

    private fun load() {
        val wpts = LinkedHashMap<String, MapWaypoint>()
        val tracks = LinkedHashMap<String, MapTrack>()
        val routes = LinkedHashMap<String, MapRoute>()
        val packs = LinkedHashMap<String, MapPack>()
        // Tracks with points but no final ("trkfull") record: recording was cut off (process killed).
        val unfinished = LinkedHashSet<String>()
        for (raw in vault.readAll()) {
            val o = runCatching { JSONObject(raw.decodeToString()) }.getOrNull() ?: continue
            when (o.optString("t")) {
                "trk", "seg" -> unfinished += o.getString("id")
                "trkfull", "del" -> unfinished -= o.getString("id")
            }
            when (o.optString("t")) {
                "wpt" -> wpts[o.getString("id")] = MapWaypoint(o.getString("id"), wptFrom(o))
                "trk" -> tracks[o.getString("id")] = MapTrack(o.getString("id"), o.getString("n"), o.optLong("at"), listOf(emptyList()))
                "trkname" -> tracks[o.getString("id")]?.let { tracks[it.id] = it.copy(name = o.getString("n")) }
                "seg" -> tracks[o.getString("id")]?.let { t -> tracks[t.id] = t.copy(segments = t.segments + listOf(emptyList())) }
                "tp" -> tracks[o.getString("id")]?.let { t ->
                    val segs = t.segments.toMutableList()
                    segs[segs.lastIndex] = segs.last() + tpFrom(o)
                    tracks[t.id] = t.copy(segments = segs)
                }
                "trkfull" -> {
                    val segs = o.getJSONArray("s").let { a -> List(a.length()) { i -> a.getJSONArray(i).let { p -> List(p.length()) { tpFrom(p.getJSONObject(it)) } } } }
                    tracks[o.getString("id")] = MapTrack(o.getString("id"), o.getString("n"), o.optLong("at"), segs)
                }
                "rte" -> routes[o.getString("id")] = MapRoute(
                    o.getString("id"), o.getString("n"),
                    o.getJSONArray("p").let { a -> List(a.length()) { wptFrom(a.getJSONObject(it)) } },
                )
                "pack" -> packs[o.getString("id")] = MapPack(
                    o.getString("id"), o.getString("n"), o.getString("path"), o.optInt("min", 0), o.optInt("max", 18),
                    o.optJSONArray("b")?.let { b -> List(b.length()) { b.getDouble(it) } },
                )
                "use" -> _activePack.value = o.optString("id").ifEmpty { null }
                "fmt" -> _format.value = runCatching { CoordFormat.valueOf(o.getString("f")) }.getOrDefault(CoordFormat.MGRS)
                "del" -> o.getString("id").let { wpts.remove(it); tracks.remove(it); routes.remove(it); packs.remove(it) }
            }
        }
        // Close interrupted recordings the way stopTrack would, so they're simplified and stored whole.
        for (id in unfinished) {
            val t = tracks[id] ?: continue
            val done = t.copy(segments = t.segments.filter { it.isNotEmpty() }.map { seg ->
                runCatching { simplifyTrack(seg, 2.0) }.getOrDefault(seg)
            })
            put(trackFullJson(done))
            tracks[id] = done
        }
        _waypoints.value = wpts.values.toList()
        _tracks.value = tracks.values.toList()
        _routes.value = routes.values.toList()
        _packs.value = packs.values.filter { File(it.path).exists() }
        if (_activePack.value !in _packs.value.map { it.id }) _activePack.value = _packs.value.firstOrNull()?.id
    }

    // ---- waypoints ------------------------------------------------------------------------

    fun saveWaypoint(w: MapWaypoint) {
        put(wptJson(w.w).put("t", "wpt").put("id", w.id))
        _waypoints.update { list -> if (list.any { it.id == w.id }) list.map { if (it.id == w.id) w else it } else list + w }
    }

    fun delete(id: String) {
        put(JSONObject().put("t", "del").put("id", id))
        _waypoints.update { l -> l.filter { it.id != id } }
        _tracks.update { l -> l.filter { it.id != id } }
        _routes.update { l -> l.filter { it.id != id } }
        _packs.value.firstOrNull { it.id == id }?.let { File(it.path).delete(); tileCache.evictAll() }
        _packs.update { l -> l.filter { it.id != id } }
        if (_activePack.value == id) usePack(_packs.value.firstOrNull()?.id)
    }

    fun setFormat(f: CoordFormat) {
        put(JSONObject().put("t", "fmt").put("f", f.name))
        _format.value = f
    }

    // ---- tracks ---------------------------------------------------------------------------

    /** Starts a new track, or a new segment of [resumeId]. Returns the track id. */
    @Synchronized
    fun startTrack(name: String, resumeId: String? = null): String {
        val existing = resumeId?.let { id -> _tracks.value.firstOrNull { it.id == id } }
        val id = if (existing != null) {
            put(JSONObject().put("t", "seg").put("id", existing.id))
            _tracks.update { l -> l.map { if (it.id == existing.id) it.copy(segments = it.segments + listOf(emptyList())) else it } }
            existing.id
        } else {
            val t = MapTrack(randomId(), name, System.currentTimeMillis(), listOf(emptyList()))
            put(JSONObject().put("t", "trk").put("id", t.id).put("n", t.name).put("at", t.createdAt))
            _tracks.update { it + t }
            t.id
        }
        _recording.value = id
        return id
    }

    @Synchronized
    fun addTrackPoint(p: TrackPoint) {
        val id = _recording.value ?: return
        put(tpJson(p).put("t", "tp").put("id", id))
        _tracks.update { l ->
            l.map { t ->
                if (t.id != id) t else t.copy(segments = t.segments.dropLast(1) + listOf(t.segments.last() + p))
            }
        }
    }

    /** Stops recording and stores a simplified copy (about 2 m tolerance) as the track's final form. */
    @Synchronized
    fun stopTrack() {
        val id = _recording.value ?: return
        _recording.value = null
        val t = _tracks.value.firstOrNull { it.id == id } ?: return
        val simplified = t.copy(segments = t.segments.filter { it.isNotEmpty() }.map { seg ->
            runCatching { simplifyTrack(seg, 2.0) }.getOrDefault(seg)
        })
        put(trackFullJson(simplified))
        _tracks.update { l -> l.map { if (it.id == id) simplified else it } }
    }

    fun renameTrack(id: String, name: String) {
        put(JSONObject().put("t", "trkname").put("id", id).put("n", name))
        _tracks.update { l -> l.map { if (it.id == id) it.copy(name = name) else it } }
    }

    // ---- routes ---------------------------------------------------------------------------

    fun saveRoute(r: MapRoute) {
        put(JSONObject().put("t", "rte").put("id", r.id).put("n", r.name).put("p", JSONArray(r.points.map(::wptJson))))
        _routes.update { l -> if (l.any { it.id == r.id }) l.map { if (it.id == r.id) r else it } else l + r }
    }

    // ---- GPX ------------------------------------------------------------------------------

    /** Adds everything in [doc]; returns counts (waypoints, routes, tracks). */
    fun importGpx(doc: GpxDocument): Triple<Int, Int, Int> {
        doc.waypoints.forEach { saveWaypoint(MapWaypoint(randomId(), it)) }
        doc.routes.forEach { saveRoute(MapRoute(randomId(), it.name.ifBlank { "Imported route" }, it.points)) }
        for (t in doc.tracks) {
            val mt = MapTrack(randomId(), t.name.ifBlank { "Imported track" }, System.currentTimeMillis(), t.segments.map { it.points })
            put(trackFullJson(mt))
            _tracks.update { it + mt }
        }
        return Triple(doc.waypoints.size, doc.routes.size, doc.tracks.size)
    }

    fun exportDocument(): GpxDocument = GpxDocument(
        waypoints = _waypoints.value.map { it.w },
        routes = _routes.value.map { Route(it.name, "", it.points) },
        tracks = _tracks.value.map { t -> Track(t.name, "", t.segments.filter { it.isNotEmpty() }.map { TrackSegment(it) }) },
    )

    // ---- map packs ------------------------------------------------------------------------

    /** Copies an MBTiles file into private storage and registers it. Call off the main thread. */
    fun importPack(uri: Uri, displayName: String?): MapPack {
        val id = randomId()
        val dest = File(packDir, "$id.mbtiles")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open the file" }
            dest.outputStream().use { input.copyTo(it) }
        }
        try {
            SQLiteDatabase.openDatabase(dest.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val meta = HashMap<String, String>()
                runCatching {
                    db.rawQuery("SELECT name, value FROM metadata", null).use { c -> while (c.moveToNext()) meta[c.getString(0)] = c.getString(1) }
                }
                if (meta["format"]?.lowercase() == "pbf") {
                    throw UnsupportedPackException("This is a vector map pack (pbf). ÆTHER Maps shows raster packs (PNG, JPEG or WebP) only.")
                }
                val (minZ, maxZ) = db.rawQuery("SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles", null).use { c ->
                    require(c.moveToFirst() && !c.isNull(0)) { "The pack has no tiles" }
                    c.getInt(0) to c.getInt(1)
                }
                val bounds = meta["bounds"]?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 4 }
                val pack = MapPack(id, meta["name"] ?: displayName?.substringBeforeLast('.') ?: "Map pack", dest.path, minZ, maxZ, bounds)
                put(JSONObject().put("t", "pack").put("id", id).put("n", pack.name).put("path", pack.path)
                    .put("min", minZ).put("max", maxZ).apply { bounds?.let { put("b", JSONArray(it)) } })
                _packs.update { it + pack }
                usePack(id)
                return pack
            }
        } catch (e: UnsupportedPackException) {
            dest.delete()
            throw e
        } catch (e: Exception) {
            dest.delete()
            throw IllegalArgumentException("Not a usable MBTiles map pack: ${e.message}", e)
        }
    }

    class UnsupportedPackException(message: String) : IllegalArgumentException(message)

    fun usePack(id: String?) {
        put(JSONObject().put("t", "use").put("id", id ?: ""))
        _activePack.value = id
    }

    private val dbs = ConcurrentHashMap<String, SQLiteDatabase>()
    private val tileCache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = ConcurrentHashMap.newKeySet<String>()

    /** Raster tile (XYZ addressing) from [pack], or null if the pack does not have it. Call off the main thread. */
    fun tile(pack: MapPack, z: Int, x: Int, y: Int): Bitmap? {
        val key = "${pack.id}/$z/$x/$y"
        tileCache.get(key)?.let { return it }
        if (key in missing) return null
        val db = dbs.getOrPut(pack.id) { SQLiteDatabase.openDatabase(pack.path, null, SQLiteDatabase.OPEN_READONLY) }
        val row = tmsRow(TileAddress(z.toUByte(), x.toUInt(), y.toUInt())).toInt()
        val bytes = db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(z.toString(), x.toString(), row.toString()),
        ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
        val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bmp == null) missing += key else tileCache.put(key, bmp)
        return bmp
    }

    fun cachedTile(pack: MapPack, z: Int, x: Int, y: Int): Bitmap? = tileCache.get("${pack.id}/$z/$x/$y")

    companion object {
        val SYMBOLS = listOf("Flag", "Rally point", "Checkpoint", "Observation post", "LZ", "Danger", "Water", "Shelter")

        fun wptJson(w: Waypoint): JSONObject = JSONObject().put("n", w.name).put("la", w.lat).put("lo", w.lon)
            .put("e", w.eleM ?: JSONObject.NULL).put("ts", w.timeMs ?: JSONObject.NULL).put("no", w.note).put("s", w.symbol)

        fun wptFrom(o: JSONObject) = Waypoint(
            o.optString("n"), o.getDouble("la"), o.getDouble("lo"),
            if (o.isNull("e")) null else o.optDouble("e"), if (o.isNull("ts")) null else o.optLong("ts"),
            o.optString("no"), o.optString("s"),
        )

        fun tpJson(p: TrackPoint): JSONObject = JSONObject().put("la", p.lat).put("lo", p.lon)
            .put("e", p.eleM ?: JSONObject.NULL).put("ts", p.timeMs ?: JSONObject.NULL)

        fun tpFrom(o: JSONObject) = TrackPoint(
            o.getDouble("la"), o.getDouble("lo"),
            if (o.isNull("e")) null else o.optDouble("e"), if (o.isNull("ts")) null else o.optLong("ts"),
        )

        fun trackFullJson(t: MapTrack): JSONObject = JSONObject().put("t", "trkfull").put("id", t.id).put("n", t.name).put("at", t.createdAt)
            .put("s", JSONArray(t.segments.map { seg -> JSONArray(seg.map(::tpJson)) }))
    }
}
