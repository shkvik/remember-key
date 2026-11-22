package local.rememberkey.core

/** Software stand-in for the Keystore: per-alias random HMAC keys. */
class FakeHw : HwMac {
    val keys = mutableMapOf<String, ByteArray>()
    var macCalls = 0
    var failNextMac: RuntimeException? = null

    override fun exists(alias: String) = alias in keys
    override fun create(alias: String) {
        keys[alias] = Crypto.random(32)
    }

    override fun mac(alias: String, data: ByteArray): ByteArray {
        failNextMac?.let { failNextMac = null; throw it }
        macCalls++
        val k = keys[alias] ?: throw IllegalStateException("no hw key $alias")
        return Crypto.hmac(k, data)
    }

    override fun delete(alias: String) {
        keys.remove(alias)
    }
}

class FakeClock(var now: Long = 1_700_000_000_000L, var today: Long = 20_413L) : RkClock {
    var elapsed = 5_000_000L
    var boot = 7
    override fun now() = now
    override fun today() = today
    override fun elapsed() = elapsed
    override fun boot() = boot

    /** real time passes: both clocks move */
    fun pass(ms: Long) {
        now += ms
        elapsed += ms
    }
}

class MemStore : VaultStore {
    var saved: String? = null
    var saves = 0
    override fun load(): VaultData? = saved?.let(Codec::decode)
    override fun save(d: VaultData) {
        saved = Codec.encode(d)
        saves++
    }
}

/** Stands in for the fingerprint layer: a plain AES-GCM key per alias. */
class FakeBio {
    private val keys = mutableMapOf<String, ByteArray>()
    fun encrypt(alias: String, plain: String): Pair<ByteArray, ByteArray> {
        val k = keys.getOrPut(alias) { Crypto.random(32) }
        return Crypto.seal(k, Crypto.utf8(plain), Vault.bioAad(alias))
    }

    fun decrypt(alias: String, iv: ByteArray, box: ByteArray): String =
        String(Crypto.open(keys.getValue(alias), iv, box, Vault.bioAad(alias)), Charsets.UTF_8)
}

val TINY = ArgonParams(64, 1)
