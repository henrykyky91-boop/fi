package de.zapko.spur

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import de.zapko.spur.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration as OsmConfig
import java.io.File

class SpurApp : Application(), Configuration.Provider, ImageLoaderFactory {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var repo: Repo
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()

    override fun onCreate() {
        super.onCreate()
        OsmConfig.getInstance().apply {
            userAgentValue = "Spur/${BuildConfig.VERSION_NAME} ($packageName)"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
            tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 250L * 1024 * 1024
        }
        repo = Repo(this, scope)
        scope.launch {
            repo.prefs.flow.first { it.loaded }
            repo.schedulePeriodic()
            repo.requestSync()
            // Netzwerk-Einstellung geaendert -> Auftraege neu planen
            repo.prefs.flow.drop(1).distinctUntilChangedBy { Triple(it.wifiOnly, it.serverUrl, it.token) }.collect {
                repo.schedulePeriodic()
                repo.requestSync()
            }
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { repo.api.client }
        .components { add(VideoFrameDecoder.Factory()) }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(400L * 1024 * 1024).build() }
        .crossfade(120)
        .respectCacheHeaders(false)
        .build()
}

val Context.repo: Repo get() = (applicationContext as SpurApp).repo
