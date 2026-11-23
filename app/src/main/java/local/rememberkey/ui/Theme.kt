package local.rememberkey.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import local.rememberkey.R

/** «Сейф»: rounded cards on a gray-blue background, blue accent; the colors of design/prototype.html. */
class Palette(
    val bg: Color, val surf: Color, val surf2: Color, val line: Color, val line2: Color,
    val text: Color, val text2: Color, val text3: Color,
    val acc: Color, val accS: Color, val onAcc: Color,
    val ok: Color, val okS: Color, val amber: Color, val amberS: Color, val onAmber: Color,
    val red: Color, val redS: Color,
    val kb: Color, val key: Color, val keyFn: Color, val keySh: Color,
    val toast: Color, val onToast: Color, val dg: Color, val sy: Color,
)

val DarkPalette = Palette(
    bg = Color(0xFF1B1F27), surf = Color(0xFF262B35), surf2 = Color(0xFF313744), line = Color(0xFF353C4A), line2 = Color(0xFF4A5262),
    text = Color(0xFFE6E9EF), text2 = Color(0xFFA9B1C1), text3 = Color(0xFF7D8697),
    acc = Color(0xFF7AA7F5), accS = Color(0x297AA7F5), onAcc = Color(0xFF0D1B33),
    ok = Color(0xFF5FC98C), okS = Color(0x295FC98C), amber = Color(0xFFF0C24B), amberS = Color(0x26F0C24B), onAmber = Color(0xFF2B2000),
    red = Color(0xFFF2857A), redS = Color(0x26F2857A),
    kb = Color(0xFF141820), key = Color(0xFF363C4A), keyFn = Color(0xFF272C37), keySh = Color(0xFF0B0D11),
    toast = Color(0xFFE6E9EF), onToast = Color(0xFF1B1F27), dg = Color(0xFF7AA7F5), sy = Color(0xFFF2857A),
)

val LightPalette = Palette(
    bg = Color(0xFFF2F5F9), surf = Color(0xFFFFFFFF), surf2 = Color(0xFFEEF1F6), line = Color(0xFFE4E8EF), line2 = Color(0xFFCFD5DF),
    text = Color(0xFF1A1D23), text2 = Color(0xFF5B6372), text3 = Color(0xFF8A92A1),
    acc = Color(0xFF175DDC), accS = Color(0xFFE2EBFB), onAcc = Color(0xFFFFFFFF),
    ok = Color(0xFF0B8A4A), okS = Color(0xFFE1F4EA), amber = Color(0xFFA86A00), amberS = Color(0xFFFDF1D8), onAmber = Color(0xFFFFFFFF),
    red = Color(0xFFC8332A), redS = Color(0xFFFDE6E4),
    kb = Color(0xFFD7DCE5), key = Color(0xFFFFFFFF), keyFn = Color(0xFFBFC6D3), keySh = Color(0xFF9AA3B3),
    toast = Color(0xFF1F232B), onToast = Color(0xFFFFFFFF), dg = Color(0xFF175DDC), sy = Color(0xFFC8332A),
)

/** The current palette; reading a color is a state read, so switching dark/light redraws everything. */
object C {
    var p by mutableStateOf(DarkPalette)
    val bg get() = p.bg
    val surf get() = p.surf
    val surf2 get() = p.surf2
    val line get() = p.line
    val line2 get() = p.line2
    val text get() = p.text
    val text2 get() = p.text2
    val text3 get() = p.text3
    val acc get() = p.acc
    val accS get() = p.accS
    val onAcc get() = p.onAcc
    val ok get() = p.ok
    val okS get() = p.okS
    val amber get() = p.amber
    val amberS get() = p.amberS
    val onAmber get() = p.onAmber
    val red get() = p.red
    val redS get() = p.redS
    val kb get() = p.kb
    val key get() = p.key
    val keyFn get() = p.keyFn
    val keySh get() = p.keySh
    val toast get() = p.toast
    val onToast get() = p.onToast
    val dg get() = p.dg
    val sy get() = p.sy
}

@OptIn(ExperimentalTextApi::class)
private fun inter(w: Int) = Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
@OptIn(ExperimentalTextApi::class)
private fun robotoMono(w: Int) = Font(R.font.roboto_mono, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Sans = FontFamily(inter(400), inter(500), inter(600), inter(700), inter(800))

/** Keys are always set in a monospace font: every character has its own cell. */
val Mono = FontFamily(robotoMono(400), robotoMono(500))

fun style(
    size: TextUnit = 15.sp,
    color: Color = C.text,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Unspecified,
    lineHeight: Float = 1.4f,
    spacing: TextUnit = TextUnit.Unspecified,
    decoration: TextDecoration? = null,
    family: FontFamily = Sans,
) = TextStyle(
    fontFamily = family, fontSize = size, color = color, fontWeight = weight, textAlign = align,
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
    family: FontFamily = Sans,
) = BasicText(
    text, modifier,
    style = style(size, color, weight, align, lineHeight, spacing, family = family),
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
    family: FontFamily = Sans,
    spacing: TextUnit = TextUnit.Unspecified,
) = BasicText(
    text, modifier,
    style = style(size, color, weight, align, lineHeight, spacing, family = family),
    maxLines = maxLines, overflow = TextOverflow.Ellipsis, inlineContent = inline,
)

/** Digits blue, signs red, letters plain — like password managers show generated passwords. */
fun symbols(s: String): AnnotatedString = buildAnnotatedString {
    for (c in s) {
        when {
            c.isDigit() -> withStyle(SpanStyle(color = C.dg)) { append(c) }
            c.isLetter() || c.isWhitespace() -> append(c)
            else -> withStyle(SpanStyle(color = C.sy)) { append(c) }
        }
    }
}

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

/** Read in the draw phase: blinking never recomposes the screen. [low] is the alpha of the "off" half. */
fun Modifier.blink(enabled: Boolean = true, low: Float = 0f): Modifier = if (!enabled) this else composed {
    val s = LocalBlink.current
    graphicsLayer { alpha = if (s.value) 1f else low }
}
