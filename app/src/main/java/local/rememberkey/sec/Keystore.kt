package local.rememberkey.sec

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import local.rememberkey.core.Crypto
import local.rememberkey.core.HwMac
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

private const val KS = "AndroidKeyStore"
private const val AES_MODE = "AES/GCM/NoPadding"

private fun keyStore(): KeyStore = KeyStore.getInstance(KS).apply { load(null) }

/**
 * Tries "usable only while the phone is unlocked" first; the ROG Phone supports it,
 * a chip that refuses still gets the key without that condition.
 */
private fun <T> withUnlockedFallback(alias: String, gen: (unlocked: Boolean) -> T): T =
    try {
        gen(true)
    } catch (e: Exception) {
        keyStore().deleteEntry(alias)
        gen(false)
    }

/** K_ver and K_pin: HMAC-SHA256 keys in the TEE. No finger needed; they never leave the chip. */
class KeystoreMac : HwMac {
    override fun exists(alias: String): Boolean = keyStore().containsAlias(alias)

    override fun create(alias: String) {
        keyStore().deleteEntry(alias)
        withUnlockedFallback(alias) { unlocked ->
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KS).apply {
                init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setUnlockedDeviceRequired(unlocked)
                        .build(),
                )
            }.generateKey()
        }
    }

    override fun mac(alias: String, data: ByteArray): ByteArray {
        val key = keyStore().getKey(alias, null) as? SecretKey ?: throw IllegalStateException("в чипе нет ключа $alias")
        return Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal(data)
        }
    }

    override fun delete(alias: String) = keyStore().deleteEntry(alias)
}

/**
 * K_bio: one AES-256-GCM key per stored key. Every single operation needs a Class 3 fingerprint,
 * and the system destroys the key when a new fingerprint is enrolled or the screen lock is removed.
 */
object BioKeys {
    fun newAlias(): String = "rk.bio." + Crypto.random(8).joinToString("") { "%02x".format(it) }

    fun create(alias: String) {
        keyStore().deleteEntry(alias)
        withUnlockedFallback(alias) { unlocked ->
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS).apply {
                init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setUserAuthenticationRequired(true)
                        .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                        .setInvalidatedByBiometricEnrollment(true)
                        .setUnlockedDeviceRequired(unlocked)
                        .build(),
                )
            }.generateKey()
        }
    }

    private fun key(alias: String): SecretKey =
        keyStore().getKey(alias, null) as? SecretKey ?: throw KeyGoneException(alias)

    /** Ready for BiometricPrompt; after the finger: updateAAD, then doFinal. */
    fun encryptCipher(alias: String): Cipher = Cipher.getInstance(AES_MODE).apply { init(Cipher.ENCRYPT_MODE, key(alias)) }

    /** Throws [KeyGoneException] or KeyPermanentlyInvalidatedException when the copy can no longer be opened. */
    fun decryptCipher(alias: String, iv: ByteArray): Cipher =
        Cipher.getInstance(AES_MODE).apply { init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(128, iv)) }

    /** Only these two errors mean the copy is gone for good; anything else may be temporary. */
    fun isGone(e: Throwable): Boolean = e is KeyGoneException || e is KeyPermanentlyInvalidatedException

    /** Startup check: true only when the system has certainly destroyed the key. */
    fun isLost(alias: String, iv: ByteArray): Boolean = try {
        decryptCipher(alias, iv)
        false
    } catch (e: Exception) {
        isGone(e)
    }

    fun delete(alias: String) {
        runCatching { keyStore().deleteEntry(alias) }
    }

    /** "TEE", "StrongBox", "программное" — where a key really lives. */
    fun securityLevel(alias: String): Int {
        val k = key(alias)
        val info = SecretKeyFactory.getInstance(k.algorithm, KS).getKeySpec(k, KeyInfo::class.java) as KeyInfo
        return info.securityLevel
    }
}

class KeyGoneException(alias: String) : Exception("ключа $alias больше нет в чипе")
