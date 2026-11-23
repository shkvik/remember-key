package local.rememberkey.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** The vault file: plain JSON in the app's private no-backup folder. Secrets in it are only salts, hashes and ciphertext. */
object Codec {
    const val VERSION = 1

    fun encode(d: VaultData): String = JSONObject()
        .put("version", VERSION)
        .put("pin", d.pin?.let(::pin) ?: JSONObject.NULL)
        .put("pinFails", d.pinFails)
        .put("lastFailAt", d.lastFailAt)
        .put("failElapsed", d.failElapsed)
        .put("failBoot", d.failBoot)
        .put("keys", JSONArray(d.keys.map(::key)))
        .put("log", JSONArray(d.log.map(::entry)))
        .put("nextLogId", d.nextLogId)
        .put("diag", d.diag?.let(::diag) ?: JSONObject.NULL)
        .put("healthAt", d.healthAt)
        .toString()

    fun decode(s: String): VaultData {
        val o = JSONObject(s)
        val v = o.getInt("version")
        require(v <= VERSION) { "vault file version $v is newer than this app ($VERSION)" }
        return VaultData(
            pin = o.optJSONObject("pin")?.let(::pin),
            pinFails = o.optInt("pinFails"),
            lastFailAt = o.optLong("lastFailAt"),
            failElapsed = o.optLong("failElapsed"),
            failBoot = o.optInt("failBoot", -1),
            keys = o.getJSONArray("keys").objects().map(::key),
            log = o.getJSONArray("log").objects().map(::entry),
            nextLogId = o.optLong("nextLogId", 1),
            diag = o.optJSONObject("diag")?.let(::diag),
            healthAt = o.optLong("healthAt"),
        )
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    private fun JSONObject.bytes(name: String): ByteArray = Base64.getDecoder().decode(getString(name))

    private fun argon(p: ArgonParams) = JSONObject().put("m", p.memKiB).put("t", p.iterations).put("p", p.parallelism)
    private fun argon(o: JSONObject) = ArgonParams(o.getInt("m"), o.getInt("t"), o.getInt("p"))

    private fun pin(p: PinRec) = JSONObject()
        .put("salt", b64(p.salt)).put("check", b64(p.check)).put("argon", argon(p.argon)).put("length", p.length)

    private fun pin(o: JSONObject) = PinRec(o.bytes("salt"), o.bytes("check"), argon(o.getJSONObject("argon")), o.getInt("length"))

    private fun key(k: KeyRec) = JSONObject()
        .put("id", k.id).put("name", k.name).put("fmt", k.fmt.code).put("size", k.size).put("parts", k.parts)
        .put("created", k.created).put("learned", k.learned).put("level", k.level).put("due", k.due)
        .put("checks", k.checks).put("first", k.first).put("peeks", k.peeks)
        .put("hist", JSONArray(k.hist.map { JSONObject().put("day", it.day).put("kind", it.kind.code).put("text", it.text).put("ref", it.ref) }))
        .put("verifier", JSONObject().put("salt", b64(k.verifier.salt)).put("mac", b64(k.verifier.mac)).put("argon", argon(k.verifier.argon)))
        .put("copy", k.copy?.let { JSONObject().put("alias", it.alias).put("ivBio", b64(it.ivBio)).put("iv", b64(it.iv)).put("box", b64(it.box)) } ?: JSONObject.NULL)
        .put("copyLost", k.copyLost)
        .put("blocks", JSONArray(k.blocks.map { h -> h.joinToString("") { if (it) "1" else "0" } }))
        .put("lessonBlock", k.lessonBlock)

    private fun key(o: JSONObject): KeyRec {
        val ver = o.getJSONObject("verifier")
        return KeyRec(
            id = o.getString("id"),
            name = o.getString("name"),
            fmt = Fmt.of(o.getString("fmt")),
            size = o.getInt("size"),
            parts = o.getInt("parts"),
            created = o.getLong("created"),
            learned = o.getBoolean("learned"),
            level = o.getInt("level"),
            due = o.getLong("due"),
            checks = o.getInt("checks"),
            first = o.getInt("first"),
            peeks = o.getInt("peeks"),
            hist = o.getJSONArray("hist").objects().map { Hist(it.getLong("day"), Ev.of(it.getString("kind")), it.getString("text"), it.optLong("ref")) },
            verifier = Verifier(ver.bytes("salt"), ver.bytes("mac"), argon(ver.getJSONObject("argon"))),
            copy = o.optJSONObject("copy")?.let { Copy(it.getString("alias"), it.bytes("ivBio"), it.bytes("iv"), it.bytes("box")) },
            copyLost = o.optBoolean("copyLost"),
            blocks = o.optJSONArray("blocks")?.let { a -> (0 until a.length()).map { i -> a.getString(i).map { it == '1' } } } ?: emptyList(),
            lessonBlock = o.optInt("lessonBlock"),
        )
    }

    private fun entry(e: LogEntry) = JSONObject()
        .put("id", e.id).put("at", e.at).put("kind", e.kind.code).put("title", e.title).put("sub", e.sub)

    private fun entry(o: JSONObject) = LogEntry(o.getLong("id"), o.getLong("at"), Ev.of(o.getString("kind")), o.getString("title"), o.getString("sub"))

    private fun diag(r: DiagRun) = JSONObject()
        .put("at", r.at)
        .put("items", JSONArray(r.items.map { JSONObject().put("id", it.id).put("title", it.title).put("result", it.result).put("status", it.status.code) }))

    private fun diag(o: JSONObject) = DiagRun(
        o.getLong("at"),
        o.getJSONArray("items").objects().map { DiagItem(it.getString("id"), it.getString("title"), it.getString("result"), St.of(it.getString("status"))) },
    )
}
