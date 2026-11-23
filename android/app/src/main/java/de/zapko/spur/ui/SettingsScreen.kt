package de.zapko.spur.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.zapko.spur.BuildConfig
import de.zapko.spur.data.MapStyle
import de.zapko.spur.data.StorageMode
import de.zapko.spur.net.ServerInfo
import de.zapko.spur.repo
import de.zapko.spur.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ServerForm(onSaved: (ServerInfo) -> Unit = {}) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val s = repo.prefs.current
    var url by remember { mutableStateOf(s.serverUrl) }
    var token by remember { mutableStateOf(s.token) }
    var result by remember { mutableStateOf<ServerInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = C.acc, unfocusedBorderColor = C.line, cursorColor = C.acc,
        focusedLabelColor = C.acc, unfocusedLabelColor = C.t2, focusedTextColor = C.t1, unfocusedTextColor = C.t1,
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            url, { url = it.trim() }, label = { Text("Server-Adresse") }, singleLine = true, colors = colors,
            placeholder = { Text("https://fotos.deine-domain.de", color = C.t3) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            token, { token = it.trim() }, label = { Text("Schlüssel") }, singleLine = true, colors = colors,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        result?.let { r ->
            val ok = r.reachable && r.auth
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) C.ok else C.danger))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (ok) "Verbunden · Spur ${r.version} · ${Fmt.count(r.count)} Medien · ${Fmt.bytes(r.diskFree)} frei"
                    else r.error ?: "Fehler",
                    color = if (ok) C.t1 else C.danger, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        PrimaryButton(if (busy) "Prüfe …" else "Verbinden & speichern", {
            if (busy) return@PrimaryButton
            busy = true
            scope.launch {
                var u = url.trim().trimEnd('/')
                if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) {
                    // IP-Adresse, .local oder eigener Port -> Heimnetz (http), sonst Domain (https)
                    val host = u.substringBefore('/').substringBefore(':')
                    val local = host.all { it.isDigit() || it == '.' } || host.endsWith(".local") ||
                        host == "localhost" || u.substringBefore('/').contains(':')
                    u = (if (local) "http://" else "https://") + u
                }
                url = u
                val info = withContext(Dispatchers.IO) { repo.api.ping(u, token) }
                result = info
                busy = false
                if (info.reachable && info.auth) {
                    val changed = u != repo.prefs.current.serverUrl
                    repo.prefs.update { it.copy(serverUrl = u, token = token, cursor = if (changed) 0 else it.cursor) }
                    repo.requestSync()
                    onSaved(info)
                }
            }
        }, Modifier.fillMaxWidth(), enabled = url.isNotBlank() && token.isNotBlank())
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val s by repo.prefs.flow.collectAsStateWithLifecycle()
    val pending by repo.pendingCount.collectAsStateWithLifecycle(0)
    val status by repo.syncStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editServer by remember { mutableStateOf(!s.serverConfigured) }
    val local by produceState(0L, pending) { value = withContext(Dispatchers.IO) { repo.localBytes() } }
    fun set(f: (de.zapko.spur.data.Settings) -> de.zapko.spur.data.Settings) = scope.launch { repo.prefs.update(f) }

    Column(Modifier.fillMaxSize().background(C.bg)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon(Icons.AutoMirrored.Outlined.ArrowBack, onBack, bg = Color.Transparent)
            Text("Einstellungen", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {

            // ---------------- Server
            SectionTitle("Eigener Server", Modifier.padding(horizontal = 16.dp))
            Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    if (editServer || !s.serverConfigured) {
                        ServerForm(onSaved = { editServer = false })
                    } else {
                        Text(s.serverUrl, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                status.running -> "Gleicht ab … ${status.current ?: ""}"
                                status.lastError != null -> "Letzter Fehler: ${status.lastError}"
                                pending > 0 -> "$pending warten auf Upload"
                                status.lastOk > 0 -> "Alles gesichert · ${Fmt.shortTime(status.lastOk)}"
                                else -> "Verbunden"
                            },
                            color = if (status.lastError != null) C.warn else C.t2, style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostButton("Jetzt abgleichen", {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { runCatching { repo.syncNow() }.isSuccess }
                                    Toast.makeText(ctx, if (ok) "Abgleich fertig" else "Server nicht erreichbar – später automatisch", Toast.LENGTH_SHORT).show()
                                }
                            })
                            GhostButton("Ändern", { editServer = true })
                        }
                    }
                }
            }
            Text(
                "Unterwegs erreichbar per Cloudflare-Tunnel (https://…) oder per WireGuard-VPN (http://10.x.x.x:8787). " +
                    "Ohne Internet speichert Spur alles auf dem Handy und lädt es später automatisch hoch.",
                color = C.t3, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // ---------------- Speicher
            SectionTitle("Wo gespeichert wird", Modifier.padding(horizontal = 16.dp))
            Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                Column {
                    StorageMode.entries.forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth().clickable { set { it.copy(storage = mode) } }.padding(14.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Box(
                                Modifier.padding(top = 3.dp).size(18.dp).clip(CircleShape)
                                    .border(2.dp, if (s.storage == mode) C.acc else C.t3, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { if (s.storage == mode) Box(Modifier.size(9.dp).clip(CircleShape).background(C.acc)) }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                                Text(mode.hint, color = C.t2, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Divider()
                    SettingSwitch("Nur über WLAN hochladen", "Mobile Daten sparen – Upload wartet aufs WLAN", s.wifiOnly) { v -> set { it.copy(wifiOnly = v) } }
                    SettingSwitch("Kopie in Handy-Galerie", "Zusätzlich unter Bilder/Spur ablegen", s.saveToGallery) { v -> set { it.copy(saveToGallery = v) } }
                    Divider()
                    SettingItem("Speicher freigeben", "Dateien, die sicher auf dem Server liegen, vom Handy löschen (Vorschau bleibt). Belegt: ${Fmt.bytes(local)}", {
                        scope.launch {
                            val freed = repo.freeUpSpace()
                            Toast.makeText(ctx, "${Fmt.bytes(freed)} freigegeben", Toast.LENGTH_SHORT).show()
                        }
                    })
                }
            }

            // ---------------- Kamera
            SectionTitle("Kamera", Modifier.padding(horizontal = 16.dp))
            Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                Column {
                    SettingSwitch("Mit Kamera starten", "App öffnet direkt die Kamera", s.openCamera) { v -> set { it.copy(openCamera = v) } }
                    SettingSwitch("Lautstärketaste löst aus", null, s.volumeShutter) { v -> set { it.copy(volumeShutter = v) } }
                    SettingSwitch("Maximale Qualität", "Etwas langsamer auslösen, dafür beste Bildqualität", s.maxQuality) { v -> set { it.copy(maxQuality = v) } }
                    SettingSwitch("Raster", null, s.grid) { v -> set { it.copy(grid = v) } }
                    SettingSwitch("Vibration beim Auslösen", null, s.haptics) { v -> set { it.copy(haptics = v) } }
                }
            }

            // ---------------- Karte
            SectionTitle("Karte", Modifier.padding(horizontal = 16.dp))
            Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                Column {
                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MapStyle.entries.forEach { st -> Chip(st.label, s.mapStyle == st, { set { it.copy(mapStyle = st) } }) }
                    }
                    SettingSwitch("Snapchat-Memories zeigen", "Importierte Memories auf Karte und in Statistik", s.showSnapchat) { v -> set { it.copy(showSnapchat = v) } }
                }
            }

            // ---------------- Sicherheit
            SectionTitle("Sicherheit", Modifier.padding(horizontal = 16.dp))
            Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                SettingSwitch("App-Sperre", "Fingerabdruck, Gesicht oder PIN beim Öffnen", s.appLock) { v ->
                    val can = BiometricManager.from(ctx).canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    ) == BiometricManager.BIOMETRIC_SUCCESS
                    if (v && !can) Toast.makeText(ctx, "Richte zuerst eine Displaysperre ein", Toast.LENGTH_LONG).show()
                    else set { it.copy(appLock = v) }
                }
            }

            // ---------------- Snapchat
            SectionTitle("Snapchat-Memories importieren", Modifier.padding(horizontal = 16.dp))
            Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("1. accounts.snapchat.com → Meine Daten → „Export your Memories“ an → nur Memories anfordern.", color = C.t2, style = MaterialTheme.typography.bodySmall)
                    Text("2. ZIP(s) herunterladen und auf die Platte am Pi kopieren.", color = C.t2, style = MaterialTheme.typography.bodySmall)
                    Text("3. Auf dem Pi:", color = C.t2, style = MaterialTheme.typography.bodySmall)
                    Text("python3 /opt/spur/spur_server.py import-snapchat --data /mnt/server013/spur /pfad/zu/mydata*.zip",
                        color = C.t1, fontFamily = Mono, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.s2).padding(10.dp))
                    Text("Datum und genauer Standort jeder Memory werden übernommen und erscheinen hier automatisch auf der Karte.",
                        color = C.t2, style = MaterialTheme.typography.bodySmall)
                }
            }

            // ---------------- Info
            SectionTitle("Info", Modifier.padding(horizontal = 16.dp))
            Card(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                Column {
                    SettingItem("Version", null, {}, trailing = BuildConfig.VERSION_NAME)
                    if (s.serverConfigured) SettingItem("Datenschutzerklärung", "${s.serverUrl}/datenschutz", {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${s.serverUrl}/datenschutz"))) }
                    })
                    SettingItem("Kartendaten", "© OpenStreetMap-Mitwirkende · © Esri", {})
                }
            }
        }
    }
}

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }
    fun finish() = scope.launch { repo.prefs.update { it.copy(onboarded = true) }; onDone() }

    Column(
        Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding().padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(40.dp))
        Text("Spur.", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.width(36.dp).height(4.dp).background(C.acc))
        Spacer(Modifier.height(24.dp))
        if (step == 0) {
            Text("Jedes Foto weiß, wo es entstanden ist.", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            listOf(
                "Ein Tipp – gespeichert. Mit Ort, Uhrzeit, Höhe und Blickrichtung.",
                "Offline? Egal. Alles bleibt sicher auf dem Handy und wird später hochgeladen.",
                "Deine Bilder liegen auf deinem eigenen Server – nicht bei irgendeiner Firma.",
                "Snapchat-Memories importieren und alles auf einer Karte sehen.",
            ).forEach {
                Row(Modifier.padding(vertical = 6.dp)) {
                    Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(C.acc))
                    Spacer(Modifier.width(12.dp))
                    Text(it, color = C.t2, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(32.dp))
            PrimaryButton("Weiter", { step = 1 }, Modifier.fillMaxWidth())
        } else {
            Text("Mit deinem Server verbinden", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Adresse und Schlüssel bekommst du beim Start des Spur-Servers auf deinem Raspberry Pi.",
                color = C.t2, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))
            ServerForm(onSaved = { finish() })
            Spacer(Modifier.height(12.dp))
            GhostButton("Später – erstmal nur auf dem Handy", { finish() }, Modifier.fillMaxWidth())
        }
    }
}
