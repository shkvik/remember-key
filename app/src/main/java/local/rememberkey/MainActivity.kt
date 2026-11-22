package local.rememberkey

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import local.rememberkey.remind.Reminders
import local.rememberkey.sec.BioPrompter
import local.rememberkey.ui.AppVm
import local.rememberkey.ui.RkRoot

class MainActivity : ComponentActivity() {
    private val vm: AppVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // no screenshots, no screen recording, no picture in the recent apps list (off only in the emulator debug build)
        if (BuildConfig.SECURE_WINDOW) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            setRecentsScreenshotEnabled(false)
        }
        // other apps' floating windows are hidden while RememberKey is on screen (fake overlays, tapjacking)
        window.setHideOverlayWindows(true)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        vm.prompter = BioPrompter(this)
        setContent { RkRoot(vm) }
        window.decorView.apply {
            // touches through a window drawn on top are ignored
            filterTouchesWhenObscured = true
            // only real accessibility tools (TalkBack etc.) may read the screen
            setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == Reminders.ACTION_OPEN_CHECK) vm.openDue()
    }

    override fun onStart() {
        super.onStart()
        vm.onStart()
    }

    override fun onStop() {
        vm.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        if (vm.prompter != null) vm.prompter = null
        super.onDestroy()
    }
}
