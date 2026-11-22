package local.rememberkey.core

import kotlin.math.max
import kotlin.math.min

/**
 * Spaced checks: 1 → 3 → 7 → 16 → 35 → 60 days, then every 60.
 * A key's level is how many checks in a row it passed on the first try; it waits [interval] (level) days.
 */
object Schedule {
    val INTERVALS = intArrayOf(1, 3, 7, 16, 35, 60)
    const val CHECKS = 6

    fun interval(level: Int): Int = INTERVALS[level.coerceIn(0, INTERVALS.size - 1)]

    /** Correct on the first try moves one step further; correct after a miss repeats the same interval. */
    fun levelAfterPass(level: Int, firstTry: Boolean): Int = if (firstTry) min(level + 1, CHECKS) else level

    fun line(level: Int): String =
        if (level >= CHECKS) "Поддержание · каждые 60 дней"
        else "Проверка ${level + 1} из $CHECKS · интервал ${Ru.days(interval(level))}"
}

/** Wrong app PIN: nothing is ever erased, the phone only makes you wait. */
object PinGuard {
    /** The app PIN has exactly this many digits. */
    const val LENGTH = 8

    private val PAUSES_S = longArrayOf(30, 60, 300, 900)

    /** Pause after [fails] wrong PINs in a row: none for the first two, then 30 s, 1, 5 and 15 minutes. */
    fun pauseMs(fails: Int): Long = if (fails < 3) 0 else PAUSES_S[min(fails - 3, PAUSES_S.size - 1)] * 1000

    /** Never more than the pause itself, even if the clock was moved back after the last wrong PIN. */
    fun waitLeftMs(fails: Int, lastFailAt: Long, now: Long): Long {
        val pause = pauseMs(fails)
        if (pause == 0L) return 0
        return min(pause, max(0, lastFailAt + pause - now))
    }
}
