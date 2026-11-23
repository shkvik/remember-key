package local.rememberkey.ui

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.rememberkey.core.Fmt
import local.rememberkey.core.KeyText
import local.rememberkey.core.PinGuard

enum class Layout { RU, EN, SYM1, SYM2 }

/** What the user is typing with the app keyboard. Only the app keyboard writes here; the system keyboard never sees it. */
class InputState {
    var buf by mutableStateOf("")
    var show by mutableStateOf(false)
    var layout by mutableStateOf(Layout.EN)
    var letters by mutableStateOf(Layout.EN)
    var shift by mutableStateOf(false)
    /** Caps lock: double tap on shift. Stays on across ?123 and RU/EN until shift is tapped again. */
    var caps by mutableStateOf(false)
    var err by mutableStateOf("")
    private var shiftAt = 0L

    val upper get() = shift || caps

    /** Shift key: one tap = next letter capital, two quick taps = caps lock, a tap while locked = off. */
    fun shiftTap(now: Long) {
        when {
            caps -> { caps = false; shift = false }
            shift && now - shiftAt < DOUBLE_TAP_MS -> { caps = true; shift = false }
            else -> shift = !shift
        }
        shiftAt = now
    }

    fun reset(fmt: Fmt) {
        buf = ""
        show = false
        shift = false
        caps = false
        err = ""
        layout = if (fmt == Fmt.WORDS) Layout.RU else Layout.EN
        letters = layout
    }

    fun type(c: Char, fmt: Fmt) {
        if (fmt == Fmt.SYMBOLS && c == ' ') return
        if (buf.length >= KeyText.MAX_LEN) return
        buf += c
        err = ""
        shift = false
    }

    fun backspace() {
        buf = buf.dropLast(1)
        err = ""
    }

    private companion object {
        const val DOUBLE_TAP_MS = 400L
    }
}

private val ROWS = mapOf(
    Layout.RU to listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"),
    Layout.EN to listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
    Layout.SYM1 to listOf("1234567890", "@#$%&-+()/", "*\"':;!?,."),
    Layout.SYM2 to listOf("1234567890", "[]{}<>\\|~`", "^_="),
)

/** The key as it looks in the field: dots unless shown; symbols in blocks of 4. */
fun masked(fmt: Fmt, buf: String, show: Boolean): String = when (fmt) {
    Fmt.WORDS -> if (show) buf else buf.map { if (it.isWhitespace()) it else '•' }.joinToString("")
    Fmt.SYMBOLS -> buf.chunked(4).joinToString(" ") { if (show) it else "•".repeat(it.length) }
}

@Composable
fun KeyField(input: InputState, fmt: Fmt, placeholder: String) {
    val inline = mapOf(
        "caret" to InlineTextContent(Placeholder(3.sp, 24.sp, PlaceholderVerticalAlign.TextCenter)) {
            Box(Modifier.size(2.dp, 24.dp).blink().background(C.acc))
        },
    )
    Box(Modifier.fillMaxWidth().heightIn(min = 96.dp).surface(C.surf, R14).border(2.dp, C.acc, R14)) {
        val text = buildAnnotatedString {
            if (input.buf.isEmpty()) {
                appendInlineContent("caret", "|")
                withStyle(SpanStyle(color = C.text3, fontSize = 15.sp, fontWeight = FontWeight.Normal, fontFamily = Sans, letterSpacing = 0.sp)) { append(" $placeholder") }
            } else {
                val m = masked(fmt, input.buf, input.show)
                if (input.show && fmt == Fmt.SYMBOLS) append(symbols(m)) else append(m)
                appendInlineContent("caret", "|")
            }
        }
        Tx(
            text, size = 20.sp, weight = FontWeight.Medium, lineHeight = 1.5f, inline = inline, family = Mono, spacing = 0.5.sp,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 52.dp),
        )
        Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(44.dp).clip(CircleShape).tap { input.show = !input.show },
            contentAlignment = Alignment.Center,
        ) { Icn(if (input.show) Ic.eyeOff else Ic.eye, C.text2, 22.dp) }
    }
}

/** The app's own keyboard: the system keyboard, its cloud and its clipboard never see the key. */
@Composable
fun AppKeyboard(input: InputState, fmt: Fmt, enabled: Boolean, onSubmit: () -> Unit) {
    val view = LocalView.current
    fun haptic() = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    val lay = input.layout
    val rows = ROWS.getValue(lay)
    val letters = lay == Layout.RU || lay == Layout.EN
    val up = input.upper && letters
    Column(
        Modifier.fillMaxWidth().background(C.kb).padding(start = 3.dp, end = 3.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icn(Ic.shield, C.text3, 12.dp)
            Tx(" Клавиатура RememberKey: системная ввод не видит", size = 11.sp, color = C.text3, maxLines = 1)
        }
        fun ch(c: Char) = if (up) c.uppercaseChar() else c
        KbRow { rows[0].forEach { c -> Key(ch(c).toString(), enabled) { haptic(); input.type(ch(c), fmt) } } }
        KbRow { rows[1].forEach { c -> Key(ch(c).toString(), enabled) { haptic(); input.type(ch(c), fmt) } } }
        KbRow {
            when (lay) {
                Layout.RU, Layout.EN -> FnKey(on = input.upper, enabled = enabled, onClick = { haptic(); input.shiftTap(SystemClock.uptimeMillis()) }) {
                    val tint = if (input.upper) C.onAcc else C.text
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icn(Ic.shift, tint, 20.dp)
                        // caps lock: a bar under the arrow, like on phone keyboards
                        if (input.caps) Box(Modifier.padding(top = 2.dp).size(12.dp, 2.dp).background(tint))
                    }
                }
                Layout.SYM1 -> FnKey(enabled = enabled, onClick = { haptic(); input.layout = Layout.SYM2 }) { FnText("1/2") }
                Layout.SYM2 -> FnKey(enabled = enabled, onClick = { haptic(); input.layout = Layout.SYM1 }) { FnText("2/2") }
            }
            rows[2].forEach { c -> Key(ch(c).toString(), enabled) { haptic(); input.type(ch(c), fmt) } }
            FnKey(enabled = enabled, onClick = { haptic(); input.backspace() }) { Icn(Ic.bksp, C.text, 22.dp) }
        }
        KbRow {
            if (letters) {
                FnKey(enabled = enabled, onClick = { haptic(); input.layout = Layout.SYM1; input.shift = false }) { FnText("?123") }
                FnKey(enabled = enabled, onClick = {
                    haptic()
                    input.layout = if (lay == Layout.RU) Layout.EN else Layout.RU
                    input.letters = input.layout
                    input.shift = false
                }) { FnText(if (lay == Layout.RU) "EN" else "RU") }
            } else {
                FnKey(enabled = enabled, onClick = { haptic(); input.layout = input.letters }) { FnText(if (input.letters == Layout.RU) "АБВ" else "ABC") }
            }
            val spaceLabel = when {
                fmt == Fmt.SYMBOLS -> "без пробелов"
                lay == Layout.EN -> "English"
                lay == Layout.RU -> "Русский"
                else -> "пробел"
            }
            Box(
                Modifier.weight(5f).height(46.dp).keyShape(C.key).alpha(if (fmt == Fmt.WORDS) 1f else 0.55f)
                    .then(if (fmt == Fmt.WORDS && enabled) Modifier.tap { haptic(); input.type(' ', fmt) } else Modifier),
                contentAlignment = Alignment.Center,
            ) { Tx(spaceLabel, size = 13.sp, color = C.text2, maxLines = 1) }
            Box(
                Modifier.weight(1.5f).height(46.dp).keyShape(if (enabled) C.acc else C.keyFn).tap(enabled) { haptic(); onSubmit() },
                contentAlignment = Alignment.Center,
            ) { Icn(Ic.check, if (enabled) C.onAcc else C.text3, 24.dp) }
        }
    }
}

/** Rounded key with a thin shadow line under it, like a phone keyboard. */
private fun Modifier.keyShape(color: Color): Modifier = drawBehind {
    val r = CornerRadius(8.dp.toPx())
    drawRoundRect(C.keySh, topLeft = Offset(0f, 1.dp.toPx()), size = size, cornerRadius = r)
    drawRoundRect(color, size = size, cornerRadius = r)
}

@Composable
private fun FnText(t: String) = Tx(t, size = 13.sp, color = C.text, weight = FontWeight.SemiBold)

@Composable
private fun KbRow(content: @Composable RowScope.() -> Unit) = Row(
    Modifier.fillMaxWidth().padding(horizontal = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
    content = content,
)

@Composable
private fun RowScope.Key(label: String, enabled: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        Modifier.weight(1f).widthIn(max = 37.dp).height(46.dp).keyShape(if (pressed) C.accS else C.key)
            .clickable(src, null, enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Tx(label, size = 19.sp, color = if (pressed) C.acc else C.text) }
}

@Composable
private fun RowScope.FnKey(on: Boolean = false, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.weight(1.5f).height(46.dp).keyShape(if (on) C.acc else C.keyFn).tap(enabled, onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ---------- PIN ----------

@Composable
fun PinDots(count: Int, bad: Boolean, shake: Int) {
    val dx = remember { Animatable(0f) }
    LaunchedEffect(shake) {
        if (shake > 0) dx.animateTo(0f, keyframes { durationMillis = 300; -8f at 75; 8f at 225; 0f at 300 })
    }
    Row(Modifier.offset { IntOffset(dx.value.dp.roundToPx(), 0) }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(PinGuard.LENGTH) { i ->
            val m = Modifier.size(14.dp).clip(CircleShape)
            if (i < count) Box(m.background(C.acc)) else Box(m.border(2.dp, if (bad) C.red else C.line2, CircleShape))
        }
    }
}

@Composable
fun PinPad(enabled: Boolean, onDigit: (Char) -> Unit, onBack: () -> Unit) {
    val view = LocalView.current
    Column(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f).padding(top = 6.dp, bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        for (row in listOf("123", "456", "789", " 0<")) {
            Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                row.forEach { c ->
                    when (c) {
                        ' ' -> Box(Modifier.size(76.dp))
                        '<' -> Box(
                            Modifier.size(76.dp).clip(CircleShape).tap(enabled) { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onBack() },
                            contentAlignment = Alignment.Center,
                        ) { Icn(Ic.bksp, C.text2, 26.dp) }
                        else -> PinKey(c, enabled) { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onDigit(c) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinKey(c: Char, enabled: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        Modifier.size(76.dp).clip(CircleShape).background(if (pressed) C.surf2 else C.surf).clickable(src, null, enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Tx(c.toString(), size = 28.sp, weight = FontWeight.Medium) }
}

@Composable
fun Gap(h: Dp) = Box(Modifier.height(h).width(1.dp))
