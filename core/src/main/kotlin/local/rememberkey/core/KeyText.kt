package local.rememberkey.core

import java.util.Locale

/** Two key formats: a phrase (words through spaces) and symbols (letters, digits and signs without spaces). */
enum class Fmt(val code: String, val title: String) {
    WORDS("words", "Фраза"),
    SYMBOLS("symbols", "Символы");

    companion object {
        fun of(code: String): Fmt = entries.firstOrNull { it.code == code } ?: error("unknown key format: $code")
    }
}

object KeyText {
    private val SPACES = Regex("\\s+")

    /** Longest key the app keyboard accepts. A 24-word seed phrase is about 170 characters. */
    const val MAX_LEN = 256

    /**
     * The form that is stored in the copy and shown: exactly as typed (case, ё), a phrase with single spaces,
     * symbols without spaces.
     */
    fun clean(fmt: Fmt, s: String): String = when (fmt) {
        Fmt.WORDS -> s.trim().replace(SPACES, " ")
        Fmt.SYMBOLS -> s.replace(SPACES, "")
    }

    /** The form that is compared and hashed: in a phrase case does not matter and ё counts as е. Symbols keep their case. */
    fun norm(fmt: Fmt, s: String): String = when (fmt) {
        Fmt.WORDS -> clean(fmt, s).lowercase(Locale.ROOT).replace('ё', 'е')
        Fmt.SYMBOLS -> clean(fmt, s)
    }

    fun same(fmt: Fmt, a: String, b: String): Boolean = norm(fmt, a) == norm(fmt, b)

    /** Parts for hints and the peek screen: the words of a phrase, blocks of 4 for symbols. */
    fun chunks(fmt: Fmt, s: String): List<String> = when (fmt) {
        Fmt.WORDS -> s.trim().split(SPACES).filter { it.isNotEmpty() }
        Fmt.SYMBOLS -> clean(fmt, s).chunked(4)
    }

    /** Words in a phrase, characters in symbols. */
    fun size(fmt: Fmt, s: String): Int = when (fmt) {
        Fmt.WORDS -> chunks(fmt, s).size
        Fmt.SYMBOLS -> clean(fmt, s).length
    }

    fun sizeText(fmt: Fmt, n: Int): String = "$n " + when (fmt) {
        Fmt.WORDS -> Ru.plural(n, "слово", "слова", "слов")
        Fmt.SYMBOLS -> Ru.plural(n, "символ", "символа", "символов")
    }

    /** "3 слова" / "2 блока" — the parts opened on the peek screen. */
    fun partsText(fmt: Fmt, n: Int): String = "$n " + when (fmt) {
        Fmt.WORDS -> Ru.plural(n, "слово", "слова", "слов")
        Fmt.SYMBOLS -> Ru.plural(n, "блок", "блока", "блоков")
    }

    /** For every part of the real key: is the typed part at the same place wrong or missing? */
    fun wrongParts(fmt: Fmt, real: String, typed: String): List<Boolean> {
        val r = chunks(fmt, real)
        val t = chunks(fmt, typed)
        return r.indices.map { i -> t.getOrNull(i)?.let { !same(fmt, it, r[i]) } ?: true }
    }
}
