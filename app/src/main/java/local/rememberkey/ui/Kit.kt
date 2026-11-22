package local.rememberkey.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import local.rememberkey.core.Fmt
import local.rememberkey.core.KeyRec
import local.rememberkey.core.Schedule

// ---------- modifiers ----------

/** 1 dp dashed frame, like `border: 1px dashed`. */
fun Modifier.dashed(color: Color, width: Dp = 1.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawRect(
        color, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
    )
}

fun Modifier.frame(color: Color, dashed: Boolean = false): Modifier = if (dashed) dashed(color) else border(1.dp, color)

/** Click without the Material ripple. */
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    clickable(remember { MutableInteractionSource() }, null, enabled, onClick = onClick)
}

/** CRT lines over the whole screen. */
fun Modifier.scanlines(): Modifier = drawWithContent {
    drawContent()
    val step = 3.dp.toPx()
    val h = 1.dp.toPx()
    val c = Color.Black.copy(alpha = 0.18f)
    var y = 0f
    while (y < size.height) {
        drawRect(c, Offset(0f, y), Size(size.width, h))
        y += step
    }
}

// ---------- page ----------

enum class NavIcon { BACK, CLOSE, NONE }

@Composable
fun Page(
    title: String,
    cmd: String?,
    sub: String? = null,
    nav: NavIcon = NavIcon.BACK,
    onNav: () -> Unit = {},
    right: (@Composable () -> Unit)? = null,
    center: Boolean = false,
    pad: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppBar(title, sub, nav, onNav, right)
        if (cmd != null) CmdLine(cmd)
        if (center) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val minH = maxHeight
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = minH).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                    content = body,
                )
            }
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = body,
            )
        }
        if (pad != null) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = pad,
            )
        }
        footer?.invoke()
    }
}

@Composable
fun AppBar(title: String, sub: String?, nav: NavIcon, onNav: () -> Unit, right: (@Composable () -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (nav != NavIcon.NONE) {
            Box(Modifier.size(48.dp).tap(onClick = onNav), contentAlignment = Alignment.Center) {
                Icn(if (nav == NavIcon.BACK) Ic.back else Ic.close, C.text2)
            }
        }
        Column(Modifier.weight(1f).padding(start = if (nav == NavIcon.NONE) 16.dp else 0.dp)) {
            Tx(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = C.text3, fontWeight = FontWeight.Normal)) { append("~/") }
                    append(title)
                },
                size = 17.sp, weight = FontWeight.Bold, maxLines = 1,
            )
            if (sub != null) Tx("# $sub", size = 12.sp, color = C.text3, maxLines = 1)
        }
        right?.invoke()
    }
}

@Composable
fun CmdLine(cmd: String) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        Tx(
            buildAnnotatedString {
                append("rk@rog:~$ ")
                withStyle(SpanStyle(color = C.acc, fontWeight = FontWeight.Medium)) { append(cmd) }
            },
            size = 13.sp, color = C.text3, maxLines = 1, modifier = Modifier.weight(1f, fill = false),
        )
        Tx("_", size = 13.sp, color = C.acc, modifier = Modifier.blink())
    }
}

// ---------- buttons, links ----------

enum class Kind { PRI, SEC, WARN, RED }

@Composable
fun TBtn(
    text: String,
    kind: Kind = Kind.PRI,
    small: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val base = when (kind) {
        Kind.PRI -> C.acc
        Kind.SEC -> C.text2
        Kind.WARN -> C.amber
        Kind.RED -> C.red
    }
    val fg = when {
        !enabled -> C.text3
        pressed && kind != Kind.SEC -> Color.Black
        else -> base
    }
    val bg = when {
        !enabled || !pressed -> Color.Transparent
        kind == Kind.SEC -> C.accS
        else -> base
    }
    val frame = when {
        !enabled -> Modifier.dashed(C.line)
        kind == Kind.SEC -> Modifier.dashed(C.dash)
        else -> Modifier.border(1.dp, base)
    }
    val size = if (small) 13.sp else 15.sp
    Row(
        modifier.fillMaxWidth().height(if (small) 40.dp else 50.dp).background(bg).then(frame)
            .clickable(src, null, enabled, onClick = onClick).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tx("[ ", size = size, color = fg, weight = FontWeight.Medium)
        if (icon != null) {
            Icn(icon, fg, 20.dp)
            Spacer(Modifier.width(8.dp))
        }
        Tx(text.uppercase(), size = size, color = fg, weight = FontWeight.Medium, spacing = 0.04.em, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        Tx(" ]", size = size, color = fg, weight = FontWeight.Medium)
    }
}

@Composable
fun TLink(text: String, color: Color = C.acc, onClick: () -> Unit) {
    Tx("> $text", size = 13.5.sp, color = color, modifier = Modifier.tap(onClick = onClick).padding(vertical = 4.dp))
}

// ---------- blocks ----------

enum class CardKind { NORMAL, DUE, ALERT }

@Composable
fun TCard(kind: CardKind = CardKind.NORMAL, modifier: Modifier = Modifier, gap: Dp = 10.dp, content: @Composable ColumnScope.() -> Unit) {
    val m = when (kind) {
        CardKind.NORMAL -> Modifier.dashed(C.dash)
        CardKind.DUE -> Modifier.dashed(C.dueBorder).drawBehind { drawRect(C.amber, size = Size(3.dp.toPx(), size.height)) }
        CardKind.ALERT -> Modifier.background(C.redS).border(1.dp, C.red)
    }
    Column(modifier.fillMaxWidth().then(m).padding(14.dp), verticalArrangement = Arrangement.spacedBy(gap), content = content)
}

@Composable
fun Cap(text: String, color: Color = C.amber) = Tx(text.uppercase(), size = 12.sp, color = color, weight = FontWeight.Bold, spacing = 0.08.em)

@Composable
fun Sect(text: String) = Tx("# ${text.uppercase()}", size = 12.sp, color = C.text3, spacing = 0.08.em, modifier = Modifier.padding(start = 2.dp, top = 8.dp))

@Composable
fun Lbl(text: String) = Tx("# $text", size = 12.sp, color = C.text3, modifier = Modifier.padding(start = 2.dp, top = 6.dp))

enum class NoteKind { PLAIN, AMBER, RED }

@Composable
fun Note(text: String, icon: ImageVector, kind: NoteKind = NoteKind.PLAIN) {
    val (border, fg, bg) = when (kind) {
        NoteKind.PLAIN -> Triple(C.dash, C.text2, Color.Transparent)
        NoteKind.AMBER -> Triple(C.amber, C.amber, Color.Transparent)
        NoteKind.RED -> Triple(C.red, C.redSoft, C.redS)
    }
    Row(
        Modifier.fillMaxWidth().background(bg).frame(border, dashed = kind != NoteKind.RED).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icn(icon, fg, 18.dp)
        Tx(text, size = 13.sp, color = fg)
    }
}

@Composable
fun ErrLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icn(Ic.warn, C.red, 18.dp, Modifier.padding(top = 1.dp))
        Tx(text, size = 13.5.sp, color = C.red)
    }
}

@Composable
fun Tip(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icn(Ic.bulb, C.amber, 18.dp)
        Tx(text, size = 13.5.sp, color = C.text2)
    }
}

/** `[A]` — the key's letter; phrase keys green, symbol keys blue. */
@Composable
fun KeyAv(id: String, fmt: Fmt, big: Boolean = false) {
    val size = if (big) 26.sp else 17.sp
    Tx(
        buildAnnotatedString {
            withStyle(SpanStyle(color = C.text3)) { append("[") }
            withStyle(SpanStyle(color = if (fmt == Fmt.SYMBOLS) C.blue else C.acc)) { append(id) }
            withStyle(SpanStyle(color = C.text3)) { append("]") }
        },
        size = size, weight = FontWeight.ExtraBold,
    )
}

enum class TagKind { PLAIN, AMBER, BLUE }

@Composable
fun StatusTag(text: String, kind: TagKind) {
    val m = when (kind) {
        TagKind.AMBER -> Modifier.background(C.amber)
        TagKind.BLUE -> Modifier.border(1.dp, C.blue)
        TagKind.PLAIN -> Modifier.border(1.dp, C.text2)
    }
    val fg = when (kind) {
        TagKind.AMBER -> Color.Black
        TagKind.BLUE -> C.blue
        TagKind.PLAIN -> C.text2
    }
    Tx(text, size = 12.sp, color = fg, maxLines = 1, modifier = m.padding(horizontal = 8.dp, vertical = 3.dp))
}

/** Seven cells: learning, then the six checks; the current one blinks when due. */
@Composable
fun Track(k: KeyRec, today: Long, labels: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            TrackCell(if (k.learned) "done" else "now")
            for (i in 0 until Schedule.CHECKS) {
                val st = when {
                    !k.learned -> ""
                    i < k.level -> "done"
                    i == k.level -> if (k.due <= today) "now" else "next"
                    else -> ""
                }
                TrackCell(st)
            }
        }
        if (labels) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                (listOf("учим") + Schedule.INTERVALS.map { "$it" }).forEach {
                    Tx(it, size = 11.sp, color = C.text3, align = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RowScope.TrackCell(st: String) {
    val m = Modifier.weight(1f).height(10.dp)
    when (st) {
        "done" -> Box(m.background(C.acc))
        "now" -> Box(m.background(C.track)) { Box(Modifier.fillMaxSize().blink().background(C.amber)) }
        "next" -> Box(m.border(1.dp, C.acc))
        else -> Box(m.background(C.track))
    }
}

/** Three cells for "3 times in a row". */
@Composable
fun Steps(round: Int, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 1..3) {
                val m = Modifier.weight(1f).height(8.dp)
                when {
                    i < round -> Box(m.background(C.acc))
                    i == round -> Box(m.background(C.track)) { Box(Modifier.fillMaxSize().blink().background(C.blue)) }
                    else -> Box(m.background(C.track))
                }
            }
        }
        Tx(label, size = 13.sp, color = C.text3)
    }
}

enum class IcoKind(val color: Color, val dashed: Boolean = false) {
    OK(C.acc), BAD(C.red), WARN(C.amber), BLUE(C.blue), PEND(C.text3, true)
}

@Composable
fun Ico(icon: ImageVector?, kind: IcoKind, size: Dp = 32.dp, iconSize: Dp = 20.dp) {
    Box(Modifier.size(size).frame(kind.color, kind.dashed), contentAlignment = Alignment.Center) {
        if (icon != null) Icn(icon, kind.color, iconSize)
    }
}

@Composable
fun BigIc(icon: ImageVector, color: Color, iconSize: Dp = 48.dp) {
    Box(Modifier.size(84.dp).border(1.dp, color), contentAlignment = Alignment.Center) { Icn(icon, color, iconSize) }
}

@Composable
fun Stats(items: List<Triple<Int, String, Boolean>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (n, label, amber) ->
            Column(Modifier.weight(1f).dashed(C.dash).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Tx("$n", size = 22.sp, weight = FontWeight.Bold, color = if (amber && n > 0) C.amber else C.text)
                Tx(label, size = 11.5.sp, color = C.text3, lineHeight = 1.25f)
            }
        }
    }
}

/** A dashed box with rows separated by dashed lines. */
@Composable
fun ListBox(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().dashed(C.dash), content = content)
}

@Composable
fun ListRow(first: Boolean, dim: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    var m = Modifier.fillMaxWidth()
    if (!first) m = m.drawBehind {
        drawLine(C.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
    }
    if (onClick != null) m = m.tap(onClick = onClick)
    Row(
        m.padding(horizontal = 12.dp, vertical = 10.dp).alpha(if (dim) 0.45f else 1f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val m = if (on) Modifier.background(C.acc) else Modifier.border(1.dp, C.line)
    Tx(text, size = 13.sp, color = if (on) Color.Black else C.text2, maxLines = 1, modifier = m.tap(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
fun Spinner(size: Dp = 16.dp) {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    Box(
        Modifier.size(size).drawBehind {
            val w = 2.dp.toPx()
            rotate(a) {
                drawRect(C.line, topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w), style = Stroke(w))
                drawLine(C.acc, Offset(0f, w / 2), Offset(this.size.width, w / 2), w)
            }
        },
    )
}
