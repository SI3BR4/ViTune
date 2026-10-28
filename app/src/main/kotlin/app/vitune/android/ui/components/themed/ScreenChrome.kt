package app.vitune.android.ui.components.themed

import androidx.compose.runtime.compositionLocalOf

/** Only suppress headings/search controls already supplied by the containing screen. */
val LocalScreenHeading = compositionLocalOf<String?> { null }
val LocalScreenSearchAvailable = compositionLocalOf { false }
