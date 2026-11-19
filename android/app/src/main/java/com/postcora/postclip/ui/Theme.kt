package com.postcora.postclip.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Red = Color(0xFFD62D20)

@Composable
fun PostClipTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFFF6B5E), error = Color(0xFFFF8A80))
    else lightColorScheme(primary = Red, error = Color(0xFFB3261E))
    MaterialTheme(colorScheme = scheme, content = content)
}
