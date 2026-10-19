package app.vitune.android.ui.screens.settings

import android.content.ActivityNotFoundException
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.work.WorkManager
import kotlinx.coroutines.flow.flowOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vitune.android.R
import app.vitune.android.ui.components.themed.DefaultDialog
import app.vitune.android.ui.components.themed.DialogTextButton
import app.vitune.android.utils.toast
import app.vitune.core.ui.LocalAppearance
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.bodies.SearchBody
import app.vitune.providers.innertube.requests.searchPage
import app.vitune.providers.innertube.utils.from
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal data class SpotifyTrack(val title: String, val artists: String, val durationMs: Long?)
internal data class SpotifyCsv(val tracks: List<SpotifyTrack>, val duplicates: Int, val invalid: Int)
internal data class SpotifyImportFile(val name: String, val playlistName: String?, val csv: SpotifyCsv)

/** Exportify uses liked.csv for liked songs; other exports keep their playlist filename. */
internal fun spotifyPlaylistName(fileName: String): String? {
    val name = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
    val stem = if (name.endsWith(".csv", ignoreCase = true)) name.dropLast(4).trim() else name
    if (Regex("liked(?:\\s*\\([0-9]+\\))?", RegexOption.IGNORE_CASE).matches(stem)) return null
    return stem.ifBlank { "Spotify" }.take(200)
}

// RFC 4180 fields, including escaped quotes, delimiters and line breaks inside quotes.
internal fun parseSpotifyCsv(source: String): SpotifyCsv {
    require(source.length <= 10 * 1024 * 1024) { "CSV too large" }
    val text = source.removePrefix("\uFEFF")
    val headerLine = text.lineSequence().firstOrNull().orEmpty()
    val separator = if (headerLine.count { it == ';' } > headerLine.count { it == ',' }) ';' else ','
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var closed = false
    var index = 0
    fun finishField() {
        row.add(field.toString().trim())
        field.setLength(0)
        closed = false
    }
    fun finishRow() {
        finishField()
        if (row.any { it.isNotBlank() }) rows.add(row)
        row = mutableListOf()
        require(rows.size <= 10001) { "Too many tracks" }
    }
    while (index < text.length) {
        val c = text[index]
        if (quoted) {
            if (c == '"') {
                if (text.getOrNull(index + 1) == '"') { field.append('"'); index++ }
                else { quoted = false; closed = true }
            } else field.append(c)
        } else when (c) {
            '"' -> { require(field.isEmpty() && !closed) { "Invalid CSV quote" }; quoted = true }
            separator -> finishField()
            '\r', '\n' -> { finishRow(); if (c == '\r' && text.getOrNull(index + 1) == '\n') index++ }
            else -> { require(!closed || c.isWhitespace()) { "Invalid CSV field" }; field.append(c) }
        }
        require(field.length <= 16384 && row.size <= 100) { "CSV field too large" }
        index++
    }
    require(!quoted) { "Unclosed CSV quote" }
    if (field.isNotEmpty() || row.isNotEmpty() || closed) finishRow()
    require(rows.isNotEmpty()) { "Empty CSV" }
    val headers = rows.first().map { it.lowercase(Locale.ROOT) }
    fun column(vararg names: String) = headers.indexOfFirst { it in names }
    val titleColumn = column("track name", "nome da faixa")
    val artistColumn = column("artist name(s)", "nome(s) do artista")
    val durationColumn = column("duration (ms)", "duração da faixa (ms)")
    val uriColumn = column("track uri", "uri da faixa")
    require(titleColumn >= 0 && artistColumn >= 0) { "Expected Exportify CSV" }
    val tracks = mutableListOf<SpotifyTrack>()
    val keys = mutableSetOf<String>()
    var duplicates = 0
    var invalid = 0
    rows.drop(1).forEach { values ->
        val title = values.getOrNull(titleColumn).orEmpty()
        val artists = values.getOrNull(artistColumn).orEmpty()
        if (values.size != headers.size || title.isBlank() || artists.isBlank()) invalid++
        else {
            val key = values.getOrNull(uriColumn)?.takeIf { it.isNotBlank() }
                ?: (normalizeSpotifyText(title) + "|" + normalizeSpotifyText(artists))
            if (!keys.add(key)) duplicates++
            else tracks.add(SpotifyTrack(title, artists, values.getOrNull(durationColumn)?.toLongOrNull()?.takeIf { it > 0 }))
        }
    }
    require(tracks.isNotEmpty()) { "No valid tracks" }
    return SpotifyCsv(tracks, duplicates, invalid)
}

internal fun normalizeSpotifyText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

private fun similarity(a: String, b: String): Double {
    val left = normalizeSpotifyText(a).split(' ').filter { it.isNotBlank() }.toSet()
    val right = normalizeSpotifyText(b).split(' ').filter { it.isNotBlank() }.toSet()
    if (left.isEmpty() || right.isEmpty()) return 0.0
    return 2.0 * left.intersect(right).size / (left.size + right.size)
}

internal fun spotifyMatchScore(track: SpotifyTrack, title: String, authors: List<String>, seconds: Long?): Double {
    val titleScore = similarity(track.title, title)
    val artistScore = authors.maxOfOrNull { similarity(track.artists.substringBefore(';'), it) } ?: 0.0
    if (titleScore < 0.84 || artistScore < 0.8) return 0.0
    val versions = setOf("live", "remix", "acoustic", "instrumental", "karaoke", "cover", "sped", "slowed")
    val sourceVersions = normalizeSpotifyText(track.title).split(' ').toSet().intersect(versions)
    val resultVersions = normalizeSpotifyText(title).split(' ').toSet().intersect(versions)
    if (sourceVersions != resultVersions) return 0.0
    if (track.durationMs != null && seconds != null && abs(track.durationMs - seconds * 1000) > maxOf(10000L, track.durationMs / 20)) return 0.0
    return titleScore * 0.7 + artistScore * 0.3
}

private fun songSeconds(text: String?): Long? {
    val parts = text?.split(':')?.map { it.toLongOrNull() ?: return null } ?: return null
    if (parts.size !in 2..3 || parts.any { it < 0 }) return null
    return parts.fold(0L) { total, part -> total * 60 + part }
}

internal suspend fun findSpotifyTrack(track: SpotifyTrack): Innertube.SongItem? {
    val songs = withTimeoutOrNull(20000L) {
        Innertube.searchPage(
            body = SearchBody(query = track.title + " " + track.artists.substringBefore(';'), params = Innertube.SearchFilter.Song.value),
            fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
        ).let { it ?: throw java.io.IOException("Search interrupted") }.getOrThrow()?.items.orEmpty()
    } ?: throw java.io.IOException("Search timed out")
    return songs.map { song ->
        song to spotifyMatchScore(track, song.info?.name.orEmpty(), song.authors.orEmpty().mapNotNull { it.name }, songSeconds(song.durationText))
    }.filter { it.second >= 0.88 }.maxByOrNull { it.second }?.first
}

@Composable
fun SpotifyImportSettings() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var csv by remember { mutableStateOf<List<SpotifyImportFile>?>(null) }
    var visible by remember { mutableStateOf(false) }
    val workManager = remember { WorkManager.getInstance(context) }
    var jobId by remember { mutableStateOf(SpotifyImportJobs.lastId(context)) }
    val workInfo by remember(jobId) { jobId?.let { workManager.getWorkInfoByIdFlow(it) } ?: flowOf(null) }.collectAsState(initial = null)
    val running = workInfo?.state?.isFinished == false
    var starting by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var failures by remember { mutableStateOf<List<String>>(emptyList()) }

    val title = stringResource(R.string.spotify_import_title)
    val description = stringResource(R.string.spotify_import_description)
    val invalidFile = stringResource(R.string.spotify_import_invalid)
    val cancelled = stringResource(R.string.spotify_import_cancelled)
    val previewFormat = stringResource(R.string.spotify_import_preview)
    val pickerError = stringResource(R.string.no_file_chooser_installed)
    LaunchedEffect(workInfo?.state, workInfo?.progress) {
        val info = workInfo ?: return@LaunchedEffect
        if (csv != null || reading) return@LaunchedEffect
        if (info.state.isFinished) {
            val result = withContext(Dispatchers.IO) {
                SpotifyImportJobs.summary(context, info.id) to SpotifyImportJobs.failures(context, info.id)
            }
            status = result.first + if (info.state == androidx.work.WorkInfo.State.CANCELLED) "\n" + cancelled else ""
            failures = result.second
        } else {
            status = info.progress.getString("status") ?: context.getString(R.string.spotify_import_waiting)
            status += "\n" + context.getString(R.string.spotify_import_background)
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            reading = true
            visible = true
            status = context.getString(R.string.spotify_import_reading)
            csv = null
            failures = emptyList()
            scope.launch {
                try {
                    val unreadable = mutableListOf<String>()
                    csv = withContext(Dispatchers.IO) {
                        require(uris.size <= 50)
                        var totalBytes = 0
                        var totalTracks = 0
                        uris.mapIndexedNotNull { fileIndex, uri ->
                            val name = runCatching {
                                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                                    if (cursor.moveToFirst()) cursor.getString(0) else null
                                }
                            }.getOrNull().orEmpty().ifBlank { "Spotify ${fileIndex + 1}.csv" }
                            try {
                                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                                    val output = java.io.ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    var count = input.read(buffer)
                                    while (count >= 0) {
                                        require(output.size() + count <= 10 * 1024 * 1024)
                                        output.write(buffer, 0, count)
                                        count = input.read(buffer)
                                    }
                                    output.toByteArray()
                                }
                                    ?: error("Cannot read file")
                                require(bytes.size <= 10 * 1024 * 1024)
                                val charset = when {
                                    bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
                                    bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
                                    else -> Charsets.UTF_8
                                }
                                totalBytes += bytes.size
                                require(totalBytes <= 20 * 1024 * 1024)
                                val parsed = parseSpotifyCsv(bytes.toString(charset))
                                totalTracks += parsed.tracks.size
                                require(totalTracks <= 10000)
                                SpotifyImportFile(name, spotifyPlaylistName(name), parsed)
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { unreadable.add(name); null }
                        }
                    }
                    val parsed = csv!!
                    csv = parsed.takeIf { it.isNotEmpty() }
                    status = if (parsed.isEmpty()) invalidFile else String.format(previewFormat,
                        parsed.sumOf { it.csv.tracks.size }, parsed.sumOf { it.csv.duplicates }, parsed.sumOf { it.csv.invalid }) +
                        "\n\n" + parsed.joinToString("\n") { file ->
                            file.name + " → " + (file.playlistName ?: context.getString(R.string.favorites))
                        }
                    if (unreadable.isNotEmpty()) status += "\n\n" + context.getString(R.string.spotify_import_skipped_files) + "\n" + unreadable.joinToString("\n")
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { status = invalidFile }
                finally { reading = false }
            }
        }
    }
    SettingsGroup(title = title) {
        SettingsEntry(
            title = stringResource(R.string.spotify_export_title),
            text = stringResource(R.string.spotify_export_description),
            onClick = { uriHandler.openUri("https://exportify.app/") }
        )
        if (workInfo != null) SettingsEntry(
            title = stringResource(R.string.spotify_import_status),
            text = if (running) stringResource(R.string.spotify_import_background) else status,
            onClick = { csv = null; visible = true }
        )
        SettingsEntry(title = title, text = description, isEnabled = !running && !reading && !starting, onClick = {
            try { launcher.launch(arrayOf("*/*")) }
            catch (_: ActivityNotFoundException) { context.toast(pickerError) }
        })
    }
    if (visible) DefaultDialog(onDismiss = { if (!reading && !starting) visible = false }) {
        val (_, typography) = LocalAppearance.current
        BasicText(text = title, style = typography.s)
        Spacer(Modifier.height(12.dp))
        BasicText(text = status, style = typography.xs,
            modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
        if (failures.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            BasicText(
                text = stringResource(R.string.spotify_import_unmatched) + "\n" + failures.joinToString("\n"),
                style = typography.xs,
                modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())
            )
        }
        Spacer(Modifier.height(12.dp))
        val pending = csv
        if (!running && !reading && !starting && pending != null) DialogTextButton(
            text = stringResource(R.string.spotify_import_start), primary = true, onClick = {
                csv = null
                starting = true
                scope.launch {
                    try {
                        jobId = SpotifyImportJobs.enqueue(context, pending)
                        status = context.getString(R.string.spotify_import_background)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { status = invalidFile }
                    finally { starting = false }
                }
            }
        )
        if (running) DialogTextButton(text = stringResource(R.string.cancel), onClick = {
            jobId?.let { workManager.cancelWorkById(it) }
        })
        if (!reading && !starting) DialogTextButton(text = stringResource(R.string.done), onClick = { visible = false })
    }
}
