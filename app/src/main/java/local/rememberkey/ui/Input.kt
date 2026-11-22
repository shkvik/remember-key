package local.rememberkey.ui

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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
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
    var layout by mutableStateOf(Layout.RU)
    var letters by mutableStateOf(Layout.RU)
    var shift by mutableStateOf(false)
    var err by mutableStateOf("")

    fun reset(fmt: Fmt) {
        buf = ""
        show = false
        shift = false
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
        "caret" to InlineTextContent(Placeholder(11.sp, 22.sp, PlaceholderVerticalAlign.TextCenter)) {
            Box(Modifier.size(11.dp, 22.dp).blink().background(C.acc))
        },
    )
    Box(Modifier.fillMaxWidth().heightIn(min = 100.dp).dashed(C.acc)) {
        val text = buildAnnotatedString {
            withStyle(SpanStyle(color = C.acc)) { append("> ") }
            if (input.buf.isEmpty()) {
                appendInlineContent("caret", "_")
                withStyle(SpanStyle(color = C.text3, fontSize = 15.sp, fontWeight = FontWeight.Normal)) { append(" $placeholder") }
            } else {
                append(masked(fmt, input.buf, input.show))
                appendInlineContent("caret", "_")
            }
        }
        Tx(
            text, size = 20.sp, weight = FontWeight.Medium, lineHeight = 1.5f, inline = inline,
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 54.dp),
        )
        Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(44.dp).tap { input.show = !input.show },
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
    val up = input.shift && letters
    Column(
        Modifier.fillMaxWidth().background(C.kb).topDashed().padding(start = 3.dp, end = 3.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icn(Ic.shield, C.text3, 12.dp)
            Tx(" Клавиатура RememberKey: системная ввод не видит", size = 11.sp, color = C.text3, maxLines = 1)
        }
        fun ch(c: Char) = if (up) c.uppercaseChar() else c
        KbRow {
            rows[0].forEach { c -> Key(ch(c).toString(), enabled = enabled) { haptic(); input.type(ch(c), fmt) } }
        }
        KbRow {
            rows[1].forEach { c -> Key(ch(c).toString(), enabled = enabled) { haptic(); input.type(ch(c), fmt) } }
        }
        KbRow {
            when (lay) {
                Layout.RU, Layout.EN -> FnKey(on = input.shift, enabled = enabled, onClick = { haptic(); input.shift = !input.shift }) { Icn(Ic.shift, if (input.shift) Color.Black else C.text2, 20.dp) }
                Layout.SYM1 -> FnKey(enabled = enabled, onClick = { haptic(); input.layout = Layout.SYM2 }) { Tx("1/2", size = 13.sp, color = C.text2, weight = FontWeight.Bold) }
                Layout.SYM2 -> FnKey(enabled = enabled, onClick = { haptic(); input.layout = Layout.SYM1 }) { Tx("2/2", size = 13.sp, color = C.text2, weight = FontWeight.Bold) }
            }
            rows[2].forEach { c -> Key(ch(c).toString(), enabled = enabled) { haptic(); input.type(ch(c), fmt) } }
            FnKey(enabled = enabled, onClick = { haptic(); input.backspace() }) { Icn(Ic.bksp, C.text2, 22.dp) }
        }
        KbRow {
            if (letters) {
                FnKey(enabled = enabled, onClick = { haptic(); input.layout = Layout.SYM1; input.shift = false }) { Tx("?123", size = 13.sp, color = C.text2, weight = FontWeight.Bold) }
                FnKey(enabled = enabled, onClick = {
                    haptic()
                    input.layout = if (lay == Layout.RU) Layout.EN else Layout.RU
                    input.letters = input.layout
                    input.shift = false
                }) { Tx(if (lay == Layout.RU) "EN" else "RU", size = 13.sp, color = C.text2, weight = FontWeight.Bold) }
            } else {
                FnKey(enabled = enabled, onClick = { haptic(); input.layout = input.letters }) {
                    Tx(if (input.letters == Layout.RU) "АБВ" else "ABC", size = 13.sp, color = C.text2, weight = FontWeight.Bold)
                }
            }
            val spaceLabel = when {
                fmt == Fmt.SYMBOLS -> "без пробелов"
                lay == Layout.EN -> "English"
                lay == Layout.RU -> "Русский"
                else -> "пробел"
            }
            Box(
                Modifier.weight(5f).height(46.dp).border(1.dp, C.line)
                    .then(if (fmt == Fmt.WORDS && enabled) Modifier.tap { haptic(); input.type(' ', fmt) } else Modifier),
                contentAlignment = Alignment.Center,
            ) { Tx(spaceLabel, size = 12.sp, color = C.text3.copy(alpha = if (fmt == Fmt.WORDS) 1f else 0.55f), maxLines = 1) }
            Box(
                Modifier.weight(1.5f).height(46.dp).background(if (enabled) C.acc else C.line).tap(enabled) { haptic(); onSubmit() },
                contentAlignment = Alignment.Center,
            ) { Icn(Ic.check, Color.Black, 24.dp) }
        }
    }
}

private fun Modifier.topDashed(): Modifier = drawBehind {
    drawLine(C.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
}

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
        Modifier.weight(1f).widthIn(max = 38.dp).height(46.dp)
            .background(if (pressed) C.accS else Color.Transparent)
            .border(1.dp, if (pressed) C.dash else C.line)
            .clickable(src, null, enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Tx(label, size = 18.sp, color = if (pressed) C.acc else C.text2) }
}

@Composable
private fun RowScope.FnKey(on: Boolean = false, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.weight(1.5f).height(46.dp).background(if (on) C.acc else C.accS).border(1.dp, C.line).tap(enabled, onClick),
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
            val m = Modifier.size(16.dp)
            if (i < count) Box(m.background(C.acc)) else Box(m.border(1.dp, if (bad) C.red else C.text3))
        }
    }
}

@Composable
fun PinPad(enabled: Boolean, onDigit: (Char) -> Unit, onBack: () -> Unit) {
    val view = LocalView.current
    Column(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f).padding(top = 6.dp, bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (row in listOf("123", "456", "789", " 0<")) {
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                row.forEach { c ->
                    when (c) {
                        ' ' -> Box(Modifier.size(84.dp, 72.dp))
                        '<' -> Box(Modifier.size(84.dp, 72.dp).tap(enabled) { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onBack() }, contentAlignment = Alignment.Center) {
                            Icn(Ic.bksp, C.text2, 26.dp)
                        }
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
        Modifier.size(84.dp, 72.dp).background(if (pressed) C.accS else Color.Transparent)
            .border(1.dp, if (pressed) C.acc else C.line).clickable(src, null, enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Tx(c.toString(), size = 28.sp, color = if (pressed) C.acc else C.text) }
}

@Composable
fun Gap(h: Dp) = Box(Modifier.height(h).width(1.dp))
