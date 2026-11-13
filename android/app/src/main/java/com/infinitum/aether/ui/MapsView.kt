package com.infinitum.aether.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.infinitum.aether.data.MapPack
import com.infinitum.aether.data.MapWaypoint
import com.infinitum.aether.data.MapsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.aether_core.GeoPoint
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

// ---- Web Mercator (unit square, y down), same convention as the core's mercatorUnit -------

private const val MAX_LAT = 85.05112878

internal fun mercX(lon: Double) = (lon + 180.0) / 360.0
internal fun mercY(lat: Double): Double {
    val s = sin(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))
    return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
}
internal fun lonOf(x: Double) = x * 360.0 - 180.0
internal fun latOf(y: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y))))

internal const val MIN_ZOOM = 1.0
internal const val MAX_ZOOM = 21.0

/** Map camera: centre in unit Mercator coordinates, fractional zoom, bearing (degrees, up = bearing). */
internal class MapViewport(cx: Double, cy: Double, zoom: Double, bearing: Float) {
    var cx by mutableDoubleStateOf(cx)
    var cy by mutableDoubleStateOf(cy)
    var zoom by mutableDoubleStateOf(zoom)
    var bearing by mutableFloatStateOf(bearing)
    /** Screen pixels of one 256-px tile at an integer zoom; set from screen density. */
    var tileBase = 256f

    val worldPx get() = tileBase * 2.0.pow(zoom)
    val centerLat get() = latOf(cy)
    val centerLon get() = lonOf(cx)
    val center get() = GeoPoint(centerLat, centerLon)

    fun centerOn(lat: Double, lon: Double) {
        cx = mercX(lon)
        cy = mercY(lat)
    }

    /** Screen offset (canvas of [w]×[h]) of a unit-square point. */
    fun toScreen(ux: Double, uy: Double, w: Float, h: Float): Offset {
        val world = worldPx
        val dx = (ux - cx) * world
        val dy = (uy - cy) * world
        val t = Math.toRadians(-bearing.toDouble())
        val c = cos(t)
        val s = sin(t)
        return Offset((dx * c - dy * s).toFloat() + w / 2f, (dx * s + dy * c).toFloat() + h / 2f)
    }

    fun geoToScreen(lat: Double, lon: Double, w: Float, h: Float) = toScreen(mercX(lon), mercY(lat), w, h)

    /** Unit-square point under a screen offset: (x, y). */
    fun toUnit(p: Offset, w: Float, h: Float): Pair<Double, Double> {
        val sx = (p.x - w / 2f).toDouble()
        val sy = (p.y - h / 2f).toDouble()
        val t = Math.toRadians(bearing.toDouble())
        val c = cos(t)
        val s = sin(t)
        val dx = sx * c - sy * s
        val dy = sx * s + sy * c
        val world = worldPx
        return (cx + dx / world) to (cy + dy / world)
    }

    fun toGeo(p: Offset, w: Float, h: Float): GeoPoint {
        val (x, y) = toUnit(p, w, h)
        return GeoPoint(latOf(y.coerceIn(0.0, 1.0)), lonOf(wrap01(x)))
    }

    /** Metres per screen pixel at the map centre. */
    fun metersPerPixel(): Double = 40_075_016.686 * cos(Math.toRadians(centerLat)) / worldPx

    fun zoomAbout(p: Offset, newZoom: Double, w: Float, h: Float) {
        val (ux, uy) = toUnit(p, w, h)
        zoom = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        keepUnder(ux, uy, p, w, h)
    }

    /** Moves the centre so the unit point ([ux], [uy]) sits under screen point [p]. */
    fun keepUnder(ux: Double, uy: Double, p: Offset, w: Float, h: Float) {
        val sx = (p.x - w / 2f).toDouble()
        val sy = (p.y - h / 2f).toDouble()
        val t = Math.toRadians(bearing.toDouble())
        val c = cos(t)
        val s = sin(t)
        val dx = sx * c - sy * s
        val dy = sx * s + sy * c
        val world = worldPx
        cx = wrap01(ux - dx / world)
        cy = (uy - dy / world).coerceIn(0.0, 1.0)
    }

    companion object {
        val Saver = listSaver<MapViewport, Any>(
            save = { listOf(it.cx, it.cy, it.zoom, it.bearing) },
            restore = { MapViewport(it[0] as Double, it[1] as Double, it[2] as Double, it[3] as Float) },
        )
    }
}

private fun wrap01(x: Double): Double = x - floor(x)

internal data class TileKey(val z: Int, val x: Int, val y: Int)

/** Tiles of [pack] covering the canvas, nearest the centre first; empty when far too many. */
internal fun visibleTiles(vp: MapViewport, w: Float, h: Float, pack: MapPack): List<TileKey> {
    if (w <= 0f || h <= 0f) return emptyList()
    val z = Math.round(vp.zoom).toInt().coerceIn(pack.minZoom, pack.maxZoom)
    val n = 1 shl z
    val corners = listOf(Offset(0f, 0f), Offset(w, 0f), Offset(0f, h), Offset(w, h)).map { vp.toUnit(it, w, h) }
    val x0 = floor(corners.minOf { it.first } * n).toInt()
    val x1 = floor(corners.maxOf { it.first } * n).toInt()
    val y0 = floor(corners.minOf { it.second } * n).toInt().coerceAtLeast(0)
    val y1 = floor(corners.maxOf { it.second } * n).toInt().coerceAtMost(n - 1)
    if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong() > 300) return emptyList()
    val ccx = vp.cx * n
    val ccy = vp.cy * n
    val out = ArrayList<TileKey>()
    for (y in y0..y1) for (x in x0..x1) {
        val wx = ((x % n) + n) % n
        out += TileKey(z, wx, y)
    }
    return out.distinct().sortedBy { hypot(it.x + 0.5 - ccx, it.y + 0.5 - ccy) }
}

// ---- layers -------------------------------------------------------------------------------

/** A polyline in unit Mercator coordinates (x0, y0, x1, y1, …) per segment. */
internal class MapLine(val id: String, val segments: List<DoubleArray>, val color: Color, val width: Float, val dashed: Boolean = false)

internal fun toLine(id: String, segments: List<List<GeoPoint>>, color: Color, width: Float, dashed: Boolean = false) = MapLine(
    id,
    segments.filter { it.isNotEmpty() }.map { seg ->
        DoubleArray(seg.size * 2).also { a -> seg.forEachIndexed { i, p -> a[2 * i] = mercX(p.lon); a[2 * i + 1] = mercY(p.lat) } }
    },
    color, width, dashed,
)

internal data class TeamMark(val key: String, val label: String, val lat: Double, val lon: Double, val timeMs: Long, val marker: Boolean, val symbol: String)

internal class MapLayers(
    val waypoints: List<MapWaypoint> = emptyList(),
    val lines: List<MapLine> = emptyList(),
    /** Points being measured or the route being drawn. */
    val draft: List<GeoPoint> = emptyList(),
    val draftClosed: Boolean = false,
    val draftColor: Color = Caution,
    val navTo: GeoPoint? = null,
    val team: List<TeamMark> = emptyList(),
    val fix: Fix? = null,
    val selected: GeoPoint? = null,
    val highlightId: String? = null,
)

/** What a tap hit. */
internal sealed interface MapHit {
    data class Waypoint(val w: MapWaypoint) : MapHit
    data class Team(val m: TeamMark) : MapHit
}

// ---- canvas -------------------------------------------------------------------------------

@Composable
internal fun MapCanvas(
    vp: MapViewport,
    maps: MapsStore,
    pack: MapPack?,
    layers: MapLayers,
    heading: State<Float?>,
    modifier: Modifier = Modifier,
    onUserMove: () -> Unit,
    onTap: (GeoPoint, MapHit?) -> Unit,
    onLongPress: (GeoPoint) -> Unit,
) {
    val density = LocalDensity.current.density
    vp.tileBase = (256f * density / 2f).coerceAtLeast(256f)
    var size by remember { mutableStateOf(IntSize.Zero) }
    var tileTick by remember { mutableIntStateOf(0) }
    val textMeasurer = rememberTextMeasurer()
    val layersNow = rememberUpdatedState(layers)
    val moveCb = rememberUpdatedState(onUserMove)
    val tapCb = rememberUpdatedState(onTap)
    val longCb = rememberUpdatedState(onLongPress)

    val needed by remember(pack) {
        derivedStateOf {
            if (pack == null) emptyList() else visibleTiles(vp, size.width.toFloat(), size.height.toFloat(), pack)
        }
    }
    LaunchedEffect(needed, pack) {
        val p = pack ?: return@LaunchedEffect
        for (t in needed) {
            if (maps.cachedTile(p, t.z, t.x, t.y) != null) continue
            val b = withContext(Dispatchers.IO) { runCatching { maps.tile(p, t.z, t.x, t.y) }.getOrNull() }
            if (b != null) tileTick++
        }
    }

    fun hitTest(at: Offset): MapHit? {
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val radius = 28f * density
        val l = layersNow.value
        var best: MapHit? = null
        var bestD = radius
        for (m in l.team) {
            val d = (vp.geoToScreen(m.lat, m.lon, w, h) - at).getDistance()
            if (d < bestD) { bestD = d; best = MapHit.Team(m) }
        }
        for (wp in l.waypoints) {
            val d = (vp.geoToScreen(wp.w.lat, wp.w.lon, w, h) - at).getDistance()
            if (d < bestD) { bestD = d; best = MapHit.Waypoint(wp) }
        }
        return best
    }

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .testTag("maps-canvas")
            .pointerInput(Unit) {
                detectTransformGestures(panZoomLock = true) { centroid, pan, zoomChange, rotation ->
                    moveCb.value()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val (ux, uy) = vp.toUnit(centroid, w, h)
                    vp.zoom = (vp.zoom + log2(zoomChange.toDouble())).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    vp.bearing = (((vp.bearing - rotation) % 360f) + 360f) % 360f
                    vp.keepUnder(ux, uy, centroid + pan, w, h)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { p ->
                        moveCb.value()
                        vp.zoomAbout(p, vp.zoom + 1.0, size.width.toFloat(), size.height.toFloat())
                    },
                    onLongPress = { p -> longCb.value(vp.toGeo(p, size.width.toFloat(), size.height.toFloat())) },
                    onTap = { p -> tapCb.value(vp.toGeo(p, size.width.toFloat(), size.height.toFloat()), hitTest(p)) },
                )
            },
    ) {
        @Suppress("UNUSED_VARIABLE") val tick = tileTick
        val w = this.size.width
        val h = this.size.height
        drawRect(Wash)
        rotate(-vp.bearing, pivot = center) {
            drawGraticule(vp, w, h, textMeasurer, labels = vp.bearing == 0f)
            if (pack != null) drawTiles(vp, maps, pack, needed, w, h)
        }
        drawLayers(vp, layersNow.value, heading.value, w, h, textMeasurer, density)
        drawCrosshair(density)
        drawScaleBar(vp, h, textMeasurer, density)
    }
}

// ---- drawing ------------------------------------------------------------------------------

/** Unrotated screen offset (the rotation is applied by the enclosing rotate block). */
private fun flat(vp: MapViewport, ux: Double, uy: Double, w: Float, h: Float): Offset {
    val world = vp.worldPx
    return Offset(((ux - vp.cx) * world).toFloat() + w / 2f, ((uy - vp.cy) * world).toFloat() + h / 2f)
}

private fun DrawScope.drawTiles(vp: MapViewport, maps: MapsStore, pack: MapPack, tiles: List<TileKey>, w: Float, h: Float) {
    val world = vp.worldPx
    for (t in tiles) {
        val n = (1 shl t.z).toDouble()
        // Choose the copy of a wrapped column nearest the centre.
        var ux = t.x / n
        if (ux - vp.cx > 0.5) ux -= 1.0 else if (vp.cx - ux > 0.5 + 1 / n) ux += 1.0
        val uy = t.y / n
        val tl = flat(vp, ux, uy, w, h)
        val side = world / n
        val l = floor(tl.x).toInt()
        val tp = floor(tl.y).toInt()
        val r = floor(tl.x + side).toInt()
        val b = floor(tl.y + side).toInt()
        if (r <= l || b <= tp) continue
        var bmp = maps.cachedTile(pack, t.z, t.x, t.y)
        var src = IntOffset.Zero
        var srcSize: IntSize? = null
        if (bmp == null) {
            // Fall back to a cached ancestor tile, scaled up, while this one loads.
            for (k in 1..4) {
                val pz = t.z - k
                if (pz < pack.minZoom) break
                val anc = maps.cachedTile(pack, pz, t.x shr k, t.y shr k) ?: continue
                val part = anc.width shr k
                if (part <= 0) break
                src = IntOffset((t.x and ((1 shl k) - 1)) * part, (t.y and ((1 shl k) - 1)) * part)
                srcSize = IntSize(part, part)
                bmp = anc
                break
            }
        }
        if (bmp == null) continue
        drawImage(
            bmp.asImageBitmap(),
            srcOffset = src,
            srcSize = srcSize ?: IntSize(bmp.width, bmp.height),
            dstOffset = IntOffset(l, tp),
            dstSize = IntSize(r - l, b - tp),
            filterQuality = FilterQuality.Low,
        )
    }
}

private val GRID_STEPS = doubleArrayOf(30.0, 10.0, 5.0, 2.0, 1.0, 0.5, 0.2, 0.1, 0.05, 0.02, 0.01, 0.005, 0.002, 0.001, 0.0005, 0.0002, 0.0001)

private fun DrawScope.drawGraticule(vp: MapViewport, w: Float, h: Float, tm: TextMeasurer, labels: Boolean) {
    val world = vp.worldPx
    val minPx = 90f * density
    val step = GRID_STEPS.lastOrNull { it / 360.0 * world >= minPx } ?: GRID_STEPS.first()
    val corners = listOf(Offset(0f, 0f), Offset(w, 0f), Offset(0f, h), Offset(w, h)).map { vp.toUnit(it, w, h) }
    // Extra margin so the rotated canvas has no bare corners.
    val pad = hypot(w.toDouble(), h.toDouble()) / world
    val xMin = corners.minOf { it.first } - pad
    val xMax = corners.maxOf { it.first } + pad
    val yMin = (corners.minOf { it.second } - pad).coerceAtLeast(0.0)
    val yMax = (corners.maxOf { it.second } + pad).coerceAtMost(1.0)
    val line = RuleStrong
    val stroke = 1f * density
    val style = TextStyle(fontSize = 10.sp, color = Muted, fontFamily = Mono)
    val top = flat(vp, 0.0, yMin, w, h).y
    val bottom = flat(vp, 0.0, yMax, w, h).y
    var lon = floor(lonOf(xMin) / step) * step
    val lonEnd = lonOf(xMax)
    var guard = 0
    while (lon <= lonEnd && guard++ < 400) {
        val xs = flat(vp, (lon + 180.0) / 360.0, 0.0, w, h).x
        drawLine(line, Offset(xs, top), Offset(xs, bottom), stroke)
        if (labels) {
            val norm = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            drawText(tm, fmtGrid(norm, step, 'E', 'W'), Offset(xs + 3 * density, 2 * density), style)
        }
        lon += step
    }
    val latTop = latOf(yMin)
    val latBottom = latOf(yMax)
    var lat = floor(latBottom / step) * step
    guard = 0
    val left = flat(vp, xMin, 0.0, w, h).x
    val right = flat(vp, xMax, 0.0, w, h).x
    while (lat <= latTop && guard++ < 400) {
        if (lat >= -MAX_LAT && lat <= MAX_LAT) {
            val y = flat(vp, 0.0, mercY(lat), w, h).y
            drawLine(line, Offset(left, y), Offset(right, y), stroke)
            if (labels) drawText(tm, fmtGrid(lat, step, 'N', 'S'), Offset(3 * density, y + 2 * density), style)
        }
        lat += step
    }
}

private fun fmtGrid(v: Double, step: Double, pos: Char, neg: Char): String {
    val decimals = when {
        step >= 1 -> 0
        step >= 0.1 -> 1
        step >= 0.01 -> 2
        step >= 0.001 -> 3
        else -> 4
    }
    val r = Math.round(v * 10_000.0) / 10_000.0
    return String.format(Locale.US, "%.${decimals}f°%c", kotlin.math.abs(r), if (r >= 0) pos else neg)
}

private fun DrawScope.drawLayers(vp: MapViewport, l: MapLayers, heading: Float?, w: Float, h: Float, tm: TextMeasurer, density: Float) {
    // Lines: tracks and routes.
    for (line in l.lines) {
        val width = (if (line.id == l.highlightId) line.width * 1.8f else line.width) * density
        val effect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(10f * density, 6f * density)) else null
        for (seg in line.segments) {
            if (seg.size < 4) {
                if (seg.size == 2) drawCircle(line.color, 3f * density, vp.toScreen(seg[0], seg[1], w, h))
                continue
            }
            val path = Path()
            var last = Offset.Unspecified
            var i = 0
            while (i < seg.size) {
                val p = vp.toScreen(seg[i], seg[i + 1], w, h)
                if (last == Offset.Unspecified) path.moveTo(p.x, p.y)
                else if ((p - last).getDistanceSquared() >= 2.25f || i == seg.size - 2) path.lineTo(p.x, p.y) else { i += 2; continue }
                last = p
                i += 2
            }
            drawPath(path, line.color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
        }
    }

    // Draft (measure or route being drawn).
    if (l.draft.isNotEmpty()) {
        val pts = l.draft.map { vp.geoToScreen(it.lat, it.lon, w, h) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (p in pts.drop(1)) lineTo(p.x, p.y)
        }
        drawPath(path, l.draftColor, style = Stroke(2.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (l.draftClosed && pts.size >= 3) {
            drawLine(l.draftColor, pts.last(), pts.first(), 2f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f * density, 6f * density)))
            val fill = Path().apply { moveTo(pts[0].x, pts[0].y); for (p in pts.drop(1)) lineTo(p.x, p.y); close() }
            drawPath(fill, l.draftColor.copy(alpha = 0.10f))
        }
        pts.forEachIndexed { i, p ->
            drawRect(Paper, Offset(p.x - 5 * density, p.y - 5 * density), Size(10 * density, 10 * density))
            drawRect(l.draftColor, Offset(p.x - 5 * density, p.y - 5 * density), Size(10 * density, 10 * density), style = Stroke(2f * density))
            if (i == 0 || i == pts.lastIndex) label(tm, "${i + 1}", p + Offset(8 * density, -8 * density), l.draftColor)
        }
    }

    // Navigation line from own fix to target.
    val fix = l.fix
    if (l.navTo != null) {
        val to = vp.geoToScreen(l.navTo.lat, l.navTo.lon, w, h)
        if (fix != null) {
            drawLine(Accent, vp.geoToScreen(fix.lat, fix.lon, w, h), to, 2.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f * density, 8f * density)))
        }
        drawCircle(Accent, 12f * density, to, style = Stroke(2f * density))
    }

    // Waypoints.
    for (wp in l.waypoints) {
        val p = vp.geoToScreen(wp.w.lat, wp.w.lon, w, h)
        if (p.x < -200 || p.y < -50 || p.x > w + 50 || p.y > h + 50) continue
        drawSymbol(wp.w.symbol, p, density, selected = wp.id == l.highlightId)
        if (wp.w.name.isNotBlank()) label(tm, wp.w.name, p + Offset(9 * density, -7 * density), Ink)
    }

    // Team: teammates' last fixes and shared markers.
    val now = System.currentTimeMillis()
    for (m in l.team) {
        val p = vp.geoToScreen(m.lat, m.lon, w, h)
        if (m.marker) {
            drawSymbol(m.symbol.ifBlank { "Marker" }, p, density, selected = false, received = true)
        } else {
            drawCircle(Paper, 8f * density, p)
            drawCircle(Ink, 6f * density, p)
        }
        label(tm, "${m.label} · ${fmtAge(m.timeMs, now)}", p + Offset(10 * density, -7 * density), Ink)
    }

    // Selected point (long press).
    l.selected?.let { s ->
        val p = vp.geoToScreen(s.lat, s.lon, w, h)
        drawCircle(Ink, 7f * density, p, style = Stroke(2f * density))
        drawLine(Ink, p + Offset(0f, -14 * density), p + Offset(0f, -7 * density), 2f * density)
        drawLine(Ink, p + Offset(0f, 7 * density), p + Offset(0f, 14 * density), 2f * density)
    }

    // Own position: accuracy circle, dot and heading.
    if (fix != null) {
        val p = vp.geoToScreen(fix.lat, fix.lon, w, h)
        fix.accuracyM?.let { acc ->
            val r = (acc / vp.metersPerPixel()).toFloat()
            if (r > 6 * density && r < 4000f) {
                drawCircle(Accent.copy(alpha = 0.12f), r, p)
                drawCircle(Accent.copy(alpha = 0.45f), r, p, style = Stroke(1f * density))
            }
        }
        val hd = heading ?: fix.bearingDeg
        if (hd != null) {
            rotate(hd - vp.bearing, pivot = p) {
                val wedge = Path().apply {
                    moveTo(p.x, p.y - 22 * density)
                    lineTo(p.x - 8 * density, p.y - 6 * density)
                    lineTo(p.x + 8 * density, p.y - 6 * density)
                    close()
                }
                drawPath(wedge, Accent)
            }
        }
        drawCircle(Paper, 9f * density, p)
        drawCircle(Accent, 6.5f * density, p)
    }
}

private fun DrawScope.label(tm: TextMeasurer, text: String, at: Offset, color: Color) {
    val r = tm.measure(text, TextStyle(fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium), maxLines = 1)
    drawRect(Paper.copy(alpha = 0.85f), Offset(at.x - 2 * density, at.y), Size(r.size.width + 4 * density, r.size.height.toFloat()))
    drawText(r, topLeft = at)
}

private fun DrawScope.drawSymbol(symbol: String, p: Offset, density: Float, selected: Boolean, received: Boolean = false) {
    val s = (if (selected) 9f else 7f) * density
    val color = when (symbol) {
        "Danger" -> Caution
        "Water", "LZ" -> Accent
        else -> Ink
    }
    val stroke = Stroke(2f * density)
    when (symbol) {
        "Danger", "Observation post" -> {
            val tri = Path().apply { moveTo(p.x, p.y - s * 1.2f); lineTo(p.x - s, p.y + s * 0.8f); lineTo(p.x + s, p.y + s * 0.8f); close() }
            drawPath(tri, Paper)
            drawPath(tri, color, style = stroke)
            if (symbol == "Danger") drawLine(color, Offset(p.x, p.y - s * 0.4f), Offset(p.x, p.y + s * 0.2f), 2f * density)
        }
        "Rally point", "Water" -> {
            drawCircle(Paper, s, p)
            drawCircle(color, s, p, style = stroke)
            drawCircle(color, s * 0.35f, p)
        }
        "LZ" -> {
            drawCircle(Paper, s * 1.1f, p)
            drawCircle(color, s * 1.1f, p, style = stroke)
            drawLine(color, Offset(p.x - s * 0.4f, p.y - s * 0.5f), Offset(p.x - s * 0.4f, p.y + s * 0.5f), 1.5f * density)
            drawLine(color, Offset(p.x + s * 0.4f, p.y - s * 0.5f), Offset(p.x + s * 0.4f, p.y + s * 0.5f), 1.5f * density)
            drawLine(color, Offset(p.x - s * 0.4f, p.y), Offset(p.x + s * 0.4f, p.y), 1.5f * density)
        }
        "Checkpoint", "Marker" -> {
            val d = Path().apply { moveTo(p.x, p.y - s); lineTo(p.x + s, p.y); lineTo(p.x, p.y + s); lineTo(p.x - s, p.y); close() }
            drawPath(d, if (received) Ink else Paper)
            drawPath(d, color, style = stroke)
        }
        "Flag" -> {
            drawLine(color, Offset(p.x, p.y + s), Offset(p.x, p.y - s * 1.3f), 2f * density)
            val f = Path().apply { moveTo(p.x, p.y - s * 1.3f); lineTo(p.x + s * 1.3f, p.y - s * 0.8f); lineTo(p.x, p.y - s * 0.3f); close() }
            drawPath(f, Accent)
        }
        else -> {
            drawRect(Paper, Offset(p.x - s, p.y - s), Size(2 * s, 2 * s))
            drawRect(if (received) Ink else color, Offset(p.x - s, p.y - s), Size(2 * s, 2 * s), style = stroke)
            if (received) drawRect(Ink, Offset(p.x - s * 0.5f, p.y - s * 0.5f), Size(s, s))
        }
    }
    if (selected) drawCircle(Accent, s * 2f, p, style = Stroke(1.5f * density))
}

private fun DrawScope.drawCrosshair(density: Float) {
    val c = center
    val a = 6f * density
    val b = 16f * density
    for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
        drawLine(Paper, c + Offset(dx * a, dy * a), c + Offset(dx * b, dy * b), 4f * density)
        drawLine(Ink, c + Offset(dx * a, dy * a), c + Offset(dx * b, dy * b), 1.5f * density)
    }
}

private val NICE = doubleArrayOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1e3, 2e3, 5e3, 1e4, 2e4, 5e4, 1e5, 2e5, 5e5, 1e6, 2e6)

private fun DrawScope.drawScaleBar(vp: MapViewport, h: Float, tm: TextMeasurer, density: Float) {
    val mpp = vp.metersPerPixel()
    if (!mpp.isFinite() || mpp <= 0) return
    val maxPx = 110f * density
    val meters = NICE.lastOrNull { it / mpp <= maxPx } ?: return
    val px = (meters / mpp).toFloat()
    val x = 12f * density
    val y = h - 14f * density
    drawRect(Paper.copy(alpha = 0.85f), Offset(x - 4 * density, y - 20 * density), Size(px + 8 * density, 26 * density))
    drawLine(Ink, Offset(x, y), Offset(x + px, y), 2f * density)
    drawLine(Ink, Offset(x, y - 5 * density), Offset(x, y), 2f * density)
    drawLine(Ink, Offset(x + px, y - 5 * density), Offset(x + px, y), 2f * density)
    drawText(tm, fmtDistance(meters), Offset(x, y - 19 * density), TextStyle(fontSize = 10.sp, color = Ink, fontFamily = Mono))
}
