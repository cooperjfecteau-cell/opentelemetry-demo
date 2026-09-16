// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The React Native register's palette, carried over verbatim so the port looks like the screens the
 * flow was signed off on. A counter is always lit, so there is no dark scheme.
 */
object RegisterColors {
    val Bar = Color(0xFF11181C)
    val Text = Color(0xFF11181C)
    val Accent = Color(0xFF0A7EA4)
    val Muted = Color(0xFF687076)
    val MutedOnDark = Color(0xFF9BA1A6)
    val Submit = Color(0xFF0000FF)
    val Primary = Color(0xFF008000)
    val Error = Color(0xFFC62828)
    val ErrorBackground = Color(0xFFFFEBEE)
    val Warn = Color(0xFFF9A825)
    val WarnBackground = Color(0xFFFFF8E1)
    val OkBackground = Color(0xFFE8F5E9)
    val Surface = Color(0xFFFFFFFF)
}

private val RegisterColorScheme = lightColorScheme(
    primary = RegisterColors.Accent,
    onPrimary = Color.White,
    background = RegisterColors.Surface,
    onBackground = RegisterColors.Text,
    surface = RegisterColors.Surface,
    onSurface = RegisterColors.Text,
    error = RegisterColors.Error,
    onError = Color.White,
)

@Composable
fun AstroShopRegisterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RegisterColorScheme, content = content)
}
