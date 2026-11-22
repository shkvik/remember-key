package local.rememberkey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.rememberkey.core.DiagItem
import local.rememberkey.core.Ev
import local.rememberkey.core.KeyRec
import local.rememberkey.core.KeyText
import local.rememberkey.core.PinGuard
import local.rememberkey.core.Ru
import local.rememberkey.core.Schedule
import local.rememberkey.core.St
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("H:mm")
fun timeOf(ms: Long): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(HM)
fun dayOf(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

// ---------- bottom menu ----------

@Composable
fun NavBar(vm: AppVm, current: Scr) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).background(C.bg).drawBehind {
            drawLine(C.dash, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
        },
    ) {
        listOf(Scr.Home to "Ключи", Scr.Journal to "Журнал", Scr.Security to "Защита").forEach { (s, label) ->
            val on = s == current
            Box(Modifier.weight(1f).height(60.dp).tap { if (!on) vm.tab(s) }, contentAlignment = Alignment.Center) {
                Tx(
                    "[$label]", size = 13.sp, color = if (on) Color.Black else C.text3,
                    modifier = if (on) Modifier.background(C.acc).padding(horizontal = 6.dp, vertical = 2.dp) else Modifier,
                )
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
        "Ключи", "rk status", sub = "${Ru.weekday(today)}, ${Ru.date(today, today)}", nav = NavIcon.NONE,
        footer = { NavBar(vm, Scr.Home) },
    ) {
        if (!vm.hwOk) {
            TCard(CardKind.ALERT) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icn(Ic.warn, C.red, 22.dp)
                    Tx("Ключи проверки пропали из чипа", size = 15.5.sp, weight = FontWeight.Bold, color = C.red)
                }
                Tx(
                    "Система удалила ключи RememberKey из защищённого хранилища. Сверять ключи по хешу и открывать копии больше нельзя: придётся начать заново и добавить ключи по памяти. Журнал сохранится.",
                    size = 13.5.sp, color = C.text2,
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
                        size = 15.5.sp, weight = FontWeight.Bold, color = C.red,
                    )
                }
                Tx(
                    "Система стёрла ключ шифрования — так бывает, когда в Android добавляют новый отпечаток. Подсматривать нельзя, пока не введёте ключ по памяти заново: хеш для проверки остался.",
                    size = 13.5.sp, color = C.text2,
                )
                TBtn("Восстановить копию · ${lost[0].name}", Kind.RED) { vm.startRestore(lost[0].id) }
            }
        }
        when {
            d.keys.isEmpty() -> TCard {
                Cap("Начало", C.text3)
                Tx("Ключей пока нет", size = 21.sp, weight = FontWeight.Bold)
                Tx("Добавьте первый ключ — выучим его сегодня же.", size = 13.5.sp, color = C.text2)
                TBtn("Добавить ключ", icon = Ic.add) { vm.startAdd() }
            }
            unlearned != null -> TCard {
                Cap("Не закончено", C.blue)
                Tx("Заучивание · ${unlearned.name}", size = 21.sp, weight = FontWeight.Bold)
                Tx("Откроем ключ по PIN и отпечатку и повторим 3 раза подряд по памяти.", size = 13.5.sp, color = C.text2)
                TBtn("Продолжить") { vm.continueLearn(unlearned.id) }
            }
            due.isNotEmpty() -> TCard(CardKind.DUE) {
                Cap("Сегодня")
                Tx("${due.size} ${Ru.plural(due.size, "проверка", "проверки", "проверок")}", size = 24.sp, weight = FontWeight.ExtraBold)
                Tx("${due.joinToString(", ") { it.name }} · по памяти, около минуты", size = 13.5.sp, color = C.text2)
                TBtn("Начать") { vm.startCheck(due[0].id) }
            }
            else -> {
                val nx = d.keys.minBy { it.due }
                TCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icn(Ic.check, C.acc, 22.dp)
                        Tx("На сегодня всё", size = 17.sp, weight = FontWeight.Bold)
                    }
                    Tx(
                        "Следующая проверка — ${nx.name}, ${Ru.date(nx.due, today)} (${Ru.rel(nx.due, today)}). Напомним уведомлением в ${Reminder.TIME}.",
                        size = 13.5.sp, color = C.text2,
                    )
                }
            }
        }
        if (d.keys.isNotEmpty()) {
            Sect("Мои ключи")
            d.keys.forEach { KeyCard(it, today) { vm.openKey(it.id) } }
            if (vm.vault.freeLetter() != null) {
                Row(
                    Modifier.fillMaxWidth().height(50.dp).dashed(C.dash).tap { vm.startAdd() },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icn(Ic.add, C.text2, 20.dp)
                    Tx(" Добавить ключ", color = C.text2)
                }
            }
        }
    }
}

object Reminder {
    const val TIME = "9:00"
}

@Composable
fun KeyCard(k: KeyRec, today: Long, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().dashed(C.dash).tap(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            KeyAv(k.id, k.fmt)
            Column(Modifier.weight(1f)) {
                Tx(k.name, size = 16.5.sp, weight = FontWeight.Bold, maxLines = 1)
                Tx("${k.fmt.title} · ${KeyText.sizeText(k.fmt, k.size)}", size = 13.sp, color = C.text3, maxLines = 1)
            }
            when {
                !k.learned -> StatusTag("Заучивание", TagKind.BLUE)
                k.due <= today -> StatusTag("Пора проверить", TagKind.AMBER)
                else -> StatusTag(Ru.rel(k.due, today), TagKind.PLAIN)
            }
        }
        Track(k, today)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Tx(if (!k.learned) "Заучивание не закончено" else Schedule.line(k.level), size = 13.sp, color = C.text2, modifier = Modifier.weight(1f), maxLines = 1)
            CopyMark(k.hasCopy)
        }
    }
}

@Composable
fun CopyMark(ok: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icn(Ic.lock, if (ok) C.acc else C.red, 14.dp)
        Tx(if (ok) "копия" else "нет копии", size = 12.sp, color = if (ok) C.acc else C.red)
    }
}

// ---------- one key ----------

@Composable
fun KeyScreen(vm: AppVm, id: String) {
    val k = vm.data.key(id) ?: return vm.home()
    val today = vm.today
    val due = k.learned && k.due <= today
    Page(
        k.name, "rk show $id", onNav = vm::userBack,
        pad = {
            if (!k.learned) TBtn("Продолжить заучивание") { vm.continueLearn(id) }
            else {
                TBtn(if (due) "Проверить сейчас" else "Потренироваться") { vm.startCheck(id) }
                if (k.hasCopy) TBtn("Подсмотреть", Kind.WARN, icon = Ic.lock) { vm.startPeek(id, afterFail = false) }
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            KeyAv(k.id, k.fmt, big = true)
            Column(Modifier.weight(1f)) {
                Tx(k.name, size = 19.sp, weight = FontWeight.Bold)
                Tx("${k.fmt.title} · ${KeyText.sizeText(k.fmt, k.size)} · добавлен ${Ru.date(k.created, today)}", size = 13.5.sp, color = C.text3)
            }
        }
        Stats(listOf(Triple(k.checks, "проверок", false), Triple(k.first, "с первой попытки", false), Triple(k.peeks, "подсмотров", true)))
        if (k.hasCopy) {
            TCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ico(Ic.lock, IcoKind.OK)
                    Column(Modifier.weight(1f)) {
                        Tx("Зашифрованная копия на месте", size = 15.5.sp, weight = FontWeight.Bold)
                        val at = vm.data.healthAt
                        if (at > 0) Tx("Проверено при запуске, ${Ru.dayTitle(dayOf(at), today).lowercase()} в ${timeOf(at)}", size = 13.sp, color = C.text3)
                    }
                }
                Tx("Открывается PIN-кодом приложения и отпечатком. Новый отпечаток в настройках Android её уничтожит.", size = 13.5.sp, color = C.text2)
            }
        } else {
            TCard(CardKind.ALERT) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ico(Ic.warn, IcoKind.BAD)
                    Column(Modifier.weight(1f)) {
                        Tx("Копия уничтожена", size = 15.5.sp, weight = FontWeight.Bold)
                        Tx("Система стёрла ключ отпечатка", size = 13.sp, color = C.text3)
                    }
                }
                Tx("Подсмотреть нельзя. Введите ключ по памяти — сверим с хешем и создадим новую копию.", size = 13.5.sp, color = C.text2)
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
                        if (now) null else Ev.DIAG,
                        "Через ${Ru.days(Schedule.INTERVALS[i])}" + if (i == Schedule.CHECKS - 1) " — и дальше всегда" else "",
                        Ru.date(d, today) + if (now) " · ${Ru.rel(d, today)}" else "",
                        future = true, next = now,
                    )
                    if (i < Schedule.CHECKS - 1) d += Schedule.INTERVALS[i + 1]
                }
            }
            if (rows.isEmpty()) Tx("Расписание начнётся после заучивания", size = 13.5.sp, color = C.text3)
            rows.forEachIndexed { i, r -> Timeline(r, last = i == rows.lastIndex) }
        }
        TLink("Удалить ключ", C.red) { vm.askDelete(k) }
    }
}

class TlRow(val kind: Ev?, val text: String, val date: String, val future: Boolean = false, val next: Boolean = false)

@Composable
private fun Timeline(r: TlRow, last: Boolean) {
    val color = when {
        r.next -> C.amber
        r.future -> C.line
        r.kind == Ev.PEEK -> C.amber
        r.kind == Ev.FAIL -> C.red
        r.kind == Ev.LEARN -> C.blue
        else -> C.acc
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
                val x = 11.5.dp.toPx()
                drawLine(C.dash, Offset(x, 26.dp.toPx()), Offset(x, size.height - 2.dp.toPx()), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
            }
        }.padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(24.dp).background(C.bg).then(if (r.future && !r.next) Modifier.dashed(C.line) else Modifier.border(1.dp, color))
                .then(if (r.next) Modifier.blink() else Modifier),
            contentAlignment = Alignment.Center,
        ) { if (icon != null) Icn(icon, color, 14.dp) }
        Column(Modifier.weight(1f)) {
            Tx(r.text, size = 14.5.sp, color = if (r.future && !r.next) C.text3 else C.text)
            Tx(r.date, size = 13.sp, color = C.text3)
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
    Page("Журнал", "rk log", nav = NavIcon.NONE, footer = { NavBar(vm, Scr.Journal) }) {
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
        if (items.isEmpty()) Tx("Пока пусто", size = 13.5.sp, color = C.text3, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
        items.groupBy { dayOf(it.at) }.forEach { (day, list) ->
            Tx("-- ${Ru.dayTitle(day, today)} --", size = 12.5.sp, color = C.text3, modifier = Modifier.padding(start = 2.dp, top = 6.dp))
            ListBox {
                list.forEachIndexed { i, e ->
                    ListRow(first = i == 0) {
                        Ico(evIcon(e.kind), evKind(e.kind))
                        Column(Modifier.weight(1f)) {
                            Tx(e.title, size = 14.5.sp, weight = FontWeight.Medium)
                            Tx(e.sub, size = 13.sp, color = C.text3)
                        }
                        Tx(timeOf(e.at), size = 13.sp, color = C.text3)
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
    Page("Защита", "rk diag --summary", nav = NavIcon.NONE, footer = { NavBar(vm, Scr.Security) }) {
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
                        size = 17.sp, weight = FontWeight.Bold,
                    )
                    if (diag != null) Tx("Проверено ${Ru.dayTitle(dayOf(diag.at), vm.today).lowercase()} в ${timeOf(diag.at)}", size = 13.sp, color = C.text3)
                }
            }
        }
        Sect("Телефон")
        ListBox {
            val rows: List<DiagItem> = diag?.items ?: emptyList()
            if (rows.isEmpty()) ListRow(first = true) {
                DiagIcon(null)
                Tx("Проверка ещё не запускалась", size = 14.5.sp, color = C.text3)
            }
            rows.forEachIndexed { i, it ->
                ListRow(first = i == 0) {
                    DiagIcon(it.status)
                    Column(Modifier.weight(1f)) {
                        Tx(it.title, size = 14.5.sp)
                        Tx(it.result, size = 13.sp, color = C.text3)
                    }
                }
            }
            ListRow(first = false) {
                DiagIcon(if (lost > 0) St.BAD else St.OK)
                Column(Modifier.weight(1f)) {
                    Tx("Копии ключей", size = 14.5.sp)
                    Tx(if (learned.isEmpty()) "ключей пока нет" else "${learned.size - lost} из ${learned.size} на месте", size = 13.sp, color = C.text3)
                }
            }
        }
        Sect("Подсмотр")
        ListBox {
            ListRow(first = true, onClick = vm::startChangePin) {
                Ico(Ic.lock, IcoKind.PEND)
                Column(Modifier.weight(1f)) {
                    Tx("PIN приложения", size = 14.5.sp)
                    Tx("${PinGuard.LENGTH} цифр · сменить", size = 13.sp, color = C.text3)
                }
                Icn(Ic.chevron, C.text3, 20.dp)
            }
            ListRow(first = false) {
                Ico(Ic.warn, IcoKind.PEND)
                Column(Modifier.weight(1f)) {
                    Tx("Неверный PIN", size = 14.5.sp)
                    Tx("после третьей ошибки пауза 30 с, дальше 1, 5 и 15 минут; ничего не стирается", size = 13.sp, color = C.text3)
                }
            }
            ListRow(first = false) {
                Ico(Ic.eye, IcoKind.PEND)
                Column(Modifier.weight(1f)) {
                    Tx("Экран копии", size = 14.5.sp)
                    Tx("закрывается сам через 60 с и при выходе из приложения; скриншоты и запись экрана запрещены", size = 13.sp, color = C.text3)
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
        "Проверка телефона", "rk diag --run", sub = "временные тестовые ключи, без ваших секретов",
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
                        row.state == "run" -> Box(Modifier.size(32.dp).dashed(C.text3), contentAlignment = Alignment.Center) { Spinner() }
                        else -> DiagIcon(null)
                    }
                    Column(Modifier.weight(1f)) {
                        Tx(row.title, size = 14.5.sp)
                        val sub = when {
                            item != null -> item.result
                            paused -> "отпечаток отменён — нажмите, чтобы повторить"
                            row.state == "run" && row.id == "biotest" -> "ждём отпечаток — ключ временный, секретов в нём нет"
                            row.state == "run" -> "проверяем…"
                            else -> ""
                        }
                        if (sub.isNotEmpty()) Tx(sub, size = 13.sp, color = C.text3)
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
    Page("RememberKey", "rk init", nav = NavIcon.NONE, pad = { TBtn("Проверить телефон") { vm.startDiagFromOnboard() } }) {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(84.dp).border(1.dp, C.acc), contentAlignment = Alignment.Center) { Icn(Ic.key, C.acc, 44.dp) }
            Tx("RememberKey", size = 26.sp, weight = FontWeight.ExtraBold)
            Tx("Тренажёр памяти для ключей, которые нужно помнить всегда", size = 14.5.sp, color = C.text2, align = TextAlign.Center)
        }
        Point(Ic.bulb, IcoKind.BLUE, "Ключ живёт в голове", "Приложение напоминает и проверяет: через 1, 3, 7, 16, 35 дней, дальше каждые 60.")
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
            Tx(title, size = 15.5.sp, weight = FontWeight.Bold)
            Tx(text, size = 13.5.sp, color = C.text2)
        }
    }
}

@Composable
fun Spacer8() = Box(Modifier.width(8.dp))

// ---------- unreadable vault ----------

@Composable
fun BrokenScreen(error: String) {
    Page("RememberKey", "rk status", nav = NavIcon.NONE, center = true) {
        BigIc(Ic.warn, C.red, 44.dp)
        Tx("Не удалось прочитать хранилище", size = 22.sp, weight = FontWeight.Bold, align = TextAlign.Center)
        Tx(
            "Файл с хешами и копиями на месте, но приложение не смогло его разобрать. Чтобы ничего не испортить, оно ничего не записывает. Не удаляйте приложение и не стирайте его данные — обновление с исправлением сможет прочитать файл.",
            size = 14.5.sp, color = C.text2, align = TextAlign.Center,
        )
        Note(error, Ic.warn, NoteKind.RED)
    }
}
