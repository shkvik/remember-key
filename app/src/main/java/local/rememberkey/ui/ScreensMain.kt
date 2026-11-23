package local.rememberkey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.rememberkey.core.Blocks
import local.rememberkey.core.DiagItem
import local.rememberkey.core.Ev
import local.rememberkey.core.KeyRec
import local.rememberkey.core.KeyText
import local.rememberkey.core.PinGuard
import local.rememberkey.core.Ru
import local.rememberkey.core.Schedule
import local.rememberkey.core.St
import local.rememberkey.core.Strength
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("H:mm")
fun timeOf(ms: Long): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(HM)
fun dayOf(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

object Reminder {
    const val TIME = "9:00"
}

// ---------- bottom menu ----------

@Composable
fun NavBar(vm: AppVm, current: Scr) {
    Row(
        Modifier.fillMaxWidth().height(70.dp).background(C.surf).drawBehind {
            drawLine(C.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
        },
    ) {
        listOf(Triple(Scr.Home, "Ключи", Ic.key), Triple(Scr.Journal, "Журнал", Ic.history), Triple(Scr.Security, "Защита", Ic.shield)).forEach { (s, label, icon) ->
            val on = s == current
            val color = if (on) C.acc else C.text2
            Column(
                Modifier.weight(1f).height(70.dp).tap { if (!on) vm.tab(s) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
            ) {
                Icn(icon, color, 24.dp)
                Tx(label, size = 12.sp, color = color, weight = if (on) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

// ---------- home ----------

@Composable
fun HomeScreen(vm: AppVm) {
    val d = vm.data
    val today = vm.today
    val lost = d.keys.filter { it.learned && !it.hasCopy }
    val due = d.keys.filter { it.learned && it.due <= today }
    val unlearned = d.keys.firstOrNull { !it.learned }
    Page(
        "Ключи", sub = "${Ru.weekday(today)}, ${Ru.date(today, today)}", nav = NavIcon.NONE,
        footer = { NavBar(vm, Scr.Home) }, bottom = C.surf,
        overlay = {
            if (d.keys.isNotEmpty() && vm.vault.freeLetter() != null) {
                Fab(Ic.add, Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 16.dp), onClick = vm::startAdd)
            }
        },
    ) {
        if (!vm.hwOk) {
            TCard(CardKind.ALERT) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icn(Ic.warn, C.red, 22.dp)
                    Tx("Ключи проверки пропали из чипа", size = 16.sp, weight = FontWeight.SemiBold, color = C.red)
                }
                Tx(
                    "Система удалила ключи RememberKey из защищённого хранилища. Сверять ключи по хешу и открывать копии больше нельзя: придётся начать заново и добавить ключи по памяти. Журнал сохранится.",
                    size = 14.sp, color = C.text2,
                )
                TBtn("Начать заново", Kind.RED, onClick = vm::askReset)
            }
        }
        if (lost.isNotEmpty()) {
            TCard(CardKind.ALERT) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icn(Ic.warn, C.red, 22.dp)
                    Tx(
                        if (lost.size > 1) "Копии ключей ${lost.joinToString(" и ") { it.id }} уничтожены" else "Копия ключа ${lost[0].id} уничтожена",
                        size = 16.sp, weight = FontWeight.SemiBold, color = C.red,
                    )
                }
                Tx(
                    "Система стёрла ключ шифрования — так бывает, когда в Android добавляют новый отпечаток. Подсматривать нельзя, пока не введёте ключ по памяти заново: хеш для проверки остался.",
                    size = 14.sp, color = C.text2,
                )
                TBtn("Восстановить копию · ${lost[0].name}", Kind.RED) { vm.startRestore(lost[0].id) }
            }
        }
        when {
            d.keys.isEmpty() -> TCard {
                Cap("Начало", C.text2)
                Tx("Ключей пока нет", size = 21.sp, weight = FontWeight.Bold)
                Tx("Добавьте первый ключ — выучим его сегодня же.", size = 14.sp, color = C.text2)
                TBtn("Добавить ключ", icon = Ic.add) { vm.startAdd() }
            }
            unlearned != null -> TCard {
                Cap("Не закончено", C.acc)
                Tx("Заучивание · ${unlearned.name}", size = 21.sp, weight = FontWeight.Bold)
                Tx(
                    if (unlearned.blocks.isNotEmpty()) "Уроки по блокам: ${lessonLine(unlearned)}. Откроем ключ по PIN и отпечатку и продолжим."
                    else "Откроем ключ по PIN и отпечатку и повторим 3 раза подряд по памяти.",
                    size = 14.sp, color = C.text2,
                )
                TBtn("Продолжить") { vm.continueLearn(unlearned.id) }
            }
            due.isNotEmpty() -> TCard(CardKind.DUE) {
                Cap("Сегодня")
                Tx("${due.size} ${Ru.plural(due.size, "проверка", "проверки", "проверок")}", size = 24.sp, weight = FontWeight.Bold)
                Tx("${due.joinToString(", ") { it.name }} · по памяти, около минуты", size = 14.sp, color = C.text2)
                TBtn("Начать") { vm.startCheck(due[0].id) }
            }
            else -> {
                val nx = d.keys.minBy { it.due }
                TCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icn(Ic.check, C.ok, 22.dp)
                        Tx("На сегодня всё", size = 17.sp, weight = FontWeight.SemiBold)
                    }
                    Tx(
                        "Следующая проверка — ${nx.name}, ${Ru.date(nx.due, today)} (${Ru.rel(nx.due, today)}). Напомним уведомлением в ${Reminder.TIME}.",
                        size = 14.sp, color = C.text2,
                    )
                }
            }
        }
        if (d.keys.isNotEmpty()) {
            Sect("Мои ключи")
            d.keys.forEach { KeyCard(it, today) { vm.openKey(it.id) } }
            Spacer(Modifier.height(64.dp))
        }
    }
}

fun lessonLine(k: KeyRec): String {
    val n = k.parts
    return if (k.lessonBlock >= n) "остался весь ключ целиком" else "блок ${k.lessonBlock + 1} из $n"
}

@Composable
fun KeyCard(k: KeyRec, today: Long, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().surface().tap(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Tx(k.name, size = 17.sp, weight = FontWeight.SemiBold, maxLines = 1)
                Tx(KeyText.sizeText(k.fmt, k.size), size = 13.sp, color = C.text2, maxLines = 1)
            }
            when {
                !k.learned -> StatusTag("Заучивание", TagKind.BLUE)
                k.due <= today -> StatusTag("Пора проверить", TagKind.AMBER)
                else -> StatusTag(Ru.rel(k.due, today), TagKind.PLAIN)
            }
        }
        Track(k, today)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Tx(
                when {
                    !k.learned && k.blocks.isNotEmpty() -> "Заучивание · ${lessonLine(k)}"
                    !k.learned -> "Заучивание не закончено"
                    else -> Schedule.line(k.level)
                },
                size = 13.sp, color = C.text2, modifier = Modifier.weight(1f), maxLines = 1,
            )
            CopyMark(k.hasCopy)
        }
    }
}

@Composable
fun CopyMark(ok: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icn(Ic.lock, if (ok) C.ok else C.red, 14.dp)
        Tx(if (ok) "копия" else "нет копии", size = 12.5.sp, color = if (ok) C.ok else C.red, weight = FontWeight.Medium)
    }
}

// ---------- one key ----------

@Composable
fun KeyScreen(vm: AppVm, id: String) {
    val k = vm.data.key(id) ?: return vm.home()
    val today = vm.today
    val due = k.learned && k.due <= today
    Page(
        k.name, onNav = vm::userBack,
        pad = {
            if (!k.learned) TBtn("Продолжить заучивание") { vm.continueLearn(id) }
            else {
                TBtn(if (due) "Проверить сейчас" else "Потренироваться") { vm.startCheck(id) }
                if (k.hasCopy) TBtn("Подсмотреть", Kind.WARN, icon = Ic.lock) { vm.startPeek(id, afterFail = false) }
            }
        },
    ) {
        Column {
            Tx(k.name, size = 20.sp, weight = FontWeight.SemiBold)
            Tx("${KeyText.sizeText(k.fmt, k.size)} · добавлен ${Ru.date(k.created, today)}", size = 14.sp, color = C.text2)
        }
        Stats(listOf(Triple(k.checks, "проверок", false), Triple(k.first, "с первой попытки", false), Triple(k.peeks, "подсмотров", true)))
        if (k.blocks.isNotEmpty()) BlocksCard(vm, k)
        if (k.hasCopy) {
            TCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ico(Ic.lock, IcoKind.OK)
                    Column(Modifier.weight(1f)) {
                        Tx("Зашифрованная копия на месте", size = 16.sp, weight = FontWeight.SemiBold)
                        val at = vm.data.healthAt
                        if (at > 0) Tx("Проверено при запуске, ${Ru.dayTitle(dayOf(at), today).lowercase()} в ${timeOf(at)}", size = 13.sp, color = C.text2)
                    }
                }
                Tx("Открывается PIN-кодом приложения и отпечатком. Новый отпечаток в настройках Android её уничтожит.", size = 14.sp, color = C.text2)
            }
        } else {
            TCard(CardKind.ALERT) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ico(Ic.warn, IcoKind.BAD)
                    Column(Modifier.weight(1f)) {
                        Tx("Копия уничтожена", size = 16.sp, weight = FontWeight.SemiBold)
                        Tx("Система стёрла ключ отпечатка", size = 13.sp, color = C.text2)
                    }
                }
                Tx("Подсмотреть нельзя. Введите ключ по памяти — сверим с хешем и создадим новую копию.", size = 14.sp, color = C.text2)
                TBtn("Восстановить копию", Kind.RED, small = true) { vm.startRestore(id) }
            }
        }
        Sect("Расписание")
        Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp)) {
            val rows = mutableListOf<TlRow>()
            k.hist.forEach { rows += TlRow(it.kind, it.text, Ru.date(it.day, today)) }
            if (k.learned) {
                var d = k.due
                val from = minOf(k.level, Schedule.CHECKS - 1)
                for (i in from until Schedule.CHECKS) {
                    val now = i == from
                    rows += TlRow(
                        null,
                        "Через ${Ru.days(Schedule.INTERVALS[i])}" + if (i == Schedule.CHECKS - 1) " — и дальше всегда" else "",
                        Ru.date(d, today) + if (now) " · ${Ru.rel(d, today)}" else "",
                        future = true, next = now,
                    )
                    if (i < Schedule.CHECKS - 1) d += Schedule.INTERVALS[i + 1]
                }
            }
            if (rows.isEmpty()) Tx("Расписание начнётся после заучивания", size = 14.sp, color = C.text2)
            rows.forEachIndexed { i, r -> Timeline(r, last = i == rows.lastIndex) }
        }
        TLink("Удалить ключ", C.red) { vm.askDelete(k) }
    }
}

/** How well each block of 4 is remembered; a tap trains that block alone. */
@Composable
private fun BlocksCard(vm: AppVm, k: KeyRec) {
    Sect("Блоки")
    TCard {
        val n = maxOf(k.parts, k.blocks.size)
        for (start in 0 until n step 3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (j in start until start + 3) {
                    Box(Modifier.weight(1f)) {
                        if (j < n) BlockTile(j, k.blocks.getOrElse(j) { emptyList() }) { vm.startBlockPractice(k.id, j) }
                    }
                }
            }
        }
        val weak = k.blocks.indices.filter { Blocks.strength(k.blocks[it]) == Strength.BAD }.map { it + 1 }
        if (weak.isNotEmpty()) {
            Note(
                "Слабее всего ${if (weak.size > 1) "блоки" else "блок"} ${weak.joinToString(", ")} — потренируйте ${if (weak.size > 1) "их" else "его"} отдельно.",
                Ic.warn, NoteKind.AMBER,
            )
        }
        Tx(
            "Нажмите на блок, чтобы потренировать его: 3 раза с подсказкой и 3 по памяти. Нужны PIN и отпечаток, расписание не сдвигается. " +
                "Полоски — последние вводы по памяти в уроках, тренировках и при подсмотре. Обычная проверка идёт по хешу и не знает, в каком блоке ошибка.",
            size = 13.sp, color = C.text2,
        )
    }
}

fun Strength.color(): Color = when (this) {
    Strength.GOOD -> C.ok
    Strength.MID -> C.amber
    Strength.BAD -> C.red
    Strength.NEW -> C.text3
}

@Composable
private fun BlockTile(j: Int, h: List<Boolean>, onClick: () -> Unit) {
    val st = Blocks.strength(h)
    val last = h.takeLast(Blocks.RECENT)
    Column(
        Modifier.fillMaxWidth().surface(C.surf2, R12).tap(onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tx("Блок ${j + 1}", size = 13.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(Modifier.size(8.dp).clip(CircleShape).background(st.color()))
        }
        Tx("••••", size = 15.sp, color = C.text3, family = Mono, spacing = 2.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (m in 0 until Blocks.RECENT) {
                val r = last.getOrNull(m)
                val c = when (r) {
                    true -> C.ok
                    false -> C.red
                    null -> C.line2.copy(alpha = 0.5f)
                }
                Box(Modifier.weight(1f).height(5.dp).clip(Pill).background(c))
            }
        }
        Tx(st.title, size = 12.sp, color = st.color(), maxLines = 2, lineHeight = 1.25f)
    }
}

class TlRow(val kind: Ev?, val text: String, val date: String, val future: Boolean = false, val next: Boolean = false)

@Composable
private fun Timeline(r: TlRow, last: Boolean) {
    val kind = when {
        r.future -> IcoKind.PEND
        r.kind == Ev.PEEK -> IcoKind.WARN
        r.kind == Ev.FAIL -> IcoKind.BAD
        r.kind == Ev.LEARN -> IcoKind.BLUE
        else -> IcoKind.OK
    }
    val icon: ImageVector? = when {
        r.future -> null
        r.kind == Ev.PEEK -> Ic.eye
        r.kind == Ev.FAIL -> Ic.close
        r.kind == Ev.LEARN -> Ic.key
        else -> Ic.check
    }
    Row(
        Modifier.fillMaxWidth().drawBehind {
            if (!last) {
                val x = 15.dp.toPx()
                drawRoundRect(C.line, topLeft = Offset(x, 36.dp.toPx()), size = Size(2.dp.toPx(), size.height - 38.dp.toPx()), cornerRadius = CornerRadius(1.dp.toPx()))
            }
        }.padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (r.next) {
            Box(Modifier.size(32.dp).blink(low = 0.35f).clip(CircleShape).background(C.surf).border(2.dp, C.amber, CircleShape))
        } else Ico(icon, kind, 32.dp, 15.dp)
        Column(Modifier.weight(1f).padding(top = 5.dp)) {
            Tx(r.text, size = 15.sp, color = if (r.future && !r.next) C.text3 else C.text)
            Tx(r.date, size = 13.sp, color = C.text2)
        }
    }
}

// ---------- journal ----------

@Composable
fun JournalScreen(vm: AppVm, filter: String, onFilter: (String) -> Unit) {
    val d = vm.data
    val today = vm.today
    val groups = mapOf("peek" to setOf(Ev.PEEK, Ev.PIN, Ev.LOST, Ev.RESTORE, Ev.DELETE), "checks" to setOf(Ev.OK, Ev.FAIL, Ev.LEARN))
    val items = d.log.asReversed().filter { filter == "all" || it.kind in groups.getValue(filter) }
    Page("Журнал", nav = NavIcon.NONE, footer = { NavBar(vm, Scr.Journal) }, bottom = C.surf) {
        Stats(
            listOf(
                Triple(d.keys.sumOf { it.checks }, "проверок", false),
                Triple(d.keys.sumOf { it.first }, "с первой попытки", false),
                Triple(d.keys.sumOf { it.peeks }, "подсмотров", true),
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Всё", filter == "all") { onFilter("all") }
            Chip("Подсмотры и PIN", filter == "peek") { onFilter("peek") }
            Chip("Проверки", filter == "checks") { onFilter("checks") }
        }
        if (items.isEmpty()) Tx("Пока пусто", size = 14.sp, color = C.text2, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
        items.groupBy { dayOf(it.at) }.forEach { (day, list) ->
            Sect(Ru.dayTitle(day, today))
            ListBox {
                list.forEachIndexed { i, e ->
                    ListRow(first = i == 0) {
                        Ico(evIcon(e.kind), evKind(e.kind))
                        Column(Modifier.weight(1f)) {
                            Tx(e.title, size = 15.sp, weight = FontWeight.SemiBold)
                            Tx(e.sub, size = 13.sp, color = C.text2)
                        }
                        Tx(timeOf(e.at), size = 13.sp, color = C.text2)
                    }
                }
            }
        }
        Note("Журнал нельзя очистить: если ключ подсматривал кто-то другой, вы это увидите.", Ic.lock)
    }
}

fun evIcon(k: Ev): ImageVector = when (k) {
    Ev.OK -> Ic.check
    Ev.FAIL -> Ic.close
    Ev.PEEK -> Ic.eye
    Ev.PIN -> Ic.lock
    Ev.LEARN -> Ic.key
    Ev.ADD -> Ic.add
    Ev.DIAG -> Ic.shield
    Ev.RESTORE -> Ic.lock
    Ev.LOST -> Ic.warn
    Ev.DELETE -> Ic.close
}

fun evKind(k: Ev): IcoKind = when (k) {
    Ev.OK, Ev.RESTORE -> IcoKind.OK
    Ev.FAIL, Ev.PIN, Ev.LOST, Ev.DELETE -> IcoKind.BAD
    Ev.PEEK -> IcoKind.WARN
    Ev.LEARN, Ev.ADD, Ev.DIAG -> IcoKind.BLUE
}

// ---------- security ----------

@Composable
private fun DiagIcon(st: St?) = when (st) {
    St.OK -> Ico(Ic.check, IcoKind.OK)
    St.WARN -> Ico(Ic.warn, IcoKind.WARN)
    St.BAD -> Ico(Ic.warn, IcoKind.BAD)
    null -> Ico(null, IcoKind.PEND)
}

@Composable
fun SecurityScreen(vm: AppVm) {
    val d = vm.data
    val learned = d.keys.filter { it.learned }
    val lost = learned.count { !it.hasCopy }
    val diag = d.diag
    val warns = (diag?.items?.count { it.status != St.OK } ?: 0) + (if (lost > 0) 1 else 0)
    Page("Защита", nav = NavIcon.NONE, footer = { NavBar(vm, Scr.Security) }, bottom = C.surf) {
        TCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Ico(Ic.shield, if (warns > 0) IcoKind.WARN else IcoKind.OK, size = 44.dp, iconSize = 26.dp)
                Column(Modifier.weight(1f)) {
                    Tx(
                        when {
                            diag == null -> "Телефон не проверен"
                            warns > 0 -> "$warns ${Ru.plural(warns, "замечание", "замечания", "замечаний")}"
                            else -> "Всё в порядке"
                        },
                        size = 17.sp, weight = FontWeight.SemiBold,
                    )
                    if (diag != null) Tx("Проверено ${Ru.dayTitle(dayOf(diag.at), vm.today).lowercase()} в ${timeOf(diag.at)}", size = 13.sp, color = C.text2)
                }
            }
        }
        Sect("Телефон")
        ListBox {
            val rows: List<DiagItem> = diag?.items ?: emptyList()
            if (rows.isEmpty()) ListRow(first = true) {
                DiagIcon(null)
                Tx("Проверка ещё не запускалась", size = 15.sp, color = C.text2)
            }
            rows.forEachIndexed { i, it ->
                ListRow(first = i == 0) {
                    DiagIcon(it.status)
                    Column(Modifier.weight(1f)) {
                        Tx(it.title, size = 15.sp)
                        Tx(it.result, size = 13.sp, color = C.text2)
                    }
                }
            }
            ListRow(first = false) {
                DiagIcon(if (lost > 0) St.BAD else St.OK)
                Column(Modifier.weight(1f)) {
                    Tx("Копии ключей", size = 15.sp)
                    Tx(if (learned.isEmpty()) "ключей пока нет" else "${learned.size - lost} из ${learned.size} на месте", size = 13.sp, color = C.text2)
                }
            }
        }
        Sect("Подсмотр")
        ListBox {
            ListRow(first = true, onClick = vm::startChangePin) {
                Ico(Ic.lock, IcoKind.PEND)
                Column(Modifier.weight(1f)) {
                    Tx("PIN приложения", size = 15.sp)
                    Tx("${PinGuard.LENGTH} цифр · сменить", size = 13.sp, color = C.text2)
                }
                Icn(Ic.chevron, C.text3, 20.dp)
            }
            ListRow(first = false) {
                Ico(Ic.warn, IcoKind.PEND)
                Column(Modifier.weight(1f)) {
                    Tx("Неверный PIN", size = 15.sp)
                    Tx("после третьей ошибки пауза 30 с, дальше 1, 5 и 15 минут; ничего не стирается", size = 13.sp, color = C.text2)
                }
            }
            ListRow(first = false) {
                Ico(Ic.eye, IcoKind.PEND)
                Column(Modifier.weight(1f)) {
                    Tx("Экран копии", size = 15.sp)
                    Tx("закрывается сам через 60 с и при выходе из приложения; скриншоты и запись экрана запрещены", size = 13.sp, color = C.text2)
                }
            }
        }
        TBtn(if (diag == null) "Проверить телефон" else "Проверить заново", Kind.SEC) { vm.startDiagFromSecurity() }
    }
}

// ---------- phone check ----------

@Composable
fun DiagScreen(vm: AppVm, fromOnboard: Boolean) {
    Page(
        "Проверка телефона", sub = "временные тестовые ключи, без ваших секретов",
        nav = if (fromOnboard) NavIcon.NONE else NavIcon.BACK, onNav = vm::userBack,
        pad = { TBtn(if (fromOnboard) "Дальше: PIN приложения" else "Готово", enabled = vm.diagDone) { vm.diagNext(fromOnboard) } },
    ) {
        ListBox {
            vm.diagRows.forEachIndexed { i, row ->
                val item = row.item
                val paused = row.state == "paused"
                ListRow(first = i == 0, dim = item == null && row.state == null, onClick = if (paused) vm::diagRetry else null) {
                    when {
                        item != null -> DiagIcon(item.status)
                        paused -> Ico(Ic.warn, IcoKind.WARN)
                        row.state == "run" -> Box(Modifier.size(36.dp).clip(CircleShape).background(C.surf2), contentAlignment = Alignment.Center) { Spinner() }
                        else -> DiagIcon(null)
                    }
                    Column(Modifier.weight(1f)) {
                        Tx(row.title, size = 15.sp)
                        val sub = when {
                            item != null -> item.result
                            paused -> "отпечаток отменён — нажмите, чтобы повторить"
                            row.state == "run" && row.id == "biotest" -> "ждём отпечаток — ключ временный, секретов в нём нет"
                            row.state == "run" -> "проверяем…"
                            else -> ""
                        }
                        if (sub.isNotEmpty()) Tx(sub, size = 13.sp, color = C.text2)
                    }
                }
            }
        }
        if (vm.diagDone) {
            val (warns, bad) = vm.diagSummary()
            val text = when {
                bad -> "Есть серьёзные проблемы (красные строки). Копии ключей на таком телефоне защищены хуже, чем задумано."
                warns > 0 -> "Телефон подходит: ключи шифрования хранятся аппаратно, отпечаток класса 3. Замечаний: $warns — жёлтые строки."
                else -> "Телефон подходит: ключи шифрования хранятся аппаратно, отпечаток класса 3, загрузчик заблокирован."
            }
            Note(text, Ic.shield, if (bad) NoteKind.RED else NoteKind.PLAIN)
        }
    }
}

// ---------- first start ----------

@Composable
fun OnboardScreen(vm: AppVm) {
    Page("RememberKey", nav = NavIcon.NONE, pad = { TBtn("Проверить телефон") { vm.startDiagFromOnboard() } }) {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(88.dp).clip(RoundedCornerShape(26.dp)).background(C.acc), contentAlignment = Alignment.Center) { Icn(Ic.key, C.onAcc, 44.dp) }
            Tx("Тренажёр памяти для ключей, которые нужно помнить всегда", size = 15.sp, color = C.text2, align = TextAlign.Center)
        }
        Point(Ic.bulb, IcoKind.BLUE, "Ключ живёт в голове", "Учим блоками по 4 символа. Потом приложение напоминает и проверяет: через 1, 3, 7, 16, 35 дней, дальше каждые 60.")
        Point(Ic.hash, IcoKind.OK, "Проверка — по хешу", "Чтобы проверить, ключ не расшифровывается. PIN и отпечаток не нужны.")
        Point(Ic.eye, IcoKind.WARN, "Подсмотреть — по PIN и отпечатку", "Сначала по частям, целиком — в крайнем случае. Каждый подсмотр попадает в журнал.")
        Point(
            Ic.warn, IcoKind.BAD, "Копия в телефоне хрупкая",
            "Новый отпечаток в настройках уничтожит её — тогда ключ нужно ввести по памяти заново. Сброс, удаление приложения или потеря телефона уничтожат всё.",
        )
    }
}

@Composable
private fun Point(icon: ImageVector, kind: IcoKind, title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Ico(icon, kind)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Tx(title, size = 16.sp, weight = FontWeight.SemiBold)
            Tx(text, size = 14.sp, color = C.text2)
        }
    }
}

// ---------- unreadable vault ----------

@Composable
fun BrokenScreen(error: String) {
    Page("RememberKey", nav = NavIcon.NONE, center = true) {
        BigIc(Ic.warn, C.red, 44.dp)
        Tx("Не удалось прочитать хранилище", size = 22.sp, weight = FontWeight.Bold, align = TextAlign.Center)
        Tx(
            "Файл с хешами и копиями на месте, но приложение не смогло его разобрать. Чтобы ничего не испортить, оно ничего не записывает. Не удаляйте приложение и не стирайте его данные — обновление с исправлением сможет прочитать файл.",
            size = 15.sp, color = C.text2, align = TextAlign.Center,
        )
        Note(error, Ic.warn, NoteKind.RED)
    }
}
