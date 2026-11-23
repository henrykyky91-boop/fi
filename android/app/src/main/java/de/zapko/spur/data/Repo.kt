package de.zapko.spur.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Geocoder
import android.location.Location
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.zapko.spur.capture.LocationTracker
import de.zapko.spur.net.Api
import de.zapko.spur.net.ApiException
import de.zapko.spur.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class DeleteMode { PHONE, EVERYWHERE }

data class SyncStatus(
    val running: Boolean = false,
    val current: String? = null,
    val progress: Float = 0f,
    val lastOk: Long = 0,
    val lastError: String? = null,
)

class Repo(val ctx: Context, val scope: CoroutineScope) {
    val prefs = Prefs(ctx, scope)
    val db = SpurDb.get(ctx)
    val dao = db.media()
    val api = Api(prefs)
    val tracker = LocationTracker(ctx)

    val items: StateFlow<List<MediaEntity>> = dao.observeAll()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val pendingCount: Flow<Int> = dao.observePendingCount()
    val syncStatus = MutableStateFlow(SyncStatus())
    val lastCaptured = MutableStateFlow<MediaEntity?>(null)

    private val syncLock = Mutex()
    private val awaitingLocation = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        tracker.onFix = { loc -> if (awaitingLocation.isNotEmpty()) scope.launch { fillLateLocation(loc) } }
    }

    // ------------------------------------------------------------------ files

    fun mediaDir(takenAt: Long): File {
        val d = java.time.Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault())
        return File(ctx.filesDir, "media/%04d/%02d".format(d.year, d.monthValue)).apply { mkdirs() }
    }

    fun thumbFile(id: String) = File(ctx.filesDir, "thumbs").apply { mkdirs() }.let { File(it, "$id.jpg") }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    fun newMediaFile(id: String, ext: String, takenAt: Long) = File(mediaDir(takenAt), "$id.$ext")

    /** Was die Galerie/Karte anzeigen soll (Datei, Vorschau oder Server-URL). */
    fun thumbModel(m: MediaEntity): Any? = when {
        m.thumbPath != null -> File(m.thumbPath)
        m.onServer -> api.thumbUrl(m.id)
        m.localPath != null -> File(m.localPath)
        else -> null
    }

    fun fullModel(m: MediaEntity): Any? = when {
        m.localPath != null && File(m.localPath).exists() -> File(m.localPath)
        m.onServer -> if (m.isVideo) api.thumbUrl(m.id) else api.fileUrl(m.id)
        else -> thumbModel(m)
    }

    // ------------------------------------------------------------------ capture

    private fun deviceName(): String {
        val man = Build.MANUFACTURER.replaceFirstChar { it.titlecase(Locale.ROOT) }
        return if (Build.MODEL.startsWith(Build.MANUFACTURER, true)) Build.MODEL else "$man ${Build.MODEL}"
    }

    private fun tzOffset(at: Long) = ZoneId.systemDefault().rules.getOffset(java.time.Instant.ofEpochMilli(at)).totalSeconds / 60

    private fun initialSync(): Int {
        val s = prefs.current
        return if (s.storage == StorageMode.LOCAL) Sync.LOCAL_ONLY else Sync.PENDING
    }

    suspend fun onPhotoSaved(id: String, file: File, takenAt: Long, loc: Location?, heading: Float?, front: Boolean) =
        withContext(Dispatchers.IO) {
            var w = 0
            var h = 0
            runCatching {
                val exif = ExifInterface(file)
                w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                if (exif.rotationDegrees == 90 || exif.rotationDegrees == 270) { val t = w; w = h; h = t }
                if (heading != null) {
                    exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, "${(heading * 100).toInt()}/100")
                    exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, "T")
                }
                exif.setAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID, id)
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Spur")
                exif.saveAttributes()
            }
            if (w == 0) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, o); w = o.outWidth; h = o.outHeight
            }
            val thumb = makePhotoThumb(file, id)
            val m = MediaEntity(
                id = id, type = "photo", takenAt = takenAt, tzOffset = tzOffset(takenAt),
                lat = loc?.latitude, lon = loc?.longitude,
                accuracy = loc?.takeIf { it.hasAccuracy() }?.accuracy,
                altitude = loc?.takeIf { it.hasAltitude() }?.altitude,
                heading = heading, speed = loc?.takeIf { it.hasSpeed() }?.speed,
                width = w, height = h, size = file.length(), mime = "image/jpeg",
                device = deviceName(), camera = if (front) "Frontkamera" else "Rückkamera",
                localPath = file.path, thumbPath = thumb?.path, sync = initialSync(),
                locationLate = loc == null,
            )
            dao.upsert(m)
            lastCaptured.value = m
            if (loc == null) awaitingLocation[id] = takenAt
            if (prefs.current.saveToGallery) copyToGallery(m)
            requestSync()
            m
        }

    suspend fun onVideoSaved(id: String, file: File, takenAt: Long, loc: Location?, heading: Float?, front: Boolean) =
        withContext(Dispatchers.IO) {
            var w = 0; var h = 0; var dur = 0L
            var thumb: File? = null
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.path)
                dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                r.getFrameAtTime(200_000)?.let { bmp -> thumb = saveThumb(scaleDown(bmp, 480), id) }
            } catch (_: Exception) {
            } finally {
                runCatching { r.release() }
            }
            val m = MediaEntity(
                id = id, type = "video", takenAt = takenAt, tzOffset = tzOffset(takenAt),
                lat = loc?.latitude, lon = loc?.longitude,
                accuracy = loc?.takeIf { it.hasAccuracy() }?.accuracy,
                altitude = loc?.takeIf { it.hasAltitude() }?.altitude,
                heading = heading, speed = loc?.takeIf { it.hasSpeed() }?.speed,
                width = w, height = h, durationMs = dur, size = file.length(), mime = "video/mp4",
                device = deviceName(), camera = if (front) "Frontkamera" else "Rückkamera",
                localPath = file.path, thumbPath = thumb?.path, sync = initialSync(),
                locationLate = loc == null,
            )
            dao.upsert(m)
            lastCaptured.value = m
            if (loc == null) awaitingLocation[id] = takenAt
            if (prefs.current.saveToGallery) copyToGallery(m)
            requestSync()
            m
        }

    /** Kam der GPS-Fix erst nach der Aufnahme, wird er nachgetragen (bis 3 Minuten). */
    private suspend fun fillLateLocation(loc: Location) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val iter = awaitingLocation.entries.iterator()
        while (iter.hasNext()) {
            val (id, at) = iter.next()
            if (now - at > 180_000) { iter.remove(); continue }
            if (loc.hasAccuracy() && loc.accuracy > 100f) continue
            val m = dao.get(id) ?: run { iter.remove(); null } ?: continue
            if (m.lat != null) { iter.remove(); continue }
            val updated = m.copy(
                lat = loc.latitude, lon = loc.longitude,
                accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                altitude = if (loc.hasAltitude()) loc.altitude else null,
                dirty = m.onServer || m.dirty,
            )
            dao.upsert(updated)
            if (!m.isVideo) m.localPath?.let { p ->
                runCatching { ExifInterface(p).apply { setGpsInfo(loc); saveAttributes() } }
            }
            iter.remove()
        }
        requestSync()
    }

    private fun makePhotoThumb(file: File, id: String): File? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= 480 && o.outHeight / (sample * 2) >= 480) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val rot = runCatching { ExifInterface(file).rotationDegrees }.getOrDefault(0)
        val rotated = if (rot != 0) {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        } else bmp
        saveThumb(scaleDown(rotated, 480), id)
    }.getOrNull()

    private fun scaleDown(b: Bitmap, max: Int): Bitmap {
        val s = max.toFloat() / maxOf(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }

    private fun saveThumb(b: Bitmap, id: String): File {
        val f = thumbFile(id)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        return f
    }

    private fun copyToGallery(m: MediaEntity) {
        val src = m.localPath?.let { File(it) } ?: return
        runCatching {
            val video = m.isVideo
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Spur_${m.id.take(8)}.${src.extension}")
                put(MediaStore.MediaColumns.MIME_TYPE, m.mime)
                put(MediaStore.MediaColumns.DATE_TAKEN, m.takenAt)
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Spur")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val coll = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = ctx.contentResolver.insert(coll, values) ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
            if (Build.VERSION.SDK_INT >= 29) {
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        }
    }

    // ------------------------------------------------------------------ edits

    suspend fun setFavorite(id: String, fav: Boolean) = edit(id) { it.copy(favorite = fav) }

    suspend fun setNote(id: String, note: String, tags: String) = edit(id) {
        it.copy(note = note.trim(), tags = tags.split(',', ' ', '#').map { t -> t.trim() }.filter { t -> t.isNotEmpty() }
            .distinct().joinToString(", "))
    }

    private suspend fun edit(id: String, f: (MediaEntity) -> MediaEntity) {
        val m = dao.get(id) ?: return
        dao.upsert(f(m).copy(dirty = m.onServer))
        if (m.onServer) requestSync()
    }

    suspend fun delete(id: String, mode: DeleteMode) = withContext(Dispatchers.IO) {
        val m = dao.get(id) ?: return@withContext
        if (mode == DeleteMode.PHONE) {
            // Nur Speicher freigeben – einzige Kopie (nicht auf dem Server) wird nie angefasst
            if (m.onServer) {
                m.localPath?.let { File(it).delete() }
                dao.upsert(m.copy(localPath = null))
            }
            return@withContext
        }
        m.localPath?.let { File(it).delete() }
        m.thumbPath?.let { File(it).delete() }
        if (m.onServer) {
            dao.upsert(m.copy(pendingDelete = true, localPath = null, thumbPath = null))
            requestSync()
        } else dao.delete(id)
    }

    suspend fun deleteMany(ids: Collection<String>, mode: DeleteMode) { ids.forEach { delete(it, mode) } }

    /** Synchronisierte Dateien vom Handy entfernen (Vorschauen bleiben). */
    suspend fun freeUpSpace(): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        dao.syncedWithLocalFile().forEach { m ->
            val f = File(m.localPath!!)
            freed += f.length()
            f.delete()
            dao.upsert(m.copy(localPath = null))
        }
        freed
    }

    fun localBytes(): Long = File(ctx.filesDir, "media").walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Datei vom Server aufs Handy holen. */
    suspend fun downloadToPhone(id: String): Boolean = withContext(Dispatchers.IO) {
        val m = dao.get(id) ?: return@withContext false
        if (m.localPath != null && File(m.localPath).exists()) return@withContext true
        val ext = when {
            m.isVideo -> "mp4"; m.mime.contains("png") -> "png"; else -> "jpg"
        }
        val f = newMediaFile(m.id, ext, m.takenAt)
        runCatching { api.download(m.id, f) }.onFailure { return@withContext false }
        dao.upsert(m.copy(localPath = f.path))
        true
    }

    /** Datei zum Teilen bereitstellen (lokal oder kurz vom Server laden). */
    suspend fun fileForShare(id: String): File? = withContext(Dispatchers.IO) {
        val m = dao.get(id) ?: return@withContext null
        m.localPath?.let { p -> File(p).takeIf { it.exists() }?.let { return@withContext it } }
        if (!m.onServer) return@withContext null
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
        val f = File(dir, "Spur_${m.id.take(8)}.${if (m.isVideo) "mp4" else "jpg"}")
        runCatching { api.download(m.id, f) }.getOrNull() ?: return@withContext null
        f
    }

    // ------------------------------------------------------------------ sync

    fun requestSync() {
        val s = prefs.current
        if (!s.serverConfigured) return
        val c = Constraints.Builder()
            .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(c).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("spur-sync", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    fun schedulePeriodic() {
        val s = prefs.current
        val c = Constraints.Builder()
            .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(c).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("spur-periodic", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun isOnline(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isUnmetered(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Ein kompletter Abgleich. Wirft IOException bei Netzproblemen (=> spaeter erneut). */
    suspend fun syncNow() = withContext(Dispatchers.IO) {
        syncLock.withLock {
            val s = prefs.load()
            if (!s.serverConfigured) return@withLock
            syncStatus.value = syncStatus.value.copy(running = true, lastError = null)
            try {
                geocodeMissing()

                // 1) Loeschungen
                for (d in dao.pendingDeletes()) {
                    api.delete(d.id)
                    dao.delete(d.id)
                }

                // 2) Uploads
                if (s.storage != StorageMode.LOCAL && (!s.wifiOnly || isUnmetered())) {
                    val pending = dao.pendingUploads()
                    pending.forEachIndexed { i, m -> upload(m, s, i, pending.size) }
                }

                // 3) geaenderte Notizen/Favoriten
                for (m in dao.dirty()) {
                    val ch = JSONObject().apply {
                        put("note", m.note); put("tags", m.tags); put("favorite", m.favorite)
                        m.lat?.let { put("lat", it) }; m.lon?.let { put("lon", it) }
                        m.address?.let { put("address", it) }; m.city?.let { put("city", it) }
                        m.country?.let { put("country", it) }
                    }
                    api.patch(m.id, ch)
                    dao.get(m.id)?.let { cur -> dao.upsert(cur.copy(dirty = false)) }
                }

                // 4) Neues vom Server (inkl. Snapchat-Import)
                pull()
                syncStatus.value = SyncStatus(lastOk = System.currentTimeMillis())
            } catch (e: Exception) {
                syncStatus.value = syncStatus.value.copy(running = false, current = null, lastError = e.message ?: "Fehler")
                throw e
            }
        }
    }

    private suspend fun upload(m: MediaEntity, s: Settings, index: Int, total: Int) {
        val file = m.localPath?.let { File(it) }
        if (file == null || !file.exists()) {
            dao.upsert(m.copy(sync = Sync.ERROR, error = "Datei fehlt auf dem Handy"))
            return
        }
        try {
            syncStatus.value = syncStatus.value.copy(current = "${index + 1} / $total", progress = 0f)
            val sha = m.sha256 ?: sha256(file)
            // Schon oben (z. B. Verbindung brach nach dem Speichern ab)? Dann nicht doppelt hochladen.
            api.get(m.id)?.let { existing ->
                val latest = dao.get(m.id) ?: return
                var done = Api.merge(existing, latest).copy(dirty = latest.dirty)
                if (s.storage == StorageMode.CLOUD) { file.delete(); done = done.copy(localPath = null) }
                dao.upsert(done)
                return
            }
            m.thumbPath?.let { File(it) }?.takeIf { it.exists() }?.let { api.uploadFile(m.id, "thumb", it) }
            api.uploadFile(m.id, "media", file) { done, all ->
                syncStatus.value = syncStatus.value.copy(progress = if (all > 0) done.toFloat() / all else 1f)
            }
            val res = api.commit(m.id, Api.toMeta(m.copy(sha256 = sha, size = file.length())))
            val latest = dao.get(m.id) ?: return
            var updated = Api.merge(res, latest).copy(dirty = latest.dirty)
            if (s.storage == StorageMode.CLOUD) {
                file.delete()
                updated = updated.copy(localPath = null)
            }
            dao.upsert(updated)
        } catch (e: ApiException) {
            if (e.code in 400..499 && e.code != 401 && e.code != 408 && e.code != 429) {
                dao.upsert(m.copy(sync = Sync.ERROR, error = e.message))
            } else throw e
        }
    }

    private suspend fun pull() {
        var cursor = prefs.load().cursor
        while (true) {
            val page = api.list(cursor, 1000)
            if (page.items.isEmpty()) break
            val batch = ArrayList<MediaEntity>(page.items.size)
            for (o in page.items) {
                val id = o.optString("id")
                val local = dao.get(id)
                if (o.optBoolean("deleted")) {
                    if (local != null) {
                        local.localPath?.let { File(it).delete() }
                        local.thumbPath?.let { File(it).delete() }
                        dao.delete(id)
                    }
                    continue
                }
                if (local?.pendingDelete == true) continue
                batch.add(Api.merge(o, local))
            }
            if (batch.isNotEmpty()) dao.upsertAll(batch)
            cursor = page.cursor
            prefs.update { it.copy(cursor = cursor) }
            if (!page.more) break
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun geocodeMissing() {
        if (!Geocoder.isPresent() || !isOnline()) return
        val g = Geocoder(ctx, Locale.GERMANY)
        for (m in dao.missingAddress()) {
            val a = runCatching { g.getFromLocation(m.lat!!, m.lon!!, 1)?.firstOrNull() }.getOrNull() ?: continue
            val street = listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ")
            val city = a.locality ?: a.subAdminArea ?: a.adminArea
            val addr = listOf(street, city ?: "").filter { it.isNotBlank() }.joinToString(", ")
            val cur = dao.get(m.id) ?: continue
            dao.upsert(cur.copy(address = addr.ifBlank { null }, city = city ?: "-", country = a.countryName,
                dirty = cur.onServer || cur.dirty))
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Server-Adresse geaendert -> alles neu abgleichen. */
    suspend fun resetServerState() = withContext(Dispatchers.IO) {
        prefs.update { it.copy(cursor = 0) }
    }

    fun throwIfOffline() { if (!isOnline()) throw IOException("offline") }
}
