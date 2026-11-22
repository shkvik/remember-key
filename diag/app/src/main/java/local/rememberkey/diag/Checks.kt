package local.rememberkey.diag

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.accessibility.AccessibilityManager
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

internal const val KS = "AndroidKeyStore"
internal const val AES_MODE = "AES/GCM/NoPadding"
internal val PLAIN = "RememberKey diagnostic: no secrets here".toByteArray()

internal fun keyStore(): KeyStore = KeyStore.getInstance(KS).apply { load(null) }
internal fun now() = SystemClock.elapsedRealtimeNanos()
internal fun msSince(t: Long) = (SystemClock.elapsedRealtimeNanos() - t) / 1_000_000.0
internal fun round1(x: Double) = Math.round(x * 10) / 10.0
internal fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
internal fun tag(strongBox: Boolean) = if (strongBox) "sb" else "tee"

internal fun err(e: Throwable): String {
    val c = e.cause
    val own = "${e.javaClass.simpleName}: ${e.message}"
    return if (c != null && c !== e) "$own <- ${c.javaClass.simpleName}: ${c.message}" else own
}

internal fun secLevel(v: Int) = when (v) {
    KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE"
    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TRUSTED_ENVIRONMENT"
    KeyProperties.SECURITY_LEVEL_STRONGBOX -> "STRONGBOX"
    KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> "UNKNOWN_SECURE"
    else -> "UNKNOWN($v)"
}

/** AES-256-GCM, optionally bound to a Class 3 biometric for every single operation (the RememberKey copy key). */
internal fun genAes(alias: String, strongBox: Boolean, auth: Boolean, unlocked: Boolean): SecretKey {
    val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setKeySize(256)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setIsStrongBoxBacked(strongBox)
        .setUnlockedDeviceRequired(unlocked)
    if (auth) {
        b.setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
    }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS).apply { init(b.build()) }.generateKey()
}

/** Tries the key with "usable only while the phone is unlocked"; if the chip refuses, records why and retries without it. */
internal fun <T> genWithUnlockedFallback(o: JSONObject, alias: String, gen: (Boolean) -> T): T =
    try {
        gen(true).also { o.put("unlocked_device_required", true) }
    } catch (e: Exception) {
        o.put("unlocked_device_required", false)
        o.put("unlocked_device_required_error", err(e))
        keyStore().deleteEntry(alias)
        gen(false)
    }

internal fun keyInfo(key: Key): JSONObject {
    val ki: KeyInfo = when (key) {
        is SecretKey -> SecretKeyFactory.getInstance(key.algorithm, KS).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        is PrivateKey -> KeyFactory.getInstance(key.algorithm, KS).getKeySpec(key, KeyInfo::class.java)
        else -> error("unknown key type ${key.javaClass}")
    }
    return JSONObject()
        .put("security_level", secLevel(ki.securityLevel))
        .put("key_size", ki.keySize)
        .put("user_auth_required", ki.isUserAuthenticationRequired)
        .put("user_auth_type", ki.userAuthenticationType)
        .put("user_auth_timeout_s", ki.userAuthenticationValidityDurationSeconds)
        .put("user_auth_enforced_by_secure_hw", ki.isUserAuthenticationRequirementEnforcedBySecureHardware)
        .put("invalidated_by_biometric_enrollment", ki.isInvalidatedByBiometricEnrollment)
}

/** Everything that needs no finger: runs on a background thread right after the start. */
class Checks(private val ctx: Context) {

    fun all(progress: (String) -> Unit): JSONObject {
        val r = JSONObject()
        r.put("tool", "rememberkey-diag 1.0")
        r.put("time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
        progress("Устройство…"); r.put("device", safe { device() })
        progress("Функции системы…"); r.put("features", safe { features() })
        progress("Блокировка и биометрия…"); r.put("lock_biometric", safe { lockBiometric() })
        progress("Окружение…"); r.put("environment", safe { environment() })
        progress("Ключи в TEE…"); r.put("keystore_tee", safe { keystore(false) })
        progress("Ключи в StrongBox…"); r.put("keystore_strongbox", safe { keystore(true) })
        progress("Скорость Argon2id…"); r.put("argon2id", safe { argon() })
        return r
    }

    private fun safe(block: () -> Any): Any =
        try { block() } catch (e: Throwable) { JSONObject().put("error", err(e)) }

    private fun step(block: () -> JSONObject): JSONObject =
        try { block().put("ok", true) } catch (e: Throwable) { JSONObject().put("ok", false).put("error", err(e)) }

    private fun device() = JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("brand", Build.BRAND)
        .put("model", Build.MODEL)
        .put("device", Build.DEVICE)
        .put("product", Build.PRODUCT)
        .put("hardware", Build.HARDWARE)
        .put("soc", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        .put("android", Build.VERSION.RELEASE)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("security_patch", Build.VERSION.SECURITY_PATCH)
        .put("build_id", Build.ID)
        .put("build_incremental", Build.VERSION.INCREMENTAL)
        .put("build_type", Build.TYPE)
        .put("build_tags", Build.TAGS)

    private fun features(): JSONObject {
        val all = ctx.packageManager.systemAvailableFeatures.filter { it.name != null }
        val version = all.associate { it.name to it.version }
        val key = JSONObject()
        for (name in KEY_FEATURES) key.put(name, version[name] ?: JSONObject.NULL)
        val list = all.map { if (it.version != 0) "${it.name}=${it.version}" else it.name }.sorted()
        return JSONObject().put("key", key).put("all", JSONArray(list))
    }

    private fun lockBiometric(): JSONObject {
        val km = ctx.getSystemService(KeyguardManager::class.java)
        val bm = ctx.getSystemService(BiometricManager::class.java)
        fun code(c: Int) = when (c) {
            0 -> "SUCCESS"
            1 -> "HW_UNAVAILABLE"
            11 -> "NONE_ENROLLED"
            12 -> "NO_HARDWARE"
            15 -> "SECURITY_UPDATE_REQUIRED"
            else -> "code_$c"
        }
        return JSONObject()
            .put("device_secure", km.isDeviceSecure)
            .put("keyguard_secure", km.isKeyguardSecure)
            .put("biometric_strong", code(bm.canAuthenticate(BIOMETRIC_STRONG)))
            .put("biometric_weak", code(bm.canAuthenticate(BIOMETRIC_WEAK)))
            .put("device_credential", code(bm.canAuthenticate(DEVICE_CREDENTIAL)))
            .put("strong_label", bm.getStrings(BIOMETRIC_STRONG).buttonLabel?.toString() ?: JSONObject.NULL)
            .put("weak_label", bm.getStrings(BIOMETRIC_WEAK).buttonLabel?.toString() ?: JSONObject.NULL)
    }

    private fun environment(): JSONObject {
        val cr = ctx.contentResolver
        val am = ctx.getSystemService(AccessibilityManager::class.java)
        val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .map { JSONObject().put("id", it.id).put("is_accessibility_tool", it.isAccessibilityTool) }
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        val props = JSONObject()
        for (p in PROPS) props.put(p, getprop(p))
        return JSONObject()
            .put("adb_enabled", Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, -1))
            .put("adb_wifi_enabled", Settings.Global.getInt(cr, "adb_wifi_enabled", -1))
            .put("developer_options", Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, -1))
            .put("accessibility_services", JSONArray(services))
            .put("storage_encryption", when (dpm.storageEncryptionStatus) {
                DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER -> "ACTIVE_PER_USER"
                DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE -> "ACTIVE"
                DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY -> "ACTIVE_DEFAULT_KEY"
                DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE -> "INACTIVE"
                DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED -> "UNSUPPORTED"
                else -> "code_${dpm.storageEncryptionStatus}"
            })
            .put("su_paths_found", JSONArray(SU_PATHS.filter { File(it).exists() }))
            .put("props", props)
    }

    private fun getprop(name: String): String = try {
        ProcessBuilder("getprop", name).redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        "error: ${e.message}"
    }

    private fun keystore(strongBox: Boolean) = JSONObject()
        .put("aes_256_gcm", step { aes(strongBox) })
        .put("hmac_sha256", step { hmac(strongBox) })
        .put("ec_p256_attestation", step { attest(strongBox) })

    private fun aes(strongBox: Boolean): JSONObject {
        val o = JSONObject()
        val alias = "rkdiag_aes_${tag(strongBox)}"
        val ks = keyStore().also { it.deleteEntry(alias) }
        try {
            val t0 = now()
            val key = genWithUnlockedFallback(o, alias) { u -> genAes(alias, strongBox, auth = false, unlocked = u) }
            o.put("generate_ms", round1(msSince(t0)))
            o.put("key_info", keyInfo(key))
            val enc = Cipher.getInstance(AES_MODE).apply { init(Cipher.ENCRYPT_MODE, key) }
            val ct = enc.doFinal(PLAIN)
            val dec = Cipher.getInstance(AES_MODE).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, enc.iv)) }
            o.put("roundtrip", dec.doFinal(ct).contentEquals(PLAIN))
            val t1 = now()
            repeat(10) { Cipher.getInstance(AES_MODE).apply { init(Cipher.ENCRYPT_MODE, key) }.doFinal(PLAIN) }
            o.put("encrypt_avg_ms", round1(msSince(t1) / 10))
        } finally {
            ks.deleteEntry(alias)
        }
        return o
    }

    private fun hmac(strongBox: Boolean): JSONObject {
        val o = JSONObject()
        val alias = "rkdiag_hmac_${tag(strongBox)}"
        val ks = keyStore().also { it.deleteEntry(alias) }
        try {
            val t0 = now()
            val key = genWithUnlockedFallback(o, alias) { u ->
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KS).apply {
                    init(
                        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                            .setIsStrongBoxBacked(strongBox)
                            .setUnlockedDeviceRequired(u)
                            .build()
                    )
                }.generateKey()
            }
            o.put("generate_ms", round1(msSince(t0)))
            o.put("key_info", keyInfo(key))
            o.put("mac_length", Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(PLAIN).size)
            val t1 = now()
            repeat(20) { Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(PLAIN) }
            o.put("mac_avg_ms", round1(msSince(t1) / 20))
        } finally {
            ks.deleteEntry(alias)
        }
        return o
    }

    private fun attest(strongBox: Boolean): JSONObject {
        val o = JSONObject()
        val alias = "rkdiag_attest_${tag(strongBox)}"
        val ks = keyStore().also { it.deleteEntry(alias) }
        try {
            val challenge = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val t0 = now()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KS).apply {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setAttestationChallenge(challenge)
                        .setIsStrongBoxBacked(strongBox)
                        .build()
                )
            }.generateKeyPair()
            o.put("generate_ms", round1(msSince(t0)))
            o.put("key_info", keyInfo(ks.getKey(alias, null)))
            val chain = ks.getCertificateChain(alias).map { it as X509Certificate }
            val desc = Attestation.parse(chain[0])
            o.put("challenge_hex", hex(challenge))
            o.put("challenge_matches", desc?.optString("challenge_hex") == hex(challenge))
            o.put("description", desc ?: JSONObject.NULL)
            var signaturesOk = true
            for (i in 0 until chain.size - 1) {
                try { chain[i].verify(chain[i + 1].publicKey) } catch (e: Exception) { signaturesOk = false }
            }
            o.put("chain_length", chain.size)
            o.put("chain_signatures_ok", signaturesOk)
            o.put("chain_subjects", JSONArray(chain.map { it.subjectX500Principal.name }))
            o.put("root_pubkey_sha256", hex(MessageDigest.getInstance("SHA-256").digest(chain.last().publicKey.encoded)))
            o.put("chain_b64", JSONArray(chain.map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }))
        } finally {
            ks.deleteEntry(alias)
        }
        return o
    }

    private fun argon(): JSONArray {
        argonOnce(8 * 1024, 1) // warm-up
        val a = JSONArray()
        for ((mib, iters) in listOf(19 to 2, 64 to 3, 128 to 3)) {
            a.put(JSONObject().put("memory_mib", mib).put("iterations", iters).put("parallelism", 1).put("ms", round1(argonOnce(mib * 1024, iters))))
        }
        return a
    }

    private fun argonOnce(memoryKiB: Int, iterations: Int): Double {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKiB)
            .withIterations(iterations)
            .withParallelism(1)
            .withSalt(ByteArray(16) { it.toByte() })
            .build()
        val gen = Argon2BytesGenerator().apply { init(params) }
        val out = ByteArray(32)
        val t = now()
        gen.generateBytes("diagnostic".toCharArray(), out)
        return msSince(t)
    }

    private companion object {
        val KEY_FEATURES = listOf(
            "android.hardware.strongbox_keystore",
            "android.hardware.hardware_keystore",
            "android.hardware.keystore.app_attest_key",
            "android.hardware.keystore.limited_use_key",
            "android.hardware.keystore.single_use_key",
            "android.hardware.device_unique_attestation",
            "android.software.device_id_attestation",
            "android.hardware.fingerprint",
            "android.hardware.biometrics.face",
            "android.hardware.biometrics.iris",
            "android.hardware.identity_credential",
            "android.hardware.se.omapi.ese",
            "android.hardware.security.model.compatible",
            "android.software.secure_lock_screen",
        )
        val PROPS = listOf(
            "ro.boot.verifiedbootstate", "ro.boot.flash.locked", "ro.boot.vbmeta.device_state", "ro.boot.veritymode",
            "ro.build.type", "ro.build.tags", "ro.debuggable", "ro.secure", "ro.adb.secure",
            "ro.crypto.state", "ro.crypto.type", "ro.product.first_api_level", "ro.board.first_api_level",
            "ro.vendor.build.security_patch", "ro.hardware.gatekeeper", "ro.hardware.keystore",
        )
        val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/bin/magisk", "/system/app/Superuser.apk")
    }
}
