package local.rememberkey.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import local.rememberkey.R

/** «Терминал»: green monospace on black, the colors of design/prototype.html. */
object C {
    val page = Color(0xFF010201)
    val bg = Color(0xFF040704)
    val line = Color(0xFF1D4021)
    val dash = Color(0xFF2A6B30)
    val text = Color(0xFFC6F8C2)
    val text2 = Color(0xFF86D982)
    val text3 = Color(0xFF4D8A4A)
    val acc = Color(0xFF3DFF6E)
    val accS = Color(0xFF0D2A12)
    val amber = Color(0xFFFFCF3D)
    val red = Color(0xFFFF5F5F)
    val redS = Color(0xFF2A0D0D)
    val redSoft = Color(0xFFFFB0B0)
    val blue = Color(0xFF5FD7FF)
    val track = Color(0xFF12301A)
    val kb = Color(0xFF020402)
    val dueBorder = Color(0xFF6B5A1F)
    val sheet = Color(0xFF050B05)
    val panel = Color(0xFF071407)
}

val Mono = FontFamily(
    Font(R.font.jbmono_light, FontWeight.Light),
    Font(R.font.jbmono_regular, FontWeight.Normal),
    Font(R.font.jbmono_medium, FontWeight.Medium),
    Font(R.font.jbmono_bold, FontWeight.Bold),
    Font(R.font.jbmono_extrabold, FontWeight.ExtraBold),
)

fun style(
    size: TextUnit = 15.sp,
    color: Color = C.text,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Unspecified,
    lineHeight: Float = 1.4f,
    spacing: TextUnit = TextUnit.Unspecified,
    decoration: TextDecoration? = null,
) = TextStyle(
    fontFamily = Mono, fontSize = size, color = color, fontWeight = weight, textAlign = align,
    lineHeight = size * lineHeight, letterSpacing = spacing, textDecoration = decoration,
)

@Composable
fun Tx(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 15.sp,
    color: Color = C.text,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    lineHeight: Float = 1.4f,
    spacing: TextUnit = TextUnit.Unspecified,
) = BasicText(
    text, modifier,
    style = style(size, color, weight, align, lineHeight, spacing),
    maxLines = maxLines, overflow = TextOverflow.Ellipsis,
)

@Composable
fun Tx(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    size: TextUnit = 15.sp,
    color: Color = C.text,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    lineHeight: Float = 1.4f,
    inline: Map<String, InlineTextContent> = emptyMap(),
) = BasicText(
    text, modifier,
    style = style(size, color, weight, align, lineHeight),
    maxLines = maxLines, overflow = TextOverflow.Ellipsis, inlineContent = inline,
)

/** One shared on/off tick for every blinking cursor and progress cell (no per-frame animation). */
val LocalBlink = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(true) }

@Composable
fun ProvideBlink(content: @Composable () -> Unit) {
    val on = remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            on.value = !on.value
        }
    }
    CompositionLocalProvider(LocalBlink provides on, content = content)
}

/** Read in the draw phase: blinking never recomposes the screen. */
fun Modifier.blink(enabled: Boolean = true): Modifier = if (!enabled) this else composed {
    val s = LocalBlink.current
    graphicsLayer { alpha = if (s.value) 1f else 0f }
}
