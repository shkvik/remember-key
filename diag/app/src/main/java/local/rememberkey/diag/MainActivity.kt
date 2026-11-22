package local.rememberkey.diag

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class MainActivity : Activity() {
    private val report = JSONObject()
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var json: TextView
    private lateinit var bioBtn: Button
    private lateinit var shareBtn: Button
    private lateinit var copyBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, px(6), 0, px(6))
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(col) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left + px(16), b.top + px(12), b.right + px(16), b.bottom + px(12))
            insets
        }
        setContentView(scroll)

        col.addView(text(22f, true).apply { text = "RememberKey · диагностика" })
        col.addView(text(14f).apply {
            text = "Проверяет, какие механизмы защиты есть в телефоне. Создаёт только временные тестовые ключи и сразу их удаляет. " +
                "Паролей не спрашивает, в интернет не ходит."
        })
        status = text(15f, true).apply { text = "Проверяю…" }
        col.addView(status)
        bioBtn = Button(this).apply { text = "Тест с отпечатком (2–4 касания)"; isEnabled = false; setOnClickListener { runBio() } }
        shareBtn = Button(this).apply { text = "Сохранить JSON и отправить"; isEnabled = false; setOnClickListener { saveAndShare() } }
        copyBtn = Button(this).apply { text = "Скопировать JSON"; isEnabled = false; setOnClickListener { copy() } }
        col.addView(bioBtn)
        col.addView(shareBtn)
        col.addView(copyBtn)
        summary = text(15f)
        col.addView(summary)
        json = text(11f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        col.addView(json)

        Thread {
            val r = Checks(applicationContext).all { msg -> runOnUiThread { status.text = msg } }
            runOnUiThread {
                for (k in r.keys()) report.put(k, r.get(k))
                status.text = "Готово. Теперь — тест с отпечатком, потом «Сохранить JSON и отправить»."
                bioBtn.isEnabled = true
                shareBtn.isEnabled = true
                copyBtn.isEnabled = true
                render()
            }
        }.start()
    }

    private fun render() {
        summary.text = Summary.build(report)
        json.text = report.toString(2)
    }

    private fun runBio() {
        bioBtn.isEnabled = false
        val bio = BioTest(this)
        launch {
            val o = JSONObject()
            status.text = "Тест в TEE: приложите палец, когда появится окно"
            o.put("tee", bio.run(false))
            if (strongBoxWorks()) {
                status.text = "Тест в StrongBox: ещё два касания"
                o.put("strongbox", bio.run(true))
            } else {
                o.put("strongbox", "skipped: ключи в StrongBox не создаются")
            }
            report.put("biometric_test", o)
            status.text = "Тест с отпечатком завершён. Теперь «Сохранить JSON и отправить»."
            bioBtn.isEnabled = true
            render()
        }
    }

    private fun strongBoxWorks() =
        report.optJSONObject("keystore_strongbox")?.optJSONObject("aes_256_gcm")?.optBoolean("ok") == true

    private fun launch(block: suspend () -> Unit) {
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result ->
            result.exceptionOrNull()?.let {
                report.put("biometric_test_crash", err(it))
                status.text = "Ошибка теста: ${err(it)}"
                bioBtn.isEnabled = true
                render()
            }
        })
    }

    private fun saveAndShare() {
        try {
            val name = "rememberkey-diag-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("файл не создан")
            contentResolver.openOutputStream(uri)!!.use { it.write(report.toString(2).toByteArray()) }
            status.text = "Сохранено в «Загрузки»: $name"
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/json")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Отправить JSON"))
        } catch (e: Exception) {
            status.text = "Не удалось сохранить: ${err(e)}"
        }
    }

    private fun copy() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("RememberKey diag", report.toString(2)))
        status.text = "JSON скопирован"
    }
}

/** A short human summary above the raw JSON. */
object Summary {
    fun build(r: JSONObject): String = buildString {
        fun line(k: String, v: Any?) = append("• ").append(k).append(": ").append(v ?: "?").append('\n')

        val d = r.optJSONObject("device")
        line("Телефон", "${d?.optString("manufacturer")} ${d?.optString("model")}, Android ${d?.optString("android")}, патч ${d?.optString("security_patch")}")

        val f = r.optJSONObject("features")?.optJSONObject("key")
        fun feat(name: String) = f?.opt(name)?.takeIf { it != JSONObject.NULL }
        line("StrongBox заявлен", feat("android.hardware.strongbox_keystore")?.let { "да, версия $it" } ?: "нет")
        line("Аппаратное хранилище", feat("android.hardware.hardware_keystore")?.let { "да, версия $it" } ?: "нет")

        for ((k, name) in listOf("keystore_tee" to "TEE", "keystore_strongbox" to "StrongBox")) {
            val ks = r.optJSONObject(k) ?: continue
            val parts = listOf("aes_256_gcm" to "AES", "hmac_sha256" to "HMAC", "ec_p256_attestation" to "аттестация").map { (t, label) ->
                val o = ks.optJSONObject(t)
                when {
                    o == null -> "$label ?"
                    o.optBoolean("ok") -> "$label ✓ ${o.optJSONObject("key_info")?.optString("security_level") ?: ""}"
                    else -> "$label ✗"
                }
            }
            line("Ключи в $name", parts.joinToString(", "))
        }

        val att = r.optJSONObject("keystore_tee")?.optJSONObject("ec_p256_attestation")?.optJSONObject("description")
        val hwRot = att?.optJSONObject("hardware_enforced")?.optJSONObject("root_of_trust")
        val rot = hwRot ?: att?.optJSONObject("software_enforced")?.optJSONObject("root_of_trust")
        if (rot != null) {
            val src = if (hwRot != null) "аттестация чипа" else "ПРОГРАММНАЯ аттестация — доверять нельзя"
            line("Загрузчик ($src)", if (rot.optBoolean("device_locked")) "заблокирован" else "РАЗБЛОКИРОВАН")
            line("Verified Boot", rot.optString("verified_boot_state"))
        }

        val lb = r.optJSONObject("lock_biometric")
        line("Экран блокировки", if (lb?.optBoolean("device_secure") == true) "есть" else "НЕТ")
        line("Биометрия Class 3", "${lb?.optString("biometric_strong")} (${lb?.optString("strong_label")})")

        val env = r.optJSONObject("environment")
        line("Отладка по USB", when (env?.optInt("adb_enabled", -1)) { 1 -> "включена"; 0 -> "выключена"; else -> "?" })
        line("Службы спец. возможностей", env?.optJSONArray("accessibility_services")?.length())
        line("Шифрование памяти", env?.optString("storage_encryption"))

        r.optJSONArray("argon2id")?.let { a ->
            line("Argon2id", (0 until a.length()).joinToString(", ") { i ->
                val o = a.getJSONObject(i)
                "${o.optInt("memory_mib")} МиБ×${o.optInt("iterations")} — ${o.optDouble("ms").toInt()} мс"
            })
        }

        val bio = r.optJSONObject("biometric_test")
        if (bio == null) {
            line("Тест с отпечатком", "ещё не запускался")
        } else {
            for (k in listOf("tee", "strongbox")) {
                val o = bio.optJSONObject(k)
                line("Тест с отпечатком, $k", when {
                    o == null -> bio.optString(k)
                    o.optBoolean("ok") -> "✓ без пальца: ${o.optJSONObject("decrypt_without_finger")?.optString("result")}, с пальцем: расшифровано"
                    else -> "✗ ${o.optString("generate_error", o.optString("encrypt_prompt", o.optString("decrypt_prompt")))}"
                })
            }
        }
    }
}
