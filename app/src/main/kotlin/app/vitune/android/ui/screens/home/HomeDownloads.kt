package app.vitune.android.ui.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.vitune.core.ui.LocalAppearance
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import app.vitune.android.Database
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.service.PlayerService
import app.vitune.android.service.isLocal
import app.vitune.core.data.enums.SongSortBy
import app.vitune.core.data.enums.SortOrder
import app.vitune.core.ui.utils.enumSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*

/** The existing manager's persistent index, not the playback cache or MediaStore. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun HomeDownloads(onSearchClick: () -> Unit) {
    val (palette) = LocalAppearance.current
    val context = LocalContext.current.applicationContext
    val binder = LocalPlayerServiceBinder.current
    var sortBy by rememberSaveable(stateSaver = enumSaver()) { mutableStateOf(SongSortBy.DateAdded) }
    var sortOrder by rememberSaveable(stateSaver = enumSaver()) { mutableStateOf(SortOrder.Descending) }
    val provider = remember(context, binder, sortBy, sortOrder) {
        {
            val completedIds = flow {
                val databaseProvider = PlayerService.createDatabaseProvider(context)
                try {
                    val index = DefaultDownloadIndex(databaseProvider)
                    while (currentCoroutineContext().isActive) {
                        val ids = mutableSetOf<String>()
                        index.getDownloads(Download.STATE_COMPLETED).use { cursor ->
                            while (cursor.moveToNext()) ids += cursor.download.request.id
                        }
                        emit(ids)
                        delay(2000)
                    }
                } finally {
                    databaseProvider.close()
                }
            }.flowOn(Dispatchers.IO)
            combine(
                Database.songsWithContentLength(sortBy, sortOrder),
                completedIds
            ) { songs, ids ->
                songs.filter { it.song.id in ids && !it.song.isLocal && binder?.isCached(it) == true }
                    .map { it.song }
            }.flowOn(Dispatchers.IO)
        }
    }
    HomeSongs(
        onSearchClick = onSearchClick,
        songProvider = provider,
        sortBy = sortBy,
        setSortBy = { sortBy = it },
        sortOrder = sortOrder,
        setSortOrder = { sortOrder = it },
        title = stringResource(R.string.navigation_downloads),
        persistKey = "home/downloads",
        showHeaderTitle = false,
        headerDescription = stringResource(R.string.navigation_downloaded_by_app),
        headerDetail = stringResource(R.string.navigation_available_offline),
        showDuration = false,
        songTrailingContent = {
            Box(Modifier.size(24.dp).background(palette.accent, CircleShape), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.navigation_check),
                    stringResource(R.string.navigation_available_offline),
                    colorFilter = ColorFilter.tint(palette.onAccent), modifier = Modifier.size(16.dp))
            }
        }
    )
}
