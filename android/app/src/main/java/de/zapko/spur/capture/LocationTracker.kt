package de.zapko.spur.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Liefert laufend den genauen Standort und die Blickrichtung der Kamera,
 * solange Kamera oder Karte offen sind. Kein Standort im Hintergrund.
 */
class LocationTracker(private val ctx: Context) : SensorEventListener {

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location
    private val _heading = MutableStateFlow<Float?>(null)
    val heading: StateFlow<Float?> = _heading

    /** Wird bei jedem neuen Fix aufgerufen (z. B. um Aufnahmen ohne Standort nachzutragen). */
    var onFix: ((Location) -> Unit)? = null

    private var users = 0
    private var running = false
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(ctx) }
    private val sensors by lazy { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private var declination = 0f
    private var smooth: Float? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { accept(it) }
        }
    }

    fun hasPermission() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun acquire() {
        users++
        if (!running) start()
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0 && running) stop()
    }

    /** Nach dem Erteilen der Berechtigung neu starten. */
    @Synchronized
    fun restart() {
        if (running) stop()
        if (users > 0) start()
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (!hasPermission()) return
        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .setWaitForAccurateLocation(false)
                .build()
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
            fused.lastLocation.addOnSuccessListener { it?.let { l -> accept(l) } }
            running = true
        } catch (_: SecurityException) {
            return
        } catch (_: Exception) {
            return
        }
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stop() {
        runCatching { fused.removeLocationUpdates(callback) }
        runCatching { sensors.unregisterListener(this) }
        running = false
    }

    private fun accept(l: Location) {
        val cur = _location.value
        // Neuere oder deutlich genauere Fixes uebernehmen
        if (cur == null || l.elapsedRealtimeNanos >= cur.elapsedRealtimeNanos ||
            (l.hasAccuracy() && cur.hasAccuracy() && l.accuracy < cur.accuracy * 0.5f)
        ) {
            _location.value = l
            declination = GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), l.time).declination
            onFix?.invoke(l)
        }
    }

    /** Aktueller Fix, wenn er frisch genug ist. */
    fun fresh(maxAgeMs: Long = 120_000): Location? {
        val l = _location.value ?: return null
        val age = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        return if (age <= maxAgeMs) l else null
    }

    private val rot = FloatArray(9)
    private val remap = FloatArray(9)
    private val orient = FloatArray(3)

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // Blickrichtung der Rueckkamera bei aufrecht gehaltenem Handy
        SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remap)
        SensorManager.getOrientation(remap, orient)
        var deg = Math.toDegrees(orient[0].toDouble()).toFloat() + declination
        deg = (deg + 360f) % 360f
        val prev = smooth
        smooth = if (prev == null) deg else {
            var diff = deg - prev
            if (diff > 180) diff -= 360
            if (diff < -180) diff += 360
            (prev + diff * 0.2f + 360f) % 360f
        }
        _heading.value = smooth
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
