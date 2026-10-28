package app.vitune.android.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.res.stringResource
import app.vitune.android.R
import app.vitune.android.ui.components.themed.LocalScreenHeading
import app.vitune.android.ui.components.themed.LocalScreenSearchAvailable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import app.vitune.android.LocalPlayerAwareWindowInsets
import app.vitune.core.data.enums.BuiltInPlaylist
import app.vitune.android.ui.screens.builtinplaylist.BuiltInPlaylistSongs
import app.vitune.android.ui.screens.settings.SettingsScreen
import app.vitune.android.models.toUiMood
import app.vitune.android.ui.screens.GlobalRoutes
import app.vitune.android.ui.screens.Route
import app.vitune.android.ui.screens.albumRoute
import app.vitune.android.ui.screens.artistRoute
import app.vitune.android.ui.screens.builtInPlaylistRoute
import app.vitune.android.ui.screens.builtinplaylist.BuiltInPlaylistScreen
import app.vitune.android.ui.screens.localPlaylistRoute
import app.vitune.android.ui.screens.localplaylist.LocalPlaylistScreen
import app.vitune.android.ui.screens.mood.MoodScreen
import app.vitune.android.ui.screens.mood.MoreAlbumsScreen
import app.vitune.android.ui.screens.mood.MoreMoodsScreen
import app.vitune.android.ui.screens.moodRoute
import app.vitune.android.ui.screens.pipedPlaylistRoute
import app.vitune.android.ui.screens.playlistRoute
import app.vitune.android.ui.screens.searchRoute
import app.vitune.compose.persist.PersistMapCleanup
import app.vitune.compose.routing.Route as NavigationRoute
import app.vitune.compose.routing.Route0
import app.vitune.compose.routing.RouteHandler

private val moreMoodsRoute = Route0("moreMoodsRoute")
private val moreAlbumsRoute = Route0("moreAlbumsRoute")

@Route
@Composable
internal fun HomeScreen(navigation: HomeNavigationState) {
    val saveableStateHolder = rememberSaveableStateHolder()
    PersistMapCleanup("home/")

    RouteHandler {
            SideEffect { navigation.closeRoute = { replace(null) } }
            fun openHomeRoute(route: NavigationRoute, vararg parameters: Any?) {
                parameters.forEachIndexed { index, value -> args[index] = value }
                replace(route)
            }
            GlobalRoutes()
    
            localPlaylistRoute { playlistId ->
                LocalPlaylistScreen(playlistId = playlistId)
            }
    
            builtInPlaylistRoute { builtInPlaylist ->
                BuiltInPlaylistScreen(builtInPlaylist = builtInPlaylist)
            }
    
            moodRoute { mood ->
                MoodScreen(mood = mood)
            }
    
            moreMoodsRoute {
                MoreMoodsScreen()
            }
    
            moreAlbumsRoute {
                MoreAlbumsScreen()
            }
    
                Content {
                Column {
                    HomeToolbar(navigation)
                    val insets = LocalPlayerAwareWindowInsets.current
                    CompositionLocalProvider(
                        LocalPlayerAwareWindowInsets provides insets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                        LocalScreenHeading provides stringResource(when (navigation.destination) {
                            HomeDestination.Home -> R.string.quick_picks
                            HomeDestination.Explore -> R.string.discover
                            HomeDestination.Local -> R.string.local
                            else -> navigation.destination.title
                        }),
                        LocalScreenSearchAvailable provides true
                    ) {
                        val currentTabIndex = navigation.destination.id
                        saveableStateHolder.SaveableStateProvider(key = currentTabIndex) {
                            val onSearchClick = { openHomeRoute(searchRoute, "") }
                            when (currentTabIndex) {
                                7 -> BuiltInPlaylistSongs(BuiltInPlaylist.History)
                                8 -> BuiltInPlaylistSongs(BuiltInPlaylist.Favorites)
                                9 -> HomeDownloads(onSearchClick)
                                10 -> SettingsScreen(onBack = { navigation.select(HomeDestination.Home) })
                                0 -> QuickPicks(
                                    onAlbumClick = { openHomeRoute(albumRoute, it.key) },
                                    onArtistClick = { openHomeRoute(artistRoute, it.key) },
                                    onPlaylistClick = {
                                        openHomeRoute(playlistRoute, it.key, null, null, it.channel?.name == "YouTube Music")
                                    },
                                    onSearchClick = onSearchClick,
                                    showHeader = false
                                )
        
                                1 -> HomeDiscovery(
                                    onMoodClick = { mood -> openHomeRoute(moodRoute, mood.toUiMood()) },
                                    onNewReleaseAlbumClick = { openHomeRoute(albumRoute, it) },
                                    onSearchClick = onSearchClick,
                                    onMoreMoodsClick = { openHomeRoute(moreMoodsRoute) },
                                    onMoreAlbumsClick = { openHomeRoute(moreAlbumsRoute) },
                                    onPlaylistClick = { openHomeRoute(playlistRoute, it, null, null, true) }
                                )
        
        
                                3 -> HomePlaylists(
                                    onBuiltInPlaylist = { openHomeRoute(builtInPlaylistRoute, it) },
                                    onPlaylistClick = { openHomeRoute(localPlaylistRoute, it.id) },
                                    onPipedPlaylistClick = { session, playlist ->
                                        openHomeRoute(pipedPlaylistRoute, session.apiBaseUrl.toString(), session.token, playlist.id.toString())
                                    },
                                    onSearchClick = onSearchClick
                                )
        
                                4 -> HomeArtistList(
                                    onArtistClick = { openHomeRoute(artistRoute, it.id) },
                                    onSearchClick = onSearchClick
                                )
        
                                5 -> HomeAlbums(
                                    onAlbumClick = { openHomeRoute(albumRoute, it.id) },
                                    onSearchClick = onSearchClick
                                )
        
                                6 -> HomeLocalSongs(
                                    onSearchClick = onSearchClick
                                )
                            }
                        }
                    }
                }
            }
        }
}
