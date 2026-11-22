package local.rememberkey.diag

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The exact key RememberKey would use for the copy: AES-256-GCM, a Class 3 biometric for every single operation,
 * destroyed when a new fingerprint is enrolled. Checks that the chip refuses without a finger and works with one.
 */
class BioTest(private val act: Activity) {

    suspend fun run(strongBox: Boolean): JSONObject {
        val o = JSONObject()
        val alias = "rkdiag_bio_${tag(strongBox)}"
        val ks = keyStore().also { it.deleteEntry(alias) }
        val key = try {
            genWithUnlockedFallback(o, alias) { u -> genAes(alias, strongBox, auth = true, unlocked = u) }
        } catch (e: Exception) {
            return o.put("ok", false).put("generate_error", err(e))
        }
        try {
            o.put("key_info", keyInfo(key))
            o.put("encrypt_without_finger", tryOp { Cipher.getInstance(AES_MODE).apply { init(Cipher.ENCRYPT_MODE, key) }.doFinal(PLAIN) })

            val enc = Cipher.getInstance(AES_MODE).apply { init(Cipher.ENCRYPT_MODE, key) }
            prompt(enc, "Шаг 1 из 2: зашифровать тестовую строку")?.let { return o.put("ok", false).put("encrypt_prompt", it) }
            val t1 = now()
            val ct = enc.doFinal(PLAIN)
            o.put("encrypt_after_finger_ms", round1(msSince(t1)))
            val iv = enc.iv

            o.put("decrypt_without_finger", tryOp {
                Cipher.getInstance(AES_MODE).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }.doFinal(ct)
            })

            val dec = Cipher.getInstance(AES_MODE).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            prompt(dec, "Шаг 2 из 2: расшифровать её")?.let { return o.put("ok", false).put("decrypt_prompt", it) }
            o.put("decrypt_after_finger_roundtrip", dec.doFinal(ct).contentEquals(PLAIN))

            // one touch must open exactly one operation
            o.put("second_operation_same_touch", tryOp {
                dec.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                dec.doFinal(ct)
            })
            o.put("ok", true)
        } finally {
            ks.deleteEntry(alias)
        }
        return o
    }

    private fun tryOp(block: () -> Unit): JSONObject = try {
        block()
        JSONObject().put("result", "ALLOWED")
    } catch (e: Exception) {
        JSONObject().put("result", "BLOCKED").put("error", err(e))
    }

    /** null when the finger was accepted, otherwise why not. */
    private suspend fun prompt(cipher: Cipher, subtitle: String): String? = suspendCoroutine { cont ->
        val done = AtomicBoolean(false)
        fun finish(v: String?) {
            if (done.compareAndSet(false, true)) cont.resume(v)
        }
        val prompt = BiometricPrompt.Builder(act)
            .setTitle("Тест шифрования")
            .setSubtitle(subtitle)
            .setDescription("Временный ключ, секретов в нём нет")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton("Отмена", act.mainExecutor) { _, _ -> finish("отменено") }
            .build()
        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            CancellationSignal(),
            act.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finish(null)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish("ошибка $errorCode: $errString")
            },
        )
    }
}
