package com.infinitum.aether.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infinitum.aether.data.MapPack
import com.infinitum.aether.data.MapRoute
import com.infinitum.aether.data.MapTrack
import com.infinitum.aether.data.MapWaypoint
import com.infinitum.aether.data.MapsStore
import com.infinitum.aether.net.AetherNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.aether_core.CoordFormat
import uniffi.aether_core.GeoPoint
import uniffi.aether_core.Geodesic
import uniffi.aether_core.PositionReport
import uniffi.aether_core.ProfilePoint
import uniffi.aether_core.TrackStats
import uniffi.aether_core.Waypoint
import uniffi.aether_core.elevationProfile
import uniffi.aether_core.geodesicInverse
import uniffi.aether_core.parseCoordinate
import uniffi.aether_core.pathLength
import uniffi.aether_core.trackStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- shared bits --------------------------------------------------------------------------

internal fun inverse(a: GeoPoint, b: GeoPoint): Geodesic? = runCatching { geodesicInverse(a, b) }.getOrNull()

internal fun routeLength(r: MapRoute): Double =
    if (r.points.size < 2) 0.0 else runCatching { pathLength(r.points.map { GeoPoint(it.lat, it.lon) }) }.getOrDefault(0.0)

@Composable
internal fun MapsHeader(title: String, tag: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = GUTTER.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BarAction("Back", "$tag-back", onBack)
        Spacer(Modifier.width(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Hairline()
}

@Composable
internal fun ActionLink(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) = TextButton(
    onClick = onClick,
    enabled = enabled,
    shape = MaterialTheme.shapes.small,
    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    modifier = Modifier.testTag(tag),
) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Accent else RuleStrong) }

@Composable
internal fun OutlineAction(text: String, tag: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) = OutlinedButton(
    onClick = onClick,
    enabled = enabled,
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(1.dp, if (enabled) Ink else Rule),
    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    modifier = modifier.testTag(tag),
) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Ink else RuleStrong) }

/** A list row with its own action links underneath. */
@Composable
private fun ActionRow(tag: String, title: String, subtitle: String, trailing: String, onClick: () -> Unit, actions: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag).padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Kicker(trailing, color = Ink)
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp).horizontalScroll(rememberScrollState())) { actions() }
        Hairline()
    }
}

private val dateFmt get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

// ---- waypoints ----------------------------------------------------------------------------

@Composable
internal fun WaypointsScreen(
    waypoints: List<MapWaypoint>,
    fix: Fix?,
    format: CoordFormat,
    onOpen: (MapWaypoint) -> Unit,
    onAddCrosshair: () -> Unit,
    onAddHere: () -> Unit,
    onAddCoord: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        MapsHeader("Waypoints", "maps-wpts", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = GUTTER.dp, end = GUTTER.dp, bottom = 32.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
                    ActionLink("Add at crosshair", "maps-wpts-add-crosshair", onClick = onAddCrosshair)
                    ActionLink("Add at my position", "maps-wpts-add-here", onClick = onAddHere)
                    ActionLink("Add by coordinate", "maps-wpts-add-coord", onClick = onAddCoord)
                }
            }
            item { Section(1, "WAYPOINTS · ${waypoints.size}") }
            if (waypoints.isEmpty()) item { Hint("No waypoints yet. Add one at the crosshair, at your GPS position or by coordinate.") }
            items(waypoints, key = { it.id }) { wp ->
                val d = fix?.let { inverse(it.point, GeoPoint(wp.w.lat, wp.w.lon)) }
                ListRow(
                    tag = "maps-wpt-${wp.id}",
                    online = false,
                    title = wp.w.name.ifBlank { "Waypoint" },
                    subtitle = "${wp.w.symbol.ifBlank { "Point" }} · ${fmtCoord(wp.w.lat, wp.w.lon, format)}",
                    trailing = d?.let { "${fmtDistance(it.distanceM)} ${fmtBearing(it.initialBearingDeg)}" } ?: "",
                    onClick = { onOpen(wp) },
                )
            }
        }
    }
}

@Composable
internal fun WaypointEditor(
    initial: MapWaypoint,
    isNew: Boolean,
    format: CoordFormat,
    fix: Fix?,
    onSave: (MapWaypoint) -> Unit,
    onDelete: () -> Unit,
    onNavigate: (MapWaypoint) -> Unit,
    onProject: (MapWaypoint) -> Unit,
    onShare: (MapWaypoint) -> Unit,
    onShow: (MapWaypoint) -> Unit,
    onBack: () -> Unit,
) {
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.w.name) }
    var coord by rememberSaveable(initial.id) { mutableStateOf(fmtCoord(initial.w.lat, initial.w.lon, format)) }
    var ele by rememberSaveable(initial.id) { mutableStateOf(initial.w.eleM?.let { String.format(Locale.US, "%.0f", it) } ?: "") }
    var note by rememberSaveable(initial.id) { mutableStateOf(initial.w.note) }
    var symbol by rememberSaveable(initial.id) { mutableStateOf(initial.w.symbol.ifBlank { MapsStore.SYMBOLS.first() }) }
    var error by remember { mutableStateOf<String?>(null) }

    fun build(): MapWaypoint? {
        val p = try {
            parseCoordinate(coord.trim())
        } catch (e: Exception) {
            error = "Coordinate not recognised. Use decimal, DMS, DDM, UTM or MGRS."
            return null
        }
        val e = ele.trim().replace(',', '.')
        val eleM = if (e.isEmpty()) null else e.toDoubleOrNull() ?: run { error = "Elevation must be a number in metres."; return null }
        error = null
        return MapWaypoint(
            initial.id,
            Waypoint(name.trim().ifBlank { "Waypoint" }.take(128), p.lat, p.lon, eleM, initial.w.timeMs ?: System.currentTimeMillis(), note.trim().take(2048), symbol),
        )
    }

    Column(Modifier.fillMaxSize()) {
        MapsHeader(if (isNew) "New waypoint" else "Waypoint", "maps-wpt", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = GUTTER.dp)) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().testTag("maps-wpt-name"))
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(coord, { coord = it }, label = { Text("Coordinate (any format)") }, singleLine = true, colors = fieldColors(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                modifier = Modifier.fillMaxWidth().testTag("maps-wpt-coord"))
            Row {
                ActionLink("Use my position", "maps-wpt-use-fix", enabled = fix != null) {
                    fix?.let {
                        coord = fmtCoord(it.lat, it.lon, format)
                        it.eleM?.let { e -> ele = String.format(Locale.US, "%.0f", e) }
                    }
                }
            }
            OutlinedTextField(ele, { ele = it }, label = { Text("Elevation, m (optional)") }, singleLine = true, colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("maps-wpt-ele"))
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(note, { note = it }, label = { Text("Note") }, colors = fieldColors(), minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag("maps-wpt-note"))
            Spacer(Modifier.height(16.dp))
            Kicker("Symbol")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in MapsStore.SYMBOLS) {
                    val on = s == symbol
                    Box(
                        Modifier.border(1.dp, if (on) Ink else RuleStrong).background(if (on) Ink else Paper)
                            .clickable { symbol = s }.padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("maps-wpt-sym-$s"),
                    ) { Text(s, style = MaterialTheme.typography.labelLarge, color = if (on) Paper else Ink) }
                }
            }
            error?.let { Text(it, color = Alert, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.height(18.dp))
            PrimaryButton("Save waypoint", true, "maps-wpt-save", Modifier.fillMaxWidth()) { build()?.let(onSave) }
            if (!isNew) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    ActionLink("Navigate", "maps-wpt-nav") { build()?.let(onNavigate) }
                    ActionLink("Project", "maps-wpt-project") { build()?.let(onProject) }
                    ActionLink("Share", "maps-wpt-share") { build()?.let(onShare) }
                    ActionLink("Show on map", "maps-wpt-show") { build()?.let(onShow) }
                    ActionLink("Delete", "maps-wpt-delete", onClick = onDelete)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ---- tracks -------------------------------------------------------------------------------

@Composable
internal fun TracksScreen(
    tracks: List<MapTrack>,
    recording: String?,
    running: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onOpen: (MapTrack) -> Unit,
    onBack: () -> Unit,
) {
    val lengths by produceState(emptyMap<String, Double>(), tracks) {
        value = withContext(Dispatchers.Default) {
            tracks.associate { t -> t.id to runCatching { trackStats(t.points).distanceM }.getOrDefault(0.0) }
        }
    }
    val rec = tracks.firstOrNull { it.id == recording }
    Column(Modifier.fillMaxSize()) {
        MapsHeader("Tracks", "maps-tracks", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = GUTTER.dp, end = GUTTER.dp, bottom = 32.dp)) {
            item { Section(1, "RECORDING") }
            item {
                when {
                    rec == null -> {
                        Hint("Records GPS fixes every 2 s or 5 m, in the background, until you stop.")
                        PrimaryButton("Start recording", true, "maps-track-start", Modifier.fillMaxWidth(), onStart)
                    }
                    else -> {
                        Text(rec.name, style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.padding(top = 12.dp))
                        Text(
                            "${if (running) "Recording" else "Paused"} · ${rec.points.size} points · ${fmtDistance(lengths[rec.id] ?: 0.0)}",
                            style = MaterialTheme.typography.bodySmall, color = Body,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (running) OutlineAction("Pause", "maps-track-pause", onClick = onPause)
                            else OutlineAction("Resume", "maps-track-resume", onClick = onResume)
                            OutlineAction("Stop and save", "maps-track-stop", onClick = onStop)
                        }
                    }
                }
            }
            item { Section(2, "TRACKS · ${tracks.size}") }
            if (tracks.isEmpty()) item { Hint("No tracks yet. Record one, or import a GPX file from Files.") }
            items(tracks.reversed(), key = { it.id }) { t ->
                ListRow(
                    tag = "maps-track-${t.id}",
                    online = t.id == recording,
                    title = t.name,
                    subtitle = "${dateFmt.format(Date(t.createdAt))} · ${t.points.size} points · ${t.segments.count { it.isNotEmpty() }} seg.",
                    trailing = fmtDistance(lengths[t.id] ?: 0.0),
                    onClick = { onOpen(t) },
                )
            }
        }
    }
}

@Composable
internal fun TrackDetail(
    track: MapTrack,
    canResume: Boolean,
    onShow: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onResume: () -> Unit,
    onBack: () -> Unit,
) {
    val data by produceState<Pair<TrackStats?, List<ProfilePoint>>?>(null, track) {
        value = withContext(Dispatchers.Default) {
            val pts = track.points
            runCatching { trackStats(pts) }.getOrNull() to runCatching { elevationProfile(pts) }.getOrDefault(emptyList())
        }
    }
    Column(Modifier.fillMaxSize()) {
        MapsHeader(track.name, "maps-trackd", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = GUTTER.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
                ActionLink("Show on map", "maps-trackd-show", onClick = onShow)
                ActionLink("Resume recording", "maps-trackd-resume", enabled = canResume, onClick = onResume)
                ActionLink("Rename", "maps-trackd-rename", onClick = onRename)
                ActionLink("Delete", "maps-trackd-delete", onClick = onDelete)
            }
            Section(1, "STATISTICS")
            val s = data?.first
            if (data == null) Hint("Calculating…")
            else if (s == null) Hint("Not enough points for statistics.")
            else {
                SpecRow("Points", s.points.toString())
                SpecRow("Distance", fmtDistance(s.distanceM))
                SpecRow("Ascent", String.format(Locale.US, "%.0f m", s.ascentM))
                SpecRow("Descent", String.format(Locale.US, "%.0f m", s.descentM))
                if (s.minEleM != null && s.maxEleM != null) {
                    SpecRow("Elevation", String.format(Locale.US, "%.0f – %.0f m", s.minEleM, s.maxEleM))
                }
                s.durationMs?.let { SpecRow("Duration", fmtDuration(it)) }
                s.movingMs?.let { SpecRow("Moving time", fmtDuration(it)) }
                s.movingSpeedMps?.let { SpecRow("Avg moving speed", fmtSpeed(it)) }
                s.maxSpeedMps?.let { SpecRow("Max speed", fmtSpeed(it)) }
            }
            Section(2, "ELEVATION PROFILE")
            val profile = data?.second.orEmpty()
            if (profile.size >= 2) {
                Spacer(Modifier.height(12.dp))
                ElevationChart(profile)
            } else if (data != null) {
                Hint("This track has no elevation data.")
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
internal fun ElevationChart(profile: List<ProfilePoint>) {
    val tm = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(180.dp).testTag("maps-elevation")) {
        val padL = 46.dp.toPx()
        val padB = 20.dp.toPx()
        val padT = 6.dp.toPx()
        val minE = profile.minOf { it.eleM }
        val maxE = profile.maxOf { it.eleM }
        val range = (maxE - minE).coerceAtLeast(10.0)
        val maxD = profile.last().distanceM.coerceAtLeast(1.0)
        val w = size.width - padL
        val h = size.height - padT - padB
        fun x(d: Double) = padL + (d / maxD * w).toFloat()
        fun y(e: Double) = padT + ((1 - (e - minE) / range) * h).toFloat()
        drawLine(Line, Offset(padL, padT), Offset(padL, padT + h), 1.dp.toPx())
        drawLine(Line, Offset(padL, padT + h), Offset(size.width, padT + h), 1.dp.toPx())
        val path = Path()
        val fill = Path()
        profile.forEachIndexed { i, p ->
            if (i == 0) { path.moveTo(x(p.distanceM), y(p.eleM)); fill.moveTo(x(p.distanceM), padT + h); fill.lineTo(x(p.distanceM), y(p.eleM)) }
            else { path.lineTo(x(p.distanceM), y(p.eleM)); fill.lineTo(x(p.distanceM), y(p.eleM)) }
        }
        fill.lineTo(x(profile.last().distanceM), padT + h)
        fill.close()
        drawPath(fill, Accent.copy(alpha = 0.08f))
        drawPath(path, Accent, style = Stroke(2.dp.toPx()))
        val style = TextStyle(fontSize = 10.sp, color = Muted, fontFamily = Mono)
        drawText(tm, String.format(Locale.US, "%.0f m", maxE), Offset(0f, padT), style)
        drawText(tm, String.format(Locale.US, "%.0f m", minE), Offset(0f, padT + h - 14.dp.toPx()), style)
        drawText(tm, "0", Offset(padL, padT + h + 4.dp.toPx()), style)
        val total = fmtDistance(maxD)
        val r = tm.measure(total, style)
        drawText(r, topLeft = Offset(size.width - r.size.width, padT + h + 4.dp.toPx()))
    }
}

// ---- routes -------------------------------------------------------------------------------

@Composable
internal fun RoutesScreen(routes: List<MapRoute>, onOpen: (MapRoute) -> Unit, onBuild: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        MapsHeader("Routes", "maps-routes", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = GUTTER.dp, end = GUTTER.dp, bottom = 32.dp)) {
            item {
                Hint("A route is an ordered list of points. Build one by tapping the map and existing waypoints.")
                PrimaryButton("Build a route on the map", true, "maps-route-build", Modifier.fillMaxWidth(), onBuild)
            }
            item { Section(1, "ROUTES · ${routes.size}") }
            if (routes.isEmpty()) item { Hint("No routes yet.") }
            items(routes, key = { it.id }) { r ->
                ListRow(
                    tag = "maps-route-${r.id}",
                    online = false,
                    title = r.name,
                    subtitle = "${r.points.size} points · ${(r.points.size - 1).coerceAtLeast(0)} legs",
                    trailing = fmtDistance(routeLength(r)),
                    onClick = { onOpen(r) },
                )
            }
        }
    }
}

@Composable
internal fun RouteDetail(route: MapRoute, onNavigate: (Int) -> Unit, onShow: () -> Unit, onDelete: () -> Unit, onBack: () -> Unit) {
    val legs = remember(route) {
        route.points.zipWithNext().map { (a, b) -> inverse(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon)) }
    }
    Column(Modifier.fillMaxSize()) {
        MapsHeader(route.name, "maps-routed", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = GUTTER.dp, end = GUTTER.dp, bottom = 32.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
                    ActionLink("Navigate from start", "maps-routed-nav", enabled = route.points.isNotEmpty()) { onNavigate(0) }
                    ActionLink("Show on map", "maps-routed-show", onClick = onShow)
                    ActionLink("Delete", "maps-routed-delete", onClick = onDelete)
                }
                SpecRow("Total length", fmtDistance(routeLength(route)))
            }
            item { Section(1, "LEGS") }
            if (route.points.size < 2) item { Hint("A route needs at least two points.") }
            items(legs.size) { i ->
                val a = route.points[i]
                val b = route.points[i + 1]
                val g = legs[i]
                ListRow(
                    tag = "maps-routed-leg-$i",
                    online = false,
                    title = "${i + 1}. ${a.name.ifBlank { "P${i + 1}" }} → ${b.name.ifBlank { "P${i + 2}" }}",
                    subtitle = g?.let { "${fmtDistance(it.distanceM)} on ${fmtBearing(it.initialBearingDeg)} true" } ?: "",
                    trailing = "GO",
                    onClick = { onNavigate(i + 1) },
                )
            }
        }
    }
}

// ---- map packs (layers) -------------------------------------------------------------------

@Composable
internal fun LayersScreen(
    packs: List<MapPack>,
    activeId: String?,
    busy: Boolean,
    onUse: (String?) -> Unit,
    onImport: () -> Unit,
    onDelete: (MapPack) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        MapsHeader("Map layers", "maps-layers", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = GUTTER.dp, end = GUTTER.dp, bottom = 32.dp)) {
            item {
                Hint("Offline raster map packs in MBTiles format (PNG or JPEG tiles). Files are copied into this device's private storage; nothing is downloaded.")
                PrimaryButton(if (busy) "Importing…" else "Import .mbtiles map pack", !busy, "maps-layers-import", Modifier.fillMaxWidth(), onImport)
            }
            item { Section(1, "BASE LAYER") }
            item {
                ListRow("maps-layer-grid", activeId == null, "Grid only", "Latitude/longitude graticule, no map pack", if (activeId == null) "IN USE" else "") { onUse(null) }
            }
            items(packs, key = { it.id }) { p ->
                val on = p.id == activeId
                ActionRow(
                    tag = "maps-layer-${p.id}",
                    title = p.name,
                    subtitle = "Zoom ${p.minZoom}–${p.maxZoom}" + (p.bounds?.let {
                        String.format(Locale.US, " · %.3f, %.3f to %.3f, %.3f", it[1], it[0], it[3], it[2])
                    } ?: ""),
                    trailing = if (on) "IN USE" else "",
                    onClick = { onUse(p.id) },
                ) {
                    ActionLink("Use", "maps-layer-use-${p.id}", enabled = !on) { onUse(p.id) }
                    ActionLink("Delete", "maps-layer-delete-${p.id}") { onDelete(p) }
                }
            }
        }
    }
}

// ---- team ---------------------------------------------------------------------------------

@Composable
internal fun TeamScreen(
    team: List<TeamMark>,
    fix: Fix?,
    format: CoordFormat,
    onShow: (TeamMark) -> Unit,
    onNavigate: (TeamMark) -> Unit,
    onSave: (TeamMark) -> Unit,
    onShareOwn: () -> Unit,
    onBack: () -> Unit,
) {
    val mates = team.filter { !it.marker }
    val marks = team.filter { it.marker }.reversed()
    @Composable
    fun row(m: TeamMark) {
        val d = fix?.let { inverse(it.point, GeoPoint(m.lat, m.lon)) }
        ActionRow(
            tag = "maps-team-${m.key}",
            title = m.label,
            subtitle = "${fmtCoord(m.lat, m.lon, format)} · ${fmtAge(m.timeMs)}",
            trailing = d?.let { "${fmtDistance(it.distanceM)} ${fmtBearing(it.initialBearingDeg)}" } ?: "",
            onClick = { onShow(m) },
        ) {
            ActionLink("Show", "maps-team-show-${m.key}") { onShow(m) }
            ActionLink("Navigate", "maps-team-nav-${m.key}") { onNavigate(m) }
            ActionLink("Save as waypoint", "maps-team-save-${m.key}") { onSave(m) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        MapsHeader("Team picture", "maps-team", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = GUTTER.dp, end = GUTTER.dp, bottom = 32.dp)) {
            item {
                Hint("Positions and markers teammates have shared with you, end-to-end encrypted over ÆTHER Talk.")
                PrimaryButton("Share my position", true, "maps-team-share-own", Modifier.fillMaxWidth(), onShareOwn)
            }
            item { Section(1, "TEAMMATES · ${mates.size}") }
            if (mates.isEmpty()) item { Hint("No teammate positions received yet.") }
            items(mates, key = { it.key }) { row(it) }
            item { Section(2, "MARKERS · ${marks.size}") }
            if (marks.isEmpty()) item { Hint("No markers received yet.") }
            items(marks, key = { it.key }) { row(it) }
        }
    }
}

@Composable
internal fun ShareScreen(node: AetherNode, what: String, report: PositionReport?, onSend: (String) -> Unit, onBack: () -> Unit) {
    val states by node.linkStates.collectAsState()
    val contacts by node.contacts.collectAsState()
    val online = remember(states) { node.onlineConvos() }
    Column(Modifier.fillMaxSize()) {
        MapsHeader("Share", "maps-share", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = GUTTER.dp, end = GUTTER.dp, bottom = 32.dp)) {
            item {
                Text(what, style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.padding(top = 16.dp))
                if (report != null) {
                    Text(fmtCoord(report.lat, report.lon, uniffi.aether_core.CoordFormat.MGRS), style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = Body)
                    Hint("Choose a teammate with a live session. The position is sent end-to-end encrypted.")
                } else {
                    Hint("No GPS fix yet. Wait for a fix, then try again.")
                }
            }
            item { Section(1, "ONLINE · ${online.size}") }
            if (online.isEmpty()) item { Hint("No teammates online.") }
            items(online, key = { it }) { c ->
                ListRow("maps-share-$c", true, contacts[c]?.name ?: "Unknown contact", "Live session", "SEND") {
                    if (report != null) onSend(c)
                }
            }
        }
    }
}

// ---- files --------------------------------------------------------------------------------

@Composable
internal fun FilesScreen(counts: String, onImportGpx: () -> Unit, onExportGpx: () -> Unit, onExportKml: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        MapsHeader("Import and export", "maps-files", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = GUTTER.dp)) {
            Section(1, "IMPORT")
            Hint("GPX 1.0 or 1.1: waypoints, routes and tracks are added to what you have.")
            PrimaryButton("Import GPX file", true, "maps-files-import", Modifier.fillMaxWidth(), onImportGpx)
            Section(2, "EXPORT")
            Hint("Everything stored in ÆTHER Maps: $counts. Exported files are not encrypted.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineAction("Export GPX", "maps-files-export-gpx", onClick = onExportGpx)
                OutlineAction("Export KML", "maps-files-export-kml", onClick = onExportKml)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
