package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ObsidianDarkColorScheme = darkColorScheme(
  primary = ElectricCyan,
  onPrimary = Color(0xFF00262E),
  primaryContainer = ElectricCyanDim,
  onPrimaryContainer = Color(0xFFB8F8FF),
  secondary = DeepViolet,
  onSecondary = Color.White,
  secondaryContainer = DeepVioletDim,
  onSecondaryContainer = Color(0xFFE5DEFF),
  tertiary = TelemetryEmerald,
  onTertiary = Color(0xFF002912),
  error = ClipCrimson,
  onError = Color.White,
  background = ObsidianBg,
  onBackground = TextPrimary,
  surface = ObsidianSurface,
  onSurface = TextPrimary,
  surfaceVariant = ObsidianSurfaceContainer,
  onSurfaceVariant = TextSecondary,
  surfaceContainer = ObsidianSurfaceContainer,
  surfaceContainerHigh = ObsidianSurfaceBright,
  outline = ObsidianOutline,
)

@Composable
fun ObsidianMidiTheme(
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = ObsidianDarkColorScheme,
    typography = ObsidianTypography,
    content = content,
  )
}
