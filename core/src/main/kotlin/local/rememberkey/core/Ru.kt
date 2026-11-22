package local.rememberkey.core

import java.time.LocalDate

/** Russian words and dates for the screens and the journal. Days are epoch days of the phone's time zone. */
object Ru {
    private val MONTHS = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )
    private val WEEKDAYS = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
    private val ORDINAL = listOf("", "первой", "второй", "третьей", "четвёртой", "пятой", "шестой")

    fun plural(n: Int, one: String, few: String, many: String): String {
        val a = n % 10
        val b = n % 100
        return when {
            a == 1 && b != 11 -> one
            a in 2..4 && (b < 12 || b > 14) -> few
            else -> many
        }
    }

    fun days(n: Int) = "$n ${plural(n, "день", "дня", "дней")}"

    /** "21 ноября"; the year is added only when it is not the current one. */
    fun date(day: Long, today: Long = day): String {
        val d = LocalDate.ofEpochDay(day)
        val base = "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}"
        return if (d.year == LocalDate.ofEpochDay(today).year) base else "$base ${d.year}"
    }

    fun weekday(day: Long): String = WEEKDAYS[LocalDate.ofEpochDay(day).dayOfWeek.value - 1]

    fun rel(day: Long, today: Long): String {
        val n = (day - today).toInt()
        return when {
            n == 0 -> "сегодня"
            n == 1 -> "завтра"
            n == 2 -> "послезавтра"
            n < 0 -> "просрочено на ${days(-n)}"
            else -> "через ${days(n)}"
        }
    }

    fun dayTitle(day: Long, today: Long): String = when (day - today) {
        0L -> "Сегодня"
        -1L -> "Вчера"
        else -> date(day, today)
    }

    /** "с первой попытки", "со второй попытки", "с седьмой" → "с 7-й попытки". */
    fun fromAttempt(n: Int): String {
        val word = if (n == 2) "со второй" else "с ${ORDINAL.getOrNull(n)?.takeIf { it.isNotEmpty() } ?: "$n-й"}"
        return "$word попытки"
    }
}
