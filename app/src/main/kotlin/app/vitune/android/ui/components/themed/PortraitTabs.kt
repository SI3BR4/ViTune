package app.vitune.android.ui.components.themed

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vitune.android.R
import app.vitune.android.ui.screens.settings.SwitchSettingsEntry
import app.vitune.core.ui.LocalAppearance
import kotlinx.collections.immutable.ImmutableList

/** Secondary categories remain available without a permanent portrait rail. */
@Composable
internal fun PortraitTabs(
    topIcon: Int,
    onTopClick: () -> Unit,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    hiddenTabs: ImmutableList<String>,
    onHiddenTabsChange: (List<String>) -> Unit,
    editingTitle: String,
    content: TabsBuilder.() -> Unit
) {
    val (palette, typography) = LocalAppearance.current
    val tabs = TabsBuilder.rememberTabs(content)
    var editing by remember { mutableStateOf(false) }
    if (editing) DefaultDialog(onDismiss = { editing = false }) {
        BasicText(editingTitle, style = typography.s.copy(color = palette.text))
        tabs.forEach { tab ->
            SwitchSettingsEntry(
                title = tab.title(), text = null,
                isChecked = tab.key !in hiddenTabs,
                onCheckedChange = { checked ->
                    if (checked) onHiddenTabsChange(hiddenTabs - tab.key)
                    else if (tabs.count { it.key !in hiddenTabs } > 1)
                        onHiddenTabsChange(hiddenTabs + tab.key)
                },
                isEnabled = tab.canHide
            )
        }
    }
    Row(Modifier.fillMaxWidth().background(palette.background0),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).combinedClickable(role = Role.Button, onClick = onTopClick),
            contentAlignment = Alignment.Center) {
            Image(painterResource(topIcon), stringResource(R.string.navigation_back),
                colorFilter = ColorFilter.tint(palette.text), modifier = Modifier.size(24.dp))
        }
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { index, tab ->
                if (index == selectedIndex || tab.key !in hiddenTabs) {
                    val color = if (selectedIndex == index) palette.accent else palette.textSecondary
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.widthIn(min = 64.dp).heightIn(min = 56.dp)
                            .semantics { selected = index == selectedIndex }
                            .combinedClickable(role = Role.Tab, onClick = { onSelect(index) },
                                onLongClick = { editing = true })
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Image(painterResource(tab.icon), null, colorFilter = ColorFilter.tint(color),
                            modifier = Modifier.size(24.dp))
                        BasicText(tab.title(), style = typography.xxs.copy(color = color))
                    }
                }
            }
        }
    }
}
