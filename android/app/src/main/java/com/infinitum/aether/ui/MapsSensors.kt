package com.infinitum.aether.ui

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.infinitum.aether.TrackService
import uniffi.aether_core.CoordFormat
import uniffi.aether_core.GeoPoint
import uniffi.aether_core.formatCoordinate
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One GPS fix, as the map uses it. */
internal data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val eleM: Double?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val timeMs: Long,
) {
    val point get() = GeoPoint(lat, lon)
}

private fun Location.toFix() = Fix(
    latitude, longitude,
    if (hasAccuracy()) accuracy else null,
    if (hasAltitude()) altitude else null,
    if (hasSpeed()) speed else null,
    if (hasBearing()) bearing else null,
    time,
)

internal fun hasFineLocation(context: Context) = TrackService.hasFineLocation(context)

/** Live GPS fixes from the GPS provider only (no network location, no Play services). */
@SuppressLint("MissingPermission")
@Composable
internal fun rememberGpsFix(enabled: Boolean): State<Fix?> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = remember { mutableStateOf<Fix?>(null) }
    DisposableEffect(enabled) {
        val lm = context.getSystemService(LocationManager::class.java)
        if (!enabled || lm == null || !hasFineLocation(context)) return@DisposableEffect onDispose { }
        val listener = LocationListener { l -> state.value = l.toFix() }
        try {
            if (state.value == null) {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { state.value = it.toFix() }
            }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
        } catch (e: IllegalArgumentException) {
            // No GPS provider on this device.
        }
        onDispose { lm.removeUpdates(listener) }
    }
    return state
}

/**
 * Compass heading in degrees from true north (magnetic heading corrected by the local
 * declination when a fix is known). Uses the rotation-vector sensor, falling back to
 * accelerometer + magnetometer. Null when the device has no usable sensors.
 */
@Composable
internal fun rememberCompass(enabled: Boolean, fix: Fix?): State<Float?> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = remember { mutableStateOf<Float?>(null) }
    val currentFix = rememberUpdatedState(fix)
    DisposableEffect(enabled) {
        val sm = context.getSystemService(SensorManager::class.java)
        if (!enabled || sm == null) return@DisposableEffect onDispose { }
        val rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val r = FloatArray(9)
        val o = FloatArray(3)
        val gravity = FloatArray(3)
        val geomag = FloatArray(3)
        var haveG = false
        var haveM = false
        var sx = 0.0
        var sy = 0.0
        var decl = 0f
        var declAt = 0L
        fun publish() {
            SensorManager.getOrientation(r, o)
            val az = Math.toDegrees(o[0].toDouble())
            // Low-pass on the unit circle so 359° → 1° does not swing through 180°.
            val a = Math.toRadians(az)
            sx = sx * 0.8 + cos(a) * 0.2
            sy = sy * 0.8 + sin(a) * 0.2
            val f = currentFix.value
            val now = System.currentTimeMillis()
            if (f != null && now - declAt > 60_000) {
                decl = GeomagneticField(f.lat.toFloat(), f.lon.toFloat(), (f.eleM ?: 0.0).toFloat(), now).declination
                declAt = now
            }
            val heading = ((Math.toDegrees(atan2(sy, sx)) + decl) % 360.0 + 360.0) % 360.0
            state.value = heading.toFloat()
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(r, e.values)
                        publish()
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        for (i in 0..2) gravity[i] = gravity[i] * 0.8f + e.values[i] * 0.2f
                        haveG = true
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        for (i in 0..2) geomag[i] = e.values[i]
                        haveM = true
                        if (haveG && SensorManager.getRotationMatrix(r, null, gravity, geomag)) publish()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (rot != null) {
            sm.registerListener(listener, rot, SensorManager.SENSOR_DELAY_UI)
        } else if (acc != null && mag != null) {
            sm.registerListener(listener, acc, SensorManager.SENSOR_DELAY_UI)
            sm.registerListener(listener, mag, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            sm.unregisterListener(listener)
        }
    }
    return state
}

// ---- formatting --------------------------------------------------------------------------

internal fun fmtCoord(lat: Double, lon: Double, format: CoordFormat): String =
    runCatching { formatCoordinate(GeoPoint(lat, lon), format) }
        .getOrElse { String.format(Locale.US, "%.6f, %.6f", lat, lon) }

internal fun formatLabel(f: CoordFormat) = when (f) {
    CoordFormat.DECIMAL -> "DEC"
    CoordFormat.DMS -> "DMS"
    CoordFormat.DDM -> "DDM"
    CoordFormat.UTM -> "UTM"
    CoordFormat.MGRS -> "MGRS"
}

internal fun fmtDistance(m: Double): String = when {
    m < 1_000 -> String.format(Locale.US, "%.0f m", m)
    m < 10_000 -> String.format(Locale.US, "%.2f km", m / 1_000)
    else -> String.format(Locale.US, "%.1f km", m / 1_000)
}

internal fun fmtArea(m2: Double): String = when {
    m2 < 10_000 -> String.format(Locale.US, "%.0f m²", m2)
    m2 < 1_000_000 -> String.format(Locale.US, "%.2f ha", m2 / 10_000)
    else -> String.format(Locale.US, "%.2f km²", m2 / 1_000_000)
}

internal fun fmtBearing(deg: Double): String = String.format(Locale.US, "%03.0f°", ((deg % 360) + 360) % 360)

internal fun fmtSpeed(mps: Double): String = String.format(Locale.US, "%.1f km/h", mps * 3.6)

internal fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

internal fun fmtAge(timeMs: Long, now: Long = System.currentTimeMillis()): String {
    val s = ((now - timeMs) / 1000).coerceAtLeast(0)
    return when {
        s < 10 -> "now"
        s < 60 -> "$s s ago"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}
