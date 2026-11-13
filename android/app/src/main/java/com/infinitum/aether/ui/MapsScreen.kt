package com.infinitum.aether.ui

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.infinitum.aether.TrackService
import com.infinitum.aether.data.MapPack
import com.infinitum.aether.data.MapRoute
import com.infinitum.aether.data.MapWaypoint
import com.infinitum.aether.data.MapsStore
import com.infinitum.aether.data.randomId
import com.infinitum.aether.net.AetherNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.aether_core.CoordFormat
import uniffi.aether_core.GeoPoint
import uniffi.aether_core.PositionKind
import uniffi.aether_core.PositionReport
import uniffi.aether_core.Waypoint
import uniffi.aether_core.geodesicDestination
import uniffi.aether_core.parseCoordinate
import uniffi.aether_core.parseGpx
import uniffi.aether_core.pathLength
import uniffi.aether_core.polygonArea
import uniffi.aether_core.polygonPerimeter
import uniffi.aether_core.writeGpx
import uniffi.aether_core.writeKml
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/** Navigation target; [routeId] and [leg] when following a route leg by leg (leg = index of the target point). */
internal data class NavState(val name: String, val lat: Double, val lon: Double, val routeId: String? = null, val leg: Int = 0)

internal enum class MapMode { BROWSE, MEASURE, ROUTE }

internal sealed interface Selection {
    data class Point(val p: GeoPoint) : Selection
    data class Wpt(val id: String) : Selection
    data class Team(val m: TeamMark) : Selection
}

private sealed interface Dlg {
    data class GoTo(val addWaypoint: Boolean) : Dlg
    data class Project(val from: GeoPoint, val fromName: String) : Dlg
    data object SaveRoute : Dlg
    data class Delete(val id: String, val what: String) : Dlg
    data class Rename(val id: String, val name: String) : Dlg
}

private const val MAX_GPX_BYTES = 32 * 1024 * 1024

/**
 * ÆTHER Maps™: offline GPS navigation. Map view with offline MBTiles packs, waypoints,
 * projection, navigation with compass, measuring, track recording, routes, GPX/KML exchange
 * and the team picture shared over ÆTHER Talk. Renders below the app's top nav row.
 */
@Composable
internal fun MapsScreen(node: AetherNode, maps: MapsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val waypoints by maps.waypoints.collectAsState()
    val tracks by maps.tracks.collectAsState()
    val routes by maps.routes.collectAsState()
    val packs by maps.packs.collectAsState()
    val activePackId by maps.activePack.collectAsState()
    val format by maps.format.collectAsState()
    val recording by maps.recording.collectAsState()
    val running by TrackService.running.collectAsState()
    val teamPositions by node.teamPositions.collectAsState()
    val markers by node.markers.collectAsState()
    val contacts by node.contacts.collectAsState()

    var screen by rememberSaveable { mutableStateOf("map") }
    var selId by rememberSaveable { mutableStateOf<String?>(null) }
    var wptBack by rememberSaveable { mutableStateOf("map") }
    var shareBack by rememberSaveable { mutableStateOf("map") }
    var shareWpt by rememberSaveable { mutableStateOf<String?>(null) }
    var placed by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(MapMode.BROWSE) }
    var highlight by rememberSaveable { mutableStateOf<String?>(null) }
    val vp = rememberSaveable(saver = MapViewport.Saver) { MapViewport(0.5, 0.5, 2.0, 0f) }
    var canvasSize by remember { mutableStateOf(IntSize(1080, 1600)) }

    val draft = remember { mutableStateListOf<GeoPoint>() }
    val draftNames = remember { mutableStateListOf<String>() }
    var measureClosed by remember { mutableStateOf(true) }
    var nav by remember { mutableStateOf<NavState?>(null) }
    var sel by remember { mutableStateOf<Selection?>(null) }
    var editing by remember { mutableStateOf<MapWaypoint?>(null) }
    var editingNew by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Dlg?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        if (message != null) {
            delay(5_000)
            message = null
        }
    }

    fun io(done: String? = null, block: () -> Unit) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            message = r.exceptionOrNull()?.let { it.message ?: "Failed" } ?: done ?: message
        }
    }

    // ---- location permission, GPS and compass ----
    var granted by remember { mutableStateOf(hasFineLocation(context)) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasFineLocation(context)
        if (granted) afterPermission?.invoke() else message = "Location permission is needed for GPS."
        afterPermission = null
    }
    fun withLocation(action: () -> Unit) {
        if (granted) action()
        else {
            afterPermission = action
            val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms += Manifest.permission.POST_NOTIFICATIONS
            permLauncher.launch(perms.toTypedArray())
        }
    }
    LaunchedEffect(Unit) { if (!granted) withLocation { } }

    val fix by rememberGpsFix(granted)
    val heading = rememberCompass(screen == "map", fix)

    LaunchedEffect(fix, follow) {
        val f = fix
        if (follow && f != null) vp.centerOn(f.lat, f.lon)
    }

    fun fit(points: List<GeoPoint>) {
        if (points.isEmpty()) return
        val xs = points.map { mercX(it.lon) }
        val ys = points.map { mercY(it.lat) }
        vp.cx = (xs.min() + xs.max()) / 2
        vp.cy = (ys.min() + ys.max()) / 2
        val sx = xs.max() - xs.min()
        val sy = ys.max() - ys.min()
        val zx = if (sx > 1e-12) log2(canvasSize.width * 0.8 / (sx * vp.tileBase)) else 16.0
        val zy = if (sy > 1e-12) log2(canvasSize.height * 0.8 / (sy * vp.tileBase)) else 16.0
        vp.zoom = min(min(zx, zy), 17.0).coerceIn(MIN_ZOOM, MAX_ZOOM)
        follow = false
        screen = "map"
    }

    fun fitPack(p: MapPack) {
        val b = p.bounds
        if (b != null && b.size == 4) fit(listOf(GeoPoint(b[1], b[0]), GeoPoint(b[3], b[2])))
        vp.zoom = vp.zoom.coerceIn(p.minZoom.toDouble(), max(p.minZoom.toDouble(), p.maxZoom.toDouble()))
    }

    // Initial placement: the active pack's area, else the first GPS fix.
    LaunchedEffect(Unit) {
        if (!placed) packs.firstOrNull { it.id == activePackId }?.let { fitPack(it); placed = true }
    }
    LaunchedEffect(fix != null) {
        val f = fix
        if (!placed && f != null) {
            vp.centerOn(f.lat, f.lon)
            vp.zoom = 15.0
            placed = true
        }
    }

    // Route navigation: advance to the next leg on arrival.
    LaunchedEffect(fix, nav) {
        val n = nav ?: return@LaunchedEffect
        val f = fix ?: return@LaunchedEffect
        val g = inverse(f.point, GeoPoint(n.lat, n.lon)) ?: return@LaunchedEffect
        if (g.distanceM > max(15.0, (f.accuracyM ?: 0f).toDouble())) return@LaunchedEffect
        val r = n.routeId?.let { id -> routes.firstOrNull { it.id == id } }
        if (r != null && n.leg + 1 < r.points.size) {
            val p = r.points[n.leg + 1]
            nav = NavState(p.name.ifBlank { "Point ${n.leg + 2}" }, p.lat, p.lon, r.id, n.leg + 1)
            message = "Arrived at ${n.name}. Next: ${nav?.name}"
        } else {
            message = "Arrived at ${n.name}"
        }
    }

    // ---- actions ----
    fun startNav(name: String, lat: Double, lon: Double) {
        nav = NavState(name, lat, lon)
        sel = null
        mode = MapMode.BROWSE
        screen = "map"
        withLocation { }
    }

    fun startRoute(r: MapRoute, leg: Int) {
        val p = r.points.getOrNull(leg) ?: return
        nav = NavState(p.name.ifBlank { "Point ${leg + 1}" }, p.lat, p.lon, r.id, leg)
        highlight = r.id
        sel = null
        mode = MapMode.BROWSE
        screen = "map"
        withLocation { }
    }

    fun newWaypoint(lat: Double, lon: Double, ele: Double? = null, name: String? = null, from: String = screen) {
        editing = MapWaypoint(
            randomId(),
            Waypoint(name ?: "WP ${waypoints.size + 1}", lat, lon, ele, System.currentTimeMillis(), "", MapsStore.SYMBOLS.first()),
        )
        editingNew = true
        wptBack = from
        screen = "wpt"
    }

    fun openWaypoint(wp: MapWaypoint, from: String = screen) {
        editing = wp
        editingNew = false
        wptBack = from
        screen = "wpt"
    }

    fun addHere(from: String = screen) = withLocation {
        val f = fix
        if (f == null) message = "Waiting for a GPS fix." else newWaypoint(f.lat, f.lon, f.eleM, from = from)
    }

    fun share(wpId: String?, from: String = screen) {
        shareWpt = wpId
        shareBack = from
        screen = "share"
        if (wpId == null) withLocation { }
    }

    fun startRecording() = withLocation {
        TrackService.start(context, "Track " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
    }

    fun resumeRecording(id: String) = withLocation {
        TrackService.start(context, tracks.firstOrNull { it.id == id }?.name ?: "Track", id)
    }

    // ---- documents (SAF) ----
    val packPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { maps.importPack(uri, displayName(context, uri)) } }
            busy = false
            r.onSuccess { p ->
                message = "Map pack added: ${p.name}"
                fitPack(p)
            }.onFailure { message = it.message ?: "Import failed" }
        }
    }
    val gpxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Could not open the file" }
                        val buf = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            buf.write(chunk, 0, n)
                            require(buf.size() <= MAX_GPX_BYTES) { "GPX file is larger than 32 MB" }
                        }
                        buf.toByteArray()
                    }
                    maps.importGpx(parseGpx(bytes.decodeToString()))
                }
            }
            message = r.fold(
                { (w, rt, t) -> "Imported $w waypoints, $rt routes, $t tracks." },
                { "GPX import failed: ${it.message}" },
            )
        }
    }
    fun exportTo(uri: Uri?, kml: Boolean) {
        if (uri == null) return
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val doc = maps.exportDocument()
                    val text = if (kml) writeKml(doc, "ÆTHER Maps") else writeGpx(doc)
                    context.contentResolver.openOutputStream(uri, "wt").use { out ->
                        requireNotNull(out) { "Could not write the file" }
                        out.write(text.toByteArray())
                    }
                }
            }
            message = r.fold({ if (kml) "KML exported." else "GPX exported." }, { "Export failed: ${it.message}" })
        }
    }
    val gpxSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { exportTo(it, kml = false) }
    val kmlSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")) { exportTo(it, kml = true) }

    // ---- derived map layers ----
    val team = remember(teamPositions, markers, contacts) {
        teamPositions.map { (convo, r) ->
            TeamMark("pos-$convo", contacts[convo]?.name ?: r.label.ifBlank { "Teammate" }, r.lat, r.lon, r.timeMs, false, r.symbol)
        } + markers.mapIndexed { i, (convo, r) ->
            TeamMark("mk-$i", "${r.label.ifBlank { "Marker" }} (${contacts[convo]?.name ?: "teammate"})", r.lat, r.lon, r.timeMs, true, r.symbol)
        }
    }
    val lineCache = remember { HashMap<String, Pair<Any, MapLine>>() }
    fun cached(id: String, src: Any, make: () -> MapLine): MapLine =
        lineCache[id]?.takeIf { it.first === src }?.second ?: make().also { lineCache[id] = src to it }
    val lines = tracks.map { t ->
        cached(t.id, t) { toLine(t.id, t.segments.map { s -> s.map { GeoPoint(it.lat, it.lon) } }, Accent, 2.5f) }
    } + routes.map { r ->
        cached(r.id, r) { toLine(r.id, listOf(r.points.map { GeoPoint(it.lat, it.lon) }), Ink, 2f, dashed = true) }
    }
    val pack = packs.firstOrNull { it.id == activePackId }

    BackHandler(enabled = screen != "map" || mode != MapMode.BROWSE || sel != null) {
        when {
            screen == "wpt" -> screen = wptBack
            screen == "track" -> screen = "tracks"
            screen == "route" -> screen = "routes"
            screen == "share" -> screen = shareBack
            screen != "map" -> screen = "map"
            sel != null -> sel = null
            else -> { mode = MapMode.BROWSE; draft.clear(); draftNames.clear() }
        }
    }

    Column(Modifier.fillMaxSize().background(Paper)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (screen) {
                "waypoints" -> WaypointsScreen(
                    waypoints, fix, format,
                    onOpen = { openWaypoint(it) },
                    onAddCrosshair = { newWaypoint(vp.centerLat, vp.centerLon) },
                    onAddHere = { addHere() },
                    onAddCoord = { dialog = Dlg.GoTo(addWaypoint = true) },
                    onBack = { screen = "map" },
                )
                "wpt" -> {
                    val e = editing
                    if (e == null) {
                        screen = "waypoints"
                    } else {
                        WaypointEditor(
                            e, editingNew, format, fix,
                            onSave = { w ->
                                io("Waypoint saved.") { maps.saveWaypoint(w) }
                                editing = w
                                editingNew = false
                                screen = wptBack
                            },
                            onDelete = { dialog = Dlg.Delete(e.id, e.w.name.ifBlank { "this waypoint" }) },
                            onNavigate = { w -> startNav(w.w.name, w.w.lat, w.w.lon) },
                            onProject = { w -> dialog = Dlg.Project(GeoPoint(w.w.lat, w.w.lon), w.w.name) },
                            onShare = { w -> share(w.id) },
                            onShow = { w -> highlight = w.id; vp.centerOn(w.w.lat, w.w.lon); if (vp.zoom < 14) vp.zoom = 15.0; follow = false; screen = "map" },
                            onBack = { screen = wptBack },
                        )
                    }
                }
                "tracks" -> TracksScreen(
                    tracks, recording, running,
                    onStart = { startRecording() },
                    onPause = { TrackService.pause(context) },
                    onResume = { recording?.let { resumeRecording(it) } },
                    onStop = { TrackService.stop(context); message = "Track saved." },
                    onOpen = { selId = it.id; screen = "track" },
                    onBack = { screen = "map" },
                )
                "track" -> {
                    val t = tracks.firstOrNull { it.id == selId }
                    if (t == null) screen = "tracks" else TrackDetail(
                        t,
                        canResume = recording == null,
                        onShow = { highlight = t.id; fit(t.points.map { GeoPoint(it.lat, it.lon) }) },
                        onRename = { dialog = Dlg.Rename(t.id, t.name) },
                        onDelete = { dialog = Dlg.Delete(t.id, t.name) },
                        onResume = { resumeRecording(t.id) },
                        onBack = { screen = "tracks" },
                    )
                }
                "routes" -> RoutesScreen(
                    routes,
                    onOpen = { selId = it.id; screen = "route" },
                    onBuild = { draft.clear(); draftNames.clear(); mode = MapMode.ROUTE; sel = null; screen = "map" },
                    onBack = { screen = "map" },
                )
                "route" -> {
                    val r = routes.firstOrNull { it.id == selId }
                    if (r == null) screen = "routes" else RouteDetail(
                        r,
                        onNavigate = { leg -> startRoute(r, leg) },
                        onShow = { highlight = r.id; fit(r.points.map { GeoPoint(it.lat, it.lon) }) },
                        onDelete = { dialog = Dlg.Delete(r.id, r.name) },
                        onBack = { screen = "routes" },
                    )
                }
                "layers" -> LayersScreen(
                    packs, activePackId, busy,
                    onUse = { id ->
                        io { maps.usePack(id) }
                        packs.firstOrNull { it.id == id }?.let { p ->
                            val b = p.bounds
                            val inside = b == null || (vp.centerLon in b[0]..b[2] && vp.centerLat in b[1]..b[3])
                            if (!inside) fitPack(p)
                        }
                        screen = "map"
                    },
                    onImport = { packPicker.launch(arrayOf("*/*")) },
                    onDelete = { p -> dialog = Dlg.Delete(p.id, p.name) },
                    onBack = { screen = "map" },
                )
                "team" -> TeamScreen(
                    team, fix, format,
                    onShow = { m ->
                        vp.centerOn(m.lat, m.lon); if (vp.zoom < 13) vp.zoom = 14.0
                        follow = false; sel = Selection.Team(m); screen = "map"
                    },
                    onNavigate = { m -> startNav(m.label, m.lat, m.lon) },
                    onSave = { m -> saveTeamMark(maps, m, ::io) },
                    onShareOwn = { share(null, "team") },
                    onBack = { screen = "map" },
                )
                "share" -> {
                    val w = shareWpt?.let { id -> waypoints.firstOrNull { it.id == id } }
                    val f = fix
                    val report = when {
                        w != null -> PositionReport(PositionKind.MARKER, w.w.name.take(128), w.w.lat, w.w.lon, w.w.eleM, null, System.currentTimeMillis(), w.w.symbol.take(32))
                        f != null -> PositionReport(PositionKind.OWN_FIX, node.profile.displayName.take(128), f.lat, f.lon, f.eleM, f.accuracyM?.toDouble(), f.timeMs, "")
                        else -> null
                    }
                    ShareScreen(
                        node,
                        what = if (w != null) "Waypoint: ${w.w.name}" else "My position",
                        report = report,
                        onSend = { convo ->
                            val rep = report ?: return@ShareScreen
                            scope.launch {
                                val name = contacts[convo]?.name ?: "teammate"
                                message = node.sendPosition(convo, rep).fold({ "Sent to $name." }, { "Not sent: ${it.message}" })
                            }
                        },
                        onBack = { screen = shareBack },
                    )
                }
                "files" -> FilesScreen(
                    counts = "${waypoints.size} waypoints, ${routes.size} routes, ${tracks.size} tracks",
                    onImportGpx = { gpxPicker.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream", "*/*")) },
                    onExportGpx = { gpxSaver.launch("aether-maps.gpx") },
                    onExportKml = { kmlSaver.launch("aether-maps.kml") },
                    onBack = { screen = "map" },
                )
                else -> MapMain(
                    vp = vp, maps = maps, pack = pack,
                    layers = MapLayers(
                        waypoints = waypoints,
                        lines = lines,
                        draft = draft.toList(),
                        draftClosed = mode == MapMode.MEASURE && measureClosed,
                        draftColor = if (mode == MapMode.ROUTE) Ink else Caution,
                        navTo = nav?.let { GeoPoint(it.lat, it.lon) },
                        team = team,
                        fix = fix,
                        selected = (sel as? Selection.Point)?.p,
                        highlightId = (sel as? Selection.Wpt)?.id ?: highlight,
                    ),
                    heading = heading,
                    follow = follow,
                    recordingName = recording?.let { id -> tracks.firstOrNull { it.id == id }?.let { "${it.name} · ${it.points.size} pts" } },
                    running = running,
                    onSize = { canvasSize = it },
                    onTool = { tool ->
                        when (tool) {
                            "measure" -> { draft.clear(); draftNames.clear(); sel = null; mode = if (mode == MapMode.MEASURE) MapMode.BROWSE else MapMode.MEASURE }
                            "goto" -> dialog = Dlg.GoTo(addWaypoint = false)
                            else -> screen = tool
                        }
                    },
                    onUserMove = { follow = false },
                    onTap = { g, hit ->
                        when (mode) {
                            MapMode.MEASURE -> draft.add(g)
                            MapMode.ROUTE -> when (hit) {
                                is MapHit.Waypoint -> { draft.add(GeoPoint(hit.w.w.lat, hit.w.w.lon)); draftNames.add(hit.w.w.name) }
                                is MapHit.Team -> { draft.add(GeoPoint(hit.m.lat, hit.m.lon)); draftNames.add(hit.m.label) }
                                null -> { draft.add(g); draftNames.add("") }
                            }
                            MapMode.BROWSE -> sel = when (hit) {
                                is MapHit.Waypoint -> Selection.Wpt(hit.w.id)
                                is MapHit.Team -> Selection.Team(hit.m)
                                null -> null
                            }
                        }
                    },
                    onLongPress = { g -> if (mode == MapMode.BROWSE) sel = Selection.Point(g) else draft.add(g).also { if (mode == MapMode.ROUTE) draftNames.add("") } },
                    onZoom = { d -> vp.zoom = (vp.zoom + d).coerceIn(MIN_ZOOM, MAX_ZOOM) },
                    onNorth = { vp.bearing = 0f },
                    onMe = {
                        withLocation {
                            val f = fix
                            follow = true
                            if (f != null) {
                                vp.centerOn(f.lat, f.lon)
                                if (vp.zoom < 14) vp.zoom = 15.0
                            } else {
                                message = "Waiting for a GPS fix."
                            }
                        }
                    },
                    onPauseRec = { TrackService.pause(context) },
                    onResumeRec = { recording?.let { resumeRecording(it) } },
                    onStopRec = { TrackService.stop(context); message = "Track saved." },
                ) {
                    // Bottom panel.
                    val selection = sel
                    when {
                        mode == MapMode.MEASURE -> MeasurePanel(
                            draft, measureClosed,
                            onToggleClosed = { measureClosed = !measureClosed },
                            onUndo = { if (draft.isNotEmpty()) draft.removeAt(draft.lastIndex) },
                            onClear = { draft.clear() },
                            onSaveRoute = { draftNames.clear(); repeat(draft.size) { draftNames.add("") }; dialog = Dlg.SaveRoute },
                            onDone = { draft.clear(); mode = MapMode.BROWSE },
                        )
                        mode == MapMode.ROUTE -> RouteDraftPanel(
                            draft,
                            onUndo = {
                                if (draft.isNotEmpty()) draft.removeAt(draft.lastIndex)
                                if (draftNames.isNotEmpty()) draftNames.removeAt(draftNames.lastIndex)
                            },
                            onClear = { draft.clear(); draftNames.clear() },
                            onSave = { dialog = Dlg.SaveRoute },
                            onCancel = { draft.clear(); draftNames.clear(); mode = MapMode.BROWSE },
                        )
                        selection != null -> SelectionPanel(
                            selection, waypoints, fix, format,
                            onClose = { sel = null },
                            onAddWaypoint = { g, name -> newWaypoint(g.lat, g.lon, name = name, from = "map") },
                            onNavigate = { name, g -> startNav(name, g.lat, g.lon) },
                            onProject = { g, name -> dialog = Dlg.Project(g, name) },
                            onEdit = { wp -> openWaypoint(wp, "map") },
                            onShare = { wp -> share(wp.id, "map") },
                        )
                        nav != null -> NavPanel(
                            nav!!, routes.firstOrNull { it.id == nav?.routeId }, fix, heading,
                            onLeg = { r, leg -> startRoute(r, leg) },
                            onStop = { nav = null },
                        )
                        else -> DefaultPanel(
                            vp, format, fix,
                            onCycleFormat = {
                                val all = CoordFormat.entries
                                val next = all[(all.indexOf(format) + 1) % all.size]
                                io { maps.setFormat(next) }
                            },
                            onAddCrosshair = { newWaypoint(vp.centerLat, vp.centerLon, from = "map") },
                            onAddHere = { addHere("map") },
                            onShareOwn = { share(null, "map") },
                        )
                    }
                }
            }
        }
        message?.let {
            Hairline()
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = Ink,
                modifier = Modifier.fillMaxWidth().background(Wash).padding(horizontal = GUTTER.dp, vertical = 10.dp).testTag("maps-message"),
            )
        }
    }

    // ---- dialogs ----
    when (val d = dialog) {
        null -> Unit
        is Dlg.GoTo -> GoToDialog(
            addWaypoint = d.addWaypoint,
            onDismiss = { dialog = null },
            onGo = { g ->
                dialog = null
                vp.centerOn(g.lat, g.lon)
                follow = false
                sel = Selection.Point(g)
                screen = "map"
            },
            onAdd = { g ->
                dialog = null
                newWaypoint(g.lat, g.lon)
            },
        )
        is Dlg.Project -> ProjectDialog(
            d.fromName,
            onDismiss = { dialog = null },
            onProject = { bearing, dist, name ->
                val dest = runCatching { geodesicDestination(d.from, bearing, dist) }
                dest.onSuccess { g ->
                    dialog = null
                    val w = MapWaypoint(randomId(), Waypoint(name, g.lat, g.lon, null, System.currentTimeMillis(), "Projected from ${d.fromName}: ${fmtBearing(bearing)} ${fmtDistance(dist)}", "Checkpoint"))
                    io("Projected waypoint added.") { maps.saveWaypoint(w) }
                    vp.centerOn(g.lat, g.lon)
                    follow = false
                    sel = Selection.Wpt(w.id)
                    screen = "map"
                }.onFailure { message = "Cannot project: ${it.message}" }
            },
        )
        Dlg.SaveRoute -> NameDialog(
            title = "Save route",
            initial = "Route ${routes.size + 1}",
            confirm = "Save",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                dialog = null
                if (draft.size < 2) {
                    message = "A route needs at least two points."
                } else {
                    val r = MapRoute(
                        randomId(), name,
                        draft.mapIndexed { i, p -> Waypoint(draftNames.getOrElse(i) { "" }.ifBlank { "P${i + 1}" }, p.lat, p.lon, null, null, "", "") },
                    )
                    io("Route saved.") { maps.saveRoute(r) }
                    draft.clear()
                    draftNames.clear()
                    mode = MapMode.BROWSE
                    highlight = r.id
                }
            },
        )
        is Dlg.Rename -> NameDialog(
            title = "Rename track",
            initial = d.name,
            confirm = "Rename",
            onDismiss = { dialog = null },
            onConfirm = { name -> dialog = null; io { maps.renameTrack(d.id, name) } },
        )
        is Dlg.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete ${d.what}?") },
            text = { Text("This cannot be undone.", color = Body) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    if (nav?.routeId == d.id) nav = null
                    if ((sel as? Selection.Wpt)?.id == d.id) sel = null
                    io("Deleted.") { maps.delete(d.id) }
                    screen = when (screen) {
                        "wpt" -> wptBack.takeIf { it != "wpt" } ?: "map"
                        "track" -> "tracks"
                        "route" -> "routes"
                        else -> screen
                    }
                }, modifier = Modifier.testTag("maps-delete-confirm")) { Text("Delete", color = Alert) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel", color = Accent) } },
        )
    }
}

private fun saveTeamMark(maps: MapsStore, m: TeamMark, io: (String?, () -> Unit) -> Unit) {
    val w = MapWaypoint(
        randomId(),
        Waypoint(m.label.take(128), m.lat, m.lon, null, m.timeMs, "Received from a teammate", m.symbol.ifBlank { "Checkpoint" }),
    )
    io("Saved as waypoint.") { maps.saveWaypoint(w) }
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

// ---- map main view --------------------------------------------------------------------------

@Composable
private fun MapMain(
    vp: MapViewport,
    maps: MapsStore,
    pack: MapPack?,
    layers: MapLayers,
    heading: State<Float?>,
    follow: Boolean,
    recordingName: String?,
    running: Boolean,
    onSize: (IntSize) -> Unit,
    onTool: (String) -> Unit,
    onUserMove: () -> Unit,
    onTap: (GeoPoint, MapHit?) -> Unit,
    onLongPress: (GeoPoint) -> Unit,
    onZoom: (Double) -> Unit,
    onNorth: () -> Unit,
    onMe: () -> Unit,
    onPauseRec: () -> Unit,
    onResumeRec: () -> Unit,
    onStopRec: () -> Unit,
    panel: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            for ((label, tool) in listOf(
                "Layers" to "layers", "Waypoints" to "waypoints", "Tracks" to "tracks", "Routes" to "routes",
                "Team" to "team", "Measure" to "measure", "Go to" to "goto", "Files" to "files",
            )) ActionLink(label, "maps-tool-$tool") { onTool(tool) }
        }
        Hairline()
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged(onSize)) {
            MapCanvas(
                vp, maps, pack, layers, heading, Modifier.fillMaxSize(),
                onUserMove = onUserMove, onTap = onTap, onLongPress = onLongPress,
            )
            Column(Modifier.align(Alignment.TopEnd).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MapButton("+", "maps-zoom-in") { onZoom(1.0) }
                MapButton("−", "maps-zoom-out") { onZoom(-1.0) }
                NorthButton(vp, onNorth)
                MapButton("◎", "maps-center-me", active = follow, onClick = onMe)
            }
            if (recordingName != null) {
                Row(
                    Modifier.align(Alignment.TopStart).padding(10.dp).background(Paper).border(1.dp, Ink)
                        .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Kicker(if (running) "REC" else "PAUSED", color = if (running) Alert else Body)
                    Spacer(Modifier.width(8.dp))
                    Text(recordingName, style = MaterialTheme.typography.labelMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(120.dp))
                    if (running) ActionLink("Pause", "maps-rec-pause", onClick = onPauseRec)
                    else ActionLink("Resume", "maps-rec-resume", onClick = onResumeRec)
                    ActionLink("Stop", "maps-rec-stop", onClick = onStopRec)
                }
            }
            if (pack == null) {
                Text(
                    "No map pack: grid only",
                    style = MaterialTheme.typography.labelMedium, color = Body,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).background(Paper).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Hairline()
        Column(Modifier.fillMaxWidth().padding(horizontal = GUTTER.dp, vertical = 10.dp)) { panel() }
    }
}

@Composable
private fun MapButton(label: String, tag: String, active: Boolean = false, onClick: () -> Unit) = Box(
    Modifier.size(44.dp).background(if (active) Ink else Paper).border(1.dp, Ink).clickable(onClick = onClick).testTag(tag),
    contentAlignment = Alignment.Center,
) { Text(label, style = MaterialTheme.typography.titleMedium, color = if (active) Paper else Ink) }

@Composable
private fun NorthButton(vp: MapViewport, onNorth: () -> Unit) = Box(
    Modifier.size(44.dp).background(Paper).border(1.dp, Ink).clickable(onClick = onNorth).testTag("maps-north"),
    contentAlignment = Alignment.Center,
) {
    Column(Modifier.rotate(-vp.bearing), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("▲", style = MaterialTheme.typography.labelMedium, color = Alert)
        Text("N", style = MaterialTheme.typography.labelMedium, color = Ink)
    }
}

// ---- bottom panels --------------------------------------------------------------------------

@Composable
private fun DefaultPanel(
    vp: MapViewport,
    format: CoordFormat,
    fix: Fix?,
    onCycleFormat: () -> Unit,
    onAddCrosshair: () -> Unit,
    onAddHere: () -> Unit,
    onShareOwn: () -> Unit,
) {
    CenterReadout(vp, format, fix, onCycleFormat)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        ActionLink("Waypoint at crosshair", "maps-add-crosshair", onClick = onAddCrosshair)
        ActionLink("Waypoint at my position", "maps-add-here", onClick = onAddHere)
        ActionLink("Share my position", "maps-share-own", onClick = onShareOwn)
    }
}

@Composable
private fun CenterReadout(vp: MapViewport, format: CoordFormat, fix: Fix?, onCycleFormat: () -> Unit) {
    val lat = vp.centerLat
    val lon = vp.centerLon
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.border(1.dp, Ink).clickable(onClick = onCycleFormat).padding(horizontal = 8.dp, vertical = 4.dp).testTag("maps-format")) {
            Kicker(formatLabel(format), color = Ink)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            fmtCoord(lat, lon, format),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
            color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("maps-readout"),
        )
    }
    val g = fix?.let { inverse(it.point, GeoPoint(lat, lon)) }
    val status = when {
        fix == null -> "No GPS fix"
        else -> "GPS ±${fix.accuracyM?.let { String.format(Locale.US, "%.0f", it) } ?: "?"} m" +
            (g?.let { " · crosshair ${fmtDistance(it.distanceM)} ${fmtBearing(it.initialBearingDeg)}" } ?: "")
    }
    Text(status, style = MaterialTheme.typography.bodySmall, color = Body, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SelectionPanel(
    selection: Selection,
    waypoints: List<MapWaypoint>,
    fix: Fix?,
    format: CoordFormat,
    onClose: () -> Unit,
    onAddWaypoint: (GeoPoint, String?) -> Unit,
    onNavigate: (String, GeoPoint) -> Unit,
    onProject: (GeoPoint, String) -> Unit,
    onEdit: (MapWaypoint) -> Unit,
    onShare: (MapWaypoint) -> Unit,
) {
    val (title, sub, point) = when (selection) {
        is Selection.Point -> Triple("Point", "", selection.p)
        is Selection.Wpt -> {
            val wp = waypoints.firstOrNull { it.id == selection.id }
            if (wp == null) { onClose(); return }
            Triple(wp.w.name.ifBlank { "Waypoint" }, listOf(wp.w.symbol, wp.w.note).filter { it.isNotBlank() }.joinToString(" · "), GeoPoint(wp.w.lat, wp.w.lon))
        }
        is Selection.Team -> Triple(selection.m.label, (if (selection.m.marker) "Marker · " else "Last fix · ") + fmtAge(selection.m.timeMs), GeoPoint(selection.m.lat, selection.m.lon))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        ActionLink("Close", "maps-sel-close", onClick = onClose)
    }
    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(fmtCoord(point.lat, point.lon, format), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono), color = Ink, modifier = Modifier.testTag("maps-sel-coord"))
    fix?.let { inverse(it.point, point) }?.let {
        Text("${fmtDistance(it.distanceM)} on ${fmtBearing(it.initialBearingDeg)} true from you", style = MaterialTheme.typography.bodySmall, color = Body)
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        ActionLink("Navigate", "maps-sel-nav") { onNavigate(title, point) }
        when (selection) {
            is Selection.Wpt -> {
                val wp = waypoints.firstOrNull { it.id == selection.id }
                if (wp != null) {
                    ActionLink("Edit", "maps-sel-edit") { onEdit(wp) }
                    ActionLink("Share", "maps-sel-share") { onShare(wp) }
                }
            }
            is Selection.Team -> ActionLink("Save as waypoint", "maps-sel-save") { onAddWaypoint(point, title) }
            is Selection.Point -> ActionLink("Add waypoint", "maps-sel-add") { onAddWaypoint(point, null) }
        }
        ActionLink("Project", "maps-sel-project") { onProject(point, title) }
    }
}

@Composable
private fun NavPanel(nav: NavState, route: MapRoute?, fix: Fix?, heading: State<Float?>, onLeg: (MapRoute, Int) -> Unit, onStop: () -> Unit) {
    val g = remember(fix, nav) { fix?.let { inverse(it.point, GeoPoint(nav.lat, nav.lon)) } }
    val compass = heading.value
    val hd = compass ?: fix?.bearingDeg
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Kicker(if (route != null) "Navigating · ${route.name} · point ${nav.leg + 1}/${route.points.size}" else "Navigating to")
            Text(nav.name.ifBlank { "Target" }, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ActionLink("Stop", "maps-nav-stop", onClick = onStop)
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val rel = if (g != null && hd != null) (g.initialBearingDeg - hd).toFloat() else g?.initialBearingDeg?.toFloat()
        Canvas(Modifier.size(64.dp).border(1.dp, Line).testTag("maps-nav-arrow")) {
            if (rel != null) rotate(rel) {
                val c = center
                val s = size.minDimension / 2f * 0.8f
                val p = Path().apply {
                    moveTo(c.x, c.y - s)
                    lineTo(c.x + s * 0.55f, c.y + s * 0.7f)
                    lineTo(c.x, c.y + s * 0.35f)
                    lineTo(c.x - s * 0.55f, c.y + s * 0.7f)
                    close()
                }
                drawPath(p, if (hd != null) Accent else RuleStrong)
            } else drawCircle(RuleStrong, 4.dp.toPx(), Offset(size.width / 2, size.height / 2))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            val speed = fix?.speedMps?.toDouble()
            NavLine("Distance", g?.let { fmtDistance(it.distanceM) } ?: "No GPS fix")
            NavLine("Bearing", g?.let { fmtBearing(it.initialBearingDeg) + " true" } ?: "–")
            NavLine("Heading", hd?.let { fmtBearing(it.toDouble()) + if (compass != null) " compass" else " GPS" } ?: "Unavailable")
            NavLine(
                "ETA",
                if (g != null && speed != null && speed > 0.5) "${fmtDuration((g.distanceM / speed * 1000).toLong())} at ${fmtSpeed(speed)}" else "–",
            )
        }
    }
    if (route != null) {
        Row {
            ActionLink("Previous point", "maps-nav-prev", enabled = nav.leg > 0) { onLeg(route, nav.leg - 1) }
            ActionLink("Next point", "maps-nav-next", enabled = nav.leg + 1 < route.points.size) { onLeg(route, nav.leg + 1) }
        }
    }
}

@Composable
private fun NavLine(k: String, v: String) = Row {
    Text(k, style = MaterialTheme.typography.bodySmall, color = Body, modifier = Modifier.width(76.dp))
    Text(v, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = Ink, modifier = Modifier.testTag("maps-nav-${k.lowercase()}"))
}

@Composable
private fun MeasurePanel(
    pts: List<GeoPoint>,
    closed: Boolean,
    onToggleClosed: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onSaveRoute: () -> Unit,
    onDone: () -> Unit,
) {
    val list = pts.toList()
    val len = if (list.size >= 2) runCatching { pathLength(list) }.getOrNull() else null
    val area = if (closed && list.size >= 3) runCatching { polygonArea(list) }.getOrNull() else null
    val perim = if (closed && list.size >= 3) runCatching { polygonPerimeter(list) }.getOrNull() else null
    Kicker("Measure · ${list.size} points")
    Text(
        if (list.isEmpty()) "Tap the map to add points." else "Path ${len?.let { fmtDistance(it) } ?: "–"}",
        style = MaterialTheme.typography.bodyMedium, color = Ink, modifier = Modifier.testTag("maps-measure-length"),
    )
    if (area != null && perim != null) {
        Text("Area ${fmtArea(area)} · perimeter ${fmtDistance(perim)}", style = MaterialTheme.typography.bodyMedium, color = Ink,
            modifier = Modifier.testTag("maps-measure-area"))
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        ActionLink("Undo", "maps-measure-undo", enabled = list.isNotEmpty(), onClick = onUndo)
        ActionLink("Clear", "maps-measure-clear", enabled = list.isNotEmpty(), onClick = onClear)
        ActionLink(if (closed) "Area: on" else "Area: off", "maps-measure-area-toggle", onClick = onToggleClosed)
        ActionLink("Save as route", "maps-measure-save", enabled = list.size >= 2, onClick = onSaveRoute)
        ActionLink("Done", "maps-measure-done", onClick = onDone)
    }
}

@Composable
private fun RouteDraftPanel(pts: List<GeoPoint>, onUndo: () -> Unit, onClear: () -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    val list = pts.toList()
    val len = if (list.size >= 2) runCatching { pathLength(list) }.getOrNull() else null
    Kicker("New route · ${list.size} points")
    Text(
        if (list.isEmpty()) "Tap the map or a waypoint to add route points in order." else "Length ${len?.let { fmtDistance(it) } ?: "–"}",
        style = MaterialTheme.typography.bodyMedium, color = Ink,
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        ActionLink("Undo", "maps-route-undo", enabled = list.isNotEmpty(), onClick = onUndo)
        ActionLink("Clear", "maps-route-clear", enabled = list.isNotEmpty(), onClick = onClear)
        ActionLink("Save", "maps-route-save", enabled = list.size >= 2, onClick = onSave)
        ActionLink("Cancel", "maps-route-cancel", onClick = onCancel)
    }
}

// ---- dialogs --------------------------------------------------------------------------------

@Composable
private fun GoToDialog(addWaypoint: Boolean, onDismiss: () -> Unit, onGo: (GeoPoint) -> Unit, onAdd: (GeoPoint) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun parsed(): GeoPoint? = try {
        parseCoordinate(text.trim()).also { error = null }
    } catch (e: Exception) {
        error = "Not recognised. Examples: 38.8895, -77.0352 · 38°53'22\"N 77°02'07\"W · 18S UJ 23371 06519"
        null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (addWaypoint) "Waypoint by coordinate" else "Go to coordinate") },
        text = {
            Column {
                OutlinedTextField(
                    text, { text = it }, label = { Text("Coordinate (decimal, DMS, DDM, UTM, MGRS)") },
                    colors = fieldColors(), textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth().testTag("maps-goto-input"),
                )
                error?.let { Text(it, color = Alert, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            Row {
                if (!addWaypoint) TextButton(onClick = { parsed()?.let(onGo) }, modifier = Modifier.testTag("maps-goto-go")) { Text("Go", color = Accent) }
                TextButton(onClick = { parsed()?.let(onAdd) }, modifier = Modifier.testTag("maps-goto-add")) { Text("Add waypoint", color = Accent) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Accent) } },
    )
}

@Composable
private fun ProjectDialog(fromName: String, onDismiss: () -> Unit, onProject: (Double, Double, String) -> Unit) {
    var bearing by rememberSaveable { mutableStateOf("") }
    var dist by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project a waypoint") },
        text = {
            Column {
                Text("From ${fromName.ifBlank { "point" }}", color = Body, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(bearing, { bearing = it }, label = { Text("Bearing, degrees true") }, singleLine = true, colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("maps-project-bearing"))
                OutlinedTextField(dist, { dist = it }, label = { Text("Distance, metres") }, singleLine = true, colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("maps-project-distance"))
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("maps-project-name"))
                error?.let { Text(it, color = Alert, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val b = bearing.trim().replace(',', '.').toDoubleOrNull()
                val d = dist.trim().replace(',', '.').toDoubleOrNull()
                when {
                    b == null || b < 0 || b >= 360 -> error = "Bearing must be 0 to 359.9 degrees."
                    d == null || d <= 0 -> error = "Distance must be more than 0 m."
                    else -> onProject(b, d, name.trim().ifBlank { "${fromName.ifBlank { "Point" }} ${fmtBearing(b)} ${fmtDistance(d)}" }.take(128))
                }
            }, modifier = Modifier.testTag("maps-project-ok")) { Text("Project", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Accent) } },
    )
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().testTag("maps-name-input"))
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim().ifBlank { initial }.take(128)) }, modifier = Modifier.testTag("maps-name-ok")) {
                Text(confirm, color = Accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Accent) } },
    )
}
