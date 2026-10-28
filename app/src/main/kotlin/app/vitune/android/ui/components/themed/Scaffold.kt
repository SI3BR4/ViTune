package app.vitune.android.ui.components.themed

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Companion.Down
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Companion.Up
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import app.vitune.android.LocalPlayerAwareWindowInsets
import app.vitune.core.ui.utils.isLandscape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import app.vitune.android.R
import app.vitune.android.preferences.UIStatePreferences
import app.vitune.core.ui.LocalAppearance
import kotlinx.collections.immutable.toImmutableList

@Composable
fun Scaffold(
    key: String,
    topIconButtonId: Int,
    onTopIconButtonClick: () -> Unit,
    tabIndex: Int,
    onTabChange: (Int) -> Unit,
    tabColumnContent: TabsBuilder.() -> Unit,
    modifier: Modifier = Modifier,
    tabsEditingTitle: String = stringResource(R.string.tabs),
    animateTabs: Boolean = true,
    showTopIcon: Boolean = true,
    content: @Composable (Int) -> Unit
) {
    val (colorPalette) = LocalAppearance.current
    var hiddenTabs by UIStatePreferences.mutableTabStateOf(key)

    val body: @Composable () -> Unit = {
        if (!animateTabs) {
            content(tabIndex)
        } else AnimatedContent(
            targetState = tabIndex,
            transitionSpec = {
                val slideDirection = if (targetState > initialState) Up else Down
                val animationSpec = spring(
                    dampingRatio = 0.9f,
                    stiffness = Spring.StiffnessLow,
                    visibilityThreshold = IntOffset.VisibilityThreshold
                )

                ContentTransform(
                    targetContentEnter = slideIntoContainer(slideDirection, animationSpec),
                    initialContentExit = slideOutOfContainer(slideDirection, animationSpec),
                    sizeTransform = null
                )
            },
            content = { content(it) },
            label = ""
        )
    }
    if (isLandscape) {
        Row(
            modifier = modifier
                .background(colorPalette.background0)
                .fillMaxSize()
        ) {
            NavigationRail(
                showTopIcon = showTopIcon,
                topIconButtonId = topIconButtonId,
                onTopIconButtonClick = onTopIconButtonClick,
                tabIndex = tabIndex,
                onTabIndexChange = onTabChange,
                hiddenTabs = hiddenTabs,
                setHiddenTabs = { hiddenTabs = it.toImmutableList() },
                tabsEditingTitle = tabsEditingTitle,
                content = tabColumnContent
            )

            body()
        }
    } else {
        val insets = LocalPlayerAwareWindowInsets.current
        val currentTitle = TabsBuilder.rememberTabs(tabColumnContent).getOrNull(tabIndex)?.title?.invoke()
        Column(modifier.background(colorPalette.background0).fillMaxSize()) {
            Box(Modifier.padding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal).asPaddingValues())) {
                PortraitTabs(topIconButtonId, onTopIconButtonClick, tabIndex, onTabChange,
                    hiddenTabs, { hiddenTabs = it.toImmutableList() }, tabsEditingTitle, showTopIcon, tabColumnContent)
            }
            CompositionLocalProvider(
                LocalPlayerAwareWindowInsets provides insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                LocalScreenHeading provides currentTitle
            ) {
                body()
            }
        }
    }
}
