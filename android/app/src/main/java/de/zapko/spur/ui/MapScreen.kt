package de.zapko.spur.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import android.location.Location
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.zapko.spur.capture.DetailList
import de.zapko.spur.data.MapStyle
import de.zapko.spur.data.MediaEntity
import de.zapko.spur.data.hasLocation
import de.zapko.spur.data.cityName
import de.zapko.spur.repo
import de.zapko.spur.util.Fmt
import de.zapko.spur.util.Geo
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class Range(val label: String) { ALL("Alles"), TODAY("Heute"), WEEK("7 Tage"), MONTH("30 Tage"), YEAR("Jahr") }

fun tileSource(style: MapStyle): ITileSource = when (style) {
    MapStyle.SATELLITE -> object : OnlineTileSourceBase("EsriWorldImagery", 0, 19, 256, "",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + "/" +
                MapTileIndex.getX(pMapTileIndex)
    }
    // OpenStreetMap – kein Schluessel noetig; Dunkel/Hell entstehen per Farbfilter
    else -> TileSourceFactory.MAPNIK
}

/** Dunkel: invertiert + entsaettigt. Hell: schlicht grau. Standard/Satellit: Originalfarben. */
fun tileFilter(style: MapStyle): ColorFilter? = when (style) {
    MapStyle.DARK -> {
        val m = ColorMatrix().apply { setSaturation(0.15f) }
        m.postConcat(ColorMatrix(floatArrayOf(
            -0.88f, 0f, 0f, 0f, 232f,
            0f, -0.88f, 0f, 0f, 232f,
            0f, 0f, -0.88f, 0f, 236f,
            0f, 0f, 0f, 1f, 0f,
        )))
        ColorMatrixColorFilter(m)
    }
    MapStyle.LIGHT -> ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.05f) })
    else -> null
}

fun MapView.applyStyle(style: MapStyle) {
    setTileSource(tileSource(style))
    overlayManager.tilesOverlay.setColorFilter(tileFilter(style))
}

fun filterRange(items: List<MediaEntity>, r: Range): List<MediaEntity> {
    if (r == Range.ALL) return items
    val now = System.currentTimeMillis()
    val from = when (r) {
        Range.TODAY -> LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        Range.WEEK -> now - 7L * 86_400_000
        Range.MONTH -> now - 30L * 86_400_000
        Range.YEAR -> now - 365L * 86_400_000
        Range.ALL -> 0L
    }
    return items.filter { it.takenAt >= from }
}

/** Schnelle Gruppierung tausender Punkte: Raster in Weltpixeln je Zoomstufe. */
class ClusterOverlay(private val density: Float, var onTap: (List<MediaEntity>) -> Unit) : Overlay() {
    class Cluster(val lat: Double, val lon: Double, val items: List<MediaEntity>, val snap: Boolean)

    var items: List<MediaEntity> = emptyList()
        set(v) { field = v; cachedZoom = -1 }
    var me: Location? = null

    private var cachedZoom = -1
    private var clusters: List<Cluster> = emptyList()
    private val pt = Point()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density; color = 0xFF000000.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); textAlign = Paint.Align.CENTER; textSize = 11f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val meFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8FF47.toInt() }
    private val meHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33E8FF47 }

    private fun build(z: Int) {
        val cell = 58.0 * density
        val map = HashMap<Long, MutableList<MediaEntity>>()
        for (m in items) {
            val lat = m.lat ?: continue
            val lon = m.lon ?: continue
            val cx = (Geo.worldX(lon, z) / cell).toLong()
            val cy = (Geo.worldY(lat, z) / cell).toLong()
            map.getOrPut((cx shl 32) xor cy) { ArrayList() }.add(m)
        }
        clusters = map.values.map { list ->
            Cluster(list.sumOf { it.lat!! } / list.size, list.sumOf { it.lon!! } / list.size, list,
                list.count { it.source == "snapchat" } * 2 > list.size)
        }
        cachedZoom = z
    }

    private fun radius(n: Int) = (if (n == 1) 7f else min(26f, 11f + ln(n.toFloat()) * 3.2f)) * density

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val z = mapView.zoomLevelDouble.toInt()
        if (z != cachedZoom) build(z)
        val pj = mapView.projection
        val w = mapView.width
        val h = mapView.height
        for (cl in clusters) {
            pj.toPixels(GeoPoint(cl.lat, cl.lon), pt)
            if (pt.x < -60 || pt.y < -60 || pt.x > w + 60 || pt.y > h + 60) continue
            val r = radius(cl.items.size)
            fill.color = if (cl.snap) 0xFFFFFC00.toInt() else 0xFFF2F2F2.toInt()
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, fill)
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, stroke)
            if (cl.items.size > 1) {
                val label = if (cl.items.size >= 1000) "${cl.items.size / 1000}k" else cl.items.size.toString()
                c.drawText(label, pt.x.toFloat(), pt.y + text.textSize / 3f, text)
            }
        }
        me?.let { l ->
            pj.toPixels(GeoPoint(l.latitude, l.longitude), pt)
            val acc = if (l.hasAccuracy()) pj.metersToPixels(l.accuracy) else 0f
            if (acc > 8 * density) c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), acc, meHalo)
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 7f * density, meFill)
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 7f * density, stroke)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val pj = mapView.projection
        var best: Cluster? = null
        var bestD = Float.MAX_VALUE
        for (cl in clusters) {
            pj.toPixels(GeoPoint(cl.lat, cl.lon), pt)
            val dx = pt.x - e.x
            val dy = pt.y - e.y
            val d = sqrt(dx * dx + dy * dy)
            if (d < max(radius(cl.items.size), 22f * density) && d < bestD) { best = cl; bestD = d }
        }
        best?.let { onTap(it.items); return true }
        return false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val settings by repo.prefs.flow.collectAsStateWithLifecycle()
    val all by repo.items.collectAsStateWithLifecycle()
    val me by repo.tracker.location.collectAsStateWithLifecycle()
    var range by rememberSaveable { mutableStateOf(Range.ALL) }
    var sheet by remember { mutableStateOf<List<MediaEntity>?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = ctx.resources.displayMetrics.density

    val visible = remember(all, range, settings.showSnapchat) {
        filterRange(all, range).filter { it.hasLocation && (settings.showSnapchat || it.source != "snapchat") }
    }

    val overlay = remember { ClusterOverlay(density) { sheet = it } }
    val route = remember {
        Polyline().apply {
            outlinePaint.color = 0xFFE8FF47.toInt()
            outlinePaint.strokeWidth = 4f * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            isEnabled = false
        }
    }
    val mapView = remember {
        MapView(ctx).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 2.5
            maxZoomLevel = 20.0
            isTilesScaledToDpi = true
            isVerticalMapRepetitionEnabled = false
            setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
            overlayManager.tilesOverlay.loadingBackgroundColor = 0xFF0A0A0A.toInt()
            overlayManager.tilesOverlay.loadingLineColor = 0xFF0A0A0A.toInt()
            overlays.add(route)
            overlays.add(overlay)
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(51.94, 8.87))
        }
    }

    DisposableEffect(lifecycleOwner) {
        repo.tracker.acquire()
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            repo.tracker.release()
            mapView.onPause()
            mapView.onDetach()
        }
    }

    LaunchedEffect(settings.mapStyle) {
        mapView.applyStyle(settings.mapStyle)
        mapView.invalidate()
    }

    var fitted by rememberSaveable { mutableStateOf(false) }
    fun fit(list: List<MediaEntity>) {
        if (list.isEmpty()) return
        if (list.size == 1) {
            mapView.controller.animateTo(GeoPoint(list[0].lat!!, list[0].lon!!), 16.0, 600L)
            return
        }
        val box = BoundingBox(list.maxOf { it.lat!! }, list.maxOf { it.lon!! }, list.minOf { it.lat!! }, list.minOf { it.lon!! })
        if (mapView.width > 0) mapView.zoomToBoundingBox(box.increaseByScale(1.25f), true, (48 * density).toInt(), 17.0, 600L)
        else mapView.addOnFirstLayoutListener { _, _, _, _, _ -> mapView.zoomToBoundingBox(box.increaseByScale(1.25f), false, (48 * density).toInt(), 17.0, 0L) }
    }

    LaunchedEffect(visible) {
        overlay.items = visible
        if (range == Range.TODAY && visible.size >= 2) {
            route.setPoints(visible.sortedBy { it.takenAt }.map { GeoPoint(it.lat!!, it.lon!!) })
            route.isEnabled = true
        } else route.isEnabled = false
        mapView.invalidate()
        if (!fitted && visible.isNotEmpty()) { fitted = true; fit(visible) }
    }
    LaunchedEffect(me) { overlay.me = me; mapView.invalidate() }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp)) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Range.entries.forEach { r ->
                    Chip(r.label, range == r, { range = r; fitted = false })
                }
                Chip("Snapchat", settings.showSnapchat, {
                    scope.launch { repo.prefs.update { it.copy(showSnapchat = !it.showSnapchat) } }
                })
            }
            Text(
                "${Fmt.count(visible.size)} Aufnahmen mit Standort" + if (route.isEnabled) " · Tagesroute" else "",
                color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 14.dp, top = 8.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color(0x88000000)).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundIcon(Icons.Outlined.Layers, {
                val next = MapStyle.entries[(settings.mapStyle.ordinal + 1) % MapStyle.entries.size]
                scope.launch { repo.prefs.update { it.copy(mapStyle = next) } }
            }, bg = Color(0xCC111111), desc = "Kartenstil")
            RoundIcon(Icons.Outlined.ZoomOutMap, { fit(visible) }, bg = Color(0xCC111111), desc = "Alles zeigen")
            RoundIcon(Icons.Outlined.MyLocation, {
                me?.let { mapView.controller.animateTo(GeoPoint(it.latitude, it.longitude), 17.0, 600L) }
            }, bg = Color(0xCC111111), tint = C.acc, desc = "Mein Standort")
        }
        Text(
            if (settings.mapStyle == MapStyle.SATELLITE) "© Esri" else "© OpenStreetMap-Mitwirkende",
            color = Color(0x99FFFFFF), style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        )
    }

    val list = sheet
    if (list != null) {
        if (list.size == 1) {
            LaunchedEffect(list) { DetailList.ids = listOf(list[0].id); sheet = null; onOpen(list[0].id) }
        } else {
            val sorted = remember(list) { list.sortedByDescending { it.takenAt } }
            ModalBottomSheet(
                onDismissRequest = { sheet = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
                containerColor = C.s1,
            ) {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    val place = sorted.firstNotNullOfOrNull { it.cityName } ?: "Dieser Ort"
                    Text(place, style = MaterialTheme.typography.titleLarge, color = C.t1)
                    Text(
                        "${sorted.size} Aufnahmen · ${Fmt.shortDay(sorted.last().takenAt)} – ${Fmt.shortDay(sorted.first().takenAt)}",
                        color = C.t2, style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.width(1.dp))
                    LazyVerticalGrid(
                        GridCells.Fixed(4), Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(top = 12.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(sorted, key = { it.id }) { m ->
                            Thumb(repo.thumbModel(m), Modifier.aspectRatio(1f).clip(RoundedCornerShape(2.dp)).clickable {
                                DetailList.ids = sorted.map { it.id }
                                sheet = null
                                onOpen(m.id)
                            })
                        }
                    }
                }
            }
        }
    }
}
