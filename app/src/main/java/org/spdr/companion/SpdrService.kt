package org.spdr.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Foreground service that keeps the sensors running with the screen off (spec §3) and feeds
 * the engine. The engine notifies the OsmAnd bridge, so OsmAnd keeps getting positions while the
 * phone sits in its pouch.
 */
class SpdrService : Service(), SensorEventListener {
    private lateinit var sm: SensorManager
    private var thread: HandlerThread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var app: SpdrApp
    private var rawLog = false
    private var lastNotified = 0L
    private var gpsListener: LocationListener? = null

    private val listener: (Snapshot) -> Unit = { s ->
        val now = System.currentTimeMillis()
        if (now - lastNotified > 5000) { lastNotified = now; updateNotification(s) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = application as SpdrApp
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (thread != null) return START_STICKY
        createChannel()
        val n = notification("Starting…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIF_ID, n)

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spdr:sensors").apply { acquire(12 * 3600_000L) }

        rawLog = app.prefs.logRawSensors
        app.engine.applySettings()
        app.engine.startSession(sessionInfo())
        app.bridge.connect()
        app.engine.addListener(listener)

        val t = HandlerThread("spdr-sensors", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        thread = t
        val h = Handler(t.looper)
        for (type in SENSORS) {
            sm.getDefaultSensor(type)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, h) }
        }
        if (app.prefs.logGps) startGpsLogging(h)
        return START_STICKY
    }

    override fun onDestroy() {
        sm.unregisterListener(this)
        gpsListener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        gpsListener = null
        thread?.quitSafely()
        thread = null
        app.engine.removeListener(listener)
        app.engine.stopSession()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onSensorChanged(e: SensorEvent) {
        val t = e.timestamp
        val v = e.values
        val eng = app.engine
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> eng.onAccel(t, v)
            Sensor.TYPE_GAME_ROTATION_VECTOR -> eng.onGameRotation(t, v)
            Sensor.TYPE_ROTATION_VECTOR -> eng.onMagRotation(t, v)
            Sensor.TYPE_MAGNETIC_FIELD -> eng.onMagnetic(t, v)
            Sensor.TYPE_GRAVITY -> eng.onGravity(t, v)
            Sensor.TYPE_PRESSURE -> eng.onPressure(t, v[0])
        }
        if (rawLog) {
            val name = RAW_NAMES[e.sensor.type] ?: return
            val sb = StringBuilder().append(t)
            for (x in v) sb.append(',').append(x)
            eng.rawLog("$name.csv", "timestamp_ns,values...", sb.toString())
        }
    }

    /** A magnetometer reporting UNRELIABLE is ignored by the tilt compass until it recalibrates. */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) app.engine.onMagAccuracy(accuracy)
    }

    /**
     * Records GPS fixes beside the prediction, for measuring the error of a validation walk above
     * ground (Settings › Log GPS). Runs on the sensor thread, so it keeps going with the screen
     * off like everything else. The fixes only ever go to the log, never into the filter.
     *
     * Both the raw GPS provider and the fused one are requested where available and the provider
     * is recorded per row, so nothing is missed and the analysis can pick. `elapsedRealtimeNanos`
     * puts the fixes on the same clock as the sensor timestamps.
     */
    private fun startGpsLogging(h: Handler) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            app.engine.mark("GPS logging wanted but precise location is not granted")
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        val listener = LocationListener { l ->
            app.engine.onGpsFix(
                l.elapsedRealtimeNanos, l.latitude, l.longitude, l.accuracy.toDouble(),
                if (l.hasAltitude()) l.altitude else Double.NaN,
                if (l.hasSpeed()) l.speed.toDouble() else Double.NaN,
                if (l.hasBearing()) l.bearing.toDouble() else Double.NaN,
                l.provider ?: "?",
            )
        }
        gpsListener = listener
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        }
        var any = false
        for (p in providers) {
            runCatching { lm.requestLocationUpdates(p, GPS_INTERVAL_MS, 0f, listener, h.looper) }
                .onSuccess { any = true }
        }
        app.engine.mark(if (any) "GPS logging on" else "GPS logging unavailable")
    }

    private fun sessionInfo(): JSONObject {
        val sensors = JSONArray()
        for (type in SENSORS) sm.getDefaultSensor(type)?.let {
            sensors.put(JSONObject().put("type", it.stringType).put("name", it.name).put("vendor", it.vendor)
                .put("range", it.maximumRange.toDouble()).put("resolution", it.resolution.toDouble())
                .put("min_delay_us", it.minDelay))
        }
        val p = app.prefs
        return JSONObject().put("sensors", sensors).put("mounting", p.mounting.name)
            .put("step_scale", p.stepScale).put("k_prior", JSONArray().put(p.kMean).put(p.kStd))
            .put("raw_sensors", rawLog).put("log_gps", p.logGps)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "sPDR tracking", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, SpdrService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("sPDR running")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun updateNotification(s: Snapshot) {
        val b = s.estimate?.best
        val text = if (b == null) "${s.stepsDetected} steps · no position yet"
        else "${s.stepsDetected} steps · ${(b.weight * 100).toInt()}% · ±${(b.spread + 3).toInt()} m"
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    companion object {
        const val ACTION_STOP = "org.spdr.companion.STOP"
        private const val CHANNEL = "spdr"
        /** One fix a second is plenty to measure a walk and is what GPS delivers anyway. */
        private const val GPS_INTERVAL_MS = 1000L
        private const val NOTIF_ID = 1
        private val SENSORS = listOf(
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_PRESSURE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED, Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_GRAVITY,
        )
        private val RAW_NAMES = mapOf(
            Sensor.TYPE_ACCELEROMETER to "accelerometer",
            Sensor.TYPE_GAME_ROTATION_VECTOR to "game_rotation_vector",
            Sensor.TYPE_ROTATION_VECTOR to "rotation_vector",
            Sensor.TYPE_PRESSURE to "pressure",
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED to "gyroscope_uncalibrated",
            Sensor.TYPE_MAGNETIC_FIELD to "magnetic_field",
            Sensor.TYPE_GRAVITY to "gravity",
        )

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, SpdrService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, SpdrService::class.java).setAction(ACTION_STOP))
    }
}
