package audio.soniqo.speech.control.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import audio.soniqo.speech.control.MicState
import kotlin.math.sin

/**
 * Push-to-talk orb in the brand orange. State reads through intensity, not
 * hue: a quiet ring at idle, a level-reactive bloom while listening, a
 * rotating sweep while the model thinks, a steady radiate while speaking.
 */
@Composable
fun TalkOrb(
    micState: MicState,
    level: Float,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "orb")

    val breath by transition.animateFloat(
        initialValue = 0f, targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(3200), RepeatMode.Restart),
        label = "breath",
    )
    val sweep by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
        label = "sweep",
    )

    // Status → color, same language as soniqo/runner's MagicRings.
    val (targetCore, targetGlow) = when (micState) {
        MicState.OFFLINE -> OrbOffline
        MicState.IDLE -> OrbIdle
        MicState.LISTENING -> OrbListening
        MicState.THINKING -> OrbThinking
        MicState.SPEAKING -> OrbSpeaking
    }
    val core by animateColorAsState(targetCore, tween(400), label = "core")
    val glow by animateColorAsState(targetGlow, tween(400), label = "glow")

    // Core intensity: mic level while listening, otherwise a slow breath.
    val target = when (micState) {
        MicState.LISTENING -> 0.62f + level.coerceIn(0f, 1f) * 0.38f
        MicState.SPEAKING -> 0.88f
        MicState.THINKING -> 0.6f
        MicState.IDLE -> 0.42f + if (reduceMotion) 0f else (sin(breath) + 1f) / 2f * 0.08f
        MicState.OFFLINE -> 0.34f
    }
    val bloom by animateFloatAsState(target, tween(140), label = "bloom")

    Canvas(modifier.size(116.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val maxR = size.minDimension / 2

        // Ambient glow — the status color bleeding out.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(core.copy(alpha = 0.22f), Color.Transparent),
                center = c, radius = maxR,
            ),
            radius = maxR, center = c,
        )
        // Core disc: light center (secondary) → saturated edge (primary).
        val coreR = maxR * 0.52f * bloom + maxR * 0.24f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glow.copy(alpha = 0.95f), core.copy(alpha = 0.7f)),
                center = c, radius = coreR,
            ),
            radius = coreR, center = c,
        )
        // Outer ring — full at rest; a rotating gap while thinking.
        val ringR = maxR * 0.78f
        val stroke = Stroke(width = maxR * 0.045f)
        if (micState == MicState.THINKING && !reduceMotion) {
            drawArc(
                color = core, startAngle = sweep, sweepAngle = 270f, useCenter = false,
                topLeft = Offset(c.x - ringR, c.y - ringR),
                size = Size(ringR * 2, ringR * 2),
                style = stroke,
            )
        } else {
            drawCircle(color = core.copy(alpha = 0.8f), radius = ringR, center = c, style = stroke)
        }
    }
}

/** One-word caption under the orb, matched to the mic state. */
fun orbCaption(micState: MicState): String = when (micState) {
    MicState.OFFLINE -> "loading"
    MicState.IDLE -> "tap to talk"
    MicState.LISTENING -> "listening"
    MicState.THINKING -> "thinking"
    MicState.SPEAKING -> "speaking"
}
