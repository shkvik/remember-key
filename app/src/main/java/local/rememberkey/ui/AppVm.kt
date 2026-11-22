package local.rememberkey.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.rememberkey.RkApp
import local.rememberkey.core.Crypto
import local.rememberkey.core.DiagItem
import local.rememberkey.core.DiagRun
import local.rememberkey.core.Fmt
import local.rememberkey.core.KeyRec
import local.rememberkey.core.KeyText
import local.rememberkey.core.PinGuard
import local.rememberkey.core.St
import local.rememberkey.core.SystemRkClock
import local.rememberkey.core.Unlock
import local.rememberkey.core.Vault
import local.rememberkey.remind.Reminders
import local.rememberkey.sec.BioKeys
import local.rememberkey.sec.BioPrompter
import local.rememberkey.sec.BioResult
import local.rememberkey.sec.PhoneCheck

enum class Mode(val title: String) { CHECK("Проверка"), PRACTICE("Тренировка"), RELEARN("Повтор"), RESTORE("Восстановление") }
enum class PinFor { PEEK, LEARN, ADD, RESTORE, DELETE, CHANGE }
enum class RKind { OK, FAIL, RESTORED }
enum class Phase { SHOW, RECALL, WRONG }
private enum class How { PUSH, REPLACE, ROOT }

sealed interface Scr {
    data object Home : Scr
    data object Journal : Scr
    data object Security : Scr
    data object Onboard : Scr
    data object Broken : Scr
    data class Diag(val fromOnboard: Boolean) : Scr
    data class PinSet(val change: Boolean) : Scr
    data class Key(val id: String) : Scr
    data class Check(val id: String, val mode: Mode, val attempt: Int = 1) : Scr
    data class Result(val id: String, val kind: RKind, val mode: Mode, val attempt: Int) : Scr
    data class Pin(val purpose: PinFor, val id: String?) : Scr
    data class Peek(val id: String) : Scr
    data class Lost(val id: String) : Scr
    data object Add1 : Scr
    data object Add2 : Scr
    data object Add3 : Scr
    data class Added(val id: String) : Scr
    data class Learn(val id: String) : Scr
    data class Learned(val id: String) : Scr
}

class NewKey(val name: String, val fmt: Fmt) {
    var first = ""
    var secret = ""
}

/** The key in memory while it is being learned: shown freely until "learned". */
class LearnSt(val id: String, val fmt: Fmt, val secret: String) {
    var phase by mutableStateOf(Phase.SHOW)
    var round by mutableIntStateOf(1)
    var wrong by mutableStateOf("")
}

/** A decrypted copy on the peek screen; dropped as soon as the screen closes. */
class PeekSt(val id: String, val fmt: Fmt, val secret: String, val last: String?, val logId: Long) {
    val openedAt = System.currentTimeMillis()
    var where by mutableStateOf(false)
    val open = mutableStateListOf<Int>()
    var full by mutableStateOf(false)
    var left by mutableIntStateOf(60)

    /** a reveal is being written to the journal; it shows only after the write */
    var saving = false
}

class DiagRow(val id: String, val title: String) {
    /** null — waiting, "run" — in progress, "paused" — fingerprint cancelled */
    var state by mutableStateOf<String?>(null)
    var item by mutableStateOf<DiagItem?>(null)
}

class Toast(val text: String, val at: Long = SystemClock.elapsedRealtime())

class Confirm(val title: String, val text: String, val button: String, val onYes: () -> Unit)

/** All screens and actions, ported from design/prototype.html. */
class AppVm(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<RkApp>()
    val vault: Vault = ctx.vault

    var data by mutableStateOf(vault.data)
        private set
    var today by mutableLongStateOf(SystemRkClock.today())
        private set
    val loadError: String? = ctx.loadError
    var screen: Scr by mutableStateOf(
        when {
            loadError != null -> Scr.Broken
            vault.hasPin -> Scr.Home
            else -> Scr.Onboard
        },
    )
        private set
    private val stack = ArrayList<Scr>()

    val input = InputState()
    var busy by mutableStateOf<String?>(null)
        private set
    var toast by mutableStateOf<Toast?>(null)
        private set
    var confirm by mutableStateOf<Confirm?>(null)
    var askNotifications by mutableStateOf(false)
    var hwOk by mutableStateOf(true)
        private set

    /** Set by the activity while it is alive: the fingerprint window needs one. */
    var prompter: BioPrompter? = null

    /** false between onStop and onStart: a step that finishes then must not show or keep a secret */
    private var foreground = true

    // flows
    private var newKey: NewKey? = null
    var learn by mutableStateOf<LearnSt?>(null)
        private set
    var peek by mutableStateOf<PeekSt?>(null)
        private set
    var round by mutableIntStateOf(1)
        private set
    var practiceFailed by mutableStateOf(false)
        private set
    private var lastWrong: String? = null
    private var peekLast: String? = null
    private var restoreSecret: String? = null
    private var changeOldKey: ByteArray? = null

    // PIN
    var pinBuf by mutableStateOf("")
        private set
    var pinErr by mutableStateOf("")
        private set
    var pinShake by mutableIntStateOf(0)
        private set
    var pinWaitUntil by mutableLongStateOf(0L)
        private set
    var pinStep by mutableIntStateOf(1)
        private set
    private var pinFirst = ""

    // phone check
    val diagRows = mutableStateListOf<DiagRow>()
    var diagDone by mutableStateOf(false)
        private set
    private var diagJob: Job? = null

    /** One thread for the vault: writes stay in order, the UI thread never waits for Argon2. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.Default.limitedParallelism(1)

    private suspend fun <T> bg(block: () -> T): T = withContext(io) { block() }

    fun sync() {
        data = vault.data
        today = SystemRkClock.today()
    }

    fun newKeyName(): String = newKey?.name ?: ""
    fun newKeyFmt(): Fmt? = newKey?.fmt

    fun showToast(text: String) {
        toast = Toast(text)
    }

    fun clearToast(t: Toast) {
        if (toast === t) toast = null
    }

    /** Runs a slow step with a busy label; any error becomes a toast instead of a crash. */
    private fun work(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        viewModelScope.launch {
            busy = label
            try {
                block()
            } catch (e: Exception) {
                Log.e("RememberKey", "operation failed", e)
                showToast("Ошибка: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = null
                if (!foreground) leaveSecrets()
                sync()
            }
        }
    }

    // ---------- navigation ----------

    private fun go(s: Scr, how: How = How.PUSH) {
        when (how) {
            How.PUSH -> stack.add(screen)
            How.ROOT -> stack.clear()
            How.REPLACE -> Unit
        }
        arrive(s)
    }

    private fun goFromHome(s: Scr) {
        stack.clear()
        stack.add(Scr.Home)
        arrive(s)
    }

    fun canGoBack(): Boolean {
        val s = screen
        return stack.isNotEmpty() || !(s == Scr.Home || s == Scr.Onboard || s == Scr.Broken || (s is Scr.PinSet && !s.change))
    }

    fun back() {
        if (screen is Scr.Peek) return peekDone()
        val prev = stack.removeLastOrNull() ?: if (vault.hasPin) Scr.Home else Scr.Onboard
        arrive(prev)
    }

    fun home() = go(Scr.Home, How.ROOT)
    fun tab(s: Scr) = go(s, How.ROOT)

    /** The ✕ and ← of a screen: ignored while a PIN check, a fingerprint or a hash is in progress. */
    fun userBack() {
        if (busy == null) back()
    }

    fun userHome() {
        if (busy == null) home()
    }

    /** After a wait: did the user or the system leave the screen the step belongs to? */
    private fun left(s: Scr): Boolean = screen != s || !foreground

    private fun arrive(s: Scr) {
        // secrets live only as long as their screens
        if (s !is Scr.Peek) peek = null
        if (s !is Scr.Learn && s !is Scr.Added && s !is Scr.Pin) learn = null
        if (s == Scr.Home || s == Scr.Journal || s == Scr.Security) {
            newKey = null
            restoreSecret = null
            lastWrong = null
        }
        if (s !is Scr.PinSet && s !is Scr.Pin) {
            changeOldKey?.let { Crypto.wipe(it) }
            changeOldKey = null
        }
        if (s !is Scr.Diag) diagJob?.cancel()
        screen = s
        input.err = ""
        pinBuf = ""
        pinErr = ""
        when (s) {
            is Scr.Check -> data.key(s.id)?.let { input.reset(it.fmt) }
            Scr.Add2, Scr.Add3 -> newKey?.let { input.reset(it.fmt) }
            is Scr.Learn -> learn?.let { input.reset(it.fmt) }
            is Scr.PinSet -> {
                pinStep = 1
                pinFirst = ""
            }
            is Scr.Pin -> {
                val left = vault.waitLeftMs()
                pinWaitUntil = if (left > 0) SystemClock.elapsedRealtime() + left else 0
            }
            is Scr.Diag -> startDiag()
            else -> Unit
        }
        sync()
    }

    /** From the notification: straight to the first due check. */
    fun openDue() {
        if (!vault.hasPin || loadError != null || busy != null) return
        // an open copy is closed properly first: journal, then typing from memory
        if (peek != null) return peekDone()
        sync()
        val k = data.keys.firstOrNull { it.learned && it.due <= today } ?: data.keys.firstOrNull { !it.learned }
        arrive(Scr.Home)
        stack.clear()
        if (k != null) startCheck(k.id)
    }

    // ---------- lifecycle ----------

    /** On every return to the app: are the fingerprint keys of the copies still there? */
    fun onStart() {
        sync()
        Reminders.clear(ctx)
        if (!vault.hasPin || loadError != null) return
        foreground = true
        viewModelScope.launch {
            try {
                hwOk = bg { vault.hwKeysPresent() }
                // only ask the chip when the fingerprint sensor is ready: right after boot or during a sensor restart
                // the framework may briefly report every fingerprint key as invalid
                if (PhoneCheck(ctx).biometricReady()) {
                    val withCopy = vault.data.keys.filter { it.copy != null }
                    val lost = bg { withCopy.filter { k -> k.copy?.let { BioKeys.isLost(it.alias, it.ivBio) } ?: false }.map { it.id } }
                    // nothing is deleted here: a copy marked lost comes back if a later check finds its key working
                    bg { vault.copiesChecked(lost, withCopy.map { it.id } - lost.toSet()) }
                }
            } catch (e: Exception) {
                Log.e("RememberKey", "health check failed", e)
            }
            sync()
        }
    }

    /** Leaving the app: an open copy closes, and no key stays in memory for whoever opens the app next. */
    fun onStop() {
        foreground = false
        if (busy == null) leaveSecrets()
    }

    private fun leaveSecrets() {
        if (peek != null) peekDone()
        pinBuf = ""
        val holdsSecret = learn != null || newKey != null || restoreSecret != null || changeOldKey != null ||
            lastWrong != null || input.buf.isNotEmpty()
        if (holdsSecret) {
            input.reset(Fmt.WORDS)
            go(Scr.Home, How.ROOT)
        }
    }

    // ---------- keys ----------

    fun openKey(id: String) = go(Scr.Key(id))

    fun startCheck(id: String) {
        val k = data.key(id) ?: return
        if (!k.learned) return continueLearn(id)
        round = 1
        practiceFailed = false
        lastWrong = null
        go(Scr.Check(id, if (k.due <= today) Mode.CHECK else Mode.PRACTICE))
    }

    fun continueLearn(id: String) {
        if (learn?.id == id) return go(Scr.Learn(id))
        go(Scr.Pin(PinFor.LEARN, id))
    }

    fun startPeek(id: String, afterFail: Boolean) {
        val k = data.key(id) ?: return
        if (!k.hasCopy) return go(Scr.Lost(id))
        peekLast = if (afterFail) lastWrong else null
        go(Scr.Pin(PinFor.PEEK, id))
    }

    fun startRestore(id: String) = go(Scr.Check(id, Mode.RESTORE), if (screen is Scr.Lost) How.REPLACE else How.PUSH)

    fun nextDue(exceptId: String): KeyRec? = data.keys.firstOrNull { it.learned && it.due <= today && it.id != exceptId }
    fun nextLost(): KeyRec? = data.keys.firstOrNull { it.learned && !it.hasCopy }

    /** The chip lost the keys behind the hashes and the PIN: start over with a new PIN, keeping the journal. */
    fun askReset() {
        confirm = Confirm(
            "Начать заново?",
            "Ключи и копии удалятся — без ключей чипа их всё равно нельзя ни проверить, ни открыть. Журнал останется. Потом — новый PIN и ключи заново по памяти.",
            "Начать заново",
        ) {
            work("Сбрасываем…") {
                val aliases = bg { vault.resetAfterChipLoss() }
                aliases.forEach { BioKeys.delete(it) }
                hwOk = true
                go(Scr.PinSet(change = false), How.ROOT)
            }
        }
    }

    fun retry(r: Scr.Result) = go(Scr.Check(r.id, r.mode, r.attempt + 1), How.REPLACE)

    fun askDelete(k: KeyRec) {
        confirm = Confirm(
            "Удалить ${k.name}?",
            "Хеш, копия и расписание пропадут навсегда. Журнал останется. Дальше — PIN и отпечаток.",
            "Удалить",
        ) { go(Scr.Pin(PinFor.DELETE, k.id)) }
    }

    // ---------- adding ----------

    fun startAdd() {
        if (vault.freeLetter() == null) return showToast("Больше 10 ключей не поместится")
        newKey = null
        go(Scr.Add1)
    }

    fun defaultName(): String = "Ключ ${vault.freeLetter() ?: ""}"

    fun add1Next(name: String, fmt: Fmt) {
        newKey = NewKey(name.trim().ifEmpty { defaultName() }.take(24), fmt)
        go(Scr.Add2)
    }

    fun startLearnAfterAdd(id: String) {
        if (learn?.id == id) go(Scr.Learn(id), How.REPLACE) else continueLearn(id)
    }

    // ---------- typing ----------

    fun submit() {
        if (busy != null || input.buf.isBlank()) return
        when (val s = screen) {
            is Scr.Check -> submitCheck(s)
            is Scr.Learn -> submitLearn()
            Scr.Add2 -> {
                val n = newKey ?: return
                n.first = input.buf
                go(Scr.Add3)
            }
            Scr.Add3 -> {
                val n = newKey ?: return
                if (KeyText.clean(n.fmt, input.buf) != KeyText.clean(n.fmt, n.first)) {
                    input.err = "Не совпадает с первым вводом (большие и маленькие буквы тоже). Введите ещё раз — или вернитесь на шаг назад."
                    input.buf = ""
                    return
                }
                n.secret = KeyText.clean(n.fmt, input.buf)
                go(Scr.Pin(PinFor.ADD, null))
            }
            else -> Unit
        }
    }

    private fun submitCheck(s: Scr.Check) {
        val typed = input.buf
        work("Сверяем с хешем…") {
            val ok = bg { vault.verify(s.id, typed) }
            if (left(s)) return@work
            val k = vault.data.key(s.id) ?: return@work
            when (s.mode) {
                Mode.RESTORE -> if (!ok) {
                    input.buf = ""
                    input.err = "Не совпало с хешем. Копии нет, подсмотреть нельзя — попробуйте ещё раз."
                } else {
                    restoreSecret = KeyText.clean(k.fmt, typed)
                    go(Scr.Pin(PinFor.RESTORE, s.id))
                }
                Mode.PRACTICE -> when {
                    !ok -> {
                        lastWrong = typed
                        practiceFailed = true
                        bg { vault.practiceFailed(s.id, round) }
                        input.buf = ""
                        input.err = "Не совпало. Попробуйте ещё раз — счёт не сбрасывается."
                    }
                    round < 3 -> {
                        round++
                        input.reset(k.fmt)
                        showToast("Верно! Ввод $round из 3")
                    }
                    else -> {
                        bg { vault.practiceDone(s.id) }
                        go(Scr.Result(s.id, RKind.OK, Mode.PRACTICE, 1), How.REPLACE)
                    }
                }
                Mode.CHECK, Mode.RELEARN -> if (!ok) {
                    lastWrong = typed
                    bg { vault.checkFailed(s.id, s.mode.title, s.attempt) }
                    go(Scr.Result(s.id, RKind.FAIL, s.mode, s.attempt), How.REPLACE)
                } else {
                    bg { if (s.mode == Mode.CHECK) vault.checkPassed(s.id, s.attempt) else vault.relearnPassed(s.id) }
                    lastWrong = null
                    go(Scr.Result(s.id, RKind.OK, s.mode, s.attempt), How.REPLACE)
                }
            }
        }
    }

    // ---------- learning ----------

    fun learnHide() {
        val l = learn ?: return
        l.phase = Phase.RECALL
        input.reset(l.fmt)
    }

    fun learnShow() {
        learn?.phase = Phase.SHOW
    }

    private fun submitLearn() {
        val l = learn ?: return
        if (l.phase != Phase.RECALL) return
        if (KeyText.same(l.fmt, input.buf, l.secret)) {
            if (l.round >= 3) {
                work("Сохраняем…") {
                    bg { vault.learned(l.id) }
                    learn = null
                    go(Scr.Learned(l.id), How.ROOT)
                }
            } else {
                l.round++
                input.reset(l.fmt)
                showToast("Верно! Повтор ${l.round} из 3 — без подсказки")
            }
        } else {
            l.wrong = input.buf
            l.phase = Phase.WRONG
            l.round = 1
        }
    }

    // ---------- PIN ----------

    fun pinLocked(): Boolean = pinWaitUntil > SystemClock.elapsedRealtime()

    fun pinDigit(d: Char) {
        if (busy != null || pinLocked() || pinBuf.length >= PinGuard.LENGTH) return
        pinBuf += d
        pinErr = ""
        if (pinBuf.length == PinGuard.LENGTH) {
            viewModelScope.launch {
                delay(150)
                pinComplete()
            }
        }
    }

    fun pinBack() {
        if (busy == null) pinBuf = pinBuf.dropLast(1)
    }

    private fun pinComplete() {
        val v = pinBuf
        if (v.length != PinGuard.LENGTH) return
        when (val s = screen) {
            is Scr.PinSet -> pinSetDone(s, v)
            is Scr.Pin -> pinUnlock(s, v)
            else -> Unit
        }
    }

    private fun weak(p: String): Boolean {
        val d = p.map { it - '0' }
        val same = d.all { it == d[0] }
        val up = d.zipWithNext().all { (a, b) -> b == (a + 1) % 10 }
        val down = d.zipWithNext().all { (a, b) -> b == (a + 9) % 10 }
        val halves = p.substring(0, 4) == p.substring(4)
        return same || up || down || halves
    }

    private fun pinSetDone(s: Scr.PinSet, v: String) {
        pinBuf = ""
        if (pinStep == 1) {
            if (weak(v)) {
                pinErr = "Слишком простой PIN — такие подбирают первыми. Придумайте другой."
                pinShake++
                return
            }
            pinFirst = v
            pinStep = 2
            return
        }
        if (v != pinFirst) {
            pinStep = 1
            pinFirst = ""
            pinErr = "PIN не совпал. Придумайте заново."
            pinShake++
            return
        }
        pinFirst = ""
        if (!s.change) {
            work("Создаём PIN…") {
                bg { vault.setPin(v) }
                Reminders.schedule(ctx)
                go(Scr.Home, How.ROOT)
                askNotifications = true
            }
        } else {
            work("Перешифровываем копии…") {
                val old = changeOldKey ?: error("нет старого PIN")
                try {
                    bg { vault.changePin(old, v) }
                } finally {
                    Crypto.wipe(old)
                    changeOldKey = null
                }
                go(Scr.Security, How.ROOT)
                showToast("PIN изменён")
            }
        }
    }

    fun pinTitle(s: Scr.Pin): String {
        val name = s.id?.let { data.key(it)?.name } ?: newKey?.name ?: ""
        return when (s.purpose) {
            PinFor.PEEK -> "Подсмотр · $name"
            PinFor.LEARN -> "Заучивание · $name"
            PinFor.ADD -> "Новый ключ · $name"
            PinFor.RESTORE -> "Восстановление · $name"
            PinFor.DELETE -> "Удаление · $name"
            PinFor.CHANGE -> "Смена PIN"
        }
    }

    private fun pinUnlock(s: Scr.Pin, v: String) {
        val context = pinTitle(s)
        work("Проверяем PIN…") {
            val r = bg { vault.unlock(v, context) }
            pinBuf = ""
            if (left(s)) {
                if (r is Unlock.Ok) Crypto.wipe(r.key)
                return@work
            }
            when (r) {
                is Unlock.Wait -> pinWaitUntil = SystemClock.elapsedRealtime() + r.waitMs
                is Unlock.Wrong -> {
                    pinShake++
                    if (r.waitMs > 0) pinWaitUntil = SystemClock.elapsedRealtime() + r.waitMs
                    pinErr = when {
                        r.waitMs > 0 -> ""
                        r.fails == 2 -> "Неверный PIN. Ещё одна ошибка — и пауза 30 секунд."
                        else -> "Неверный PIN"
                    }
                }
                is Unlock.Ok -> try {
                    afterPin(s, r.key)
                } finally {
                    Crypto.wipe(r.key)
                }
            }
        }
    }

    private fun prompter(): BioPrompter = prompter ?: error("окно отпечатка недоступно")

    private suspend fun afterPin(s: Scr.Pin, key: ByteArray) {
        when (s.purpose) {
            PinFor.PEEK, PinFor.LEARN -> openSecret(s, key)
            PinFor.ADD -> saveNew(s, key)
            PinFor.RESTORE -> saveRestore(s, key)
            PinFor.DELETE -> deleteKey(s)
            PinFor.CHANGE -> {
                changeOldKey = key.copyOf()
                go(Scr.PinSet(change = true), How.REPLACE)
            }
        }
    }

    private fun bioStopped(r: BioResult) {
        back()
        showToast(if (r is BioResult.Failed) "Отпечаток: ${r.message}" else "Отпечаток отменён")
    }

    private suspend fun openSecret(s: Scr.Pin, key: ByteArray) {
        val id = s.id!!
        val k = vault.data.key(id) ?: return
        val c = k.copy?.takeIf { k.hasCopy } ?: return go(Scr.Lost(id), How.REPLACE)
        val inner = bg { vault.openCopy(id, key) }
        val cipher = try {
            bg { BioKeys.decryptCipher(c.alias, c.ivBio) }
        } catch (e: Exception) {
            if (!BioKeys.isGone(e)) throw e
            bg { vault.markLost(listOf(id)) } // the chip key itself is kept, see copiesChecked
            return go(Scr.Lost(id), How.REPLACE)
        }
        val sub = if (s.purpose == PinFor.PEEK) "Откроется копия: ${k.name}" else "Откроется ${k.name}, чтобы продолжить заучивание"
        busy = "Ждём отпечаток…"
        val r = prompter().auth("Подтвердите, что это вы", sub, cipher)
        if (r != BioResult.Ok) return bioStopped(r)
        if (left(s)) return
        val plain = bg {
            cipher.updateAAD(Vault.bioAad(c.alias))
            cipher.doFinal(inner)
        }
        val secret = String(plain, Charsets.UTF_8)
        Crypto.wipe(plain, inner)
        if (s.purpose == PinFor.PEEK) {
            val logId = bg { vault.peekOpened(id) }
            peek = PeekSt(id, k.fmt, secret, peekLast, logId)
            peekLast = null
            go(Scr.Peek(id), How.REPLACE)
        } else {
            bg { vault.learnReopened(id) }
            learn = LearnSt(id, k.fmt, secret)
            go(Scr.Learn(id), How.REPLACE)
        }
    }

    /** Encrypts [secret] with a new fingerprint key; returns (alias, iv, box) or null if the finger was not given. */
    private suspend fun bioEncrypt(s: Scr, secret: String, title: String, sub: String): Triple<String, ByteArray, ByteArray>? {
        val alias = BioKeys.newAlias()
        val cipher = bg {
            BioKeys.create(alias)
            BioKeys.encryptCipher(alias)
        }
        busy = "Ждём отпечаток…"
        val r = prompter().auth(title, sub, cipher)
        if (r != BioResult.Ok) {
            BioKeys.delete(alias)
            bioStopped(r)
            return null
        }
        if (left(s)) {
            BioKeys.delete(alias)
            return null
        }
        return try {
            bg {
                cipher.updateAAD(Vault.bioAad(alias))
                val plain = Crypto.utf8(secret)
                val box = cipher.doFinal(plain)
                Crypto.wipe(plain)
                Triple(alias, cipher.iv, box)
            }
        } catch (e: Exception) {
            BioKeys.delete(alias)
            throw e
        }
    }

    private suspend fun saveNew(s: Scr.Pin, key: ByteArray) {
        val n = newKey ?: return
        val (alias, iv, box) = bioEncrypt(s, n.secret, "Подтвердите отпечатком", "Зашифруем копию ключа — по ней можно будет подсмотреть") ?: return
        busy = "Считаем хеш…"
        val k = try {
            bg { vault.addKey(n.name, n.fmt, n.secret, key, alias, iv, box) }
        } catch (e: Exception) {
            BioKeys.delete(alias)
            throw e
        }
        newKey = null
        if (!foreground) return go(Scr.Home, How.ROOT) // saved; learning continues later with PIN and finger
        learn = LearnSt(k.id, k.fmt, n.secret)
        go(Scr.Added(k.id), How.ROOT)
    }

    private suspend fun saveRestore(s: Scr.Pin, key: ByteArray) {
        val id = s.id!!
        val secret = restoreSecret ?: return back()
        val k = vault.data.key(id) ?: return
        val (alias, iv, box) = bioEncrypt(s, secret, "Подтвердите отпечатком", "Создадим новую копию: ${k.name}") ?: return
        val old = try {
            bg { vault.restoreCopy(id, key, alias, iv, box) }
        } catch (e: Exception) {
            BioKeys.delete(alias)
            throw e
        }
        old?.let { BioKeys.delete(it) } // the replaced, dead fingerprint key
        restoreSecret = null
        stack.clear()
        stack.add(Scr.Home)
        arrive(Scr.Result(id, RKind.RESTORED, Mode.RESTORE, 1))
    }

    private suspend fun deleteKey(s: Scr.Pin) {
        val id = s.id!!
        val k = vault.data.key(id) ?: return
        busy = "Ждём отпечаток…"
        val r = prompter().auth("Удаление ключа", "Удалим ${k.name}: хеш и копию", null)
        if (r != BioResult.Ok) return bioStopped(r)
        if (left(s)) return
        val alias = bg { vault.deleteKey(id) }
        alias?.let { BioKeys.delete(it) }
        go(Scr.Home, How.ROOT)
        showToast("${k.name} удалён")
    }

    fun startChangePin() = go(Scr.Pin(PinFor.CHANGE, null))

    // ---------- peek ----------

    fun peekLevels(p: PeekSt, where: Boolean = p.where, open: Collection<Int> = p.open, full: Boolean = p.full): String {
        val out = mutableListOf<String>()
        if (where) out += "где ошибка"
        if (open.isNotEmpty()) out += "открыто ${KeyText.partsText(p.fmt, open.size)} из ${KeyText.chunks(p.fmt, p.secret).size}"
        if (full) out += "показан целиком"
        return out.joinToString(", ")
    }

    /** Journal and schedule first; only after a successful write does the screen show the next part. */
    private fun reveal(p: PeekSt, levels: String, show: () -> Unit) {
        if (p.saving) return
        p.saving = true
        viewModelScope.launch {
            try {
                bg { vault.peekProgress(p.id, p.logId, levels) }
                if (peek === p) show()
            } catch (e: Exception) {
                Log.e("RememberKey", "peek log failed", e)
                showToast("Не удалось записать в журнал — ничего не показано")
            } finally {
                p.saving = false
                sync()
            }
        }
    }

    fun peekWhere() {
        val p = peek ?: return
        if (!p.where) reveal(p, peekLevels(p, where = true)) { p.where = true }
    }

    fun peekPart(i: Int) {
        val p = peek ?: return
        if (i !in p.open) reveal(p, peekLevels(p, open = p.open + i)) { p.open += i }
    }

    fun peekFull() {
        val p = peek ?: return
        if (!p.full) reveal(p, peekLevels(p, full = true)) { p.full = true }
    }

    fun peekDone() {
        val p = peek ?: return
        val levels = peekLevels(p)
        peek = null
        if (levels.isEmpty()) {
            viewModelScope.launch {
                bg { vault.peekClosedEmpty(p.logId) }
                sync()
            }
            val prev = stack.removeLastOrNull() ?: Scr.Home
            arrive(prev)
        } else {
            lastWrong = null
            goFromHome(Scr.Check(p.id, Mode.RELEARN))
        }
    }

    // ---------- phone check ----------

    fun startDiagFromOnboard() = go(Scr.Diag(fromOnboard = true))
    fun startDiagFromSecurity() = go(Scr.Diag(fromOnboard = false))

    private fun startDiag() {
        diagJob?.cancel()
        diagRows.clear()
        PhoneCheck.STEPS.forEach { (id, title) -> diagRows += DiagRow(id, title) }
        diagDone = false
        runDiag()
    }

    fun diagRetry() = runDiag()

    private fun runDiag() {
        val check = PhoneCheck(ctx)
        diagJob = viewModelScope.launch {
            for (row in diagRows) {
                if (row.item != null) continue
                row.state = "run"
                delay(250)
                val item = when (row.id) {
                    "keystore" -> bg { check.keystore() }
                    "biometric" -> check.biometric()
                    "biotest" -> check.bioTest { cipher ->
                        prompter().auth("Тест шифрования", "Временный ключ: зашифруем тестовую строку. Без отпечатка шифрование уже отказало.", cipher)
                    }
                    "lock" -> check.lock()
                    "boot" -> bg { check.boot() }
                    "a11y" -> check.a11y()
                    "adb" -> check.adb()
                    "patch" -> check.patch(SystemRkClock.today())
                    else -> null
                }
                if (item == null) {
                    row.state = "paused"
                    return@launch
                }
                row.item = item
                row.state = null
            }
            val run = DiagRun(System.currentTimeMillis(), diagRows.mapNotNull { it.item })
            bg { vault.saveDiag(run) }
            diagDone = true
            sync()
        }
    }

    fun diagNext(fromOnboard: Boolean) {
        if (fromOnboard) go(Scr.PinSet(change = false), How.ROOT) else go(Scr.Security, How.ROOT)
    }

    fun diagSummary(): Pair<Int, Boolean> {
        val items = diagRows.mapNotNull { it.item }
        return items.count { it.status != St.OK } to items.any { it.status == St.BAD }
    }

    override fun onCleared() {
        changeOldKey?.let { Crypto.wipe(it) }
        super.onCleared()
    }
}
