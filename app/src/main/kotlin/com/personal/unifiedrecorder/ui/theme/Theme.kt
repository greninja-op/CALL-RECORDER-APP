package com.personal.unifiedrecorder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

/**
 * Lightweight Material 3 theme wrapper for the Unified Recorder UI. Kept
 * dependency-free (no dynamic color) so the composables render identically on
 * every supported API level. The final application wiring can replace this with
 * a richer theme if desired.
 */
@Composable
fun UnifiedRecorderTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkColors else LightColors,
        content = content
    )
}
