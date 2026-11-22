package local.rememberkey.core

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Non-exportable HMAC-SHA256 keys in the phone's secure hardware (Android Keystore, TEE).
 * Without the chip nobody can compute a verifier or the PIN key, so a copied vault file cannot be brute-forced elsewhere.
 */
interface HwMac {
    fun exists(alias: String): Boolean

    /** Creates a fresh key, replacing any old one under the same name. */
    fun create(alias: String)

    /** Throws if the key is missing: a lost key must never be silently replaced, the old hashes would stop matching. */
    fun mac(alias: String, data: ByteArray): ByteArray

    fun delete(alias: String)
}

object Crypto {
    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun argon2id(secret: ByteArray, salt: ByteArray, p: ArgonParams, length: Int = 32): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(p.memKiB)
            .withIterations(p.iterations)
            .withParallelism(p.parallelism)
            .withSalt(salt)
            .build()
        val out = ByteArray(length)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(secret, out)
        return out
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** AES-256-GCM with a fresh 12-byte IV; returns (iv, ciphertext with tag). */
    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = random(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return iv to c.doFinal(plain)
    }

    /** Throws AEADBadTagException when the key, IV, AAD or data do not match. */
    fun open(key: ByteArray, iv: ByteArray, box: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return c.doFinal(box)
    }

    /** Constant-time comparison. */
    fun same(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun wipe(vararg arrays: ByteArray?) {
        for (a in arrays) a?.fill(0)
    }

    fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)
}
