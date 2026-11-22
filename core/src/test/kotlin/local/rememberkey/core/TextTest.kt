package local.rememberkey.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TextTest {
    @Test
    fun phraseIgnoresCaseSpacesAndYo() {
        assertTrue(KeyText.same(Fmt.WORDS, "  Ёж  СИРЕНЬ\tолово ", "еж сирень олово"))
        assertEquals("Ёж СИРЕНЬ олово", KeyText.clean(Fmt.WORDS, "  Ёж  СИРЕНЬ\tолово "))
        assertFalse(KeyText.same(Fmt.WORDS, "ёж сирень", "ёж сирен"))
    }

    @Test
    fun symbolsKeepCaseDropSpaces() {
        assertTrue(KeyText.same(Fmt.SYMBOLS, "Tq7# mV2!", "Tq7#mV2!"))
        assertFalse(KeyText.same(Fmt.SYMBOLS, "tq7#mV2!", "Tq7#mV2!"))
        assertEquals(listOf("Tq7#", "mV2!", "pR9x"), KeyText.chunks(Fmt.SYMBOLS, "Tq7#mV2!pR9x"))
        assertEquals(12, KeyText.size(Fmt.SYMBOLS, "Tq7#mV2!pR9x"))
    }

    @Test
    fun wrongPartsMarksMissingAndDifferent() {
        assertEquals(
            listOf(false, false, true, false, false, true),
            KeyText.wrongParts(Fmt.WORDS, "якорь сирень олово туман пряник витраж", "Якорь сирень трамвай туман пряник"),
        )
        assertEquals(listOf(false, true, false), KeyText.wrongParts(Fmt.SYMBOLS, "Tq7#mV2!pR9x", "Tq7#mv2!pR9x"))
    }

    @Test
    fun russianWords() {
        assertEquals("1 день", Ru.days(1))
        assertEquals("3 дня", Ru.days(3))
        assertEquals("7 дней", Ru.days(7))
        assertEquals("11 дней", Ru.days(11))
        assertEquals("21 день", Ru.days(21))
        assertEquals("6 слов", KeyText.sizeText(Fmt.WORDS, 6))
        assertEquals("2 блока", KeyText.partsText(Fmt.SYMBOLS, 2))
        assertEquals("с первой попытки", Ru.fromAttempt(1))
        assertEquals("со второй попытки", Ru.fromAttempt(2))
        assertEquals("с 9-й попытки", Ru.fromAttempt(9))
        val today = LocalDate.of(2026, 11, 21).toEpochDay()
        assertEquals("21 ноября", Ru.date(today, today))
        assertEquals("5 сентября 2024", Ru.date(LocalDate.of(2024, 9, 5).toEpochDay(), today))
        assertEquals("суббота", Ru.weekday(today))
        assertEquals("завтра", Ru.rel(today + 1, today))
        assertEquals("через 7 дней", Ru.rel(today + 7, today))
        assertEquals("просрочено на 2 дня", Ru.rel(today - 2, today))
    }

    @Test
    fun schedule() {
        assertEquals(1, Schedule.levelAfterPass(0, true))
        assertEquals(0, Schedule.levelAfterPass(0, false))
        assertEquals(6, Schedule.levelAfterPass(6, true))
        assertEquals(60, Schedule.interval(6))
        assertEquals(3, Schedule.interval(1))
    }

    @Test
    fun pinPauses() {
        assertEquals(0, PinGuard.pauseMs(2))
        assertEquals(30_000, PinGuard.pauseMs(3))
        assertEquals(60_000, PinGuard.pauseMs(4))
        assertEquals(300_000, PinGuard.pauseMs(5))
        assertEquals(900_000, PinGuard.pauseMs(6))
        assertEquals(900_000, PinGuard.pauseMs(40))
        assertEquals(20_000, PinGuard.waitLeftMs(3, 1000, 11_000))
        assertEquals(0, PinGuard.waitLeftMs(3, 1000, 40_000))
        // clock moved back by a day: still only the pause itself
        assertEquals(30_000, PinGuard.waitLeftMs(3, 100_000_000, 1000))
    }
}
