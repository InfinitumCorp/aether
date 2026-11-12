package com.infinitum.aether

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.aether_core.TrackPoint

/**
 * ÆTHER Maps™ track recording. A foreground service of type `location` that takes GPS fixes
 * (GPS provider only: no network location, no Play services) about every 2 s / 5 m and appends
 * them to the track being recorded in [com.infinitum.aether.data.MapsStore].
 *
 * Start / resume: [start]. Pause keeps the track open (resume adds a new segment): [pause].
 * Stop closes the track and stores its simplified form: [stop].
 */
class TrackService : Service() {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lm: LocationManager? = null
    private val listener = LocationListener { l -> onFix(l) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_PAUSE -> end(closeTrack = false)
            ACTION_STOP -> end(closeTrack = true)
            else -> end(closeTrack = false)
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun begin(intent: Intent) {
        // startForegroundService() requires startForeground() promptly, whatever happens next.
        val n = notification(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(ID, n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot start foreground", e)
            stopSelf()
            return
        }
        if (thread != null) return // already recording
        if (!hasFineLocation(this)) {
            Log.w(TAG, "no location permission")
            end(closeTrack = false)
            return
        }
        val maps = (application as AetherApp).features.maps
        val name = intent.getStringExtra(EXTRA_NAME) ?: "Track"
        val resumeId = intent.getStringExtra(EXTRA_RESUME)
        val t = HandlerThread("aether-track").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        h.post { runCatching { maps.startTrack(name, resumeId) }.onFailure { Log.w(TAG, "startTrack failed", it) } }
        val m = getSystemService(LocationManager::class.java)
        lm = m
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, MIN_DISTANCE_M, listener, t.looper)
            _running.value = true
        } catch (e: Exception) {
            // SecurityException (permission revoked) or IllegalArgumentException (no GPS hardware).
            Log.w(TAG, "GPS unavailable", e)
            end(closeTrack = false)
        }
    }

    private fun onFix(l: Location) {
        if (l.hasAccuracy() && l.accuracy > MAX_ACCURACY_M) return
        val p = TrackPoint(
            l.latitude,
            l.longitude,
            if (l.hasAltitude()) l.altitude else null,
            l.time,
        )
        runCatching { (application as AetherApp).features.maps.addTrackPoint(p) }
            .onFailure { Log.w(TAG, "addTrackPoint failed", it) }
    }

    private fun end(closeTrack: Boolean) {
        lm?.removeUpdates(listener)
        lm = null
        _running.value = false
        val maps = (application as AetherApp).features.maps
        val h = handler
        val t = thread
        if (closeTrack) {
            val close = Runnable { runCatching { maps.stopTrack() }.onFailure { Log.w(TAG, "stopTrack failed", it) } }
            if (h != null) h.post(close) else Thread(close, "aether-track-stop").start()
        }
        t?.quitSafely()
        thread = null
        handler = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        lm?.removeUpdates(listener)
        lm = null
        thread?.quitSafely()
        thread = null
        handler = null
        _running.value = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TrackService"
        private const val ID = 7338
        private const val CHANNEL = "aether.track"
        private const val ACTION_START = "com.infinitum.aether.track.START"
        private const val ACTION_PAUSE = "com.infinitum.aether.track.PAUSE"
        private const val ACTION_STOP = "com.infinitum.aether.track.STOP"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_RESUME = "resume"
        private const val INTERVAL_MS = 2_000L
        private const val MIN_DISTANCE_M = 5f
        private const val MAX_ACCURACY_M = 50f

        private val _running = MutableStateFlow(false)
        /** True while GPS updates are being recorded (false when paused or stopped). */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun hasFineLocation(context: Context) =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Starts a new track named [name], or resumes [resumeId] with a new segment. */
        fun start(context: Context, name: String, resumeId: String? = null) {
            val i = Intent(context, TrackService::class.java).setAction(ACTION_START).putExtra(EXTRA_NAME, name)
            if (resumeId != null) i.putExtra(EXTRA_RESUME, resumeId)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
            }.onFailure { Log.w(TAG, "start failed", it) }
        }

        /** Stops taking fixes but keeps the track open for [start] with its id. */
        fun pause(context: Context) {
            if (!_running.value) return
            runCatching { context.startService(Intent(context, TrackService::class.java).setAction(ACTION_PAUSE)) }
        }

        /** Stops recording and closes the track. Works while paused too. */
        fun stop(context: Context) {
            if (_running.value) {
                runCatching { context.startService(Intent(context, TrackService::class.java).setAction(ACTION_STOP)) }
            } else {
                val app = context.applicationContext as AetherApp
                Thread({ runCatching { app.features.maps.stopTrack() } }, "aether-track-stop").start()
            }
        }

        private fun notification(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "ÆTHER Maps track recording", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shown while a GPS track is being recorded."
                        setShowBadge(false)
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val stop = PendingIntent.getService(
                context, 1,
                Intent(context, TrackService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(context)
            return b.setSmallIcon(R.drawable.ic_aether)
                .setContentTitle("Recording track")
                .setContentText("ÆTHER Maps is recording GPS fixes.")
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_aether), "Stop", stop).build())
                .build()
        }
    }
}
