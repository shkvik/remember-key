package local.rememberkey.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import local.rememberkey.core.Fmt
import local.rememberkey.core.KeyRec
import local.rememberkey.core.KeyText
import local.rememberkey.core.Ru
import local.rememberkey.core.Schedule

// ---------- typing a key ----------

@Composable
private fun InputPage(
    vm: AppVm,
    title: String,
    cmd: String,
    fmt: Fmt,
    prompt: String,
    hint: String,
    sub: String? = null,
    btn: String = "Проверить",
    onClose: () -> Unit = vm::userBack,
    top: (@Composable ColumnScope.() -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val input = vm.input
    val busy = vm.busy
    Page(
        title, cmd, sub = sub, nav = NavIcon.CLOSE, onNav = onClose,
        pad = { TBtn(busy ?: btn, enabled = input.buf.isNotBlank() && busy == null, onClick = vm::submit) },
        footer = { AppKeyboard(input, fmt, enabled = busy == null, onSubmit = vm::submit) },
    ) {
        top?.invoke(this)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Tx(prompt, size = 19.sp, weight = FontWeight.Bold)
            Tx(hint, size = 13.5.sp, color = C.text3)
        }
        KeyField(input, fmt, if (fmt == Fmt.WORDS) "Слова через пробел" else "Символы без пробелов")
        if (input.buf.isNotBlank()) Tx("Набрано: ${KeyText.sizeText(fmt, KeyText.size(fmt, input.buf))}", size = 13.sp, color = C.text3)
        if (input.err.isNotEmpty()) ErrLine(input.err)
        below?.invoke(this)
    }
}

@Composable
fun CheckScreen(vm: AppVm, s: Scr.Check) {
    val k = vm.data.key(s.id) ?: return vm.home()
    val size = KeyText.sizeText(k.fmt, k.size)
    val title = "${s.mode.title} · ${k.name}"
    when (s.mode) {
        Mode.PRACTICE -> InputPage(
            vm, title, "rk practice ${k.id} --times 3", k.fmt,
            prompt = if (vm.round == 1) "Введите ключ по памяти" else "Ещё раз — по памяти",
            hint = "$size · вне расписания, проверка ${Ru.date(k.due, vm.today)} не сдвинется",
            top = { Steps(vm.round, "Ввод ${vm.round} из 3") },
            below = {
                if (vm.practiceFailed && k.hasCopy) TLink("Подсмотреть — PIN и отпечаток", C.amber) { vm.startPeek(k.id, afterFail = true) }
            },
        )
        Mode.CHECK -> InputPage(
            vm, title, "rk check ${k.id}", k.fmt, "Введите ключ по памяти",
            size + if (s.attempt > 1) " · попытка ${s.attempt}" else "",
        )
        Mode.RELEARN -> InputPage(vm, title, "rk recall ${k.id}", k.fmt, "Теперь — по памяти", "$size · копия скрыта, проверка — ${Ru.rel(k.due, vm.today)}")
        Mode.RESTORE -> InputPage(
            vm, title, "rk restore ${k.id}", k.fmt, "Введите ключ по памяти", "$size · сверим с хешем и создадим новую копию",
            btn = "Сверить с хешем",
        )
    }
}

@Composable
fun ResultScreen(vm: AppVm, r: Scr.Result) {
    val k = vm.data.key(r.id) ?: return vm.home()
    val today = vm.today
    val title = if (r.kind == RKind.RESTORED) "Восстановление" else r.mode.title
    Page(
        "$title · ${k.name}", "rk ${if (r.kind == RKind.RESTORED) "restore" else "check"} ${k.id}",
        nav = NavIcon.CLOSE, onNav = vm::userHome, center = true,
        pad = {
            when (r.kind) {
                RKind.OK -> {
                    val nx = vm.nextDue(k.id)
                    if (nx != null) {
                        TBtn("Дальше: ${nx.name}") { vm.startCheck(nx.id) }
                        TBtn("На главную", Kind.SEC, onClick = vm::userHome)
                    } else TBtn("Готово", onClick = vm::userHome)
                }
                RKind.FAIL -> {
                    TBtn("Ещё раз") { vm.retry(r) }
                    if (k.hasCopy) TBtn("Подсмотреть", Kind.WARN, icon = Ic.lock) { vm.startPeek(k.id, afterFail = true) }
                }
                RKind.RESTORED -> {
                    val nl = vm.nextLost()
                    if (nl != null) {
                        TBtn("Дальше: ${nl.name}") { vm.startRestore(nl.id) }
                        TBtn("На главную", Kind.SEC, onClick = vm::userHome)
                    } else TBtn("Готово", onClick = vm::userHome)
                }
            }
        },
    ) {
        when (r.kind) {
            RKind.OK -> {
                BigIc(Ic.check, C.acc)
                Tx("Верно!", size = 24.sp, weight = FontWeight.ExtraBold)
                val text = when (r.mode) {
                    Mode.CHECK -> if (r.attempt == 1) "Следующая проверка ${Ru.rel(k.due, today)} — ${Ru.date(k.due, today)}."
                    else "Верно ${Ru.fromAttempt(r.attempt)}, поэтому интервал не растёт: снова ${Ru.rel(k.due, today)} — ${Ru.date(k.due, today)}."
                    Mode.RELEARN -> "Ключ снова на повторе: проверка ${Ru.rel(k.due, today)}, ${Ru.date(k.due, today)}. Интервалы пойдут заново — 1, 3, 7…"
                    else -> "Три раза верно. Это тренировка — расписание не меняется: проверка ${Ru.date(k.due, today)}."
                }
                Tx(text, size = 14.5.sp, color = C.text2, align = TextAlign.Center, modifier = Modifier.widthIn(max = 340.dp))
                TCard {
                    Track(k, today, labels = true)
                    Tx(
                        if (k.level >= Schedule.CHECKS) "Поддержание: каждые 60 дней" else "Пройдено проверок: ${k.level} из 6, потом — каждые 60 дней",
                        size = 13.sp, color = C.text3,
                    )
                }
            }
            RKind.FAIL -> {
                BigIc(Ic.close, C.red)
                Tx("Не совпало", size = 24.sp, weight = FontWeight.ExtraBold)
                Tx(
                    "Хеш не подсказывает, где ошибка. Попробуйте ещё раз — например, тот вариант, в котором сомневаетесь.",
                    size = 14.5.sp, color = C.text2, align = TextAlign.Center, modifier = Modifier.widthIn(max = 340.dp),
                )
                if (k.hasCopy) {
                    Tx(
                        "Подсмотр — по PIN и отпечатку, попадает в журнал. После него ключ вернётся на проверку завтра.",
                        size = 13.sp, color = C.text3, align = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp),
                    )
                } else Note("Копия ключа уничтожена — подсмотреть нельзя. Остаётся вспоминать.", Ic.warn, NoteKind.RED)
            }
            RKind.RESTORED -> {
                BigIc(Ic.lock, C.acc, 44.dp)
                Tx("Копия восстановлена", size = 24.sp, weight = FontWeight.ExtraBold)
                Tx("${k.name} снова можно подсмотреть по PIN и отпечатку.", size = 14.5.sp, color = C.text2, align = TextAlign.Center)
            }
        }
    }
}

// ---------- PIN ----------

@Composable
private fun PinCountdown(vm: AppVm): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(vm.pinWaitUntil) {
        while (SystemClock.elapsedRealtime() < vm.pinWaitUntil) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
        now = SystemClock.elapsedRealtime()
    }
    return ((vm.pinWaitUntil - now + 999) / 1000).coerceAtLeast(0)
}

@Composable
fun PinScreen(vm: AppVm, s: Scr.Pin) {
    val left = PinCountdown(vm)
    val busy = vm.busy
    Page(
        vm.pinTitle(s), "rk unlock ${s.id ?: ""} --pin".replace("  ", " "), nav = NavIcon.CLOSE, onNav = vm::userBack, center = true,
        footer = { PinPad(enabled = busy == null && left == 0L, onDigit = vm::pinDigit, onBack = vm::pinBack) },
    ) {
        BigIc(Ic.lock, C.blue, 44.dp)
        Tx(if (s.purpose == PinFor.CHANGE) "Введите текущий PIN" else "Введите PIN приложения", size = 19.sp, weight = FontWeight.Bold)
        PinDots(vm.pinBuf.length, vm.pinErr.isNotEmpty(), vm.pinShake)
        when {
            busy != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner()
                Tx(busy, size = 13.5.sp, color = C.text2)
            }
            left > 0 -> Tx("Слишком много ошибок. Следующая попытка через $left с", size = 13.5.sp, color = C.red, align = TextAlign.Center)
            vm.pinErr.isNotEmpty() -> Tx(vm.pinErr, size = 13.5.sp, color = C.red, align = TextAlign.Center)
            s.purpose == PinFor.CHANGE -> Tx("Потом — новый PIN дважды", size = 13.5.sp, color = C.text3, align = TextAlign.Center)
            else -> Tx("Потом — отпечаток пальца", size = 13.5.sp, color = C.text3, align = TextAlign.Center)
        }
    }
}

@Composable
fun PinSetScreen(vm: AppVm, s: Scr.PinSet) {
    val busy = vm.busy
    Page(
        if (s.change) "Новый PIN" else "PIN приложения", if (s.change) "rk pin --change" else "rk pin --set",
        sub = "шаг ${vm.pinStep} из 2", nav = if (s.change) NavIcon.CLOSE else NavIcon.NONE, onNav = vm::userBack, center = true,
        footer = { PinPad(enabled = busy == null, onDigit = vm::pinDigit, onBack = vm::pinBack) },
    ) {
        BigIc(Ic.lock, C.blue, 44.dp)
        Tx(if (vm.pinStep == 1) "Придумайте PIN из 8 цифр" else "Повторите PIN", size = 19.sp, weight = FontWeight.Bold)
        PinDots(vm.pinBuf.length, vm.pinErr.isNotEmpty(), vm.pinShake)
        when {
            busy != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner()
                Tx(busy, size = 13.5.sp, color = C.text2)
            }
            else -> Tx(
                vm.pinErr.ifEmpty { "Нужен для подсмотра, вместе с отпечатком. Не берите PIN блокировки телефона — его видят чаще." },
                size = 13.5.sp, color = if (vm.pinErr.isNotEmpty()) C.red else C.text3, align = TextAlign.Center,
                modifier = Modifier.widthIn(max = 330.dp),
            )
        }
    }
}

// ---------- parts of a key ----------

/** A phrase is always one line of words, never numbered blocks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhraseBox(big: Boolean = false, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().background(C.acc.copy(alpha = 0.03f)).drawBehind { drawRect(C.acc, size = Size(2.dp.toPx(), size.height)) }
            .padding(horizontal = if (big) 16.dp else 14.dp, vertical = if (big) 14.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(if (big) 12.dp else 11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

enum class W { PLAIN, BAD, FIX, OPEN, HIDDEN, HINT }

@Composable
fun Word(text: String, w: W, big: Boolean = false, onClick: (() -> Unit)? = null) {
    val fontSize = if (big) 21.sp else 18.sp
    val color = when (w) {
        W.PLAIN -> C.text
        W.BAD -> C.red
        W.FIX -> C.acc
        W.OPEN -> C.amber
        W.HIDDEN, W.HINT -> C.text3
    }
    var m: Modifier = Modifier
    if (w == W.HINT) m = m.drawBehind { drawRect(C.red, topLeft = Offset(0f, size.height - 2.dp.toPx()), size = Size(size.width, 2.dp.toPx())) }
    if (onClick != null) m = m.tap(onClick = onClick)
    androidx.compose.foundation.text.BasicText(
        text, m.padding(horizontal = if (w == W.HIDDEN || w == W.HINT) 2.dp else 0.dp),
        style = style(fontSize, color, FontWeight.Medium, lineHeight = 1.6f, decoration = if (w == W.BAD) TextDecoration.LineThrough else null),
    )
}

enum class Cell { PLAIN, GOOD, BAD, SLOT, SLOT_BAD, OPEN }

/** Symbols in numbered blocks of 4: 3 columns when they divide evenly, else 2. */
@Composable
fun ChunkGrid(n: Int, big: Boolean = false, cell: @Composable (Int) -> Unit) {
    val cols = if (n % 3 == 0) 3 else 2
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (start in 0 until n step cols) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in start until start + cols) {
                    Box(Modifier.weight(1f)) { if (i < n) cell(i) }
                }
            }
        }
    }
}

@Composable
fun Chunk(i: Int, text: String, kind: Cell, big: Boolean = false, fix: String? = null, onClick: (() -> Unit)? = null) {
    val frame = when (kind) {
        Cell.PLAIN, Cell.SLOT -> Modifier.dashed(C.dash)
        Cell.GOOD -> Modifier.border(1.dp, C.dash)
        Cell.BAD -> Modifier.border(1.dp, C.red)
        Cell.SLOT_BAD -> Modifier.dashed(C.red)
        Cell.OPEN -> Modifier.border(1.dp, C.amber)
    }
    val color = when (kind) {
        Cell.SLOT, Cell.SLOT_BAD -> C.text3
        Cell.OPEN -> C.amber
        else -> C.text
    }
    Box(Modifier.fillMaxWidth().heightIn(min = if (big) 58.dp else 48.dp).then(frame).then(if (onClick != null) Modifier.tap(onClick = onClick) else Modifier)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (big) 58.dp else 48.dp).padding(start = 9.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tx("${i + 1}", size = 12.sp, color = C.text3, modifier = Modifier.width(21.dp))
            Column(Modifier.weight(1f)) {
                Tx(text, size = if (big) 20.sp else 17.sp, color = color, weight = FontWeight.Medium, lineHeight = 1.3f)
                if (fix != null) Tx(fix, size = 14.sp, color = C.acc, weight = FontWeight.Medium)
            }
        }
        if (kind == Cell.GOOD || kind == Cell.BAD) {
            Icn(if (kind == Cell.GOOD) Ic.check else Ic.close, if (kind == Cell.GOOD) C.acc else C.red, 14.dp, Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 6.dp))
        }
    }
}

// ---------- peek ----------

@Composable
fun PeekScreen(vm: AppVm) {
    val p = vm.peek ?: return
    val k = vm.data.key(p.id) ?: return
    LaunchedEffect(p) {
        while (p.left > 0) {
            delay(1000)
            p.left--
        }
        vm.peekDone()
    }
    val real = KeyText.chunks(p.fmt, p.secret)
    val mine = p.last?.let { KeyText.chunks(p.fmt, it) } ?: emptyList()
    val wrong = p.last?.let { KeyText.wrongParts(p.fmt, p.secret, it) } ?: emptyList()
    val words = p.fmt == Fmt.WORDS
    var holding by remember { mutableStateOf(false) }
    Page(
        "Подсмотр · ${k.name}", "rk peek ${k.id} --pin --finger", nav = NavIcon.CLOSE, onNav = vm::peekDone,
        right = {
            Tx(
                "%d:%02d".format(p.left / 60, p.left % 60), size = 14.sp, color = C.amber, weight = FontWeight.Bold,
                modifier = Modifier.padding(end = 8.dp).border(1.dp, C.amber).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        },
        pad = { TBtn("Закрыть и ввести по памяти", onClick = vm::peekDone) },
    ) {
        Note("Записано в журнал, ${timeOf(p.openedAt)}. Экран закроется сам через минуту.", Ic.eye, NoteKind.AMBER)
        var level = 1
        if (p.last != null) {
            LevelCard(level++, "Где ошибка", "Отметим ${if (words) "красным неверные слова" else "неверные блоки"} в вашем вводе. Сам ключ не покажем.") {
                if (!p.where) TBtn("Показать, где ошибка", Kind.SEC, small = true, onClick = vm::peekWhere)
                else if (words) PhraseBox {
                    real.indices.forEach { i -> if (wrong[i]) Word(mine.getOrNull(i) ?: "…", W.BAD) else Word(mine[i], W.PLAIN) }
                    mine.drop(real.size).forEach { Word(it, W.BAD) }
                } else ChunkGrid(real.size) { i -> Chunk(i, mine.getOrNull(i) ?: "—", if (wrong[i]) Cell.BAD else Cell.GOOD) }
            }
        }
        LevelCard(level++, "Подсказать часть", if (words) "Нажмите на скрытое слово, чтобы открыть только его." else "Нажмите на блок, чтобы открыть только его.") {
            if (words) PhraseBox {
                real.forEachIndexed { i, w ->
                    when {
                        i in p.open -> Word(w, W.OPEN)
                        p.where && wrong.getOrElse(i) { false } -> Word("•••", W.HINT) { vm.peekPart(i) }
                        else -> Word("•••", W.HIDDEN) { vm.peekPart(i) }
                    }
                }
            } else ChunkGrid(real.size) { i ->
                when {
                    i in p.open -> Chunk(i, real[i], Cell.OPEN)
                    p.where && wrong.getOrElse(i) { false } -> Chunk(i, "••••", Cell.SLOT_BAD) { vm.peekPart(i) }
                    else -> Chunk(i, "••••", Cell.SLOT) { vm.peekPart(i) }
                }
            }
        }
        LevelCard(level, "Показать целиком", "Видно, пока держите палец на кнопке.") {
            Box(Modifier.fillMaxWidth().heightIn(min = 58.dp).dashed(C.dash).padding(12.dp), contentAlignment = Alignment.Center) {
                if (holding && p.full) Tx(if (words) p.secret else real.joinToString(" "), size = 17.sp, color = C.amber, weight = FontWeight.Medium, align = TextAlign.Center)
                else Tx("•".repeat(minOf(KeyText.clean(p.fmt, p.secret).length, 18)), size = 17.sp, color = C.text3, spacing = 3.sp)
            }
            Row(
                Modifier.fillMaxWidth().height(46.dp).background(if (holding) C.amber else Color.Transparent).border(1.dp, C.amber)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            holding = true
                            vm.peekFull()
                            tryAwaitRelease()
                            holding = false
                        })
                    },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icn(Ic.eye, if (holding) Color.Black else C.amber, 20.dp)
                Tx("  УДЕРЖИВАЙТЕ, ЧТОБЫ ВИДЕТЬ", size = 13.5.sp, color = if (holding) Color.Black else C.amber, weight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun LevelCard(n: Int, title: String, text: String, content: @Composable ColumnScope.() -> Unit) {
    TCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.padding(top = 1.dp).size(26.dp).border(1.dp, C.amber), contentAlignment = Alignment.Center) {
                Tx("$n", size = 13.sp, color = C.amber, weight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Tx(title, size = 15.5.sp, weight = FontWeight.Bold)
                Tx(text, size = 13.sp, color = C.text3)
            }
        }
        content()
    }
}

@Composable
fun LostScreen(vm: AppVm, id: String) {
    val k = vm.data.key(id) ?: return vm.home()
    Page(
        "Подсмотр · ${k.name}", "rk peek ${k.id}", nav = NavIcon.CLOSE, onNav = vm::userBack, center = true,
        pad = { TBtn("Восстановить копию") { vm.startRestore(id) } },
    ) {
        BigIc(Ic.warn, C.red, 44.dp)
        Tx("Копия уничтожена", size = 22.sp, weight = FontWeight.Bold)
        Tx(
            "Система стёрла ключ шифрования копии — так бывает после нового отпечатка в настройках Android. Так задумано: новый палец не должен открывать старые секреты.",
            size = 14.5.sp, color = C.text2, align = TextAlign.Center, modifier = Modifier.widthIn(max = 340.dp),
        )
        Note("Хеш для проверок остался. Если помните ключ — введите его: сверим с хешем и создадим новую копию.", Ic.hash)
    }
}

// ---------- adding ----------

@Composable
fun Add1Screen(vm: AppVm) {
    var name by rememberSaveable { mutableStateOf(vm.newKeyName().ifEmpty { vm.defaultName() }) }
    var fmt by rememberSaveable { mutableStateOf(vm.newKeyFmt()) }
    var focused by remember { mutableStateOf(false) }
    Page(
        "Новый ключ", "rk add", sub = "шаг 1 из 4", nav = NavIcon.CLOSE, onNav = vm::userBack,
        pad = { TBtn("Дальше", enabled = fmt != null) { fmt?.let { vm.add1Next(name, it) } } },
    ) {
        Lbl("Название")
        BasicTextField(
            value = name,
            onValueChange = { name = it.take(24) },
            singleLine = true,
            textStyle = style(15.sp, C.text),
            cursorBrush = SolidColor(C.acc),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().height(48.dp).onFocusChanged { focused = it.isFocused }
                .frame(if (focused) C.acc else C.dash, dashed = !focused),
            decorationBox = { inner -> Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) { inner() } },
        )
        Tx("Видно на главной и в уведомлениях — не пишите в нём сам ключ.", size = 13.sp, color = C.text3, modifier = Modifier.padding(horizontal = 4.dp))
        Lbl("Из чего состоит ключ")
        FmtOption(Fmt.WORDS, "несколько слов через пробел", "река окно …", fmt == Fmt.WORDS) { fmt = Fmt.WORDS }
        FmtOption(Fmt.SYMBOLS, "буквы, цифры и знаки", "k7#Q…", fmt == Fmt.SYMBOLS) { fmt = Fmt.SYMBOLS }
    }
}

@Composable
private fun FmtOption(f: Fmt, desc: String, ex: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().frame(if (on) C.acc else C.dash, dashed = !on).tap(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp).border(1.dp, if (on) C.acc else C.text3), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(10.dp).background(C.acc))
        }
        Column(Modifier.weight(1f)) {
            Tx(f.title, size = 15.5.sp, weight = FontWeight.Bold)
            Tx(desc, size = 13.sp, color = C.text3)
        }
        Tx(ex, size = 13.sp, color = C.text3)
    }
}

@Composable
fun Add2Screen(vm: AppVm) {
    val f = vm.newKeyFmt() ?: return vm.home()
    InputPage(
        vm, "Новый ключ · ${vm.newKeyName()}", "rk add --input", f, "Введите ключ",
        "Ключ видит только RememberKey: системная клавиатура не участвует. Копия сохранит его в точности, с большими буквами" +
            if (f == Fmt.WORDS) "; при проверках регистр и ё не важны." else ".",
        sub = "шаг 2 из 4", btn = "Дальше",
    )
}

@Composable
fun Add3Screen(vm: AppVm) {
    val f = vm.newKeyFmt() ?: return vm.home()
    InputPage(
        vm, "Новый ключ · ${vm.newKeyName()}", "rk add --confirm", f, "Повторите ключ",
        "Чтобы исключить опечатку. Потом — PIN приложения и отпечаток: они нужны, чтобы зашифровать копию.", sub = "шаг 3 из 4", btn = "Сохранить",
    )
}

@Composable
fun AddedScreen(vm: AppVm, id: String) {
    val k = vm.data.key(id) ?: return vm.home()
    Page(
        "Новый ключ · ${k.name}", "rk add --save ${k.id}", sub = "шаг 4 из 4", nav = NavIcon.CLOSE, onNav = vm::userHome, center = true,
        pad = { TBtn("Начать заучивание") { vm.startLearnAfterAdd(id) } },
    ) {
        BigIc(Ic.check, C.acc)
        Tx("Ключ сохранён", size = 24.sp, weight = FontWeight.ExtraBold)
        TCard {
            DoneRow("Проверочный хеш", "для проверок — без PIN и отпечатка")
            DoneRow("Зашифрованная копия", "чтобы подсмотреть — PIN и отпечаток")
        }
        Tx(
            "Дальше — заучивание: 3 раза подряд по памяти, пара минут. Пока оно идёт, ключ можно смотреть свободно.",
            size = 13.5.sp, color = C.text2, align = TextAlign.Center, modifier = Modifier.widthIn(max = 340.dp),
        )
    }
}

@Composable
private fun DoneRow(title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Ico(Ic.check, IcoKind.OK)
        Column(Modifier.weight(1f)) {
            Tx(title, size = 15.5.sp, weight = FontWeight.Bold)
            Tx(text, size = 13.sp, color = C.text3)
        }
    }
}

// ---------- learning ----------

private val TIPS = mapOf(
    Fmt.WORDS to "Свяжите слова в одну нелепую сцену — её легче вспомнить, чем список. Проговорите сцену вслух.",
    Fmt.SYMBOLS to "Учите блоками по 4 символа и проговаривайте каждый: «Т большая, q, семь, решётка».",
)

@Composable
fun LearnScreen(vm: AppVm, id: String) {
    val l = vm.learn ?: return vm.home()
    val k = vm.data.key(id) ?: return vm.home()
    val real = KeyText.chunks(l.fmt, l.secret)
    val words = l.fmt == Fmt.WORDS
    val title = "Заучивание · ${k.name}"
    val steps: @Composable ColumnScope.() -> Unit = { Steps(l.round, "Повтор ${l.round} из 3 — подряд, без ошибок") }
    when (l.phase) {
        Phase.SHOW -> Page(title, "rk learn ${k.id}", nav = NavIcon.CLOSE, onNav = vm::userHome, pad = { TBtn("Запомнил — скрыть", onClick = vm::learnHide) }) {
            steps()
            Tx("Прочитайте и запомните", size = 19.sp, weight = FontWeight.Bold)
            if (words) PhraseBox(big = true) { real.forEach { Word(it, W.PLAIN, big = true) } }
            else ChunkGrid(real.size, big = true) { i -> Chunk(i, real[i], Cell.PLAIN, big = true) }
            Tip(TIPS.getValue(l.fmt))
        }
        Phase.WRONG -> {
            val mine = KeyText.chunks(l.fmt, l.wrong)
            Page(title, "rk learn ${k.id}", nav = NavIcon.CLOSE, onNav = vm::userHome, pad = { TBtn("Посмотреть и запомнить", onClick = vm::learnShow) }) {
                steps()
                Tx("Почти. Вот расхождение", size = 19.sp, weight = FontWeight.Bold)
                Tx(
                    (if (words) "Зачёркнуто — ваша ошибка, зелёным — как правильно." else "В красной рамке — ваш ввод с ошибкой, зелёным под ним — как правильно.") +
                        " Счёт повторов начнётся заново.",
                    size = 13.5.sp, color = C.text3,
                )
                if (words) PhraseBox {
                    real.forEachIndexed { i, w ->
                        val m = mine.getOrNull(i)
                        if (m != null && KeyText.same(l.fmt, m, w)) Word(m, W.PLAIN)
                        else {
                            if (m != null) Word(m, W.BAD)
                            Word(w, W.FIX)
                        }
                    }
                    mine.drop(real.size).forEach { Word(it, W.BAD) }
                } else ChunkGrid(real.size) { i ->
                    val m = mine.getOrNull(i)
                    val ok = m != null && KeyText.same(l.fmt, m, real[i])
                    Chunk(i, m ?: "—", if (ok) Cell.GOOD else Cell.BAD, fix = if (ok) null else real[i])
                }
            }
        }
        Phase.RECALL -> InputPage(
            vm, title, "rk learn ${k.id}", l.fmt,
            prompt = if (l.round == 1) "Теперь — по памяти" else "Ещё раз — без подсказки",
            hint = "${KeyText.sizeText(l.fmt, KeyText.size(l.fmt, l.secret))} · пока идёт заучивание, смотреть можно свободно",
            onClose = vm::userHome,
            top = steps,
            below = { TLink("Показать ещё раз", onClick = vm::learnShow) },
        )
    }
}

@Composable
fun LearnedScreen(vm: AppVm, id: String) {
    val k: KeyRec = vm.data.key(id) ?: return vm.home()
    Page(
        "Заучивание · ${k.name}", "rk learn ${k.id} --done", nav = NavIcon.CLOSE, onNav = vm::userHome, center = true,
        pad = { TBtn("Готово", onClick = vm::userHome) },
    ) {
        BigIc(Ic.check, C.acc)
        Tx("Выучено на сегодня", size = 24.sp, weight = FontWeight.ExtraBold)
        Tx(
            "Первая проверка завтра — ${Ru.date(k.due, vm.today)}. Потом через 3, 7, 16 и 35 дней, дальше — каждые 60. Напомним уведомлением.",
            size = 14.5.sp, color = C.text2, align = TextAlign.Center, modifier = Modifier.widthIn(max = 340.dp),
        )
        TCard { Track(k, vm.today, labels = true) }
        Note("Ключ стёрт из памяти приложения. Остались проверочный хеш и зашифрованная копия.", Ic.lock)
    }
}
