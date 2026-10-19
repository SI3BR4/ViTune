package app.vitune.android.ui.screens.settings

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.vitune.android.Database
import app.vitune.android.MainActivity
import app.vitune.android.R
import app.vitune.android.internal
import app.vitune.android.models.Playlist
import app.vitune.android.models.SongPlaylistMap
import app.vitune.android.utils.asMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object SpotifyImportJobs {
    const val NAME = "spotify-csv-import"
    private fun file(context: Context, id: UUID, state: Boolean): File {
        val directory = File(context.noBackupFilesDir, "spotify-import").apply { mkdirs() }
        return File(directory, id.toString() + if (state) "-state.json" else "-input.json")
    }
    fun write(context: Context, id: UUID, value: JSONObject, state: Boolean) {
        val atomic = AtomicFile(file(context, id, state))
        val stream = atomic.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    fun read(context: Context, id: UUID, state: Boolean): JSONObject = runCatching {
        AtomicFile(file(context, id, state)).openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    }.getOrElse { JSONObject() }
    fun lastId(context: Context): UUID? = runCatching {
        UUID.fromString(context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString("last", null))
    }.getOrNull()
    suspend fun enqueue(context: Context, files: List<SpotifyImportFile>): UUID = withContext(Dispatchers.IO) {
        val manager = WorkManager.getInstance(context)
        val active = manager.getWorkInfosForUniqueWork(NAME).get().firstOrNull { !it.state.isFinished }
        if (active != null) return@withContext active.id
        lastId(context)?.let { previous ->
            if (manager.getWorkInfoById(previous).get()?.state?.isFinished == true) {
                AtomicFile(file(context, previous, false)).delete()
                AtomicFile(file(context, previous, true)).delete()
            }
        }
        val worker = OneTimeWorkRequestBuilder<SpotifyImportWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        val entries = JSONArray()
        files.forEach { file -> file.csv.tracks.forEach { track ->
            entries.put(JSONObject().put("file", file.name).put("playlist", file.playlistName ?: JSONObject.NULL)
                .put("title", track.title).put("artists", track.artists).put("duration", track.durationMs ?: JSONObject.NULL))
        } }
        write(context, worker.id, JSONObject().put("tracks", entries), false)
        write(context, worker.id, JSONObject().put("cursor", 0).put("imported", 0).put("existing", 0).put("missed", JSONArray()), true)
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, worker).result.get()
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString("last", worker.id.toString()).apply()
        worker.id
    }
    fun summary(context: Context, id: UUID): String {
        val state = read(context, id, true)
        val total = read(context, id, false).optJSONArray("tracks")?.length() ?: 0
        val imported = state.optInt("imported")
        val existing = state.optInt("existing")
        val missed = state.optJSONArray("missed")?.length() ?: 0
        val message = state.optString("message")
        return context.getString(R.string.spotify_import_summary, imported, existing, missed,
            (total - state.optInt("cursor")).coerceAtLeast(0)) + if (message.isBlank()) "" else "\n" + message
    }
    fun failures(context: Context, id: UUID): List<String> {
        val tracks = read(context, id, false).optJSONArray("tracks") ?: return emptyList()
        val missed = read(context, id, true).optJSONArray("missed") ?: return emptyList()
        return (0 until missed.length()).mapNotNull { index ->
            tracks.optJSONObject(missed.optInt(index, -1))?.let {
                it.optString("file") + ": " + it.optString("title") + " — " + it.optString("artists")
            }
        }
    }
}

class SpotifyImportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val tracks = SpotifyImportJobs.read(applicationContext, id, false).optJSONArray("tracks") ?: return@withContext Result.failure()
        val state = SpotifyImportJobs.read(applicationContext, id, true)
        var cursor = state.optInt("cursor")
        var imported = state.optInt("imported")
        var existing = state.optInt("existing")
        val missed = state.optJSONArray("missed") ?: JSONArray()
        var errors = 0
        try {
            setForeground(notification(applicationContext.getString(R.string.spotify_import_reading), cursor, tracks.length()))
            while (cursor < tracks.length()) {
                val entry = tracks.getJSONObject(cursor)
                val track = SpotifyTrack(entry.getString("title"), entry.getString("artists"),
                    if (entry.isNull("duration")) null else entry.getLong("duration"))
                val label = entry.getString("file") + "\n" + applicationContext.getString(R.string.spotify_import_progress, cursor + 1, tracks.length(), track.title)
                setProgress(workDataOf("status" to label.take(1500), "cursor" to cursor, "total" to tracks.length()))
                setForeground(notification(label, cursor, tracks.length()))
                try {
                    val match = findSpotifyTrack(track)
                    if (match == null) missed.put(cursor) else {
                        val media = match.asMediaItem
                        if (entry.isNull("playlist")) {
                            if (Database.song(media.mediaId).first()?.likedAt != null) existing++ else {
                                Database.internal.runInTransaction {
                                    Database.insert(media)
                                    Database.like(media.mediaId, System.currentTimeMillis())
                                }
                                imported++
                            }
                        } else {
                            var added = false
                            Database.internal.runInTransaction {
                                val name = entry.getString("playlist")
                                val playlistId = Database.localPlaylistByName(name)?.id ?: Database.insert(Playlist(name = name))
                                check(playlistId > 0)
                                if (!Database.playlistContainsSong(playlistId, media.mediaId)) {
                                    Database.insert(media)
                                    Database.insert(SongPlaylistMap(media.mediaId, playlistId, Database.lastPlaylistPosition(playlistId) + 1))
                                    added = true
                                }
                            }
                            if (added) imported++ else existing++
                        }
                    }
                    errors = 0
                    cursor++
                    state.put("cursor", cursor).put("imported", imported).put("existing", existing).put("missed", missed).put("message", "")
                    SpotifyImportJobs.write(applicationContext, id, state, true)
                    delay(350)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    errors++
                    if (errors >= 3) {
                        state.put("message", applicationContext.getString(R.string.spotify_import_network_error))
                        SpotifyImportJobs.write(applicationContext, id, state, true)
                        return@withContext if (runAttemptCount < 3) Result.retry() else Result.failure()
                    }
                    delay(2000)
                }
            }
            state.put("message", applicationContext.getString(R.string.spotify_import_finished))
            SpotifyImportJobs.write(applicationContext, id, state, true)
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            state.put("message", applicationContext.getString(R.string.spotify_import_network_error))
            SpotifyImportJobs.write(applicationContext, id, state, true)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun notification(text: String, progress: Int, total: Int): ForegroundInfo {
        val channel = "spotify-import"
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channel,
            applicationContext.getString(R.string.spotify_import_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 317,
            Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(R.drawable.musical_notes).setContentTitle(applicationContext.getString(R.string.spotify_import_title))
            .setContentText(text.replace('\n', ' ')).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(total, progress, false).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, applicationContext.getString(R.string.cancel),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(7317, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(7317, notification)
    }
}
