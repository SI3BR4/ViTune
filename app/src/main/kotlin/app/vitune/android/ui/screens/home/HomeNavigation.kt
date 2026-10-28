package app.vitune.android.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.vitune.android.utils.medium
import app.vitune.android.R
import app.vitune.android.ui.screens.searchRoute
import app.vitune.core.ui.LocalAppearance
import kotlinx.coroutines.launch

/** IDs 0..6 retain the meaning of the existing home tabs. No library migration. */
internal enum class HomeDestination(val id: Int, val title: Int, val icon: Int) {
    Home(0, R.string.navigation_home, R.drawable.navigation_home),
    Explore(1, R.string.navigation_explore, R.drawable.globe),
    Songs(2, R.string.songs, R.drawable.musical_notes),
    Playlists(3, R.string.playlists, R.drawable.playlist),
    Artists(4, R.string.artists, R.drawable.person),
    Albums(5, R.string.albums, R.drawable.disc),
    Local(6, R.string.navigation_local, R.drawable.navigation_folder),
    History(7, R.string.history, R.drawable.history),
    Favorites(8, R.string.favorites, R.drawable.heart),
    Downloads(9, R.string.navigation_downloads, R.drawable.navigation_downloads),
    Settings(10, R.string.navigation_settings, R.drawable.settings);

    companion object {
        val primary = listOf(Home, Explore, Downloads, Settings)
        val library = listOf(History, Favorites, Playlists, Artists, Albums, Local, Songs)
    }
}

internal class HomeNavigationState(
    val selected: MutableState<Int>,
    val generation: MutableState<Int>,
    val drawer: DrawerState
) {
    val destination get() = HomeDestination.entries.first { it.id == selected.value }
    // Content height only. System navigation padding is accounted for separately.
    var barHeight by mutableStateOf(72.dp)
    var toolbarHeight by mutableStateOf(80.dp)
    fun select(destination: HomeDestination) {
        selected.value = destination.id
        generation.value++
    }
}

@Composable
internal fun rememberHomeNavigationState(): HomeNavigationState {
    val selected = rememberSaveable { mutableStateOf(HomeDestination.Home.id) }
    val generation = rememberSaveable { mutableStateOf(0) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    return remember(selected, generation, drawer) { HomeNavigationState(selected, generation, drawer) }
}

@Composable
internal fun HomeNavigationHost(
    state: HomeNavigationState,
    chromeVisible: Boolean,
    content: @Composable BoxScope.() -> Unit
) {
    val (palette, typography) = LocalAppearance.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val menuVisible = app.vitune.android.ui.components.LocalMenuState.current.isDisplayed
    // Register before nested route/player handlers: those consume Back first.
    BackHandler(enabled = state.destination != HomeDestination.Home && !state.drawer.isOpen) {
        state.select(HomeDestination.Home)
    }
    ModalNavigationDrawer(
        drawerState = state.drawer,
        gesturesEnabled = state.drawer.isOpen,
        scrimColor = palette.background0.copy(alpha = 0.65f),
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = palette.background1,
                drawerContentColor = palette.text,
                modifier = Modifier.widthIn(max = 340.dp).fillMaxHeight()
            ) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText(stringResource(R.string.navigation_library), style = typography.m.copy(color = palette.text),
                            modifier = Modifier.weight(1f).padding(12.dp))
                        NavigationIcon(R.drawable.close, stringResource(R.string.navigation_close_menu)) {
                            scope.launch { state.drawer.close() }
                        }
                    }
                    HomeDestination.library.forEach { destination ->
                        val label = stringResource(destination.title)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .background(if (state.destination == destination) palette.background2 else palette.background1)
                                .selectable(state.destination == destination, role = Role.Tab, onClick = {
                                    state.select(destination)
                                    scope.launch { state.drawer.close() }
                                }).padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Image(painterResource(destination.icon), null, colorFilter = ColorFilter.tint(palette.text),
                                modifier = Modifier.size(24.dp))
                            BasicText(label, style = typography.s.copy(color = palette.text))
                        }
                    }
                }
            }
        }
    ) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (chromeVisible && !menuVisible) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(palette.background1).navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().onSizeChanged {
                        state.barHeight = with(density) { it.height.toDp() }
                    }.heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
                        HomeDestination.primary.forEach { destination ->
                            val selected = destination == state.destination
                            val color = if (selected) palette.onAccent else palette.text
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                                modifier = Modifier.weight(1f).heightIn(min = 72.dp)
                                    .selectable(selected, role = Role.Tab, onClick = { state.select(destination) })
                                    .padding(horizontal = 2.dp, vertical = 8.dp)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                        .background(if (selected) palette.accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(18.dp))
                                        .padding(horizontal = 4.dp, vertical = 6.dp)
                                ) {
                                    Image(painterResource(destination.icon), null, colorFilter = ColorFilter.tint(color),
                                        modifier = Modifier.size(24.dp))
                                    BasicText(stringResource(destination.title), style = typography.xxs.copy(color = color))
                                }
                            }
                        }
                    }
                }
                // Leave the Android system Back gesture region untouched. Only a narrow edge
                // strip handles drawer opening; horizontal carousels elsewhere keep their gestures.
                val gestureLeft = with(density) { WindowInsets.systemGestures.getLeft(density, LocalLayoutDirection.current).toDp() }
                val toolbarBottom = state.toolbarHeight
                Box(Modifier.align(Alignment.CenterStart).padding(start = gestureLeft)
                    .padding(top = toolbarBottom, bottom = state.barHeight + 96.dp)
                    .width(20.dp).fillMaxHeight()
                    .pointerInput(state.drawer, density) {
                        var distance = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { distance = 0f },
                            onDragCancel = { distance = 0f },
                            onDragEnd = {
                                if (distance > with(density) { 48.dp.toPx() })
                                    scope.launch { state.drawer.open() }
                            }
                        ) { change, amount -> distance += amount; change.consume() }
                    })
            }
        }
    }
    // Drawer Back takes precedence even over player/route Back handlers.
    BackHandler(enabled = state.drawer.isOpen) { scope.launch { state.drawer.close() } }
}

@Composable
internal fun HomeToolbar(state: HomeNavigationState) {
    val (palette, typography) = LocalAppearance.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    Row(Modifier.fillMaxWidth().onSizeChanged { state.toolbarHeight = with(density) { it.height.toDp() } }
        .background(palette.background0).statusBarsPadding()
        .heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        NavigationIcon(R.drawable.navigation_menu, stringResource(R.string.navigation_open_menu)) {
            scope.launch { state.drawer.open() }
        }
        BasicText(stringResource(if (state.destination == HomeDestination.Home) R.string.quick_picks else state.destination.title), style = typography.xxl.medium.copy(color = palette.text),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        NavigationIcon(R.drawable.search, stringResource(R.string.navigation_search)) { scope.launch { searchRoute.ensureGlobal("") } }
    }
}

@Composable
private fun NavigationIcon(icon: Int, label: String, onClick: () -> Unit) {
    val (palette) = LocalAppearance.current
    Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Image(painterResource(icon), label, colorFilter = ColorFilter.tint(palette.text),
            modifier = Modifier.size(24.dp))
    }
}
