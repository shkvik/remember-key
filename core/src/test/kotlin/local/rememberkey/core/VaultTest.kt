package local.rememberkey.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.AEADBadTagException

class VaultTest {
    private val hw = FakeHw()
    private val clock = FakeClock()
    private val store = MemStore()
    private val bio = FakeBio()
    private var vault = Vault(store, hw, clock, TINY, TINY)

    private val pin = "12345678"
    private val phrase = "якорь сирень олово туман пряник витраж"

    private fun reopen() {
        vault = Vault(store, hw, clock, TINY, TINY)
    }

    private fun unlockOk(p: String = pin): ByteArray = (vault.unlock(p, "test") as Unlock.Ok).key

    private fun add(secret: String = phrase, fmt: Fmt = Fmt.WORDS, alias: String = "bio.a"): KeyRec {
        val key = unlockOk()
        val (iv, box) = bio.encrypt(alias, KeyText.clean(fmt, secret))
        return vault.addKey("Мой ключ", fmt, secret, key, alias, iv, box)
    }

    private fun peekSecret(id: String, key: ByteArray): String {
        val c = vault.data.key(id)!!.copy!!
        return bio.decrypt(c.alias, c.ivBio, vault.openCopy(id, key))
    }

    @Test
    fun pinSetAndUnlock() {
        vault.setPin(pin)
        assertTrue(hw.exists(Vault.VER_ALIAS) && hw.exists(Vault.PIN_ALIAS))
        assertTrue(vault.unlock(pin, "t") is Unlock.Ok)
        val w = vault.unlock("00000000", "Подсмотр · Ключ A") as Unlock.Wrong
        assertEquals(1, w.fails)
        assertEquals(0, w.waitMs)
        assertEquals("Подсмотр · Ключ A · ошибка 1 подряд", vault.data.log.last().sub)
        assertThrows(IllegalArgumentException::class.java) { Vault(MemStore(), FakeHw(), clock, TINY, TINY).setPin("123456") }
    }

    @Test
    fun wrongPinPausesSurviveRestartAndResetOnSuccess() {
        vault.setPin(pin)
        repeat(2) { vault.unlock("11111111", "t") }
        val third = vault.unlock("11111111", "t") as Unlock.Wrong
        assertEquals(3, third.fails)
        assertEquals(30_000, third.waitMs)
        reopen()
        // even the right PIN is not checked during the pause
        val macs = hw.macCalls
        assertEquals(Unlock.Wait(30_000), vault.unlock(pin, "t"))
        assertEquals(macs, hw.macCalls)
        clock.pass(30_000)
        val fourth = vault.unlock("11111111", "t") as Unlock.Wrong
        assertEquals(60_000, fourth.waitMs)
        clock.pass(60_000)
        assertTrue(vault.unlock(pin, "t") is Unlock.Ok)
        assertEquals(0, vault.data.pinFails)
    }

    @Test
    fun attemptIsCountedBeforeTheSlowCheck() {
        vault.setPin(pin)
        repeat(2) { vault.unlock("11111111", "t") }
        hw.failNextMac = RuntimeException("app killed")
        assertThrows(RuntimeException::class.java) { vault.unlock(pin, "t") }
        reopen()
        assertEquals(3, vault.data.pinFails)
        assertTrue(vault.unlock(pin, "t") is Unlock.Wait)
    }

    @Test
    fun clockMovedBackDoesNotLockForever() {
        vault.setPin(pin)
        repeat(3) { vault.unlock("11111111", "t") }
        clock.now -= 86_400_000
        assertEquals(Unlock.Wait(30_000), vault.unlock(pin, "t"))
        clock.pass(30_000)
        assertTrue(vault.unlock(pin, "t") is Unlock.Ok)
    }

    @Test
    fun addVerifyAndPeekRoundTrip() {
        vault.setPin(pin)
        val k = add(" Якорь  сирень олово туман пряник витраж")
        assertEquals("A", k.id)
        assertEquals(6, k.size)
        assertFalse(k.learned)
        assertTrue(vault.verify("A", "ЯКОРЬ сирень олово туман пряник витраж"))
        assertFalse(vault.verify("A", "якорь сирень олово туман пряник"))
        reopen()
        assertTrue(vault.verify("A", phrase))
        // the copy keeps the phrase exactly as typed; only checks ignore case
        assertEquals("Якорь сирень олово туман пряник витраж", peekSecret("A", unlockOk()))
        // the file holds no plain key
        assertFalse(store.saved!!.contains("сирень"))
        assertEquals("6 слов · хеш и зашифрованная копия", vault.data.log.last().sub)
    }

    @Test
    fun wrongPinKeyCannotOpenTheCopy() {
        vault.setPin(pin)
        add()
        assertThrows(AEADBadTagException::class.java) { vault.openCopy("A", Crypto.random(32)) }
    }

    @Test
    fun copyIsBoundToItsAlias() {
        vault.setPin(pin)
        add(alias = "bio.a")
        add("Tq7#mV2!pR9x", Fmt.SYMBOLS, "bio.b")
        val a = vault.data.key("A")!!.copy!!
        val swapped = vault.data.keys.map { if (it.id == "B") it.copy(copy = Copy("bio.b", a.ivBio, a.iv, a.box)) else it }
        store.save(vault.data.copy(keys = swapped))
        reopen()
        assertThrows(AEADBadTagException::class.java) { vault.openCopy("B", unlockOk()) }
    }

    @Test
    fun changePinReencryptsCopies() {
        vault.setPin(pin)
        add()
        add("Tq7#mV2!pR9x", Fmt.SYMBOLS, "bio.b")
        vault.changePin(unlockOk(), "87654321")
        assertTrue(vault.unlock(pin, "t") is Unlock.Wrong)
        val key = unlockOk("87654321")
        assertEquals(phrase, peekSecret("A", key))
        assertEquals("Tq7#mV2!pR9x", peekSecret("B", key))
        assertTrue(vault.verify("B", "Tq7#mV2!pR9x"))
    }

    @Test
    fun scheduleThroughChecks() {
        vault.setPin(pin)
        add()
        val today = clock.today
        var k = vault.learned("A")
        assertEquals(today + 1, k.due)
        assertEquals(0, k.level)
        clock.today += 1
        k = vault.checkPassed("A", 1)
        assertEquals(1, k.level)
        assertEquals(clock.today + 3, k.due)
        assertEquals("Верно с первой попытки · следующая через 3 дня", vault.data.log.last().sub)
        clock.today += 3
        vault.checkFailed("A", "Проверка", 1)
        k = vault.checkPassed("A", 2)
        assertEquals(1, k.level) // same interval again
        assertEquals(clock.today + 3, k.due)
        assertEquals("Через 3 дня — верно со второй попытки", k.hist.last().text)
        assertEquals(2, k.checks)
        assertEquals(1, k.first)
        repeat(8) { k = vault.checkPassed("A", 1) }
        assertEquals(6, k.level)
        assertEquals(clock.today + 60, k.due)
    }

    @Test
    fun peekCountsOnlyWhenSomethingIsRevealed() {
        vault.setPin(pin)
        add()
        vault.learned("A")
        repeat(3) { vault.checkPassed("A", 1) }
        val empty = vault.peekOpened("A")
        vault.peekClosedEmpty(empty)
        var k = vault.data.key("A")!!
        assertEquals(3, k.level)
        assertEquals(0, k.peeks)
        assertEquals("PIN и отпечаток · закрыто, ничего не открыто", vault.data.log.first { it.id == empty }.sub)

        val id = vault.peekOpened("A")
        vault.peekProgress("A", id, "где ошибка")
        vault.peekProgress("A", id, "где ошибка, открыто 1 слово из 6")
        k = vault.data.key("A")!!
        assertEquals(0, k.level)
        assertEquals(clock.today + 1, k.due)
        assertEquals(1, k.peeks)
        assertEquals(1, k.hist.count { it.kind == Ev.PEEK })
        assertEquals("Подсмотр (где ошибка, открыто 1 слово из 6) — повтор заново с 1 дня", k.hist.last().text)
        assertEquals("PIN и отпечаток · где ошибка, открыто 1 слово из 6", vault.data.log.first { it.id == id }.sub)
    }

    @Test
    fun lostCopyRestoreAndDelete() {
        vault.setPin(pin)
        add()
        vault.learned("A")
        vault.markLost(listOf("A"))
        assertFalse(vault.data.key("A")!!.hasCopy)
        assertEquals("bio.a", vault.data.key("A")!!.copy!!.alias) // kept: the loss may have been a glitch
        assertEquals(Ev.LOST, vault.data.log.last().kind)
        assertThrows(IllegalStateException::class.java) { vault.openCopy("A", Crypto.random(32)) }
        assertTrue(vault.verify("A", phrase)) // the hash survives
        val key = unlockOk()
        val (iv, box) = bio.encrypt("bio.a2", phrase)
        assertEquals("bio.a", vault.restoreCopy("A", key, "bio.a2", iv, box))
        assertTrue(vault.data.key("A")!!.hasCopy)
        assertEquals(phrase, peekSecret("A", key))
        assertEquals("bio.a2", vault.deleteKey("A"))
        assertTrue(vault.data.keys.isEmpty())
        assertEquals(Ev.DELETE, vault.data.log.last().kind)
        assertEquals("A", vault.freeLetter())
    }

    @Test
    fun copyMarkedLostByAGlitchComesBack() {
        vault.setPin(pin)
        add()
        vault.copiesChecked(listOf("A"), emptyList())
        vault.copiesChecked(listOf("A"), emptyList()) // logged once
        assertEquals(1, vault.data.log.count { it.kind == Ev.LOST })
        vault.copiesChecked(emptyList(), listOf("A"))
        assertTrue(vault.data.key("A")!!.hasCopy)
        assertEquals("Копия снова открывается · Мой ключ", vault.data.log.last().title)
        assertEquals(phrase, peekSecret("A", unlockOk()))
    }

    @Test
    fun movingTheClockForwardDoesNotEndThePause() {
        vault.setPin(pin)
        repeat(3) { vault.unlock("11111111", "t") }
        clock.now += 3_600_000 // wall clock an hour ahead, no real time passed
        reopen()
        assertEquals(30_000L, vault.waitLeftMs())
        assertEquals(Unlock.Wait(30_000), vault.unlock(pin, "t"))
        clock.elapsed += 30_000
        assertTrue(vault.unlock(pin, "t") is Unlock.Ok)
    }

    @Test
    fun afterARebootThePauseFallsBackToTheWallClock() {
        vault.setPin(pin)
        repeat(3) { vault.unlock("11111111", "t") }
        clock.boot += 1
        clock.elapsed = 1_000
        assertEquals(30_000L, vault.waitLeftMs())
        clock.now += 30_000
        assertTrue(vault.unlock(pin, "t") is Unlock.Ok)
    }

    @Test
    fun missingPinChipKeyIsNotCountedAsWrongPin() {
        vault.setPin(pin)
        add()
        hw.delete(Vault.PIN_ALIAS)
        repeat(5) { assertThrows(IllegalStateException::class.java) { vault.unlock(pin, "t") } }
        assertEquals(0, vault.data.pinFails)
        val logBefore = vault.data.log.size
        assertEquals(listOf("bio.a"), vault.resetAfterChipLoss())
        assertTrue(vault.data.keys.isEmpty())
        assertNull(vault.data.pin)
        assertEquals(logBefore + 1, vault.data.log.size) // the journal stays
        vault.setPin(pin) // and the app can start over
        assertTrue(vault.hwKeysPresent())
    }

    @Test
    fun blockStatsAndLessonProgress() {
        vault.setPin(pin)
        val k = add("Tq7#mV2!pR9xLk4&Zw8$", Fmt.SYMBOLS, "bio.s")
        assertEquals(5, k.blocks.size)
        assertEquals(Strength.NEW, Blocks.strength(k.blocks[0]))
        repeat(3) { vault.blockResults("A", mapOf(0 to true, 2 to false)) }
        vault.blockResults("A", mapOf(2 to true))
        vault.lessonProgress("A", 3)
        reopen()
        val r = vault.data.key("A")!!
        assertEquals(Strength.GOOD, Blocks.strength(r.blocks[0]))
        assertEquals(Strength.BAD, Blocks.strength(r.blocks[2]))
        assertEquals(Strength.NEW, Blocks.strength(r.blocks[1]))
        assertEquals(3, r.lessonBlock)
        repeat(30) { vault.blockResults("A", mapOf(4 to true)) }
        assertEquals(Blocks.KEEP, vault.data.key("A")!!.blocks[4].size)
        val logId = vault.blockPracticeOpened("A", 2)
        vault.blockPracticeDone(logId)
        assertEquals("Тренировка блока 3 · Мой ключ", vault.data.log.last().title)
        assertEquals(0, vault.data.key("A")!!.peeks) // practice is not a peek
    }

    @Test
    fun strengthRules() {
        assertEquals(Strength.MID, Blocks.strength(listOf(true)))
        assertEquals(Strength.GOOD, Blocks.strength(listOf(false, false, true, true, true, true, true)))
        assertEquals(Strength.MID, Blocks.strength(listOf(true, false, true, true, true)))
        assertEquals(Strength.BAD, Blocks.strength(listOf(false, true, false)))
    }

    @Test
    fun lettersAreReusedAndLimited() {
        vault.setPin(pin)
        repeat(10) { add(alias = "bio.$it") }
        assertNull(vault.freeLetter())
        assertThrows(IllegalStateException::class.java) { add(alias = "bio.x") }
    }

    @Test
    fun lostChipKeyIsNeverRecreated() {
        vault.setPin(pin)
        add()
        hw.delete(Vault.VER_ALIAS)
        assertFalse(vault.hwKeysPresent())
        assertThrows(IllegalStateException::class.java) { vault.verify("A", phrase) }
        assertFalse(hw.exists(Vault.VER_ALIAS))
    }

    @Test
    fun diagSummary() {
        vault.saveDiag(DiagRun(1, listOf(DiagItem("patch", "Обновление безопасности", "старое", St.WARN), DiagItem("adb", "Отладка по USB", "вкл", St.WARN), DiagItem("tee", "Хранилище", "TEE", St.OK))))
        assertEquals("2 замечания: обновление безопасности, отладка по USB", vault.data.log.last().sub)
        assertNotNull(vault.data.diag)
    }

    @Test
    fun codecRoundTripAndFileStore() {
        vault.setPin(pin)
        add()
        vault.learned("A")
        vault.saveDiag(DiagRun(5, listOf(DiagItem("x", "X", "y", St.BAD))))
        val dir = Files.createTempDirectory("rk").toFile()
        val fs = FileStore(File(dir, "vault.json"))
        fs.save(vault.data)
        fs.save(vault.data) // replaces the existing file
        val back = fs.load()!!
        assertEquals(Codec.encode(vault.data), Codec.encode(back))
        assertArrayEquals(vault.data.pin!!.check, back.pin!!.check)
        assertFalse(File(dir, "vault.json.tmp").exists())
        dir.deleteRecursively()
    }
}
