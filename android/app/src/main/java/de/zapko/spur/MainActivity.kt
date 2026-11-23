package de.zapko.spur

import android.content.Intent
import android.graphics.Color as AColor
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.outlined.AutoGraph
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.zapko.spur.capture.NavBus
import de.zapko.spur.capture.ShutterBus
import de.zapko.spur.ui.C
import de.zapko.spur.ui.CameraScreen
import de.zapko.spur.ui.DetailScreen
import de.zapko.spur.ui.GalleryScreen
import de.zapko.spur.ui.MapScreen
import de.zapko.spur.ui.OnboardingScreen
import de.zapko.spur.ui.PrimaryButton
import de.zapko.spur.ui.RecapScreen
import de.zapko.spur.ui.SettingsScreen
import de.zapko.spur.ui.SpurTheme

class MainActivity : FragmentActivity() {

    companion object {
        const val ACTION_CAMERA = "de.zapko.spur.CAMERA"
        const val ACTION_MAP = "de.zapko.spur.MAP"
        private const val RELOCK_MS = 30_000L
    }

    private var unlocked by mutableStateOf(false)
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) unlocked = savedInstanceState.getBoolean("unlocked", false)
        handleIntent(intent)
        setContent {
            SpurTheme {
                val s by repo.prefs.flow.collectAsStateWithLifecycle()
                when {
                    !s.loaded -> Box(Modifier.fillMaxSize().background(C.bg))
                    s.appLock && !unlocked -> LockScreen { authenticate() }
                    else -> SpurNav(startCamera = s.openCamera, onboarded = s.onboarded)
                }
                LaunchedEffect(s.loaded, s.appLock) {
                    if (s.loaded && s.appLock && !unlocked) authenticate()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("unlocked", unlocked)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        when (i?.action) {
            ACTION_CAMERA, "android.media.action.STILL_IMAGE_CAMERA" -> NavBus.route.value = "camera"
            ACTION_MAP -> NavBus.route.value = "map"
        }
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        if (stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > RELOCK_MS && repo.prefs.current.appLock) {
            unlocked = false
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) &&
            ShutterBus.cameraActive && repo.prefs.current.volumeShutter && (!repo.prefs.current.appLock || unlocked)
        ) {
            if (event?.repeatCount == 0) ShutterBus.events.tryEmit(Unit)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private var prompting = false

    private fun authenticate() {
        if (prompting || unlocked) return
        val allowed = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            unlocked = true // keine Sperre eingerichtet -> nicht aussperren
            return
        }
        prompting = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false; unlocked = true
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompting = false
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Spur entsperren")
                .setAllowedAuthenticators(allowed)
                .build(),
        )
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(C.bg).padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Lock, null, tint = C.t1, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        Text("Spur ist gesperrt", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Entsperren", onUnlock, Modifier.fillMaxWidth())
    }
}

private data class Tab(val route: String, val label: String, val on: ImageVector, val off: ImageVector)

private val tabs = listOf(
    Tab("camera", "Kamera", Icons.Filled.CameraAlt, Icons.Outlined.CameraAlt),
    Tab("map", "Karte", Icons.Filled.Map, Icons.Outlined.Map),
    Tab("gallery", "Galerie", Icons.Filled.GridView, Icons.Outlined.GridView),
    Tab("recap", "Rückblick", Icons.Filled.AutoGraph, Icons.Outlined.AutoGraph),
)

private fun NavHostController.goTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun SpurNav(startCamera: Boolean, onboarded: Boolean) {
    val nav = rememberNavController()
    // Startziel nur beim Start (bzw. nach dem Einrichten) festlegen – sonst springt die Navigation bei Einstellungs-Aenderungen
    val start = remember(onboarded) {
        when {
            !onboarded -> "onboarding"
            startCamera -> "camera"
            else -> "map"
        }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val pending by NavBus.route.collectAsStateWithLifecycle()
    LaunchedEffect(pending, onboarded) {
        val r = pending ?: return@LaunchedEffect
        if (onboarded) { nav.goTab(r); NavBus.route.value = null }
    }
    val open: (String) -> Unit = { id -> nav.navigate("detail/$id") }

    Column(Modifier.fillMaxSize().background(C.bg)) {
        Box(Modifier.weight(1f)) {
            NavHost(nav, startDestination = start) {
                composable("onboarding") {
                    // Nach dem Einrichten wechselt das Startziel automatisch auf die Kamera
                    OnboardingScreen(onDone = {})
                }
                composable("camera") { CameraScreen(onOpen = open) }
                composable("map") { MapScreen(onOpen = open) }
                composable("gallery") { GalleryScreen(onOpen = open, onSettings = { nav.navigate("settings") }) }
                composable("recap") { RecapScreen(onOpen = open, onSettings = { nav.navigate("settings") }) }
                composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                    DetailScreen(startId = e.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() })
                }
            }
        }
        if (route in tabs.map { it.route }) {
            Row(
                Modifier.fillMaxWidth().background(C.bg).navigationBarsPadding().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                tabs.forEach { t ->
                    val sel = route == t.route
                    Column(
                        Modifier.clip(CircleShape).clickable { if (!sel) nav.goTab(t.route) }.padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(if (sel) t.on else t.off, t.label, tint = if (sel) C.t1 else C.t3, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(2.dp))
                        Text(t.label, color = if (sel) C.t1 else C.t3, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(3.dp))
                        Box(Modifier.width(14.dp).height(2.dp).background(if (sel) C.acc else C.bg))
                    }
                }
            }
        }
    }
}
