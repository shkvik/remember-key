package local.rememberkey.sec

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import kotlin.coroutines.resume

sealed interface BioResult {
    data object Ok : BioResult
    data object Cancelled : BioResult
    data class Failed(val code: Int, val message: String) : BioResult
}

/** The system fingerprint window. With a cipher, the chip unlocks exactly one operation of that cipher. */
class BioPrompter(private val act: Activity) {
    suspend fun auth(title: String, subtitle: String, cipher: Cipher?): BioResult = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        val done = AtomicBoolean(false)
        fun finish(r: BioResult) {
            if (done.compareAndSet(false, true)) cont.resume(r)
        }

        val prompt = BiometricPrompt.Builder(act)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton("Отмена", act.mainExecutor) { _, _ -> finish(BioResult.Cancelled) }
            .build()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finish(BioResult.Ok)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish(
                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED || errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) BioResult.Cancelled
                else BioResult.Failed(errorCode, errString.toString()),
            )
        }
        try {
            if (cipher != null) prompt.authenticate(BiometricPrompt.CryptoObject(cipher), signal, act.mainExecutor, callback)
            else prompt.authenticate(signal, act.mainExecutor, callback)
        } catch (e: Exception) {
            finish(BioResult.Failed(-1, e.message ?: e.javaClass.simpleName))
        }
    }
}
