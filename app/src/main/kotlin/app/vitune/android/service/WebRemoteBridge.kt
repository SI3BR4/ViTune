package app.vitune.android.service

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import androidx.media3.common.Player
import app.vitune.android.Database
import app.vitune.android.internal
import app.vitune.android.models.Song
import app.vitune.android.utils.asMediaItem
import app.vitune.android.utils.forceSeekToNext
import app.vitune.android.utils.forceSeekToPrevious
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Personal, opt-in LAN remote; TLS trust is restricted to the paired computer. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class WebRemoteBridge(private val context: Context, private val player: Player, private val scope: CoroutineScope) {
    companion object {
        const val DEFAULT_URL = "https://vitune.local:4180"
        private val videoId = Regex("[A-Za-z0-9_-]{11}")
        private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "vitune-web-connection.json"))
        fun connection(context: Context): JSONObject? = runCatching {
            file(context).openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        }.getOrNull()
        fun disconnect(context: Context) { file(context).delete() }
        private fun save(context: Context, data: JSONObject) {
            val target = file(context)
            val output = target.startWrite()
            try { output.write(data.toString().toByteArray()); target.finishWrite(output) }
            catch (e: Exception) { target.failWrite(output); throw e }
        }
        private fun request(base: String, path: String, body: JSONObject, token: String? = null): JSONObject {
            // No arbitrary servers, cleartext fallback, custom hostname verifier or trust-all TLS.
            require(base == DEFAULT_URL) { "Use o endereço mostrado no computador." }
            val connection = URL(base + path).openConnection() as HttpsURLConnection
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 6000
                connection.readTimeout = 6000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
                val ok = connection.responseCode in 200..299
                val stream = if (ok) connection.inputStream else connection.errorStream
                val data = stream?.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) } ?: JSONObject()
                check(ok) { data.optString("error", "Não foi possível conectar.") }
                return data
            } finally { connection.disconnect() }
        }
        suspend fun pair(context: Context, base: String, code: String) = withContext(Dispatchers.IO) {
            require(Regex("[0-9]{6}").matches(code)) { "Digite o código de 6 números." }
            val result = request(base, "/device/pair", JSONObject().put("code", code).put("name", Build.MODEL))
            save(context, JSONObject().put("url", base).put("token", result.getString("token")))
        }
    }

    fun start() = scope.launch(Dispatchers.IO) {
        var lastToken = ""
        var ack = 0L
        var cycle = 0
        while (isActive) {
            val config = connection(context)
            if (config != null) try {
                val token = config.getString("token")
                if (token != lastToken) { lastToken = token; ack = 0; cycle = 0 }
                val state = withContext(Dispatchers.Main) {
                    val media = player.currentMediaItem
                    val song = media?.let {
                        JSONObject().put("id", it.mediaId)
                            .put("title", it.mediaMetadata.title?.toString().orEmpty())
                            .put("artist", it.mediaMetadata.artist?.toString().orEmpty())
                    }
                    JSONObject().put("song", song ?: JSONObject.NULL)
                        .put("playing", player.isPlaying)
                        .put("position", player.currentPosition.coerceAtLeast(0) / 1000.0)
                        .put("duration", player.duration.coerceAtLeast(0) / 1000.0)
                        .put("volume", (player.volume * 100).toInt())
                        .put("shuffle", player.shuffleModeEnabled)
                        .put("repeat", player.repeatMode == Player.REPEAT_MODE_ONE)
                }
                val songId = state.optJSONObject("song")?.optString("id")
                state.put("liked", songId?.let { Database.likedAt(it).first() != null } ?: false)
                val body = JSONObject().put("ack", ack).put("state", state)
                if (cycle++ % 8 == 0) {
                    val favorites = JSONArray()
                    Database.favoritesByLikedAtDesc().first().take(10000).forEach {
                        if (videoId.matches(it.id)) favorites.put(JSONObject().put("id", it.id)
                            .put("title", it.title).put("artist", it.artistsText.orEmpty())
                            .put("durationText", it.durationText.orEmpty()))
                    }
                    body.put("favorites", favorites)
                }
                val response = request(config.getString("url"), "/device/poll", body, token)
                val commands = response.optJSONArray("commands") ?: JSONArray()
                for (index in 0 until commands.length()) {
                    val command = commands.getJSONObject(index)
                    val id = command.getLong("id")
                    if (id <= ack) continue
                    applyCommand(command)
                    ack = id
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The next poll retries; the website shows the device as offline. */ }
            delay(2000)
        }
    }

    private suspend fun applyCommand(command: JSONObject) {
        val data = command.optJSONObject("data") ?: JSONObject()
        when (command.optString("action")) {
            "favorite" -> {
                val id = data.optString("id")
                if (!videoId.matches(id)) return
                Database.like(id, if (data.optBoolean("liked")) System.currentTimeMillis() else null)
            }
            "playTrack" -> {
                val dataSong = data.optJSONObject("song") ?: return
                val id = dataSong.optString("id")
                if (!videoId.matches(id)) return
                val song = Database.song(id).first() ?: Song(
                    id = id, title = dataSong.optString("title").take(160).ifBlank { "Música" },
                    artistsText = dataSong.optString("artist").take(160), durationText = null,
                    thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                )
                Database.internal.runInTransaction { Database.insert(song) }
                withContext(Dispatchers.Main) {
                    player.setMediaItem(song.asMediaItem)
                    player.prepare()
                    player.play()
                }
            }
            else -> withContext(Dispatchers.Main) {
                when (command.optString("action")) {
                    "setPlaying" -> if (data.optBoolean("playing")) player.play() else player.pause()
                    "next" -> player.forceSeekToNext()
                    "previous" -> player.forceSeekToPrevious()
                    "seek" -> player.seekTo((data.optDouble("position", 0.0).coerceIn(0.0, 86400.0) * 1000).toLong())
                    "volume" -> player.volume = (data.optInt("volume", 70).coerceIn(0, 100) / 100f)
                    "shuffle" -> player.shuffleModeEnabled = data.optBoolean("enabled")
                    "repeat" -> player.repeatMode = if (data.optBoolean("enabled")) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                }
            }
        }
    }
}
