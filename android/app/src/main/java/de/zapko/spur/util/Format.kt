package de.zapko.spur.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Fmt {
    private val de = Locale.GERMANY
    private val dayFmt = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", de)
    private val shortDay = DateTimeFormatter.ofPattern("d. MMM yyyy", de)
    private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", de)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss", de)
    private val shortTime = DateTimeFormatter.ofPattern("HH:mm", de)
    private val monthShort = DateTimeFormatter.ofPattern("MMM", de)

    /** Zeitzone der Aufnahme (gespeicherter Offset), sonst Geraetezone. */
    fun zone(tzOffsetMin: Int?, source: String = "spur"): ZoneId =
        if (source == "spur" && tzOffsetMin != null) ZoneOffset.ofTotalSeconds(tzOffsetMin * 60) else ZoneId.systemDefault()

    fun day(ms: Long, z: ZoneId = ZoneId.systemDefault()): String = dayFmt.format(Instant.ofEpochMilli(ms).atZone(z))
    fun shortDay(ms: Long, z: ZoneId = ZoneId.systemDefault()): String = shortDay.format(Instant.ofEpochMilli(ms).atZone(z))
    fun month(ms: Long): String = monthFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
        .replaceFirstChar { it.titlecase(de) }
    fun monthShort(d: LocalDate): String = monthShort.format(d).trimEnd('.')
    fun time(ms: Long, z: ZoneId = ZoneId.systemDefault()): String = timeFmt.format(Instant.ofEpochMilli(ms).atZone(z))
    fun shortTime(ms: Long, z: ZoneId = ZoneId.systemDefault()): String = shortTime.format(Instant.ofEpochMilli(ms).atZone(z))
    fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

    fun tz(offsetMin: Int): String {
        val sign = if (offsetMin < 0) "-" else "+"
        val a = kotlin.math.abs(offsetMin)
        return "UTC$sign%02d:%02d".format(a / 60, a % 60)
    }

    fun bytes(b: Long): String = when {
        b >= 1_000_000_000 -> "%.1f GB".format(de, b / 1e9)
        b >= 1_000_000 -> "%.1f MB".format(de, b / 1e6)
        b >= 1_000 -> "%d KB".format(b / 1000)
        else -> "$b B"
    }

    fun duration(ms: Long): String {
        val s = (ms / 1000).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    fun coord(lat: Double, lon: Double): String = "%.6f, %.6f".format(Locale.US, lat, lon)

    fun compass(deg: Float): String {
        val dirs = arrayOf("N", "NO", "O", "SO", "S", "SW", "W", "NW")
        val d = ((deg % 360) + 360) % 360
        return dirs[((d / 45f).roundToInt()) % 8]
    }

    fun km(m: Double): String = if (m >= 10_000) "%d km".format((m / 1000).roundToInt())
    else if (m >= 1000) "%.1f km".format(de, m / 1000) else "%d m".format(m.roundToInt())

    fun count(n: Int): String = "%,d".format(de, n)
}

object Geo {
    /** Entfernung in Metern. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Web-Mercator Weltpixel bei Zoomstufe z (256er Kacheln). */
    fun worldX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * 256.0 * (1 shl z)
    fun worldY(lat: Double, z: Int): Double {
        val l = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        return (1.0 - kotlin.math.ln(kotlin.math.tan(l) + 1.0 / cos(l)) / Math.PI) / 2.0 * 256.0 * (1 shl z)
    }
}
