package local.rememberkey.diag

import org.json.JSONArray
import org.json.JSONObject
import java.security.cert.X509Certificate

/** One DER element: just enough of ASN.1 to read the key attestation extension. */
class Der(val tag: Int, val value: ByteArray) {
    fun children(): List<Der> = parseAll(value)

    fun long(): Long {
        var r = 0L
        for (b in value) r = (r shl 8) or (b.toLong() and 0xff)
        if (value.isNotEmpty() && value[0] < 0) r -= 1L shl (8 * value.size)
        return r
    }

    companion object {
        fun parseAll(b: ByteArray): List<Der> {
            val out = mutableListOf<Der>()
            var i = 0
            while (i < b.size) {
                val (d, next) = parseAt(b, i)
                out += d
                i = next
            }
            return out
        }

        fun parseAt(b: ByteArray, start: Int): Pair<Der, Int> {
            var i = start
            val first = b[i++].toInt() and 0xff
            var tag = first and 0x1f
            if (tag == 0x1f) { // high tag number, e.g. [704] rootOfTrust
                tag = 0
                do {
                    val x = b[i++].toInt() and 0xff
                    tag = (tag shl 7) or (x and 0x7f)
                } while (x and 0x80 != 0)
            }
            var len = b[i++].toInt() and 0xff
            if (len and 0x80 != 0) {
                val n = len and 0x7f
                len = 0
                repeat(n) { len = (len shl 8) or (b[i++].toInt() and 0xff) }
            }
            return Der(tag, b.copyOfRange(i, i + len)) to (i + len)
        }
    }
}

/**
 * Reads the Android key attestation extension (KeyDescription) of the leaf certificate.
 * Schema: source.android.com/docs/security/features/keystore/attestation
 */
object Attestation {
    private const val OID = "1.3.6.1.4.1.11129.2.1.17"

    fun parse(cert: X509Certificate): JSONObject? {
        val ext = cert.getExtensionValue(OID) ?: return null
        val octet = Der.parseAt(ext, 0).first.value // getExtensionValue wraps the value in an OCTET STRING
        val seq = Der.parseAt(octet, 0).first.children()
        return JSONObject()
            .put("attestation_version", seq[0].long())
            .put("attestation_security_level", level(seq[1].long()))
            .put("keymint_version", seq[2].long())
            .put("keymint_security_level", level(seq[3].long()))
            .put("challenge_hex", hex(seq[4].value))
            .put("software_enforced", authList(seq[6]))
            .put("hardware_enforced", authList(seq[7]))
    }

    private fun level(v: Long) = when (v) {
        0L -> "Software"
        1L -> "TrustedEnvironment"
        2L -> "StrongBox"
        else -> "unknown($v)"
    }

    private fun authList(list: Der): JSONObject {
        val o = JSONObject()
        val other = JSONArray()
        for (field in list.children()) {
            val v = field.children().firstOrNull() // every field is an EXPLICIT [tag]
            when (field.tag) {
                1 -> o.put("purpose", JSONArray(v?.children()?.map { it.long() } ?: emptyList<Long>()))
                2 -> o.put("algorithm", when (v?.long()) { 1L -> "RSA"; 3L -> "EC"; 32L -> "AES"; 128L -> "HMAC"; else -> "${v?.long()}" })
                3 -> o.put("key_size", v?.long())
                10 -> o.put("ec_curve", v?.long())
                303 -> o.put("rollback_resistance", true)
                503 -> o.put("no_auth_required", true)
                504 -> o.put("user_auth_type", v?.long())
                505 -> o.put("auth_timeout", v?.long())
                509 -> o.put("unlocked_device_required", true)
                701 -> o.put("creation_datetime", v?.long())
                702 -> o.put("origin", v?.long())
                704 -> o.put("root_of_trust", rootOfTrust(v))
                705 -> o.put("os_version", v?.long())
                706 -> o.put("os_patch_level", v?.long())
                709 -> o.put("attestation_application_id", "present")
                718 -> o.put("vendor_patch_level", v?.long())
                719 -> o.put("boot_patch_level", v?.long())
                else -> other.put(field.tag)
            }
        }
        if (other.length() > 0) o.put("other_tags", other)
        return o
    }

    private fun rootOfTrust(d: Der?): JSONObject {
        val f = d?.children() ?: return JSONObject().put("error", "missing")
        val o = JSONObject()
            .put("verified_boot_key_hex", hex(f[0].value))
            .put("device_locked", f[1].value.firstOrNull()?.toInt() != 0)
            .put("verified_boot_state", when (f[2].long()) { 0L -> "Verified"; 1L -> "SelfSigned"; 2L -> "Unverified"; 3L -> "Failed"; else -> "unknown(${f[2].long()})" })
        if (f.size > 3) o.put("verified_boot_hash_hex", hex(f[3].value))
        return o
    }
}
