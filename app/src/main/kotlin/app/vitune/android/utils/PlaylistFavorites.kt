package app.vitune.android.utils

import androidx.media3.common.MediaItem
import app.vitune.android.Database
import app.vitune.android.internal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only songs without a like date are updated, preserving existing favorites and order. */
suspend fun addPlaylistToFavorites(items: List<MediaItem>): Int = withContext(Dispatchers.IO) {
    val unique = items.distinctBy { it.mediaId }
    var added = 0
    Database.internal.runInTransaction {
        unique.forEach { Database.insert(it) }
        val now = System.currentTimeMillis()
        unique.map { it.mediaId }.chunked(400).forEach { added += Database.likeUnlikedSongs(it, now) }
    }
    added
}
