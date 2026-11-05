package com.enskygr234.eclyra.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object C {
    val Night = Color(0xFF0B0618)
    val Night2 = Color(0xFF160C2E)
    val Card = Color(0xFF1C1236)
    val Card2 = Color(0xFF261A47)
    val Line = Color(0xFF34265C)
    val Violet = Color(0xFFA78BFA)
    val VioletDeep = Color(0xFF7C3AED)
    val Ice = Color(0xFF67E8F9)
    val Rose = Color(0xFFF472B6)
    val Text = Color(0xFFEDE9FE)
    val Muted = Color(0xFFA59CC9)
    val Error = Color(0xFFFCA5A5)
}

private val scheme = darkColorScheme(
    primary = C.Violet,
    onPrimary = Color(0xFF1A0B3D),
    primaryContainer = C.VioletDeep,
    onPrimaryContainer = Color.White,
    secondary = C.Ice,
    onSecondary = Color(0xFF052E35),
    background = C.Night,
    onBackground = C.Text,
    surface = C.Card,
    onSurface = C.Text,
    surfaceVariant = C.Card2,
    onSurfaceVariant = C.Muted,
    outline = C.Line,
    error = C.Error,
)

@Composable
fun EclyraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
