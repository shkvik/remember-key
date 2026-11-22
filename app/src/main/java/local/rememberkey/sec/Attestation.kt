package local.rememberkey.sec

import java.security.cert.X509Certificate

/** One DER element: just enough ASN.1 to read the key attestation extension. */
private class Der(val tag: Int, val value: ByteArray) {
    fun children(): List<Der> {
        val out = mutableListOf<Der>()
        var i = 0
        while (i < value.size) {
            val (d, next) = parseAt(value, i)
            out += d
            i = next
        }
        return out
    }

    fun long(): Long {
        var r = 0L
        for (b in value) r = (r shl 8) or (b.toLong() and 0xff)
        if (value.isNotEmpty() && value[0] < 0) r -= 1L shl (8 * value.size)
        return r
    }

    companion object {
        fun parseAt(b: ByteArray, start: Int): Pair<Der, Int> {
            var i = start
            val first = b[i++].toInt() and 0xff
            var tag = first and 0x1f
            if (tag == 0x1f) { // high tag numbers, e.g. [704] rootOfTrust
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
 * The parts of Android's KeyDescription that the phone check needs.
 * Schema: source.android.com/docs/security/features/keystore/attestation
 */
class Attestation(val hardware: Boolean, val challenge: ByteArray, val rootOfTrust: RootOfTrust?) {
    /** [state]: 0 Verified, 1 SelfSigned, 2 Unverified, 3 Failed. */
    class RootOfTrust(val locked: Boolean, val state: Long)

    companion object {
        private const val OID = "1.3.6.1.4.1.11129.2.1.17"

        fun parse(cert: X509Certificate): Attestation? {
            val ext = cert.getExtensionValue(OID) ?: return null
            val octet = Der.parseAt(ext, 0).first.value // getExtensionValue wraps the value in an OCTET STRING
            val seq = Der.parseAt(octet, 0).first.children()
            val level = seq[1].long() // 0 software, 1 TEE, 2 StrongBox
            // the emulator puts rootOfTrust into the software list
            val rot = rootOfTrust(seq[7]) ?: rootOfTrust(seq[6])
            return Attestation(level >= 1, seq[4].value, rot)
        }

        private fun rootOfTrust(list: Der): RootOfTrust? {
            val field = list.children().firstOrNull { it.tag == 704 } ?: return null
            val f = field.children().firstOrNull()?.children() ?: return null
            return RootOfTrust(locked = f[1].value.firstOrNull()?.toInt() != 0, state = f[2].long())
        }
    }
}
