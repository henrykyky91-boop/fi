package de.zapko.spur.net

import de.zapko.spur.data.MediaEntity
import de.zapko.spur.data.Prefs
import de.zapko.spur.data.Sync
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message)

data class ServerInfo(
    val reachable: Boolean,
    val auth: Boolean,
    val version: String = "",
    val count: Int = 0,
    val bytes: Long = 0,
    val diskFree: Long = 0,
    val diskTotal: Long = 0,
    val error: String? = null,
)

data class Page(val items: List<JSONObject>, val cursor: Long, val more: Boolean)

class Api(private val prefs: Prefs) {

    private val json = "application/json; charset=utf-8".toMediaType()
    private val octet = "application/octet-stream".toMediaType()

    /** Ein Client fuer alles (auch Bilder) – haengt den Schluessel automatisch an Anfragen an den eigenen Server. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val req = chain.request()
            val s = prefs.current
            val base = s.serverUrl.trim().trimEnd('/')
            if (base.isNotEmpty() && s.token.isNotBlank() && req.header("Authorization") == null &&
                req.url.toString().startsWith(base)
            ) {
                chain.proceed(req.newBuilder().header("Authorization", "Bearer ${s.token}").build())
            } else chain.proceed(req)
        }
        .build()

    private val base get() = prefs.current.serverUrl.trim().trimEnd('/')

    fun fileUrl(id: String) = "$base/api/media/$id/file"
    fun thumbUrl(id: String) = "$base/api/media/$id/thumb"

    private fun Response.bodyJson(): JSONObject {
        val txt = body?.string().orEmpty()
        return if (txt.isBlank()) JSONObject() else JSONObject(txt)
    }

    private fun check(r: Response): JSONObject {
        val j = r.bodyJson()
        if (!r.isSuccessful) throw ApiException(r.code, j.optString("error", "HTTP ${r.code}"))
        return j
    }

    fun ping(url: String = base, token: String = prefs.current.token): ServerInfo {
        return try {
            val req = Request.Builder().url(url.trim().trimEnd('/') + "/api/ping")
                .header("Authorization", "Bearer ${token.trim()}").build()
            client.newCall(req).execute().use { r ->
                val j = r.bodyJson()
                if (!r.isSuccessful || j.optString("name") != "Spur") {
                    return ServerInfo(true, false, error = "Kein Spur-Server unter dieser Adresse")
                }
                ServerInfo(
                    reachable = true, auth = j.optBoolean("auth"), version = j.optString("version"),
                    count = j.optInt("count"), bytes = j.optLong("bytes"),
                    diskFree = j.optLong("diskFree"), diskTotal = j.optLong("diskTotal"),
                    error = if (j.optBoolean("auth")) null else "Schlüssel falsch",
                )
            }
        } catch (e: Exception) {
            ServerInfo(false, false, error = e.message ?: "Nicht erreichbar")
        }
    }

    fun uploadedSize(id: String, kind: String): Long {
        val req = Request.Builder().url("$base/api/upload/$id/$kind").build()
        client.newCall(req).execute().use { return check(it).optLong("size") }
    }

    /** Laedt eine Datei in Stuecken hoch. Setzt nach Abbruch automatisch dort fort, wo der Server steht. */
    fun uploadFile(id: String, kind: String, file: File, chunk: Int = 4 * 1024 * 1024, progress: (Long, Long) -> Unit = { _, _ -> }) {
        val total = file.length()
        var offset = uploadedSize(id, kind)
        if (offset > total) throw ApiException(409, "Upload-Konflikt")
        val buf = ByteArray(chunk)
        RandomAccessFile(file, "r").use { raf ->
            var guard = 0
            while (offset < total) {
                raf.seek(offset)
                val n = raf.read(buf, 0, minOf(chunk.toLong(), total - offset).toInt())
                if (n <= 0) break
                val body = buf.copyOf(n).toRequestBody(octet)
                val req = Request.Builder().url("$base/api/upload/$id/$kind?offset=$offset").put(body).build()
                client.newCall(req).execute().use { r ->
                    val j = r.bodyJson()
                    when {
                        r.isSuccessful -> offset = j.optLong("size")
                        r.code == 409 -> { offset = j.optLong("size"); if (++guard > 5) throw ApiException(409, "Upload-Konflikt") }
                        else -> throw ApiException(r.code, j.optString("error", "HTTP ${r.code}"))
                    }
                }
                progress(offset, total)
            }
        }
    }

    /** Eintrag auf dem Server (null, wenn noch nicht vorhanden). */
    fun get(id: String): JSONObject? {
        val req = Request.Builder().url("$base/api/media/$id").build()
        client.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            val j = check(r)
            return if (j.optBoolean("deleted")) null else j
        }
    }

    fun commit(id: String, meta: JSONObject): JSONObject {
        val req = Request.Builder().url("$base/api/media/$id/commit")
            .post(meta.toString().toRequestBody(json)).build()
        client.newCall(req).execute().use { return check(it) }
    }

    fun list(since: Long, limit: Int = 1000): Page {
        val req = Request.Builder().url("$base/api/media?since=$since&limit=$limit").build()
        client.newCall(req).execute().use { r ->
            val j = check(r)
            val arr = j.optJSONArray("items")
            val items = ArrayList<JSONObject>(arr?.length() ?: 0)
            if (arr != null) for (i in 0 until arr.length()) items.add(arr.getJSONObject(i))
            return Page(items, j.optLong("cursor", since), j.optBoolean("more"))
        }
    }

    fun patch(id: String, changes: JSONObject) {
        val req = Request.Builder().url("$base/api/media/$id")
            .patch(changes.toString().toRequestBody(json)).build()
        client.newCall(req).execute().use { check(it) }
    }

    fun delete(id: String) {
        val req = Request.Builder().url("$base/api/media/$id").delete().build()
        client.newCall(req).execute().use { r -> if (!r.isSuccessful && r.code != 404) check(r) }
    }

    fun download(id: String, dest: File) {
        val req = Request.Builder().url(fileUrl(id)).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "Download fehlgeschlagen")
            val tmp = File(dest.path + ".part")
            dest.parentFile?.mkdirs()
            r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
            if (!tmp.renameTo(dest)) throw IOException("Speichern fehlgeschlagen")
        }
    }

    companion object {
        fun toMeta(m: MediaEntity): JSONObject = JSONObject().apply {
            put("type", m.type); put("source", m.source); put("takenAt", m.takenAt); put("tzOffset", m.tzOffset)
            m.lat?.let { put("lat", it) }; m.lon?.let { put("lon", it) }
            m.accuracy?.let { put("accuracy", it.toDouble()) }; m.altitude?.let { put("altitude", it) }
            m.heading?.let { put("heading", it.toDouble()) }; m.speed?.let { put("speed", it.toDouble()) }
            m.address?.let { put("address", it) }; m.city?.let { put("city", it) }; m.country?.let { put("country", it) }
            put("width", m.width); put("height", m.height); put("durationMs", m.durationMs)
            put("size", m.size); put("mime", m.mime); m.sha256?.let { put("sha256", it) }
            m.device?.let { put("device", it) }; m.camera?.let { put("camera", it) }
            put("note", m.note); put("tags", m.tags); put("favorite", m.favorite)
        }

        private fun JSONObject.dOrNull(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }
        private fun JSONObject.sOrNull(k: String): String? = if (!has(k) || isNull(k)) null else optString(k)

        /** Server-Eintrag mit lokalem Stand zusammenfuehren. */
        fun merge(o: JSONObject, local: MediaEntity?): MediaEntity {
            val base = local ?: MediaEntity(id = o.getString("id"), takenAt = o.optLong("takenAt"))
            val keepEdits = local?.dirty == true
            return base.copy(
                type = o.optString("type", base.type),
                source = o.optString("source", base.source),
                takenAt = o.optLong("takenAt", base.takenAt),
                tzOffset = o.optInt("tzOffset", base.tzOffset),
                lat = o.dOrNull("lat") ?: base.lat,
                lon = o.dOrNull("lon") ?: base.lon,
                accuracy = o.dOrNull("accuracy")?.toFloat() ?: base.accuracy,
                altitude = o.dOrNull("altitude") ?: base.altitude,
                heading = o.dOrNull("heading")?.toFloat() ?: base.heading,
                speed = o.dOrNull("speed")?.toFloat() ?: base.speed,
                address = o.sOrNull("address")?.takeIf { it.isNotBlank() } ?: base.address,
                city = o.sOrNull("city")?.takeIf { it.isNotBlank() } ?: base.city,
                country = o.sOrNull("country")?.takeIf { it.isNotBlank() } ?: base.country,
                width = o.optInt("width").takeIf { it > 0 } ?: base.width,
                height = o.optInt("height").takeIf { it > 0 } ?: base.height,
                durationMs = o.optLong("durationMs").takeIf { it > 0 } ?: base.durationMs,
                size = o.optLong("size").takeIf { it > 0 } ?: base.size,
                mime = o.sOrNull("mime") ?: base.mime,
                device = o.sOrNull("device") ?: base.device,
                camera = o.sOrNull("camera") ?: base.camera,
                note = if (keepEdits) base.note else o.optString("note", base.note),
                tags = if (keepEdits) base.tags else o.optString("tags", base.tags),
                favorite = if (keepEdits) base.favorite else o.optBoolean("favorite", base.favorite),
                sync = Sync.SYNCED,
                onServer = true,
                serverThumb = o.optBoolean("hasThumb"),
                sha256 = o.sOrNull("sha256") ?: base.sha256,
                updatedAt = o.optLong("updatedAt"),
                error = null,
            )
        }
    }
}
