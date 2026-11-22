package local.rememberkey

import android.app.Application
import android.os.SystemClock
import android.provider.Settings
import local.rememberkey.core.FileStore
import local.rememberkey.core.RkClock
import local.rememberkey.core.SystemRkClock
import local.rememberkey.core.Vault
import local.rememberkey.core.VaultData
import local.rememberkey.core.VaultStore
import local.rememberkey.remind.Reminders
import local.rememberkey.sec.KeystoreMac
import java.io.File

class RkApp : Application() {
    /** Set when the vault file exists but cannot be read: the app then shows only an error and never writes. */
    var loadError: String? = null
        private set

    /** noBackupFilesDir: never copied by Android backup, and the copies only work with this phone's chip anyway. */
    val vault: Vault by lazy {
        try {
            Vault(FileStore(File(noBackupFilesDir, "vault.json")), KeystoreMac(), AndroidClock(Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)))
        } catch (e: Exception) {
            loadError = "${e.javaClass.simpleName}: ${e.message}".take(200)
            Vault(NoWrites, KeystoreMac())
        }
    }

    /** Wall clock plus the monotonic clock of this boot, so a PIN pause cannot be skipped by changing the time. */
    private class AndroidClock(private val bootCount: Int) : RkClock {
        override fun now() = System.currentTimeMillis()
        override fun today() = SystemRkClock.today()
        override fun elapsed() = SystemClock.elapsedRealtime()
        override fun boot() = bootCount
    }

    /** Stands in for a vault file that could not be read, so nothing can overwrite it. */
    private object NoWrites : VaultStore {
        override fun load() = VaultData()
        override fun save(d: VaultData) = throw IllegalStateException("файл хранилища не прочитан — запись запрещена")
    }

    override fun onCreate() {
        super.onCreate()
        Reminders.schedule(this)
    }
}
