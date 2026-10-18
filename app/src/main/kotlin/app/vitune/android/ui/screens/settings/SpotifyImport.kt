package app.vitune.android.ui.screens.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vitune.android.Database
import app.vitune.android.R
import app.vitune.android.internal
import app.vitune.android.ui.components.themed.DefaultDialog
import app.vitune.android.ui.components.themed.DialogTextButton
import app.vitune.android.utils.asMediaItem
import app.vitune.android.utils.toast
import app.vitune.core.ui.LocalAppearance
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.bodies.SearchBody
import app.vitune.providers.innertube.requests.searchPage
import app.vitune.providers.innertube.utils.from
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal data class SpotifyTrack(val title: String, val artists: String, val durationMs: Long?)
internal data class SpotifyCsv(val tracks: List<SpotifyTrack>, val duplicates: Int, val invalid: Int)

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
    val titleColumn = headers.indexOf("track name")
    val artistColumn = headers.indexOf("artist name(s)")
    val durationColumn = headers.indexOf("duration (ms)")
    val uriColumn = headers.indexOf("track uri")
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

private suspend fun findSpotifyTrack(track: SpotifyTrack): Innertube.SongItem? {
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
    val scope = rememberCoroutineScope()
    var csv by remember { mutableStateOf<SpotifyCsv?>(null) }
    var visible by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var failures by remember { mutableStateOf<List<String>>(emptyList()) }
    var job by remember { mutableStateOf<Job?>(null) }
    val title = stringResource(R.string.spotify_import_title)
    val description = stringResource(R.string.spotify_import_description)
    val invalidFile = stringResource(R.string.spotify_import_invalid)
    val progressFormat = stringResource(R.string.spotify_import_progress)
    val summaryFormat = stringResource(R.string.spotify_import_summary)
    val cancelled = stringResource(R.string.spotify_import_cancelled)
    val networkError = stringResource(R.string.spotify_import_network_error)
    val previewFormat = stringResource(R.string.spotify_import_preview)
    val pickerError = stringResource(R.string.no_file_chooser_installed)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            reading = true
            visible = true
            status = context.getString(R.string.spotify_import_reading)
            csv = null
            failures = emptyList()
            scope.launch {
                try {
                    csv = withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { input -> val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var count = input.read(buffer); while (count >= 0) { require(output.size() + count <= 10 * 1024 * 1024); output.write(buffer, 0, count); count = input.read(buffer) }; output.toByteArray() }
                            ?: error("Cannot read file")
                        require(bytes.size <= 10 * 1024 * 1024)
                        val charset = when {
                            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
                            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
                            else -> Charsets.UTF_8
                        }
                        parseSpotifyCsv(bytes.toString(charset))
                    }
                    val parsed = csv!!
                    status = String.format(previewFormat, parsed.tracks.size, parsed.duplicates, parsed.invalid)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { status = invalidFile }
                finally { reading = false }
            }
        }
    }
    SettingsGroup(title = title) {
        SettingsEntry(title = title, text = description, isEnabled = !running && !reading, onClick = {
            try { launcher.launch(arrayOf("*/*")) }
            catch (_: ActivityNotFoundException) { context.toast(pickerError) }
        })
    }
    if (visible) DefaultDialog(onDismiss = { if (!running && !reading) visible = false }) {
        val (_, typography) = LocalAppearance.current
        BasicText(text = title, style = typography.s)
        Spacer(Modifier.height(12.dp))
        BasicText(text = status, style = typography.xs)
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
        if (!running && !reading && pending != null) DialogTextButton(
            text = stringResource(R.string.spotify_import_start), primary = true, onClick = {
                csv = null
                running = true
                job = scope.launch {
                    var imported = 0
                    var existing = 0
                    var processed = 0
                    var errors = 0
                    val missed = mutableListOf<String>()
                    var reason = ""
                    try {
                        for (track in pending.tracks) {
                            status = String.format(progressFormat, processed + 1, pending.tracks.size, track.title)
                            try {
                                val outcome = withContext(Dispatchers.IO) {
                                    val match = findSpotifyTrack(track)
                                    if (match == null) 0 else {
                                        val media = match.asMediaItem
                                        if (Database.song(media.mediaId).first()?.likedAt != null) 2 else {
                                            Database.internal.runInTransaction {
                                                Database.insert(media)
                                                Database.like(media.mediaId, System.currentTimeMillis())
                                            }
                                            1
                                        }
                                    }
                                }
                                errors = 0
                                when (outcome) { 1 -> imported++; 2 -> existing++; else -> missed.add(track.title + " — " + track.artists) }
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) {
                                errors++
                                missed.add(track.title + " — " + track.artists)
                                if (errors >= 3) { reason = networkError; break }
                                delay(2000)
                            }
                            processed++
                            delay(350)
                        }
                    } catch (_: CancellationException) { reason = cancelled }
                    finally {
                        failures = missed.toList()
                        status = String.format(summaryFormat, imported, existing, missed.size, pending.tracks.size - imported - existing - missed.size) +
                            if (reason.isBlank()) "" else "\n" + reason
                        running = false
                    }
                }
            }
        )
        if (!reading) DialogTextButton(
            text = stringResource(if (running) R.string.cancel else R.string.done),
            onClick = { if (running) job?.cancel() else visible = false }
        )
    }
}
