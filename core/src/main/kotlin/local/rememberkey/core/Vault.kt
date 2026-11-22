package local.rememberkey.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

interface VaultStore {
    fun load(): VaultData?
    fun save(d: VaultData)
}

/** Writes a temporary file, syncs it to disk and swaps it in, so a crash never leaves half a vault. */
class FileStore(private val file: File) : VaultStore {
    override fun load(): VaultData? = if (file.exists()) Codec.decode(file.readText()) else null

    override fun save(d: VaultData) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(Codec.encode(d).toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}

interface RkClock {
    fun now(): Long
    fun today(): Long

    /** Monotonic milliseconds since boot (wall-clock changes do not move it), -1 if unknown. */
    fun elapsed(): Long = -1

    /** Number of this boot, -1 if unknown. */
    fun boot(): Int = -1
}

object SystemRkClock : RkClock {
    override fun now() = System.currentTimeMillis()
    override fun today() = LocalDate.now().toEpochDay()
}

sealed interface Unlock {
    /** [key] encrypts the outer layer of the copies; wipe it after use. */
    class Ok(val key: ByteArray) : Unlock
    data class Wrong(val fails: Int, val waitMs: Long) : Unlock
    data class Wait(val waitMs: Long) : Unlock
}

/**
 * All state changes of the app. Every change is written to disk before the method returns.
 *
 * Crypto, per key:
 *  - verifier = HMAC(K_ver, Argon2id(norm(key))) — checks need no PIN and no finger, and K_ver never leaves the chip;
 *  - copy = AES-GCM(pinKey, AES-GCM(K_bio, key)) — K_bio is a per-key fingerprint key in the chip (done by the app),
 *    pinKey = HMAC(HMAC(K_pin, Argon2id(PIN)), "enc"). Changing the PIN re-encrypts only the outer layer.
 */
class Vault(
    private val store: VaultStore,
    private val hw: HwMac,
    private val clock: RkClock = SystemRkClock,
    private val keyArgon: ArgonParams = KEY_ARGON,
    private val pinArgon: ArgonParams = PIN_ARGON,
) {
    companion object {
        /** about 1 s on the ROG Phone 9 Pro */
        val KEY_ARGON = ArgonParams(64 * 1024, 3)

        /** about 2 s on the ROG Phone 9 Pro */
        val PIN_ARGON = ArgonParams(128 * 1024, 3)
        const val VER_ALIAS = "rk.ver"
        const val PIN_ALIAS = "rk.pin"
        const val LETTERS = "ABCDEFGHIJ"

        /** Associated data of the fingerprint layer: binds the ciphertext to its key. */
        fun bioAad(alias: String): ByteArray = Crypto.utf8("rk/bio/v1/$alias")
        private fun copyAad(alias: String) = Crypto.utf8("rk/copy/v1/$alias")
        private const val KEY_DOMAIN = "rk/key/v1\n"
        private const val PIN_DOMAIN = "rk/pin/v1\n"
    }

    @Volatile
    var data: VaultData = store.load() ?: VaultData()
        private set

    private val lock = Any()

    val hasPin: Boolean get() = data.pin != null

    fun freeLetter(): String? = LETTERS.map { it.toString() }.firstOrNull { data.key(it) == null }

    /** The chip keys behind the hashes and the PIN are still there. */
    fun hwKeysPresent(): Boolean = data.pin == null || (hw.exists(VER_ALIAS) && hw.exists(PIN_ALIAS))

    private fun commit(d: VaultData) {
        store.save(d)
        data = d
    }

    private fun log(d: VaultData, kind: Ev, title: String, sub: String): VaultData =
        d.copy(log = d.log + LogEntry(d.nextLogId, clock.now(), kind, title, sub), nextLogId = d.nextLogId + 1)

    /** Runs [f] on one key; returning null for the key deletes it. */
    private fun change(id: String, f: (k: KeyRec, d: VaultData, today: Long) -> Pair<KeyRec?, VaultData>): KeyRec? =
        synchronized(lock) {
            val k = data.key(id) ?: error("no key $id")
            val (nk, d) = f(k, data, clock.today())
            val keys = if (nk == null) d.keys.filter { it.id != id } else d.keys.map { if (it.id == id) nk else it }
            commit(d.copy(keys = keys))
            nk
        }

    // ---------- PIN ----------

    fun setPin(pin: String): Unit = synchronized(lock) {
        check(data.pin == null && data.keys.isEmpty()) { "PIN already set" }
        // nothing depends on old chip keys yet (e.g. left after "clear storage"), so start with fresh ones
        hw.create(VER_ALIAS)
        hw.create(PIN_ALIAS)
        val (rec, key) = newPin(pin)
        Crypto.wipe(key)
        commit(log(data.copy(pin = rec, pinFails = 0, lastFailAt = 0), Ev.DIAG, "PIN приложения создан", "Нужен для подсмотра вместе с отпечатком"))
    }

    fun waitLeftMs(): Long = waitLeft(data)

    /** Within one boot the pause runs on the monotonic clock, so moving the time forward does not end it. */
    private fun waitLeft(d: VaultData): Long {
        val pause = PinGuard.pauseMs(d.pinFails)
        if (pause == 0L) return 0
        val el = clock.elapsed()
        if (d.failBoot >= 0 && d.failBoot == clock.boot() && el >= d.failElapsed) {
            return (d.failElapsed + pause - el).coerceIn(0, pause)
        }
        return PinGuard.waitLeftMs(d.pinFails, d.lastFailAt, clock.now())
    }

    private fun failed(d: VaultData, fails: Int, at: Long) =
        d.copy(pinFails = fails, lastFailAt = at, failElapsed = clock.elapsed(), failBoot = clock.boot())

    /** Slow: Argon2id with 128 MiB, about 2 s. [context] goes to the journal on a wrong PIN, e.g. "Подсмотр · Ключ A". */
    fun unlock(pin: String, context: String): Unlock = synchronized(lock) {
        val rec = data.pin ?: error("PIN is not set")
        // a missing chip key is not a wrong PIN: do not count it
        check(hw.exists(PIN_ALIAS)) { "в чипе нет ключа PIN" }
        val now = clock.now()
        if (now < data.lastFailAt) commit(data.copy(lastFailAt = now)) // clock moved back: the pause runs from now
        val left = waitLeft(data)
        if (left > 0) return Unlock.Wait(left)
        // counted before the slow check, so killing the app in the middle does not skip the pause
        val fails = data.pinFails + 1
        commit(failed(data, fails, now))
        val raw = pinRaw(pin, rec.salt, rec.argon)
        try {
            if (Crypto.same(pinCheck(raw), rec.check)) {
                commit(data.copy(pinFails = 0, lastFailAt = 0))
                return Unlock.Ok(pinKey(raw))
            }
        } finally {
            Crypto.wipe(raw)
        }
        val at = clock.now()
        commit(log(failed(data, fails, at), Ev.PIN, "Неверный PIN", "$context · ошибка $fails подряд"))
        return Unlock.Wrong(fails, PinGuard.pauseMs(fails))
    }

    /** [oldKey] from [unlock] with the old PIN. Re-encrypts the PIN layer of every copy; no finger needed. */
    fun changePin(oldKey: ByteArray, newPin: String): Unit = synchronized(lock) {
        val (rec, newKey) = newPin(newPin)
        try {
            val keys = data.keys.map { k ->
                val c = k.copy ?: return@map k
                val inner = Crypto.open(oldKey, c.iv, c.box, copyAad(c.alias))
                val (iv, box) = Crypto.seal(newKey, inner, copyAad(c.alias))
                k.copy(copy = Copy(c.alias, c.ivBio, iv, box))
            }
            commit(log(data.copy(pin = rec, keys = keys, pinFails = 0, lastFailAt = 0), Ev.DIAG, "PIN приложения изменён", "Копии ключей перешифрованы новым PIN"))
        } finally {
            Crypto.wipe(newKey)
        }
    }

    private fun pinRaw(pin: String, salt: ByteArray, argon: ArgonParams): ByteArray {
        val input = Crypto.utf8(PIN_DOMAIN + pin)
        val pre = Crypto.argon2id(input, salt, argon)
        try {
            return hw.mac(PIN_ALIAS, pre)
        } finally {
            Crypto.wipe(pre, input)
        }
    }

    private fun pinCheck(raw: ByteArray) = Crypto.hmac(raw, Crypto.utf8("rk/pin/check/v1"))
    private fun pinKey(raw: ByteArray) = Crypto.hmac(raw, Crypto.utf8("rk/pin/enc/v1"))

    private fun newPin(pin: String): Pair<PinRec, ByteArray> {
        require(pin.length == PinGuard.LENGTH && pin.all { it in '0'..'9' }) { "PIN must be ${PinGuard.LENGTH} digits" }
        val salt = Crypto.random(16)
        val raw = pinRaw(pin, salt, pinArgon)
        try {
            return PinRec(salt, pinCheck(raw), pinArgon, pin.length) to pinKey(raw)
        } finally {
            Crypto.wipe(raw)
        }
    }

    // ---------- keys ----------

    /**
     * [bioBox] is the key already encrypted by the fingerprint key [alias] (with [bioAad]).
     * Slow: computes the verifier.
     */
    fun addKey(name: String, fmt: Fmt, secret: String, pinKey: ByteArray, alias: String, ivBio: ByteArray, bioBox: ByteArray): KeyRec =
        synchronized(lock) {
            val id = freeLetter() ?: error("no free letter")
            val clean = KeyText.clean(fmt, secret)
            require(clean.isNotEmpty()) { "empty key" }
            val today = clock.today()
            val k = KeyRec(
                id = id,
                name = name.trim().ifEmpty { "Ключ $id" },
                fmt = fmt,
                size = KeyText.size(fmt, clean),
                parts = KeyText.chunks(fmt, clean).size,
                created = today,
                due = today,
                verifier = newVerifier(fmt, clean),
                copy = sealCopy(pinKey, alias, ivBio, bioBox),
            )
            commit(log(data.copy(keys = data.keys + k), Ev.ADD, "Добавлен · ${k.name}", "${KeyText.sizeText(fmt, k.size)} · хеш и зашифрованная копия"))
            k
        }

    private fun newVerifier(fmt: Fmt, clean: String): Verifier {
        val salt = Crypto.random(16)
        return Verifier(salt, verifierMac(fmt, clean, salt, keyArgon), keyArgon)
    }

    private fun verifierMac(fmt: Fmt, s: String, salt: ByteArray, argon: ArgonParams): ByteArray {
        val input = Crypto.utf8(KEY_DOMAIN + KeyText.norm(fmt, s))
        val pre = Crypto.argon2id(input, salt, argon)
        try {
            return hw.mac(VER_ALIAS, pre)
        } finally {
            Crypto.wipe(pre, input)
        }
    }

    /** Is [input] the key? Slow (Argon2id, about 1 s), changes nothing, needs neither PIN nor finger. */
    fun verify(id: String, input: String): Boolean {
        val k = data.key(id) ?: error("no key $id")
        return Crypto.same(verifierMac(k.fmt, input, k.verifier.salt, k.verifier.argon), k.verifier.mac)
    }

    private fun sealCopy(pinKey: ByteArray, alias: String, ivBio: ByteArray, bioBox: ByteArray): Copy {
        val (iv, box) = Crypto.seal(pinKey, bioBox, copyAad(alias))
        return Copy(alias, ivBio, iv, box)
    }

    /** Removes the PIN layer; the result still has to be decrypted by the chip with a finger. */
    fun openCopy(id: String, pinKey: ByteArray): ByteArray {
        val k = data.key(id) ?: error("no key $id")
        val c = k.copy?.takeIf { k.hasCopy } ?: error("key $id has no copy")
        return Crypto.open(pinKey, c.iv, c.box, copyAad(c.alias))
    }

    // ---------- checks ----------

    fun checkPassed(id: String, attempt: Int): KeyRec = change(id) { k, d, today ->
        val level = Schedule.levelAfterPass(k.level, attempt == 1)
        val due = today + Schedule.interval(level)
        val text = "Через ${Ru.days(Schedule.interval(k.level))} — верно" + if (attempt > 1) " ${Ru.fromAttempt(attempt)}" else ""
        val nk = k.copy(
            level = level, due = due, checks = k.checks + 1, first = k.first + if (attempt == 1) 1 else 0,
            hist = k.hist + Hist(today, Ev.OK, text),
        )
        nk to log(d, Ev.OK, "Проверка · ${k.name}", "Верно ${Ru.fromAttempt(attempt)} · следующая ${Ru.rel(due, today)}")
    }!!

    /** [what] is "Проверка" or "Повтор". */
    fun checkFailed(id: String, what: String, attempt: Int) {
        change(id) { k, d, _ -> k to log(d, Ev.FAIL, "$what · ${k.name}", "Не совпало · попытка $attempt") }
    }

    fun practiceFailed(id: String, round: Int) {
        change(id) { k, d, _ -> k to log(d, Ev.FAIL, "Тренировка · ${k.name}", "Не совпало · ввод $round из 3") }
    }

    fun practiceDone(id: String) {
        change(id) { k, d, _ -> k to log(d, Ev.OK, "Тренировка · ${k.name}", "Верно 3 раза · расписание не меняется") }
    }

    /** Typed from memory after a peek; the schedule was already reset by the peek. */
    fun relearnPassed(id: String) {
        change(id) { k, d, today ->
            k.copy(hist = k.hist + Hist(today, Ev.OK, "После подсмотра — верно по памяти")) to
                log(d, Ev.OK, "Повтор · ${k.name}", "Верно по памяти после подсмотра · проверка ${Ru.rel(k.due, today)}")
        }
    }

    fun learned(id: String): KeyRec = change(id) { k, d, today ->
        k.copy(learned = true, level = 0, due = today + 1, hist = k.hist + Hist(today, Ev.LEARN, "Заучивание")) to
            log(d, Ev.LEARN, "Заучен · ${k.name}", "3 раза подряд по памяти · первая проверка завтра")
    }!!

    fun learnReopened(id: String) {
        change(id) { k, d, _ -> k to log(d, Ev.PEEK, "Заучивание · ${k.name}", "Открыт по PIN и отпечатку, чтобы продолжить заучивание") }
    }

    // ---------- peek ----------

    /** Written right after the copy is decrypted, before anything is shown. Returns the journal entry id. */
    fun peekOpened(id: String): Long {
        var logId = 0L
        change(id) { k, d, _ ->
            logId = d.nextLogId
            k to log(d, Ev.PEEK, "Подсмотр · ${k.name}", "PIN и отпечаток · пока ничего не открыто")
        }
        return logId
    }

    /**
     * Something was revealed; [levels] says what so far, e.g. "где ошибка, открыто 1 слово из 6".
     * The first reveal of a peek counts it and restarts the schedule: relearn now, check tomorrow.
     */
    fun peekProgress(id: String, logId: Long, levels: String) {
        change(id) { k, d, today ->
            val d2 = d.copy(log = d.log.map { if (it.id == logId) it.copy(sub = "PIN и отпечаток · $levels") else it })
            val text = "Подсмотр ($levels) — повтор заново с 1 дня"
            val nk = if (k.hist.any { it.ref == logId }) {
                k.copy(hist = k.hist.map { if (it.ref == logId) it.copy(text = text) else it })
            } else {
                k.copy(level = 0, due = today + 1, peeks = k.peeks + 1, hist = k.hist + Hist(today, Ev.PEEK, text, logId))
            }
            nk to d2
        }
    }

    /** Closed without revealing anything: does not count as a peek. */
    fun peekClosedEmpty(logId: Long): Unit = synchronized(lock) {
        commit(data.copy(log = data.log.map { if (it.id == logId) it.copy(sub = "PIN и отпечаток · закрыто, ничего не открыто") else it }))
    }

    // ---------- copies ----------

    /**
     * The chip reports the fingerprint keys of [lost] as gone (new fingerprint, lock screen removed) and those of
     * [working] as fine. Nothing is deleted: a copy marked lost by a passing glitch comes back on a later check.
     */
    fun copiesChecked(lost: Collection<String>, working: Collection<String>): Unit = synchronized(lock) {
        var d = data
        for (id in lost) {
            val k = d.key(id) ?: continue
            if (k.copy == null || k.copyLost) continue
            d = log(d, Ev.LOST, "Копия уничтожена · ${k.name}", "Система стёрла ключ отпечатка — например, после нового отпечатка в настройках")
            d = d.copy(keys = d.keys.map { if (it.id == id) it.copy(copyLost = true) else it })
        }
        for (id in working) {
            val k = d.key(id) ?: continue
            if (!k.copyLost) continue
            d = log(d, Ev.RESTORE, "Копия снова открывается · ${k.name}", "Ключ отпечатка снова работает")
            d = d.copy(keys = d.keys.map { if (it.id == id) it.copy(copyLost = false) else it })
        }
        commit(d.copy(healthAt = clock.now()))
    }

    fun markLost(ids: Collection<String>) = copiesChecked(ids, emptyList())

    fun healthChecked(): Unit = synchronized(lock) { commit(data.copy(healthAt = clock.now())) }

    /**
     * After the key was typed from memory and matched its hash: a new copy under a new fingerprint key.
     * Returns the old fingerprint key alias, to delete from the chip now that it is replaced.
     */
    fun restoreCopy(id: String, pinKey: ByteArray, alias: String, ivBio: ByteArray, bioBox: ByteArray): String? {
        var old: String? = null
        change(id) { k, d, _ ->
            old = k.copy?.alias?.takeIf { it != alias }
            k.copy(copy = sealCopy(pinKey, alias, ivBio, bioBox), copyLost = false) to
                log(d, Ev.RESTORE, "Копия восстановлена · ${k.name}", "Совпало с хешем · новая копия создана")
        }
        return old
    }

    /**
     * The chip keys behind the hashes or the PIN are gone, so nothing stored can be checked or opened any more.
     * Starts over with a new PIN; the journal stays. Returns fingerprint key aliases to delete.
     */
    fun resetAfterChipLoss(): List<String> = synchronized(lock) {
        check(!hwKeysPresent()) { "chip keys are present" }
        val aliases = data.keys.mapNotNull { it.copy?.alias }
        val d = data.copy(pin = null, keys = emptyList(), pinFails = 0, lastFailAt = 0, failElapsed = 0, failBoot = -1)
        commit(log(d, Ev.LOST, "Хранилище начато заново", "Ключи хеша и PIN пропали из чипа · журнал сохранён"))
        aliases
    }

    /** Returns the fingerprint key alias to delete from the chip, if any. */
    fun deleteKey(id: String): String? {
        var alias: String? = null
        change(id) { k, d, _ ->
            alias = k.copy?.alias
            null to log(d, Ev.DELETE, "Удалён · ${k.name}", "PIN и отпечаток · хеш и копия стёрты")
        }
        return alias
    }

    // ---------- phone check ----------

    fun saveDiag(run: DiagRun): Unit = synchronized(lock) {
        val bad = run.items.filter { it.status != St.OK }
        val sub = if (bad.isEmpty()) "Всё в порядке"
        else "${bad.size} ${Ru.plural(bad.size, "замечание", "замечания", "замечаний")}: " + bad.joinToString(", ") { it.title.replaceFirstChar { c -> c.lowercaseChar() } }
        commit(log(data.copy(diag = run), Ev.DIAG, "Проверка телефона", sub))
    }
}
