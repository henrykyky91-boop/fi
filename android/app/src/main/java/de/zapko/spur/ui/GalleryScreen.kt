package de.zapko.spur.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.zapko.spur.capture.DetailList
import de.zapko.spur.data.DeleteMode
import de.zapko.spur.data.MediaEntity
import de.zapko.spur.data.Sync
import de.zapko.spur.data.isVideo
import de.zapko.spur.data.hasLocation
import de.zapko.spur.data.cityName
import de.zapko.spur.repo
import de.zapko.spur.util.Fmt
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class GFilter(val label: String) {
    ALL("Alle"), FAV("Favoriten"), VIDEO("Videos"), SPUR("Spur"), SNAP("Snapchat"),
    NOPLACE("Ohne Ort"), LOCAL("Nicht gesichert"), PHONE("Auf dem Handy")
}

private val searchDate = DateTimeFormatter.ofPattern("d. MMMM yyyy EEEE MMMM yyyy", Locale.GERMANY)

fun searchText(m: MediaEntity): String = buildString {
    append(m.address ?: ""); append(' '); append(m.cityName ?: ""); append(' '); append(m.country ?: ""); append(' ')
    append(m.note); append(' '); append(m.tags); append(' '); append(m.source); append(' ')
    append(searchDate.format(java.time.Instant.ofEpochMilli(m.takenAt).atZone(java.time.ZoneId.systemDefault())))
}.lowercase(Locale.GERMANY)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val all by repo.items.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(GFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val list = remember(all, filter, query) {
        val q = query.trim().lowercase(Locale.GERMANY)
        all.filter { m ->
            when (filter) {
                GFilter.ALL -> true
                GFilter.FAV -> m.favorite
                GFilter.VIDEO -> m.isVideo
                GFilter.SPUR -> m.source == "spur"
                GFilter.SNAP -> m.source == "snapchat"
                GFilter.NOPLACE -> !m.hasLocation
                GFilter.LOCAL -> !m.onServer
                GFilter.PHONE -> m.localPath != null
            } && (q.isEmpty() || q.split(' ').filter { it.isNotBlank() }.all { searchText(m).contains(it) })
        }
    }
    val sections = remember(list) { list.groupBy { Fmt.month(it.takenAt) } }
    val selecting = selected.isNotEmpty()

    Column(Modifier.fillMaxSize().background(C.bg)) {
        // Kopfzeile
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                RoundIcon(Icons.Outlined.Close, { selected = emptySet() }, bg = C.s2, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Text("${selected.size} ausgewählt", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                RoundIcon(Icons.Outlined.FavoriteBorder, {
                    scope.launch {
                        val target = !all.filter { it.id in selected }.all { it.favorite }
                        selected.forEach { repo.setFavorite(it, target) }
                    }
                }, bg = C.s2, size = 40.dp, desc = "Favorit")
                Spacer(Modifier.width(8.dp))
                RoundIcon(Icons.Outlined.Delete, { confirmDelete = true }, bg = C.s2, size = 40.dp, tint = C.danger, desc = "Löschen")
            } else {
                Column(Modifier.weight(1f)) {
                    Text("Galerie", style = MaterialTheme.typography.headlineSmall)
                    Text("${Fmt.count(list.size)} von ${Fmt.count(all.size)}", color = C.t2, style = MaterialTheme.typography.bodySmall)
                }
                RoundIcon(Icons.Outlined.Search, { searching = !searching; if (!searching) query = "" }, bg = C.s2, size = 40.dp)
                Spacer(Modifier.width(8.dp))
                RoundIcon(Icons.Outlined.Settings, onSettings, bg = C.s2, size = 40.dp, desc = "Einstellungen")
            }
        }
        if (searching && !selecting) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(C.s2).padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = C.t2, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Ort, Straße, Monat, Notiz, #tag …", color = C.t3, style = MaterialTheme.typography.bodyLarge)
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = TextStyle(color = C.t1, fontSize = 16.sp), cursorBrush = SolidColor(C.acc),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GFilter.entries.forEach { f -> Chip(f.label, filter == f, { filter = f }) }
        }

        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (all.isEmpty()) "Noch keine Aufnahmen.\nMach dein erstes Foto." else "Nichts gefunden.",
                    color = C.t2, style = MaterialTheme.typography.bodyLarge)
            }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            sections.forEach { (month, group) ->
                item(key = "h-$month", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                        Text(month, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("${group.size}", color = C.t2, style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(group, key = { it.id }) { m ->
                    val isSel = m.id in selected
                    Box(
                        Modifier.aspectRatio(1f).combinedClickable(
                            onClick = {
                                if (selecting) selected = if (isSel) selected - m.id else selected + m.id
                                else { DetailList.ids = list.map { it.id }; onOpen(m.id) }
                            },
                            onLongClick = { selected = if (isSel) selected - m.id else selected + m.id },
                        ),
                    ) {
                        Thumb(repo.thumbModel(m), Modifier.fillMaxSize())
                        if (isSel) Box(Modifier.fillMaxSize().background(Color(0x66000000)).border(3.dp, C.acc))
                        if (m.isVideo) Row(
                            Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(4.dp))
                                .background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                            if (m.durationMs > 0) Text(Fmt.duration(m.durationMs), color = Color.White, fontSize = 10.sp)
                        }
                        if (m.favorite) Icon(Icons.Filled.Favorite, null, tint = Color.White,
                            modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).size(14.dp))
                        if (m.source == "snapchat") Box(Modifier.align(Alignment.TopStart).padding(5.dp).size(7.dp)
                            .clip(CircleShape).background(C.snap))
                        else SyncDot(m.sync, m.onServer, Modifier.align(Alignment.TopStart).padding(5.dp))
                        if (isSel) Icon(Icons.Filled.CheckCircle, null, tint = C.acc,
                            modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp))
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmDelete) {
        val sel = all.filter { it.id in selected }
        val anyOnServer = sel.any { it.onServer }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = C.s1,
            title = { Text("${sel.size} löschen?") },
            text = {
                Text(
                    if (anyOnServer) "„Nur vom Handy“ gibt Speicher frei – auf dem Server bleibt alles. „Überall“ löscht auch auf dem Server (30 Tage Papierkorb)."
                    else "Diese Aufnahmen sind nur auf dem Handy und werden endgültig gelöscht.",
                    color = C.t2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selected.toList(); confirmDelete = false; selected = emptySet()
                    scope.launch { repo.deleteMany(ids, DeleteMode.EVERYWHERE) }
                }) { Text("Überall löschen", color = C.danger) }
            },
            dismissButton = {
                if (anyOnServer) TextButton(onClick = {
                    val ids = selected.toList(); confirmDelete = false; selected = emptySet()
                    scope.launch { repo.deleteMany(ids, DeleteMode.PHONE) }
                }) { Text("Nur vom Handy", color = C.t1) }
                else TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen", color = C.t1) }
            },
        )
    }
}

fun isPending(m: MediaEntity) = m.sync == Sync.PENDING || m.sync == Sync.ERROR
