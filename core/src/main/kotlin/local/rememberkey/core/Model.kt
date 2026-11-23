package local.rememberkey.core

/** Kinds of journal and history events; the code is what the vault file stores. */
enum class Ev(val code: String) {
    OK("ok"), FAIL("fail"), PEEK("peek"), PIN("pin"), LEARN("learn"), ADD("add"),
    DIAG("diag"), RESTORE("restore"), LOST("lost"), DELETE("delete");

    companion object {
        fun of(code: String): Ev = entries.firstOrNull { it.code == code } ?: DIAG
    }
}

data class ArgonParams(val memKiB: Int, val iterations: Int, val parallelism: Int = 1)

/** HMAC(K_ver in the chip, Argon2id(key)). Checking a key needs neither the PIN nor a finger. */
class Verifier(val salt: ByteArray, val mac: ByteArray, val argon: ArgonParams)

/**
 * The encrypted copy: the key is encrypted by the fingerprint key [alias] in the chip (iv [ivBio]),
 * and that result is encrypted again by the key derived from the app PIN ([iv], [box]).
 */
class Copy(val alias: String, val ivBio: ByteArray, val iv: ByteArray, val box: ByteArray)

/** A line of the key's own history. [ref] links a peek line to its journal entry. */
data class Hist(val day: Long, val kind: Ev, val text: String, val ref: Long = 0)

data class KeyRec(
    val id: String,
    val name: String,
    val fmt: Fmt,
    /** words or characters */
    val size: Int,
    /** words or blocks of 4 */
    val parts: Int,
    val created: Long,
    val learned: Boolean = false,
    val level: Int = 0,
    /** epoch day of the next check */
    val due: Long,
    val checks: Int = 0,
    val first: Int = 0,
    val peeks: Int = 0,
    val hist: List<Hist> = emptyList(),
    val verifier: Verifier,
    val copy: Copy?,
    /**
     * The chip said the fingerprint key is gone. The copy data is kept anyway (the alias too): if that was a passing
     * glitch, the next check finds the key working again. A restore replaces the copy.
     */
    val copyLost: Boolean = false,
    /** Per block of 4 symbols: the latest entries from memory (true — right), newest last, at most [Blocks.KEEP]. */
    val blocks: List<List<Boolean>> = emptyList(),
    /** Block lessons: how many blocks are learned; == parts means only the whole-key round is left. */
    val lessonBlock: Int = 0,
) {
    /** Can be opened with PIN + finger right now. */
    val hasCopy: Boolean get() = copy != null && !copyLost
}

/** PIN check value and salt; the PIN itself and keys derived from it are never stored. */
class PinRec(val salt: ByteArray, val check: ByteArray, val argon: ArgonParams, val length: Int)

data class LogEntry(val id: Long, val at: Long, val kind: Ev, val title: String, val sub: String)

enum class St(val code: String) {
    OK("ok"), WARN("warn"), BAD("bad");

    companion object {
        fun of(code: String): St = entries.firstOrNull { it.code == code } ?: WARN
    }
}

data class DiagItem(val id: String, val title: String, val result: String, val status: St)

data class DiagRun(val at: Long, val items: List<DiagItem>)

data class VaultData(
    val pin: PinRec? = null,
    val pinFails: Int = 0,
    val lastFailAt: Long = 0,
    /** the same moment on the monotonic clock, and which boot it was: moving the wall clock does not shorten a pause */
    val failElapsed: Long = 0,
    val failBoot: Int = -1,
    val keys: List<KeyRec> = emptyList(),
    /** oldest first; there is no way to delete entries */
    val log: List<LogEntry> = emptyList(),
    val nextLogId: Long = 1,
    val diag: DiagRun? = null,
    /** when the app last confirmed that the copies can still be opened */
    val healthAt: Long = 0,
) {
    fun key(id: String): KeyRec? = keys.firstOrNull { it.id == id }
}
