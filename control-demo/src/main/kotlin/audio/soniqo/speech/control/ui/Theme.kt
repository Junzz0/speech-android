package audio.soniqo.speech.control.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// ---------------------------------------------------------------------------
// soniqo.audio dark-theme tokens (shadcn variables from the site stylesheet):
//   --background 24 10% 7% · --card 24 10% 9% · --muted 24 8% 14%
//   --border 24 8% 18% · --foreground 30 25% 96% · --muted-fg 28 12% 65%
//   --primary 27 88% 56%
// ---------------------------------------------------------------------------

val Background = Color(0xFF141210)
val Card = Color(0xFF191614)
val MutedSurface = Color(0xFF272321)
val Border = Color(0xFF312D2A)
val Foreground = Color(0xFFF8F5F0)
val MutedFg = Color(0xFFB0A59B)
val FaintFg = Color(0xFF79726B)
val Primary = Color(0xFFF1862C)
val Destructive = Color(0xFFD22C2C)

// Talk-orb status colors, matched to soniqo/runner's MagicRings phase palette
// [primary, secondary]: idle orange · listening red · thinking amber ·
// speaking green · loading grey. Same status→color language as the desktop app.
val OrbIdle = Color(0xFFF08030) to Color(0xFFFFB37A)
val OrbListening = Color(0xFFFF4D4D) to Color(0xFFFF9A9A)
val OrbThinking = Color(0xFFFFB02E) to Color(0xFFFFD98A)
val OrbSpeaking = Color(0xFF34D39A) to Color(0xFF9FF0CF)
val OrbOffline = Color(0xFF5B6470) to Color(0xFF878F9C)

// Keep the demo self-contained and use Android's installed typefaces. The
// display/instrumentation distinction mirrors the original Control app
// without adding font binaries to the SDK repository.
val Grotesk = FontFamily.SansSerif
val Plex = FontFamily.Monospace

private val SoniqoColors = darkColorScheme(
    primary = Primary,
    onPrimary = Background,
    background = Background,
    onBackground = Foreground,
    surface = Card,
    onSurface = Foreground,
    surfaceVariant = MutedSurface,
    onSurfaceVariant = MutedFg,
    outline = Border,
    error = Destructive,
)

@Composable
fun SoniqoControlTheme(content: @Composable () -> Unit) {
    // Single-look brand surface (the site's dark palette) in both system themes.
    MaterialTheme(colorScheme = SoniqoColors, content = content)
}
