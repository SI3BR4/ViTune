package app.vitune.android.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.vitune.android.utils.medium
import app.vitune.android.R
import app.vitune.android.ui.screens.searchRoute
import app.vitune.core.ui.LocalAppearance
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** IDs 0..6 retain the meaning of the existing home tabs. No library migration. */
internal enum class HomeDestination(val id: Int, val title: Int, val icon: Int) {
    Home(0, R.string.navigation_home, R.drawable.navigation_home),
    Explore(1, R.string.navigation_explore, R.drawable.globe),
    Playlists(3, R.string.playlists, R.drawable.playlist),
    Artists(4, R.string.artists, R.drawable.person),
    Albums(5, R.string.albums, R.drawable.disc),
    Local(6, R.string.navigation_local, R.drawable.navigation_folder),
    History(7, R.string.history, R.drawable.history),
    Favorites(8, R.string.favorites, R.drawable.heart),
    Downloads(9, R.string.navigation_downloads, R.drawable.navigation_downloads),
    Settings(10, R.string.navigation_settings, R.drawable.settings),
    Offline(11, R.string.offline, R.drawable.airplane);

    companion object {
        val primary = listOf(Home, Explore, Downloads, Settings)
        val library = listOf(History, Favorites, Playlists, Offline, Artists, Albums, Local)
    }
}

internal class HomeNavigationState(
    val selected: MutableState<Int>
) {
    val destination get() = HomeDestination.entries.firstOrNull { it.id == selected.value } ?: HomeDestination.Home
    // Content height only. System navigation padding is accounted for separately.
    var barHeight by mutableStateOf(72.dp)
    var toolbarHeight by mutableStateOf(80.dp)
    var closeRoute: (() -> Unit)? = null
    var homeRootVisible by mutableStateOf(false)
    var drawerFraction by mutableFloatStateOf(0f)
    fun select(destination: HomeDestination) {
        closeRoute?.invoke()
        if (selected.value != destination.id) selected.value = destination.id
    }
}

@Composable
internal fun rememberHomeNavigationState(): HomeNavigationState {
    val selected = rememberSaveable { mutableStateOf(HomeDestination.Home.id) }
    return remember(selected) { HomeNavigationState(selected) }
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
    var settleJob by remember { mutableStateOf<Job?>(null) }
    fun settleDrawer(open: Boolean) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(state.drawerFraction, if (open) 1f else 0f, animationSpec = tween(180)) { value, _ ->
                state.drawerFraction = value.coerceIn(0f, 1f)
            }
        }
    }
    BackHandler(enabled = state.destination != HomeDestination.Home && state.drawerFraction == 0f) {
        state.select(HomeDestination.Home)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val drawerWidth = minOf(340.dp, (maxWidth - 48.dp).coerceAtLeast(0.dp))
        val drawerWidthPx = with(density) { drawerWidth.toPx() }.coerceAtLeast(1f)
        content()
        if (chromeVisible && !menuVisible) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(palette.background1).navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().onSizeChanged {
                        state.barHeight = with(density) { it.height.toDp() }
                    }.heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
                        HomeDestination.primary.forEach { destination ->
                            val selected = destination == state.destination
                            val label = stringResource(destination.title)
                            val interaction = remember { MutableInteractionSource() }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                                modifier = Modifier.weight(1f).heightIn(min = 72.dp)
                                    .semantics { contentDescription = label }
                                    .selectable(selected, interactionSource = interaction, indication = null,
                                        role = Role.Tab, onClick = { state.select(destination) })
                                    .padding(horizontal = 1.dp, vertical = 8.dp)
                            ) {
                                Box(Modifier.size(width = 56.dp, height = 32.dp)
                                    .background(if (selected) palette.accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(16.dp)),
                                    contentAlignment = Alignment.Center) {
                                    Image(painterResource(destination.icon), null,
                                        colorFilter = ColorFilter.tint(if (selected) palette.onAccent else palette.text),
                                        modifier = Modifier.size(22.dp))
                                }
                                BasicText(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                    style = typography.xxs.copy(color = palette.text, fontSize = 10.sp, lineHeight = 12.sp))
                            }
                        }
                    }
                }
        }
        if (state.drawerFraction > 0f && chromeVisible && !menuVisible) {
            Box(Modifier.fillMaxSize()
                .background(palette.background0.copy(alpha = 0.65f * state.drawerFraction))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { settleDrawer(false) })
            ModalDrawerSheet(
                drawerContainerColor = palette.background1,
                drawerContentColor = palette.text,
                modifier = Modifier.offset { IntOffset((drawerWidthPx * (state.drawerFraction - 1f)).roundToInt(), 0) }
                    .width(drawerWidth).fillMaxHeight()
            ) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText(stringResource(R.string.navigation_library), style = typography.m.copy(color = palette.text),
                            modifier = Modifier.weight(1f).padding(12.dp))
                        NavigationIcon(R.drawable.close, stringResource(R.string.navigation_close_menu)) { settleDrawer(false) }
                    }
                    HomeDestination.library.forEach { destination ->
                        val label = stringResource(destination.title)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .background(if (state.destination == destination) palette.background2 else palette.background1)
                                .selectable(state.destination == destination,
                                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                                    role = Role.Tab, onClick = { state.select(destination); settleDrawer(false) })
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Image(painterResource(destination.icon), null, colorFilter = ColorFilter.tint(palette.text),
                                modifier = Modifier.size(24.dp))
                            BasicText(label, style = typography.s.copy(color = palette.text))
                        }
                    }
                }
            }
        }
        // Only Home exposes the handle; it tracks the drawer under the finger in both directions.
        if (chromeVisible && !menuVisible && ((state.destination == HomeDestination.Home && state.homeRootVisible) || state.drawerFraction > 0f)) {
            val gestureLeft = with(density) { WindowInsets.systemGestures.getLeft(density, LocalLayoutDirection.current).toDp() }
            val openMenuLabel = stringResource(R.string.navigation_open_menu)
            Box(Modifier.align(Alignment.CenterStart)
                .offset { IntOffset((drawerWidthPx * state.drawerFraction).roundToInt(), 0) }
                .padding(start = if (state.drawerFraction == 0f) gestureLeft else 0.dp)
                .size(width = 48.dp, height = 80.dp)
                .semantics { contentDescription = openMenuLabel }
                .pointerInput(drawerWidthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { settleJob?.cancel() },
                        onDragCancel = { settleDrawer(state.drawerFraction >= 0.5f) },
                        onDragEnd = { settleDrawer(state.drawerFraction >= 0.5f) }
                    ) { change, amount ->
                        state.drawerFraction = (state.drawerFraction + amount / drawerWidthPx).coerceIn(0f, 1f)
                        change.consume()
                    }
                }, contentAlignment = Alignment.CenterStart) {
                Box(Modifier.width(20.dp).height(72.dp)
                    .background(palette.background2, RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)),
                    contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.chevron_forward), null,
                        colorFilter = ColorFilter.tint(palette.text), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
    // Register after nested route handlers so Back closes an open drawer first.
    BackHandler(enabled = state.drawerFraction > 0f) { settleDrawer(false) }
}

@Composable
internal fun HomeToolbar(state: HomeNavigationState) {
    val (palette, typography) = LocalAppearance.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    DisposableEffect(state) {
        state.homeRootVisible = true
        onDispose { state.homeRootVisible = false }
    }
    Row(Modifier.fillMaxWidth().onSizeChanged { state.toolbarHeight = with(density) { it.height.toDp() } }
        .background(palette.background0).statusBarsPadding()
        .heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(stringResource(if (state.destination == HomeDestination.Home) R.string.quick_picks else state.destination.title), maxLines = 1, overflow = TextOverflow.Ellipsis, style = typography.l.medium.copy(color = palette.text),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        NavigationIcon(R.drawable.search, stringResource(R.string.navigation_search)) { scope.launch { searchRoute.ensureGlobal("") } }
    }
}

@Composable
private fun NavigationIcon(icon: Int, label: String, onClick: () -> Unit) {
    val (palette) = LocalAppearance.current
    Box(Modifier.size(48.dp).clickable(interactionSource = remember { MutableInteractionSource() },
        indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Image(painterResource(icon), label, colorFilter = ColorFilter.tint(palette.text),
            modifier = Modifier.size(24.dp))
    }
}
