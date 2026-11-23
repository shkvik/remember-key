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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import local.rememberkey.core.KeyRec
import local.rememberkey.core.Schedule

// ---------- shapes, modifiers ----------

val R8 = RoundedCornerShape(8.dp)
val R12 = RoundedCornerShape(12.dp)
val R14 = RoundedCornerShape(14.dp)
val R16 = RoundedCornerShape(16.dp)
val Pill = RoundedCornerShape(50)

/** Click without the Material ripple. */
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    clickable(remember { MutableInteractionSource() }, null, enabled, onClick = onClick)
}

/** A rounded surface: the basic card. */
fun Modifier.surface(color: Color = C.surf, shape: androidx.compose.ui.graphics.Shape = R16): Modifier = clip(shape).background(color)

// ---------- page ----------

enum class NavIcon { BACK, CLOSE, NONE }

/**
 * A screen: app bar (large title on the tabs), scrolling body, buttons, then a footer (menu or keyboard).
 * [bottom] colors the strip behind the system gesture bar, so it continues the footer.
 */
@Composable
fun Page(
    title: String,
    sub: String? = null,
    nav: NavIcon = NavIcon.BACK,
    onNav: () -> Unit = {},
    right: (@Composable () -> Unit)? = null,
    center: Boolean = false,
    pad: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    bottom: Color = C.bg,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AppBar(title, sub, nav, onNav, right)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (center) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
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
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = body,
                )
            }
            overlay?.invoke(this)
        }
        if (pad != null) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = pad,
            )
        }
        footer?.invoke()
        Spacer(Modifier.fillMaxWidth().background(bottom).windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
fun AppBar(title: String, sub: String?, nav: NavIcon, onNav: () -> Unit, right: (@Composable () -> Unit)?) {
    val large = nav == NavIcon.NONE
    Row(
        Modifier.fillMaxWidth().height(if (large) 76.dp else 56.dp).padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!large) {
            Box(Modifier.size(48.dp).clip(CircleShape).tap(onClick = onNav), contentAlignment = Alignment.Center) {
                Icn(if (nav == NavIcon.BACK) Ic.back else Ic.close, C.text)
            }
        }
        Column(Modifier.weight(1f).padding(start = if (large) 16.dp else 0.dp)) {
            Tx(title, size = if (large) 28.sp else 18.sp, weight = if (large) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, lineHeight = 1.2f)
            if (sub != null) Tx(sub, size = 13.sp, color = C.text2, maxLines = 1)
        }
        right?.invoke()
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
    val (bg, fg) = when {
        !enabled -> C.surf2 to C.text3
        kind == Kind.PRI -> C.acc to C.onAcc
        kind == Kind.SEC -> Color.Transparent to C.acc
        kind == Kind.WARN -> C.amberS to C.amber
        else -> C.redS to C.red
    }
    var m = modifier.fillMaxWidth().height(if (small) 40.dp else 48.dp).clip(Pill).background(bg)
    if (kind == Kind.SEC && enabled) m = m.border(1.5.dp, C.line2, Pill)
    Row(
        m.clickable(src, null, enabled, onClick = onClick).alpha(if (pressed) 0.8f else 1f).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icn(icon, fg, 20.dp)
            Spacer(Modifier.width(8.dp))
        }
        Tx(text, size = if (small) 14.sp else 15.sp, color = fg, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun TLink(text: String, color: Color = C.acc, onClick: () -> Unit) {
    Tx(text, size = 14.sp, color = color, weight = FontWeight.SemiBold, modifier = Modifier.clip(R8).tap(onClick = onClick).padding(vertical = 4.dp))
}

/** The floating round "+" of the main screen. */
@Composable
fun Fab(icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(58.dp).drawBehind { drawCircle(Color.Black.copy(alpha = 0.16f), radius = size.minDimension / 2, center = center + Offset(0f, 2.dp.toPx())) }
            .clip(CircleShape).background(C.acc).tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icn(icon, C.onAcc, 30.dp) }
}

// ---------- blocks ----------

enum class CardKind { NORMAL, DUE, ALERT }

@Composable
fun TCard(kind: CardKind = CardKind.NORMAL, modifier: Modifier = Modifier, gap: Dp = 10.dp, content: @Composable ColumnScope.() -> Unit) {
    var m = modifier.fillMaxWidth().surface(if (kind == CardKind.ALERT) C.redS else C.surf)
    if (kind == CardKind.DUE) m = m.border(1.5.dp, C.acc, R16)
    Column(m.padding(16.dp), verticalArrangement = Arrangement.spacedBy(gap), content = content)
}

@Composable
fun Cap(text: String, color: Color = C.amber) = Tx(text.uppercase(), size = 12.sp, color = color, weight = FontWeight.Bold, spacing = 0.06.em)

@Composable
fun Sect(text: String) = Tx(text.uppercase(), size = 12.sp, color = C.text2, weight = FontWeight.SemiBold, spacing = 0.06.em, modifier = Modifier.padding(start = 4.dp, top = 10.dp))

@Composable
fun Lbl(text: String) = Tx(text.uppercase(), size = 12.sp, color = C.text2, weight = FontWeight.SemiBold, spacing = 0.06.em, modifier = Modifier.padding(start = 4.dp, top = 8.dp))

enum class NoteKind { PLAIN, AMBER, RED }

@Composable
fun Note(text: String, icon: ImageVector, kind: NoteKind = NoteKind.PLAIN) {
    val (fg, bg) = when (kind) {
        NoteKind.PLAIN -> C.text2 to C.surf
        NoteKind.AMBER -> C.amber to C.amberS
        NoteKind.RED -> C.red to C.redS
    }
    Row(Modifier.fillMaxWidth().surface(bg, R14).padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icn(icon, fg, 18.dp)
        Tx(text, size = 13.5.sp, color = fg)
    }
}

@Composable
fun ErrLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icn(Ic.warn, C.red, 18.dp, Modifier.padding(top = 1.dp))
        Tx(text, size = 14.sp, color = C.red)
    }
}

@Composable
fun Tip(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icn(Ic.bulb, C.amber, 18.dp)
        Tx(text, size = 14.sp, color = C.text2)
    }
}

enum class TagKind { PLAIN, AMBER, BLUE }

@Composable
fun StatusTag(text: String, kind: TagKind) {
    val (fg, bg) = when (kind) {
        TagKind.AMBER -> C.amber to C.amberS
        TagKind.BLUE -> C.acc to C.accS
        TagKind.PLAIN -> C.text2 to C.surf2
    }
    Tx(text, size = 12.sp, color = fg, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.clip(Pill).background(bg).padding(horizontal = 10.dp, vertical = 4.dp))
}

/** Seven bars: learning, then the six checks; the current one pulses when due. */
@Composable
fun Track(k: KeyRec, today: Long, labels: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (listOf("учим") + Schedule.INTERVALS.map { "$it" }).forEach {
                    Tx(it, size = 11.sp, color = C.text3, align = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RowScope.TrackCell(st: String) {
    val m = Modifier.weight(1f).height(6.dp).clip(Pill)
    when (st) {
        "done" -> Box(m.background(C.acc))
        "now" -> Box(m.blink(low = 0.3f).background(C.amber))
        "next" -> Box(m.background(C.accS).border(1.5.dp, C.acc, Pill))
        else -> Box(m.background(C.surf2))
    }
}

/** Three bars for "3 times in a row". */
@Composable
fun Steps(round: Int, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 1..3) {
                val m = Modifier.weight(1f).height(6.dp).clip(Pill)
                when {
                    i < round -> Box(m.background(C.acc))
                    i == round -> Box(m.blink(low = 0.3f).background(C.acc))
                    else -> Box(m.background(C.surf2))
                }
            }
        }
        Tx(label, size = 13.sp, color = C.text2)
    }
}

enum class IcoKind { OK, BAD, WARN, BLUE, PEND }

fun IcoKind.fg(): Color = when (this) {
    IcoKind.OK -> C.ok
    IcoKind.BAD -> C.red
    IcoKind.WARN -> C.amber
    IcoKind.BLUE -> C.acc
    IcoKind.PEND -> C.text2
}

fun IcoKind.bg(): Color = when (this) {
    IcoKind.OK -> C.okS
    IcoKind.BAD -> C.redS
    IcoKind.WARN -> C.amberS
    IcoKind.BLUE -> C.accS
    IcoKind.PEND -> C.surf2
}

/** A tinted circle with an icon: list rows, journal, statuses. */
@Composable
fun Ico(icon: ImageVector?, kind: IcoKind, size: Dp = 36.dp, iconSize: Dp = 20.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(kind.bg()), contentAlignment = Alignment.Center) {
        if (icon != null) Icn(icon, kind.fg(), iconSize)
    }
}

@Composable
fun BigIc(icon: ImageVector, color: Color, iconSize: Dp = 46.dp) {
    Box(Modifier.size(88.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Icn(icon, color, iconSize) }
}

@Composable
fun Stats(items: List<Triple<Int, String, Boolean>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (n, label, amber) ->
            Column(Modifier.weight(1f).surface(shape = R14).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Tx("$n", size = 22.sp, weight = FontWeight.Bold, color = if (amber && n > 0) C.amber else C.text)
                Tx(label, size = 12.sp, color = C.text2, lineHeight = 1.25f)
            }
        }
    }
}

/** A rounded list with thin dividers. */
@Composable
fun ListBox(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().surface(), content = content)
}

@Composable
fun ListRow(first: Boolean, dim: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    var m = Modifier.fillMaxWidth()
    if (!first) m = m.drawBehind { drawLine(C.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
    if (onClick != null) m = m.tap(onClick = onClick)
    Row(
        m.padding(horizontal = 16.dp, vertical = 12.dp).alpha(if (dim) 0.45f else 1f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Tx(
        text, size = 13.5.sp, color = if (on) C.onAcc else C.text2, weight = FontWeight.Medium, maxLines = 1,
        modifier = Modifier.clip(Pill).background(if (on) C.acc else C.surf).tap(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
fun Spinner(size: Dp = 18.dp) {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    Box(
        Modifier.size(size).drawBehind {
            val w = 2.dp.toPx()
            drawCircle(C.line2, radius = this.size.minDimension / 2 - w / 2, style = Stroke(w))
            drawArc(C.acc, a, 90f, false, topLeft = Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
        },
    )
}
