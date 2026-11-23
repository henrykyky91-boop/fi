package de.zapko.spur.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.zapko.spur.capture.DetailList
import de.zapko.spur.data.DeleteMode
import de.zapko.spur.data.MediaEntity
import de.zapko.spur.data.isVideo
import de.zapko.spur.data.hasLocation
import de.zapko.spur.data.cityName
import de.zapko.spur.repo
import de.zapko.spur.util.Fmt
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.io.File
import kotlin.math.roundToInt

@Composable
fun DetailScreen(startId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val all by repo.items.collectAsStateWithLifecycle()
    val byId = remember(all) { all.associateBy { it.id } }
    val ids = remember(all) {
        val base = DetailList.ids.takeIf { startId in it } ?: listOf(startId)
        base.filter { it in byId }
    }
    if (ids.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val startIndex = remember { ids.indexOf(startId).coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startIndex) { ids.size }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editNote by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val current = byId[ids.getOrNull(pager.currentPage.coerceIn(0, ids.size - 1))]

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager, modifier = Modifier.fillMaxSize(), userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1, key = { ids[it] },
        ) { page ->
            val m = byId[ids[page]] ?: return@HorizontalPager
            if (m.isVideo) VideoPage(m, active = pager.currentPage == page, onToggleChrome = { chrome = !chrome })
            else ZoomableImage(repo.fullModel(m), repo.thumbModel(m), onZoom = { zoomed = it }, onTap = { chrome = !chrome })
        }

        if (current != null) {
            // Kopfzeile
            AnimatedVisibility(chrome, enter = slideInVertically { -it }, exit = slideOutVertically { -it }) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundIcon(Icons.AutoMirrored.Outlined.ArrowBack, onBack, bg = Color.Transparent, desc = "Zurück")
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        val z = Fmt.zone(current.tzOffset, current.source)
                        Text(current.cityName ?: Fmt.shortDay(current.takenAt, z), style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
                        Text("${Fmt.shortDay(current.takenAt, z)} · ${Fmt.shortTime(current.takenAt, z)}",
                            style = MaterialTheme.typography.bodySmall, color = Color(0xB3FFFFFF))
                    }
                    RoundIcon(if (current.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        { scope.launch { repo.setFavorite(current.id, !current.favorite) } },
                        bg = Color.Transparent, tint = if (current.favorite) C.acc else Color.White, desc = "Favorit")
                    RoundIcon(Icons.Outlined.Share, { share(ctx, current) }, bg = Color.Transparent, desc = "Teilen")
                    RoundIcon(Icons.Outlined.Info, { showInfo = !showInfo }, bg = Color.Transparent,
                        tint = if (showInfo) C.acc else Color.White, desc = "Details")
                    RoundIcon(Icons.Outlined.Delete, { confirmDelete = true }, bg = Color.Transparent, desc = "Löschen")
                }
            }

            // Detail-Panel
            AnimatedVisibility(
                showInfo, enter = slideInVertically { it }, exit = slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                InfoPanel(current, onEdit = { editNote = true }, onClose = { showInfo = false })
            }
        }
    }

    if (confirmDelete && current != null) {
        val canPhoneOnly = current.onServer && current.localPath != null
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = C.s1,
            title = { Text("Löschen?") },
            text = {
                Text(
                    when {
                        canPhoneOnly -> "„Nur vom Handy“ gibt Speicher frei, auf dem Server bleibt es. „Überall“ löscht auch auf dem Server (30 Tage Papierkorb)."
                        current.onServer -> "Wird auf dem Server gelöscht (30 Tage Papierkorb)."
                        else -> "Diese Aufnahme liegt nur auf dem Handy und ist danach weg."
                    }, color = C.t2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { repo.delete(current.id, DeleteMode.EVERYWHERE) }
                }) { Text("Überall löschen", color = C.danger) }
            },
            dismissButton = {
                if (canPhoneOnly) TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { repo.delete(current.id, DeleteMode.PHONE) }
                }) { Text("Nur vom Handy", color = C.t1) }
                else TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen", color = C.t1) }
            },
        )
    }

    if (editNote && current != null) {
        var note by remember(current.id) { mutableStateOf(current.note) }
        var tags by remember(current.id) { mutableStateOf(current.tags) }
        val colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.acc, unfocusedBorderColor = C.line, cursorColor = C.acc,
            focusedLabelColor = C.acc, unfocusedLabelColor = C.t2,
        )
        AlertDialog(
            onDismissRequest = { editNote = false },
            containerColor = C.s1,
            title = { Text("Notiz & Tags") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(note, { note = it }, label = { Text("Notiz") }, minLines = 2, colors = colors,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(tags, { tags = it }, label = { Text("Tags (mit Komma)") }, singleLine = true, colors = colors,
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editNote = false
                    scope.launch { repo.setNote(current.id, note, tags) }
                }) { Text("Speichern", color = C.acc) }
            },
            dismissButton = { TextButton(onClick = { editNote = false }) { Text("Abbrechen", color = C.t1) } },
        )
    }
}

@Composable
private fun ZoomableImage(model: Any?, placeholder: Any?, onZoom: (Boolean) -> Unit, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                        onZoom(scale > 1f)
                    },
                )
            }
            .pointerInput(Unit) {
                // Zwei Finger = zoomen; mit einem Finger nur verschieben, wenn schon gezoomt (sonst wischt der Pager)
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.count { it.pressed } > 1
                        if (multi || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale > 1f) offset + event.calculatePan() else Offset.Zero
                            onZoom(scale > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Thumb(placeholder, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Thumb(
            model,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            },
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun VideoPage(m: MediaEntity, active: Boolean, onToggleChrome: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    var playing by remember(m.id) { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) playing = false }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!playing) {
            Thumb(repo.thumbModel(m), Modifier.fillMaxSize().clickable { onToggleChrome() }, contentScale = ContentScale.Fit)
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(Color(0x99000000)).clickable { playing = true },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, "Abspielen", tint = Color.White, modifier = Modifier.size(40.dp)) }
        } else {
            val local = m.localPath?.let { File(it) }?.takeIf { it.exists() }
            val token = repo.prefs.current.token
            AndroidView(
                factory = { c ->
                    VideoView(c).apply {
                        val mc = MediaController(c)
                        mc.setAnchorView(this)
                        setMediaController(mc)
                        if (local != null) setVideoURI(Uri.fromFile(local))
                        else setVideoURI(Uri.parse(repo.api.fileUrl(m.id)), mapOf("Authorization" to "Bearer $token"))
                        setOnPreparedListener { mp -> mp.isLooping = true; start() }
                        setOnErrorListener { _, _, _ ->
                            Toast.makeText(c, "Video kann nicht geladen werden", Toast.LENGTH_SHORT).show(); true
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.stopPlayback() },
            )
        }
    }
}

@Composable
private fun InfoPanel(m: MediaEntity, onEdit: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val z = Fmt.zone(m.tzOffset, m.source)
    Column(
        Modifier.fillMaxWidth().fillMaxHeight(0.62f)
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(C.s1)
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().clickable { onClose() }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.s3))
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            Text(m.cityName ?: if (m.hasLocation) "Unbekannter Ort" else "Ohne Standort", style = MaterialTheme.typography.headlineSmall)
            Text(Fmt.day(m.takenAt, z), color = C.t2, style = MaterialTheme.typography.bodyMedium)

            if (m.hasLocation) {
                Spacer(Modifier.height(14.dp))
                MiniMap(m.lat!!, m.lon!!, Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp))) {
                    val label = Uri.encode(m.cityName ?: "Aufnahme")
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${m.lat},${m.lon}?q=${m.lat},${m.lon}($label)")))
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            InfoRow("Uhrzeit", Fmt.time(m.takenAt, z) + "  " + (if (m.source == "spur") Fmt.tz(m.tzOffset) else "(Zeitzone Handy)"), mono = true)
            InfoRow("Adresse", m.address)
            InfoRow("Land", m.country)
            if (m.hasLocation) InfoRow("Koordinaten", Fmt.coord(m.lat!!, m.lon!!), mono = true) {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Koordinaten", Fmt.coord(m.lat!!, m.lon!!)))
                Toast.makeText(ctx, "Koordinaten kopiert", Toast.LENGTH_SHORT).show()
            }
            InfoRow("Genauigkeit", m.accuracy?.let { "± ${it.roundToInt()} m" }, mono = true)
            InfoRow("Höhe", m.altitude?.let { "${it.roundToInt()} m ü. NN (GPS)" }, mono = true)
            InfoRow("Blickrichtung", m.heading?.let { "${it.roundToInt()}° ${Fmt.compass(it)}" }, mono = true)
            InfoRow("Tempo", m.speed?.takeIf { it > 0.5f }?.let { "${(it * 3.6f).roundToInt()} km/h" }, mono = true)
            if (m.locationLate) InfoRow("Hinweis", "Standort kurz nach der Aufnahme ermittelt")
            InfoRow("Typ", if (m.isVideo) "Video · ${Fmt.duration(m.durationMs)}" else "Foto")
            InfoRow("Auflösung", if (m.width > 0) "${m.width} × ${m.height}  (${"%.1f".format(m.width * m.height / 1e6)} MP)" else null, mono = true)
            InfoRow("Größe", if (m.size > 0) Fmt.bytes(m.size) else null, mono = true)
            InfoRow("Gerät", m.device)
            InfoRow("Kamera", m.camera)
            InfoRow("Quelle", if (m.source == "snapchat") "Snapchat-Import" else "Spur")
            InfoRow("Speicherort", syncLabel(m.sync, m.onServer, m.localPath != null))
            m.error?.let { InfoRow("Fehler", it) }

            SectionTitle("Notiz & Tags")
            Text(
                listOf(m.note, m.tags.takeIf { it.isNotBlank() }?.split(", ")?.joinToString(" ") { "#$it" } ?: "")
                    .filter { it.isNotBlank() }.joinToString("\n").ifBlank { "Tippen zum Hinzufügen" },
                color = if (m.note.isBlank() && m.tags.isBlank()) C.t3 else C.t1,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.s2).clickable { onEdit() }.padding(14.dp),
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (m.localPath == null && m.onServer) {
                    GhostButton(if (busy) "Lädt …" else "Aufs Handy laden", {
                        if (!busy) { busy = true; scope.launch {
                            val ok = repo.downloadToPhone(m.id); busy = false
                            Toast.makeText(ctx, if (ok) "Auf dem Handy gespeichert" else "Download fehlgeschlagen", Toast.LENGTH_SHORT).show()
                        } }
                    })
                }
                if (m.hasLocation) GhostButton("In Maps öffnen", {
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${m.lat},${m.lon}")))
                    }
                })
            }
        }
    }
}

@Composable
private fun MiniMap(lat: Double, lon: Double, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val style = repo.prefs.current.mapStyle
    val density = ctx.resources.displayMetrics.density
    val map = remember {
        MapView(ctx).apply {
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isClickable = false
            applyStyle(style)
            overlayManager.tilesOverlay.loadingBackgroundColor = 0xFF0A0A0A.toInt()
            overlays.add(ClusterOverlay(density) {})
        }
    }
    LaunchedEffect(lat, lon) {
        (map.overlays.firstOrNull { it is ClusterOverlay } as? ClusterOverlay)?.items =
            listOf(MediaEntity(id = "x", takenAt = 0, lat = lat, lon = lon))
        map.controller.setZoom(15.5)
        map.controller.setCenter(GeoPoint(lat, lon))
        map.invalidate()
    }
    DisposableEffect(Unit) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    Box(modifier) {
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
        // Klicks abfangen (Karte soll nur zeigen, nicht verschoben werden)
        Box(Modifier.fillMaxSize().clickable(onClick = onClick))
    }
}

private fun share(ctx: Context, m: MediaEntity) {
    val repo = ctx.repo
    repo.scope.launch {
        val f = repo.fileForShare(m.id)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (f == null) {
                Toast.makeText(ctx, "Datei nicht verfügbar", Toast.LENGTH_SHORT).show(); return@withContext
            }
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = m.mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, "Teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
