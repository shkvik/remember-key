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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
        title, sub = sub, nav = NavIcon.CLOSE, onNav = onClose,
        pad = { TBtn(busy ?: btn, enabled = input.buf.isNotBlank() && busy == null, onClick = vm::submit) },
        footer = { AppKeyboard(input, fmt, enabled = busy == null, onSubmit = vm::submit) },
        bottom = C.kb,
    ) {
        top?.invoke(this)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Tx(prompt, size = 20.sp, weight = FontWeight.SemiBold)
            Tx(hint, size = 14.sp, color = C.text2)
        }
        KeyField(input, fmt, if (fmt == Fmt.WORDS) "Слова через пробел" else "Символы без пробелов")
        if (input.buf.isNotBlank()) Tx("Набрано: ${KeyText.sizeText(fmt, KeyText.size(fmt, input.buf))}", size = 13.sp, color = C.text2)
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
            vm, title, k.fmt,
            prompt = if (vm.round == 1) "Введите ключ по памяти" else "Ещё раз — по памяти",
            hint = "$size · вне расписания, проверка ${Ru.date(k.due, vm.today)} не сдвинется",
            top = { Steps(vm.round, "Ввод ${vm.round} из 3") },
            below = {
                if (vm.practiceFailed && k.hasCopy) TLink("Подсмотреть — PIN и отпечаток", C.amber) { vm.startPeek(k.id, afterFail = true) }
            },
        )
        Mode.CHECK -> InputPage(vm, title, k.fmt, "Введите ключ по памяти", size + if (s.attempt > 1) " · попытка ${s.attempt}" else "")
        Mode.RELEARN -> InputPage(vm, title, k.fmt, "Теперь — по памяти", "$size · копия скрыта, проверка — ${Ru.rel(k.due, vm.today)}")
        Mode.RESTORE -> InputPage(vm, title, k.fmt, "Введите ключ по памяти", "$size · сверим с хешем и создадим новую копию", btn = "Сверить с хешем")
    }
}

@Composable
fun ResultScreen(vm: AppVm, r: Scr.Result) {
    val k = vm.data.key(r.id) ?: return vm.home()
    val today = vm.today
    val title = if (r.kind == RKind.RESTORED) "Восстановление" else r.mode.title
    Page(
        "$title · ${k.name}", nav = NavIcon.CLOSE, onNav = vm::userHome, center = true,
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
                BigIc(Ic.check, C.ok)
                Tx("Верно!", size = 24.sp, weight = FontWeight.Bold)
                val text = when (r.mode) {
                    Mode.CHECK -> if (r.attempt == 1) "Следующая проверка ${Ru.rel(k.due, today)} — ${Ru.date(k.due, today)}."
                    else "Верно ${Ru.fromAttempt(r.attempt)}, поэтому интервал не растёт: снова ${Ru.rel(k.due, today)} — ${Ru.date(k.due, today)}."
                    Mode.RELEARN -> "Ключ снова на повторе: проверка ${Ru.rel(k.due, today)}, ${Ru.date(k.due, today)}. Интервалы пойдут заново — 1, 3, 7…"
                    else -> "Три раза верно. Это тренировка — расписание не меняется: проверка ${Ru.date(k.due, today)}."
                }
                Tx(text, size = 15.sp, color = C.text2, align = TextAlign.Center, modifier = Modifier.widthIn(max = 340.dp))
                TCard {
                    Track(k, today, labels = true)
                    Tx(
                        if (k.level >= Schedule.CHECKS) "Поддержание: каждые 60 дней" else "Пройдено проверок: ${k.level} из 6, потом — каждые 60 дней",
                        size = 13.sp, color = C.text2,
                    )
                }
            }
            RKind.FAIL -> {
                BigIc(Ic.close, C.red)
                Tx("Не совпало", size = 24.sp, weight = FontWeight.Bold)
                Tx(
                    "Хеш не подсказывает, где ошибка. Попробуйте ещё раз — например, тот вариант, в котором сомневаетесь.",
                    size = 15.sp, color = C.text2, align = TextAlign.Center, modifier = Modifier.widthIn(max = 340.dp),
                )
                if (k.hasCopy) {
                    Tx(
                        "Подсмотр — по PIN и отпечатку, попадает в журнал. После него ключ вернётся на проверку завтра.",
                        size = 13.sp, color = C.text2, align = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp),
                    )
                } else Note("Копия ключа уничтожена — подсмотреть нельзя. Остаётся вспоминать.", Ic.warn, NoteKind.RED)
            }
            RKind.RESTORED -> {
                BigIc(Ic.lock, C.ok, 42.dp)
                Tx("Копия восстановлена", size = 24.sp, weight = FontWeight.Bold)
                Tx("${k.name} снова можно подсмотреть по PIN и отпечатку.", size = 15.sp, color = C.text2, align = TextAlign.Center)
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
        vm.pinTitle(s), nav = NavIcon.CLOSE, onNav = vm::userBack, center = true,
        footer = { PinPad(enabled = busy == null && left == 0L, onDigit = vm::pinDigit, onBack = vm::pinBack) },
    ) {
        BigIc(Ic.lock, C.acc, 42.dp)
        Tx(if (s.purpose == PinFor.CHANGE) "Введите текущий PIN" else "Введите PIN приложения", size = 20.sp, weight = FontWeight.SemiBold)
        PinDots(vm.pinBuf.length, vm.pinErr.isNotEmpty(), vm.pinShake)
        when {
            busy != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner()
                Tx(busy, size = 14.sp, color = C.text2)
            }
            left > 0 -> Tx("Слишком много ошибок. Следующая попытка через $left с", size = 14.sp, color = C.red, align = TextAlign.Center)
            vm.pinErr.isNotEmpty() -> Tx(vm.pinErr, size = 14.sp, color = C.red, align = TextAlign.Center)
            s.purpose == PinFor.CHANGE -> Tx("Потом — новый PIN дважды", size = 14.sp, color = C.text2, align = TextAlign.Center)
            else -> Tx("Потом — отпечаток пальца", size = 14.sp, color = C.text2, align = TextAlign.Center)
        }
    }
}

@Composable
fun PinSetScreen(vm: AppVm, s: Scr.PinSet) {
    val busy = vm.busy
    Page(
        if (s.change) "Новый PIN" else "PIN приложения", sub = "шаг ${vm.pinStep} из 2",
        nav = if (s.change) NavIcon.CLOSE else NavIcon.NONE, onNav = vm::userBack, center = true,
        footer = { PinPad(enabled = busy == null, onDigit = vm::pinDigit, onBack = vm::pinBack) },
    ) {
        BigIc(Ic.lock, C.acc, 42.dp)
        Tx(if (vm.pinStep == 1) "Придумайте PIN из 8 цифр" else "Повторите PIN", size = 20.sp, weight = FontWeight.SemiBold)
        PinDots(vm.pinBuf.length, vm.pinErr.isNotEmpty(), vm.pinShake)
        when {
            busy != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner()
                Tx(busy, size = 14.sp, color = C.text2)
            }
            else -> Tx(
                vm.pinErr.ifEmpty { "Нужен для подсмотра, вместе с отпечатком. Не берите PIN блокировки телефона — его видят чаще." },
                size = 14.sp, color = if (vm.pinErr.isNotEmpty()) C.red else C.text2, align = TextAlign.Center,
                modifier = Modifier.widthIn(max = 330.dp),
            )
        }
    }
}

// ---------- parts of a key ----------

/** A phrase (only keys added before phrases were dropped) is one line of words, never numbered blocks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhraseBox(big: Boolean = false, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().surface(C.surf2, R14).padding(horizontal = if (big) 18.dp else 16.dp, vertical = if (big) 16.dp else 12.dp),
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
        W.FIX -> C.ok
        W.OPEN -> C.amber
        W.HIDDEN, W.HINT -> C.text3
    }
    var m: Modifier = Modifier
    if (w == W.HINT) m = m.drawBehind { drawRect(C.red, topLeft = Offset(0f, size.height - 2.dp.toPx()), size = Size(size.width, 2.dp.toPx())) }
    if (onClick != null) m = m.clip(R8).tap(onClick = onClick)
    BasicText(
        text, m.padding(horizontal = if (w == W.HIDDEN || w == W.HINT) 3.dp else 0.dp),
        style = style(fontSize, color, FontWeight.Medium, lineHeight = 1.6f, decoration = if (w == W.BAD) TextDecoration.LineThrough else null, family = Mono),
    )
}

enum class Cell { PLAIN, GOOD, BAD, SLOT, SLOT_BAD, OPEN }

/** Symbols in numbered blocks of 4: 3 columns when they divide evenly, else 2. */
@Composable
fun ChunkGrid(n: Int, cell: @Composable (Int) -> Unit) {
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
    val bg = if (kind == Cell.OPEN) C.amberS else C.surf2
    var m = Modifier.fillMaxWidth().heightIn(min = if (big) 60.dp else 50.dp).surface(bg, R12)
    when (kind) {
        Cell.GOOD -> m = m.border(1.5.dp, C.ok, R12)
        Cell.BAD -> m = m.border(1.5.dp, C.red, R12)
        Cell.SLOT_BAD -> m = m.border(1.5.dp, C.red, R12)
        else -> Unit
    }
    if (onClick != null) m = m.tap(onClick = onClick)
    Box(m) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (big) 60.dp else 50.dp).padding(start = 11.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tx("${i + 1}", size = 12.sp, color = C.text3, weight = FontWeight.Medium, modifier = Modifier.width(21.dp))
            Column(Modifier.weight(1f)) {
                val hidden = kind == Cell.SLOT || kind == Cell.SLOT_BAD
                if (hidden) Tx(text, size = if (big) 20.sp else 17.sp, color = C.text3, family = Mono, spacing = 2.sp)
                else Tx(symbols(text), size = if (big) 20.sp else 17.sp, weight = FontWeight.Medium, lineHeight = 1.3f, family = Mono)
                if (fix != null) Tx(symbols(fix), size = 14.sp, color = C.ok, weight = FontWeight.Medium, family = Mono)
            }
        }
        if (kind == Cell.GOOD || kind == Cell.BAD) {
            Icn(if (kind == Cell.GOOD) Ic.check else Ic.close, if (kind == Cell.GOOD) C.ok else C.red, 14.dp, Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 8.dp))
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
        "Подсмотр · ${k.name}", nav = NavIcon.CLOSE, onNav = vm::peekDone,
        right = {
            Tx(
                "%d:%02d".format(p.left / 60, p.left % 60), size = 14.sp, color = C.amber, weight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = 8.dp).clip(Pill).background(C.amberS).padding(horizontal = 12.dp, vertical = 5.dp),
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
            Box(Modifier.fillMaxWidth().heightIn(min = 60.dp).surface(C.surf2, R14).padding(12.dp), contentAlignment = Alignment.Center) {
                if (holding && p.full) {
                    if (words) Tx(p.secret, size = 17.sp, color = C.amber, weight = FontWeight.Medium, align = TextAlign.Center, family = Mono)
                    else Tx(symbols(real.joinToString(" ")), size = 17.sp, weight = FontWeight.Medium, align = TextAlign.Center, family = Mono)
                } else Tx("•".repeat(minOf(KeyText.clean(p.fmt, p.secret).length, 18)), size = 17.sp, color = C.text3, spacing = 3.sp)
            }
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(Pill).background(if (holding) C.amber else C.amberS)
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
                Icn(Ic.eye, if (holding) C.onAmber else C.amber, 20.dp)
                Tx("  Удерживайте, чтобы видеть", size = 14.sp, color = if (holding) C.onAmber else C.amber, weight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun LevelCard(n: Int, title: String, text: String, content: @Composable ColumnScope.() -> Unit) {
    TCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(C.amberS), contentAlignment = Alignment.Center) {
                Tx("$n", size = 13.sp, color = C.amber, weight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Tx(title, size = 16.sp, weight = FontWeight.SemiBold)
                Tx(text, size = 13.sp, color = C.text2)
            }
        }
        content()
    }
}

@Composable
fun LostScreen(vm: AppVm, id: String) {
    val k = vm.data.key(id) ?: return vm.home()
    Page(
        "Подсмотр · ${k.name}", nav = NavIcon.CLOSE, onNav = vm::userBack, center = true,
        pad = { TBtn("Восстановить копию") { vm.startRestore(id) } },
    ) {
        BigIc(Ic.warn, C.red, 42.dp)
        Tx("Копия уничтожена", size = 22.sp, weight = FontWeight.Bold)
        Tx(
            "Система стёрла ключ шифрования копии — так бывает после нового отпечатка в настройках Android. Так задумано: новый палец не должен открывать старые секреты.",
            size = 15.sp, color = C.text2, align = TextAlign.Center, modifier = Modifier.widthIn(max = 340.dp),
        )
        Note("Хеш для проверок остался. Если помните ключ — введите его: сверим с хешем и создадим новую копию.", Ic.hash)
    }
}

// ---------- adding ----------

@Composable
fun Add1Screen(vm: AppVm) {
    var name by rememberSaveable { mutableStateOf(vm.newKeyName().ifEmpty { vm.defaultName() }) }
    var focused by remember { mutableStateOf(false) }
    Page(
        "Новый ключ", sub = "шаг 1 из 4", nav = NavIcon.CLOSE, onNav = vm::userBack,
        pad = { TBtn("Дальше") { vm.add1Next(name, Fmt.SYMBOLS) } },
    ) {
        Lbl("Название")
        BasicTextField(
            value = name,
            onValueChange = { name = it.take(24) },
            singleLine = true,
            textStyle = style(16.sp, C.text),
            cursorBrush = SolidColor(C.acc),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().height(52.dp).onFocusChanged { focused = it.isFocused }
                .surface(C.surf, R14).border(if (focused) 2.dp else 1.5.dp, if (focused) C.acc else C.line2, R14),
            decorationBox = { inner -> Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) { inner() } },
        )
        Tx("Видно на главной и в уведомлениях — не пишите в нём сам ключ.", size = 13.sp, color = C.text2, modifier = Modifier.padding(horizontal = 4.dp))
        Note("Ключ — буквы, цифры и знаки, без пробелов. Учить будем блоками по 4 символа.", Ic.key)
    }
}

@Composable
fun Add2Screen(vm: AppVm) {
    val f = vm.newKeyFmt() ?: return vm.home()
    val pasted = vm.newKeyPasted()
    InputPage(
        vm, "Новый ключ · ${vm.newKeyName()}", f,
        prompt = if (pasted) "Проверьте ключ" else "Введите или вставьте ключ",
        hint = if (pasted) "Нажмите на глаз, чтобы сверить его с оригиналом." else "Ключ видит только RememberKey: системная клавиатура не участвует.",
        sub = if (pasted) "шаг 2 из 3" else "шаг 2 из 4", btn = if (pasted) "Сохранить" else "Дальше",
        below = {
            if (pasted) Note(
                "Вставлено из буфера обмена, буфер очищен. Если клавиатура хранит историю буфера (например, Gboard), удалите ключ и оттуда. Повторный ввод не нужен — дальше PIN и отпечаток.",
                Ic.check,
            ) else TLink("Вставить из буфера обмена", onClick = vm::paste)
        },
    )
}

@Composable
fun Add3Screen(vm: AppVm) {
    val f = vm.newKeyFmt() ?: return vm.home()
    InputPage(
        vm, "Новый ключ · ${vm.newKeyName()}", f, "Повторите ключ",
        "Чтобы исключить опечатку. Потом — PIN приложения и отпечаток: они нужны, чтобы зашифровать копию.", sub = "шаг 3 из 4", btn = "Сохранить",
    )
}

@Composable
fun AddedScreen(vm: AppVm, id: String) {
    val k = vm.data.key(id) ?: return vm.home()
    Page(
        "Новый ключ · ${k.name}", sub = "шаг 4 из 4", nav = NavIcon.CLOSE, onNav = vm::userHome, center = true,
        pad = { TBtn("Начать заучивание") { vm.startLearnAfterAdd(id) } },
    ) {
        BigIc(Ic.check, C.ok)
        Tx("Ключ сохранён", size = 24.sp, weight = FontWeight.Bold)
        TCard {
            DoneRow("Проверочный хеш", "для проверок — без PIN и отпечатка")
            DoneRow("Зашифрованная копия", "чтобы подсмотреть — PIN и отпечаток")
        }
        Tx(
            if (k.fmt == Fmt.SYMBOLS) "Дальше — уроки по блокам из 4 символов: каждый блок 3 раза с подсказкой и 3 раза по памяти, потом весь ключ целиком. Пока они идут, ключ можно смотреть свободно."
            else "Дальше — заучивание: 3 раза подряд по памяти. Пока оно идёт, ключ можно смотреть свободно.",
            size = 14.sp, color = C.text2, align = TextAlign.Center, modifier = Modifier.widthIn(max = 340.dp),
        )
    }
}

@Composable
private fun DoneRow(title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Ico(Ic.check, IcoKind.OK)
        Column(Modifier.weight(1f)) {
            Tx(title, size = 16.sp, weight = FontWeight.SemiBold)
            Tx(text, size = 13.sp, color = C.text2)
        }
    }
}

// ---------- block lessons ----------

/** All blocks in one row: learned ✓, the current one highlighted (open only while the hint is shown). */
@Composable
private fun KeyStrip(l: LessonSt) {
    val n = l.parts.size
    val cols = minOf(n, 5)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (start in 0 until n step cols) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (j in start until start + cols) {
                    Box(Modifier.weight(1f)) { if (j < n) StripCell(l, j) }
                }
            }
        }
    }
}

@Composable
private fun StripCell(l: LessonSt, j: Int) {
    val whole = l.block == null
    val cur = !whole && j == l.block
    val done = !l.solo && !whole && j < (l.block ?: 0)
    var m = Modifier.fillMaxWidth().surface(if (done) C.okS else if (cur && l.stage == Stage.HINT) C.accS else C.surf, R12)
    if (cur || whole) m = m.border(2.dp, C.acc, R12)
    Column(m.padding(top = 6.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Tx(if (done) "✓" else "${j + 1}", size = 11.sp, weight = if (cur) FontWeight.Bold else FontWeight.Medium, color = if (done) C.ok else if (cur) C.acc else C.text3)
        when {
            cur && l.stage == Stage.HINT -> Tx(symbols(l.parts[j]), size = 16.sp, weight = FontWeight.Medium, family = Mono)
            cur -> Tx("????", size = 16.sp, family = Mono)
            else -> Tx("••••", size = 16.sp, color = if (done) C.ok else C.text3, family = Mono)
        }
    }
}

@Composable
fun LessonScreen(vm: AppVm, id: String) {
    val l = vm.lesson ?: return vm.home()
    val k = vm.data.key(id) ?: return vm.home()
    val n = l.parts.size
    val title = if (l.solo) "Блок ${(l.block ?: 0) + 1} · ${k.name}" else "Заучивание · ${k.name}"
    val close: () -> Unit = if (l.solo) vm::userBack else vm::userHome
    val strip: @Composable ColumnScope.() -> Unit = { KeyStrip(l) }
    val b = l.block
    when {
        l.stage == Stage.WRONG -> Page(title, nav = NavIcon.CLOSE, onNav = close, pad = { TBtn("Понятно — снова по памяти", onClick = vm::lessonRetry) }) {
            strip()
            Tx("Почти. Вот расхождение", size = 20.sp, weight = FontWeight.SemiBold)
            Tx(
                "В красной рамке — ваш ввод, зелёным под ним — как правильно. " + if (b == null) "Весь ключ — снова 3 раза подряд." else "Счёт «по памяти» начнётся заново.",
                size = 14.sp, color = C.text2,
            )
            if (b == null) {
                val mine = KeyText.chunks(Fmt.SYMBOLS, l.wrong)
                ChunkGrid(n) { i ->
                    val m = mine.getOrNull(i)
                    val ok = m != null && KeyText.same(Fmt.SYMBOLS, m, l.parts[i])
                    Chunk(i, m ?: "—", if (ok) Cell.GOOD else Cell.BAD, fix = if (ok) null else l.parts[i])
                }
            } else {
                Chunk(b, l.wrong.ifEmpty { "—" }, Cell.BAD, fix = l.parts[b])
            }
        }
        b == null -> InputPage(
            vm, title, Fmt.SYMBOLS, onClose = close,
            prompt = "Теперь весь ключ целиком",
            hint = "$n ${Ru.plural(n, "блок", "блока", "блоков")} подряд, без подсказки",
            top = {
                strip()
                Steps(l.round, "Весь ключ · ${l.round} из 3 подряд")
            },
        )
        l.stage == Stage.HINT -> InputPage(
            vm, title, Fmt.SYMBOLS, onClose = close,
            prompt = "Введите блок ${b + 1}, глядя на подсказку",
            hint = "Проговаривайте символы вслух: «Т большая, q, семь, решётка».",
            top = {
                strip()
                Column(Modifier.fillMaxWidth().surface(C.accS).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Tx("ПОДСКАЗКА · БЛОК ${b + 1}", size = 12.sp, color = C.acc, weight = FontWeight.SemiBold, spacing = 0.06.em)
                    Tx(symbols(l.parts[b]), size = 36.sp, weight = FontWeight.Medium, family = Mono, spacing = 4.sp, lineHeight = 1.2f)
                }
                Steps(l.round, "Блок ${b + 1}${if (l.solo) "" else " из $n"} · с подсказкой, ${l.round} из 3")
            },
        )
        else -> InputPage(
            vm, title, Fmt.SYMBOLS, onClose = close,
            prompt = "Блок ${b + 1} — по памяти",
            hint = "Подсказка скрыта. Ошибка — и счёт «по памяти» начнётся заново.",
            top = {
                strip()
                Steps(l.round, "Блок ${b + 1}${if (l.solo) "" else " из $n"} · по памяти, ${l.round} из 3")
            },
            below = { TLink("Показать подсказку", onClick = vm::lessonHint) },
        )
    }
}

// ---------- learning a phrase (keys added before phrases were dropped) ----------

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
        Phase.SHOW -> Page(title, nav = NavIcon.CLOSE, onNav = vm::userHome, pad = { TBtn("Запомнил — скрыть", onClick = vm::learnHide) }) {
            steps()
            Tx("Прочитайте и запомните", size = 20.sp, weight = FontWeight.SemiBold)
            if (words) PhraseBox(big = true) { real.forEach { Word(it, W.PLAIN, big = true) } }
            else ChunkGrid(real.size) { i -> Chunk(i, real[i], Cell.PLAIN, big = true) }
            Tip(TIPS.getValue(l.fmt))
        }
        Phase.WRONG -> {
            val mine = KeyText.chunks(l.fmt, l.wrong)
            Page(title, nav = NavIcon.CLOSE, onNav = vm::userHome, pad = { TBtn("Посмотреть и запомнить", onClick = vm::learnShow) }) {
                steps()
                Tx("Почти. Вот расхождение", size = 20.sp, weight = FontWeight.SemiBold)
                Tx(
                    (if (words) "Зачёркнуто — ваша ошибка, зелёным — как правильно." else "В красной рамке — ваш ввод с ошибкой, зелёным под ним — как правильно.") +
                        " Счёт повторов начнётся заново.",
                    size = 14.sp, color = C.text2,
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
            vm, title, l.fmt,
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
        "Заучивание · ${k.name}", nav = NavIcon.CLOSE, onNav = vm::userHome, center = true,
        pad = { TBtn("Готово", onClick = vm::userHome) },
    ) {
        BigIc(Ic.check, C.ok)
        Tx("Выучено на сегодня", size = 24.sp, weight = FontWeight.Bold)
        Tx(
            "Первая проверка завтра — ${Ru.date(k.due, vm.today)}. Потом через 3, 7, 16 и 35 дней, дальше — каждые 60. Напомним уведомлением.",
            size = 15.sp, color = C.text2, align = TextAlign.Center, modifier = Modifier.widthIn(max = 340.dp),
        )
        TCard { Track(k, vm.today, labels = true) }
        Note("Ключ стёрт из памяти приложения. Остались проверочный хеш и зашифрованная копия.", Ic.lock)
    }
}
