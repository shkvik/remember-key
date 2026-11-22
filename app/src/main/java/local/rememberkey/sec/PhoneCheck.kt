package local.rememberkey.sec

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.view.accessibility.AccessibilityManager
import local.rememberkey.core.DiagItem
import local.rememberkey.core.Ru
import local.rememberkey.core.St
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.LocalDate
import javax.crypto.Cipher

/**
 * The phone check on the «Защита» tab: the same questions the diagnostic APK answered, with temporary keys only.
 * Each step returns one line of the result list.
 */
class PhoneCheck(private val ctx: Context) {
    companion object {
        val STEPS = listOf(
            "keystore" to "Хранилище ключей",
            "biometric" to "Отпечаток пальца",
            "biotest" to "Тест шифрования",
            "lock" to "Блокировка экрана",
            "boot" to "Загрузчик и Verified Boot",
            "a11y" to "Специальные возможности",
            "adb" to "Отладка по USB",
            "patch" to "Обновление безопасности",
        )

        /** SHA-256 of Google's attestation root public keys (developer.android.com, security-key-attestation). */
        private val GOOGLE_ROOTS = setOf(
            "feb2ea7551ee316ed4bb443c8293b884dbfdea40b603ee3e4f4a897e4580fbae", // RSA root, 2016+
            "3ee44512a1af2beb39c889490c60ea3f82e43f5d5a5532f5ab9419f676cd07ec", // Key Attestation CA1, 2025+
        )

        /** The security patch is called old after this many days. */
        const val PATCH_MAX_AGE_DAYS = 120
    }

    private fun item(id: String, result: String, st: St) = DiagItem(id, STEPS.first { it.first == id }.second, result, st)

    fun keystore(): DiagItem {
        val alias = "rk.diag.level"
        return try {
            BioKeys.create(alias)
            val level = BioKeys.securityLevel(alias)
            val sb = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            val sbText = if (sb) "StrongBox тоже есть" else "StrongBox в телефоне нет"
            when (level) {
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> item("keystore", "аппаратное, TEE · $sbText", St.OK)
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> item("keystore", "аппаратное, StrongBox", St.OK)
                KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> item("keystore", "аппаратное · $sbText", St.OK)
                else -> item("keystore", "программное — ключи не защищены чипом", St.BAD)
            }
        } catch (e: Exception) {
            item("keystore", "не удалось создать ключ: ${e.message}", St.BAD)
        } finally {
            BioKeys.delete(alias)
        }
    }

    fun biometricReady(): Boolean =
        ctx.getSystemService(BiometricManager::class.java).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun biometric(): DiagItem = when (ctx.getSystemService(BiometricManager::class.java).canAuthenticate(BIOMETRIC_STRONG)) {
        BiometricManager.BIOMETRIC_SUCCESS -> item("biometric", "Class 3 — подходит для шифрования", St.OK)
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> item("biometric", "отпечаток не добавлен — добавьте в настройках Android", St.BAD)
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> item("biometric", "нет сканера класса 3", St.BAD)
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> item("biometric", "система требует обновления безопасности", St.BAD)
        else -> item("biometric", "сканер сейчас недоступен", St.BAD)
    }

    /**
     * A temporary fingerprint key: without a finger encryption must be refused, with one it must work,
     * and one touch must open exactly one operation. Returns null when the user cancelled the fingerprint window.
     */
    suspend fun bioTest(auth: suspend (Cipher) -> BioResult): DiagItem? {
        if (!biometricReady()) return item("biotest", "нельзя проверить: нет отпечатка класса 3", St.BAD)
        val alias = "rk.diag.bio"
        val plain = "RememberKey: проверка, секретов нет".toByteArray()
        try {
            BioKeys.create(alias)
            val blocked = runCatching { BioKeys.encryptCipher(alias).doFinal(plain) }.isFailure
            val cipher = BioKeys.encryptCipher(alias)
            when (val r = auth(cipher)) {
                BioResult.Cancelled -> return null
                is BioResult.Failed -> return item("biotest", "отпечаток не принят: ${r.message}", St.BAD)
                BioResult.Ok -> Unit
            }
            val works = runCatching { cipher.doFinal(plain) }
            val ct = works.getOrNull()
            val second = ct != null && runCatching {
                cipher.init(Cipher.ENCRYPT_MODE, KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null))
                cipher.doFinal(plain)
            }.isSuccess
            return when {
                !blocked -> item("biotest", "ключ работает без отпечатка — защите нельзя доверять", St.BAD)
                ct == null -> item("biotest", "с отпечатком не сработало: ${works.exceptionOrNull()?.message}", St.BAD)
                second -> item("biotest", "одно касание открыло две операции", St.WARN)
                else -> item("biotest", "AES-256-GCM: без отпечатка — отказ, с отпечатком — работает", St.OK)
            }
        } catch (e: Exception) {
            return item("biotest", "ошибка: ${e.message}", St.BAD)
        } finally {
            BioKeys.delete(alias)
        }
    }

    fun lock(): DiagItem =
        if (ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure) item("lock", "включена", St.OK)
        else item("lock", "нет — без блокировки экрана копии не работают", St.BAD)

    /** Bootloader state and Verified Boot, signed by the chip in a key attestation certificate. */
    fun boot(): DiagItem {
        val alias = "rk.diag.attest"
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val challenge = ByteArray(16).also { SecureRandom().nextBytes(it) }
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setAttestationChallenge(challenge)
                        .build(),
                )
            }.generateKeyPair()
            val chain = ks.getCertificateChain(alias).map { it as X509Certificate }
            val signed = (0 until chain.size - 1).all { i -> runCatching { chain[i].verify(chain[i + 1].publicKey) }.isSuccess }
            val rootHash = MessageDigest.getInstance("SHA-256").digest(chain.last().publicKey.encoded).joinToString("") { "%02x".format(it) }
            val google = signed && rootHash in GOOGLE_ROOTS
            val att = Attestation.parse(chain[0]) ?: return item("boot", "в сертификате нет аттестации", St.WARN)
            if (!att.challenge.contentEquals(challenge)) return item("boot", "аттестация не совпала с запросом", St.BAD)
            val rot = att.rootOfTrust ?: return item("boot", "аттестация без данных о загрузке", St.WARN)
            val state = when (rot.state) {
                0L -> "система не изменена"
                1L -> "система подписана не производителем"
                2L -> "проверка системы отключена"
                else -> "проверка системы не прошла"
            }
            val lockText = if (rot.locked) "загрузчик заблокирован" else "загрузчик РАЗБЛОКИРОВАН"
            return when {
                !att.hardware -> item("boot", "$lockText, $state · аттестация программная, доверять нельзя", St.WARN)
                !google -> item("boot", "$lockText, $state · цепочка не ведёт к корню Google", St.WARN)
                rot.locked && rot.state == 0L -> item("boot", "$lockText, $state · подписано чипом", St.OK)
                else -> item("boot", "$lockText, $state", St.BAD)
            }
        } catch (e: Exception) {
            return item("boot", "не удалось проверить: ${e.message}", St.WARN)
        } finally {
            runCatching { ks.deleteEntry(alias) }
        }
    }

    fun a11y(): DiagItem {
        val services = ctx.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val others = services.filter { !it.isAccessibilityTool }
        return when {
            services.isEmpty() -> item("a11y", "включённых служб нет", St.OK)
            others.isEmpty() -> item("a11y", "включены только инструменты доступности: ${services.size}", St.OK)
            else -> item("a11y", "включено служб: ${others.size} — экран RememberKey им скрыт, но лучше выключить лишние", St.WARN)
        }
    }

    fun adb(): DiagItem =
        if (Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1)
            item("adb", "включена — выключите в настройках для разработчиков", St.WARN)
        else item("adb", "выключена", St.OK)

    fun patch(today: Long): DiagItem {
        val p = runCatching { LocalDate.parse(Build.VERSION.SECURITY_PATCH).toEpochDay() }.getOrNull()
            ?: return item("patch", "неизвестно: ${Build.VERSION.SECURITY_PATCH}", St.WARN)
        val date = Ru.date(p, -1) // always with the year
        return if (today - p <= PATCH_MAX_AGE_DAYS) item("patch", "$date — свежее", St.OK)
        else item("patch", "$date — устарело, обновите систему", St.WARN)
    }
}
