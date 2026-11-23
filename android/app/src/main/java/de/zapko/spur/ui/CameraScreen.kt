package de.zapko.spur.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Grid3x3
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.zapko.spur.capture.ShutterBus
import de.zapko.spur.data.Sync
import de.zapko.spur.repo
import de.zapko.spur.util.Fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun Context.has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
@Composable
fun CameraScreen(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val settings by repo.prefs.flow.collectAsStateWithLifecycle()
    var camOk by remember { mutableStateOf(ctx.has(Manifest.permission.CAMERA)) }
    var locOk by remember { mutableStateOf(ctx.has(Manifest.permission.ACCESS_FINE_LOCATION)) }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        camOk = ctx.has(Manifest.permission.CAMERA)
        locOk = ctx.has(Manifest.permission.ACCESS_FINE_LOCATION) || ctx.has(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (res.isNotEmpty()) repo.tracker.restart()
    }
    LaunchedEffect(Unit) {
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.RECORD_AUDIO).filter { !ctx.has(it) }
        if (missing.isNotEmpty()) perms.launch(missing.toTypedArray())
    }

    // Standort + Kompass nur solange die Kamera offen ist
    DisposableEffect(Unit) {
        repo.tracker.acquire()
        ShutterBus.cameraActive = true
        onDispose { repo.tracker.release(); ShutterBus.cameraActive = false }
    }

    if (!camOk) {
        PermissionWall(onGrant = {
            perms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.RECORD_AUDIO))
        })
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val controller = remember {
        LifecycleCameraController(ctx).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE)
            imageCaptureFlashMode = ImageCapture.FLASH_MODE_OFF
        }
    }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }
    LaunchedEffect(settings.maxQuality) {
        controller.imageCaptureMode = if (settings.maxQuality) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
    }

    val location by repo.tracker.location.collectAsStateWithLifecycle()
    val heading by repo.tracker.heading.collectAsStateWithLifecycle()
    val last by repo.lastCaptured.collectAsStateWithLifecycle()
    val items by repo.items.collectAsStateWithLifecycle()
    val pending by repo.pendingCount.collectAsStateWithLifecycle(0)
    val online = remember(pending, last) { repo.isOnline() }

    var front by remember { mutableStateOf(false) }
    var flash by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordMs by remember { mutableLongStateOf(0L) }
    var toast by remember { mutableStateOf<String?>(null) }
    val flashAnim = remember { Animatable(0f) }

    fun vibrate() {
        if (!settings.haptics) return
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31)
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(18, 160))
        }
    }

    fun savedToast() {
        toast = when {
            !settings.uploads -> "Gespeichert · auf dem Handy"
            repo.isOnline() -> "Gespeichert · wird hochgeladen"
            else -> "Gespeichert · offline, Upload folgt"
        }
    }

    fun takePhoto() {
        if (recording != null) return
        val now = System.currentTimeMillis()
        val loc = repo.tracker.fresh()
        val head = if (front) null else heading
        val id = repo.newId()
        val file = repo.newMediaFile(id, "jpg", now)
        val meta = ImageCapture.Metadata().apply {
            this.location = loc
            isReversedHorizontal = front
        }
        val opts = ImageCapture.OutputFileOptions.Builder(file).setMetadata(meta).build()
        vibrate()
        scope.launch { flashAnim.snapTo(0.85f); flashAnim.animateTo(0f, tween(220)) }
        controller.takePicture(opts, ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                scope.launch { repo.onPhotoSaved(id, file, now, loc, head, front); savedToast() }
            }
            override fun onError(exception: ImageCaptureException) {
                toast = "Foto fehlgeschlagen: ${exception.message}"
            }
        })
    }

    fun startVideo() {
        if (recording != null) return
        val now = System.currentTimeMillis()
        val loc = repo.tracker.fresh()
        val head = if (front) null else heading
        val id = repo.newId()
        val file = repo.newMediaFile(id, "mp4", now)
        val out = FileOutputOptions.Builder(file).apply { if (loc != null) setLocation(loc) }.build()
        val audio = if (ctx.has(Manifest.permission.RECORD_AUDIO)) AudioConfig.create(true) else AudioConfig.AUDIO_DISABLED
        if (flash == ImageCapture.FLASH_MODE_ON) controller.enableTorch(true)
        vibrate()
        recordMs = 0
        recording = controller.startRecording(out, audio, ContextCompat.getMainExecutor(ctx)) { ev ->
            when (ev) {
                is VideoRecordEvent.Status -> recordMs = ev.recordingStats.recordedDurationNanos / 1_000_000
                is VideoRecordEvent.Finalize -> {
                    controller.enableTorch(false)
                    recording = null
                    if (file.exists() && file.length() > 0) {
                        scope.launch { repo.onVideoSaved(id, file, now, loc ?: repo.tracker.fresh(), head, front); savedToast() }
                    } else {
                        file.delete()
                        toast = "Video fehlgeschlagen"
                    }
                }
                else -> {}
            }
        }
    }

    fun stopVideo() {
        recording?.stop()
    }

    LaunchedEffect(Unit) { ShutterBus.events.collect { takePhoto() } }
    DisposableEffect(Unit) { onDispose { recording?.stop() } }
    LaunchedEffect(toast) { if (toast != null) { delay(1800); toast = null } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PreviewView(c).apply {
                    this.controller = controller
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (settings.grid) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Color(0x44FFFFFF)
                for (i in 1..2) {
                    drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1f)
                    drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1f)
                }
            }
        }

        // Ausloese-Blitz
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashAnim.value)))

        // ---------- oben: Standort-HUD + Schalter
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GpsHud(
                    accuracy = location?.takeIf { it.hasAccuracy() }?.accuracy,
                    lat = location?.latitude, lon = location?.longitude,
                    heading = if (front) null else heading, locOk = locOk,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                RoundIcon(
                    icon = when (flash) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Outlined.FlashOn
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Outlined.FlashAuto
                        else -> Icons.Outlined.FlashOff
                    },
                    onClick = {
                        flash = when (flash) {
                            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                            else -> ImageCapture.FLASH_MODE_OFF
                        }
                        controller.imageCaptureFlashMode = flash
                    }, desc = "Blitz",
                )
                Spacer(Modifier.width(8.dp))
                RoundIcon(
                    icon = if (settings.grid) Icons.Outlined.Grid3x3 else Icons.Outlined.GridOff,
                    onClick = { scope.launch { repo.prefs.update { it.copy(grid = !it.grid) } } }, desc = "Raster",
                )
            }
            Spacer(Modifier.height(8.dp))
            SyncPill(pending = pending, online = online, uploads = settings.uploads, configured = settings.serverConfigured)
        }

        // ---------- Aufnahme-Timer
        AnimatedVisibility(recording != null, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 96.dp)) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(C.danger).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                Spacer(Modifier.width(8.dp))
                Text(Fmt.duration(recordMs), color = Color.White, fontFamily = Mono, fontWeight = FontWeight.Bold)
            }
        }

        // ---------- Hinweis "Gespeichert"
        AnimatedVisibility(toast != null, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)) {
            Text(
                toast ?: "", color = C.bg, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.t1).padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }

        // ---------- unten: letzte Aufnahme, Ausloeser, Kamera wechseln
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (recording != null) "Loslassen zum Speichern" else "Tippen: Foto · Halten: Video",
                color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val lastItem = last ?: items.firstOrNull()
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).border(2.dp, Color.White, RoundedCornerShape(12.dp))
                        .clickable(enabled = lastItem != null) { lastItem?.let { onOpen(it.id) } },
                ) {
                    if (lastItem != null) Thumb(repo.thumbModel(lastItem), Modifier.fillMaxSize())
                }
                Shutter(
                    recording = recording != null,
                    onTap = { takePhoto() },
                    onLongPress = { startVideo() },
                    onRelease = { stopVideo() },
                )
                RoundIcon(
                    Icons.Outlined.FlipCameraAndroid,
                    onClick = {
                        if (recording == null) {
                            front = !front
                            controller.cameraSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                        }
                    }, size = 52.dp, desc = "Kamera wechseln",
                )
            }
        }
    }
}

@Composable
private fun Shutter(recording: Boolean, onTap: () -> Unit, onLongPress: () -> Unit, onRelease: () -> Unit) {
    val inner by animateFloatAsState(if (recording) 0.42f else 0.82f, label = "shutter")
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)
    val release by rememberUpdatedState(onRelease)
    Box(
        Modifier.size(84.dp).pointerInput(Unit) {
            detectTapGestures(
                onTap = { tap() },
                onLongPress = { long() },
                onPress = { tryAwaitRelease(); release() },
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White, radius = size.minDimension / 2 - 3f, style = Stroke(width = 6f))
            drawCircle(if (recording) C.danger else Color.White, radius = size.minDimension / 2 * inner)
        }
    }
}

@Composable
private fun GpsHud(accuracy: Float?, lat: Double?, lon: Double?, heading: Float?, locOk: Boolean, modifier: Modifier = Modifier) {
    val color = when {
        !locOk || accuracy == null -> C.danger
        accuracy <= 15f -> C.ok
        accuracy <= 50f -> C.warn
        else -> C.danger
    }
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x77000000)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                when {
                    !locOk -> "Standort nicht erlaubt"
                    lat == null || lon == null -> "Suche GPS …"
                    else -> "± ${accuracy?.toInt() ?: "?"} m" + (heading?.let { "  ·  ${Fmt.compass(it)} ${it.toInt()}°" } ?: "")
                },
                color = Color.White, style = MaterialTheme.typography.labelLarge,
            )
            if (lat != null && lon != null) {
                Text("%.5f, %.5f".format(java.util.Locale.US, lat, lon), color = Color(0xB3FFFFFF),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono))
            }
        }
    }
}

@Composable
private fun SyncPill(pending: Int, online: Boolean, uploads: Boolean, configured: Boolean) {
    val (icon, text) = when {
        !configured -> Icons.Outlined.CloudOff to "Kein Server verbunden · alles bleibt auf dem Handy"
        !uploads -> Icons.Outlined.CloudOff to "Modus: nur Handy"
        pending > 0 && !online -> Icons.Outlined.CloudOff to "Offline · $pending warten auf Upload"
        pending > 0 -> Icons.Outlined.CloudUpload to "$pending werden hochgeladen"
        else -> Icons.Outlined.CloudDone to "Alles gesichert"
    }
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x66000000)).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color(0xDDFFFFFF), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color(0xDDFFFFFF), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PermissionWall(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(C.bg).padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Kamera & Standort", style = MaterialTheme.typography.headlineSmall, color = C.t1)
        Spacer(Modifier.height(8.dp))
        Text(
            "Spur braucht die Kamera und deinen genauen Standort, damit jede Aufnahme sofort mit Ort, Zeit und Richtung gespeichert wird. " +
                "Der Standort wird nur genutzt, solange Kamera oder Karte offen sind.",
            color = C.t2, style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Erlauben", onGrant, Modifier.fillMaxWidth())
    }
}

/** Nicht genutzt fuer Status, nur Hilfsfunktion fuer andere Screens. */
fun syncText(sync: Int) = when (sync) { Sync.SYNCED -> "Gesichert"; Sync.LOCAL_ONLY -> "Lokal"; else -> "Wartet" }
