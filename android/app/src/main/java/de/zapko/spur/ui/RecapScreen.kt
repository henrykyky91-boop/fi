package de.zapko.spur.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.zapko.spur.capture.DetailList
import de.zapko.spur.data.MediaEntity
import de.zapko.spur.net.ServerInfo
import de.zapko.spur.data.isVideo
import de.zapko.spur.data.hasLocation
import de.zapko.spur.data.cityName
import de.zapko.spur.repo
import de.zapko.spur.util.Fmt
import de.zapko.spur.util.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

data class Recap(
    val total: Int = 0, val photos: Int = 0, val videos: Int = 0, val snap: Int = 0,
    val places: Int = 0, val countries: Int = 0, val days: Int = 0, val distanceM: Double = 0.0,
    val longestStreak: Int = 0, val currentStreak: Int = 0, val favorites: Int = 0,
    val months: List<Pair<LocalDate, Int>> = emptyList(),
    val hours: IntArray = IntArray(24),
    val topPlaces: List<Pair<String, Int>> = emptyList(),
    val onThisDay: List<Pair<Int, List<MediaEntity>>> = emptyList(),
    val firstAt: Long? = null,
)

fun buildRecap(all: List<MediaEntity>): Recap {
    if (all.isEmpty()) return Recap()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val dates = all.map { java.time.Instant.ofEpochMilli(it.takenAt).atZone(zone) }
    val daySet = dates.map { it.toLocalDate() }.toSortedSet()

    var longest = 0; var run = 0; var prev: LocalDate? = null
    for (d in daySet) {
        run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
        longest = maxOf(longest, run); prev = d
    }
    var cur = 0
    var d = if (today in daySet) today else today.minusDays(1)
    while (d in daySet) { cur++; d = d.minusDays(1) }

    val geo = all.filter { it.hasLocation }.sortedBy { it.takenAt }
    var dist = 0.0
    for (i in 1 until geo.size) {
        val a = geo[i - 1]; val b = geo[i]
        val step = Geo.distance(a.lat!!, a.lon!!, b.lat!!, b.lon!!)
        if (step < 3_000_000) dist += step // grobe Ausreisser ignorieren
    }

    val startMonth = today.withDayOfMonth(1).minusMonths(11)
    val monthCounts = (0 until 12).map { i -> startMonth.plusMonths(i.toLong()) }.map { m ->
        m to dates.count { it.year == m.year && it.monthValue == m.monthValue }
    }
    val hours = IntArray(24)
    dates.forEach { hours[it.hour]++ }

    val cities = all.mapNotNull { it.cityName }
    val top = cities.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8).map { it.key to it.value }

    val otd = all.zip(dates).filter { (_, z) ->
        z.monthValue == today.monthValue && z.dayOfMonth == today.dayOfMonth && z.year < today.year
    }.groupBy({ today.year - it.second.year }, { it.first }).toSortedMap().map { it.key to it.value }

    return Recap(
        total = all.size, photos = all.count { !it.isVideo }, videos = all.count { it.isVideo },
        snap = all.count { it.source == "snapchat" }, places = cities.toSet().size,
        countries = all.mapNotNull { it.country?.takeIf { c -> c.isNotBlank() } }.toSet().size,
        days = daySet.size, distanceM = dist, longestStreak = longest, currentStreak = cur,
        favorites = all.count { it.favorite }, months = monthCounts, hours = hours, topPlaces = top,
        onThisDay = otd, firstAt = all.minOf { it.takenAt },
    )
}

@Composable
fun RecapScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val all by repo.items.collectAsStateWithLifecycle()
    val pending by repo.pendingCount.collectAsStateWithLifecycle(0)
    val recap by produceState(Recap(), all) { value = withContext(Dispatchers.Default) { buildRecap(all) } }
    val server by produceState<ServerInfo?>(null) {
        value = if (repo.prefs.current.serverConfigured) withContext(Dispatchers.IO) { repo.api.ping() } else null
    }
    val local by produceState(0L, all) { value = withContext(Dispatchers.IO) { repo.localBytes() } }

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Rückblick", style = MaterialTheme.typography.headlineSmall)
                recap.firstAt?.let { Text("seit ${Fmt.shortDay(it)}", color = C.t2, style = MaterialTheme.typography.bodySmall) }
            }
            RoundIcon(Icons.Outlined.Casino, {
                if (all.isNotEmpty()) { val m = all.random(); DetailList.ids = listOf(m.id); onOpen(m.id) }
            }, bg = C.s2, size = 40.dp, desc = "Zufällige Erinnerung")
            Spacer(Modifier.width(8.dp))
            RoundIcon(Icons.Outlined.Settings, onSettings, bg = C.s2, size = 40.dp, desc = "Einstellungen")
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            // Heute vor X Jahren
            if (recap.onThisDay.isNotEmpty()) {
                SectionTitle("Heute vor …")
                recap.onThisDay.forEach { (years, list) ->
                    Text(if (years == 1) "1 Jahr" else "$years Jahren", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                        items(list, key = { it.id }) { m ->
                            Thumb(repo.thumbModel(m), Modifier.size(104.dp).clip(RoundedCornerShape(10.dp)).clickable {
                                DetailList.ids = list.map { it.id }; onOpen(m.id)
                            })
                        }
                    }
                }
            }

            SectionTitle("Zahlen")
            val tiles = listOf(
                Fmt.count(recap.total) to "Aufnahmen",
                Fmt.count(recap.places) to "Orte",
                Fmt.km(recap.distanceM) to "Strecke",
                Fmt.count(recap.days) to "Tage",
                "${recap.currentStreak}" to "Tage-Serie",
                "${recap.longestStreak}" to "Rekord-Serie",
                Fmt.count(recap.videos) to "Videos",
                Fmt.count(recap.countries) to "Länder",
                Fmt.count(recap.snap) to "Snapchat",
            )
            tiles.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    row.forEach { (v, l) ->
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(C.s1).padding(12.dp),
                        ) {
                            Text(v, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = C.t1, maxLines = 1)
                            Text(l, color = C.t2, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            SectionTitle("Letzte 12 Monate")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    val max = (recap.months.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
                    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                        val n = recap.months.size.coerceAtLeast(1)
                        val gap = 6.dp.toPx()
                        val bw = (size.width - gap * (n - 1)) / n
                        recap.months.forEachIndexed { i, (_, c) ->
                            val h = (size.height * c / max).coerceAtLeast(if (c > 0) 4f else 2f)
                            drawRoundRect(
                                color = if (i == n - 1) C.acc else C.t1.copy(alpha = if (c > 0) 0.85f else 0.15f),
                                topLeft = Offset(i * (bw + gap), size.height - h), size = Size(bw, h),
                                cornerRadius = CornerRadius(3.dp.toPx()),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row {
                        recap.months.forEach { (m, _) ->
                            Text(Fmt.monthShort(m).take(3), color = C.t2, fontSize = 9.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            SectionTitle("Wann du fotografierst")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    val max = (recap.hours.maxOrNull() ?: 0).coerceAtLeast(1)
                    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                        val bw = size.width / 24f
                        for (hr in 0 until 24) {
                            val c = recap.hours[hr]
                            val h = (size.height * c / max).coerceAtLeast(2f)
                            drawRoundRect(C.t1.copy(alpha = 0.2f + 0.8f * c / max), Offset(hr * bw + 1f, size.height - h),
                                Size(bw - 2f, h), CornerRadius(2f))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("0", "6", "12", "18", "24").forEach { Text(it, color = C.t2, fontSize = 10.sp) }
                    }
                    val peak = recap.hours.indices.maxByOrNull { recap.hours[it] }
                    if (peak != null && recap.total > 0) Text("Meistens gegen $peak Uhr", color = C.t2,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }

            if (recap.topPlaces.isNotEmpty()) {
                SectionTitle("Top-Orte")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val max = recap.topPlaces.first().second.toFloat()
                        recap.topPlaces.forEachIndexed { i, (city, n) ->
                            Column {
                                Row {
                                    Text("${i + 1}", color = C.t2, fontFamily = Mono, modifier = Modifier.width(22.dp))
                                    Text(city, color = C.t1, modifier = Modifier.weight(1f), maxLines = 1)
                                    Text(Fmt.count(n), color = C.t2, fontFamily = Mono)
                                }
                                Box(Modifier.padding(start = 22.dp, top = 4.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.s3)) {
                                    Box(Modifier.fillMaxWidth(n / max).height(3.dp).background(if (i == 0) C.acc else C.t1))
                                }
                            }
                        }
                    }
                }
            }

            SectionTitle("Speicher")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    InfoRow("Auf dem Handy", Fmt.bytes(local), mono = true)
                    InfoRow("Warten auf Upload", "$pending", mono = true)
                    val s = server
                    when {
                        s == null -> InfoRow("Server", "nicht verbunden")
                        !s.reachable -> InfoRow("Server", "nicht erreichbar")
                        else -> {
                            InfoRow("Auf dem Server", "${Fmt.count(s.count)} · ${Fmt.bytes(s.bytes)}", mono = true)
                            InfoRow("Platte frei", "${Fmt.bytes(s.diskFree)} von ${Fmt.bytes(s.diskTotal)}", mono = true)
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
