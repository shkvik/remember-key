package local.rememberkey.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun RkRoot(vm: AppVm) {
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(vm.askNotifications) {
        if (vm.askNotifications) {
            vm.askNotifications = false
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    BackHandler(enabled = vm.canGoBack() || vm.busy != null) { if (vm.busy == null) vm.back() }
    var journalFilter by rememberSaveable { mutableStateOf("all") }

    ProvideBlink {
        Box(Modifier.fillMaxSize().background(C.bg).scanlines()) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                when (val s = vm.screen) {
                    Scr.Home -> HomeScreen(vm)
                    Scr.Journal -> JournalScreen(vm, journalFilter) { journalFilter = it }
                    Scr.Security -> SecurityScreen(vm)
                    Scr.Onboard -> OnboardScreen(vm)
                    Scr.Broken -> BrokenScreen(vm.loadError ?: "")
                    is Scr.Diag -> DiagScreen(vm, s.fromOnboard)
                    is Scr.PinSet -> PinSetScreen(vm, s)
                    is Scr.Key -> KeyScreen(vm, s.id)
                    is Scr.Check -> CheckScreen(vm, s)
                    is Scr.Result -> ResultScreen(vm, s)
                    is Scr.Pin -> PinScreen(vm, s)
                    is Scr.Peek -> PeekScreen(vm)
                    is Scr.Lost -> LostScreen(vm, s.id)
                    Scr.Add1 -> Add1Screen(vm)
                    Scr.Add2 -> Add2Screen(vm)
                    Scr.Add3 -> Add3Screen(vm)
                    is Scr.Added -> AddedScreen(vm, s.id)
                    is Scr.Learn -> LearnScreen(vm, s.id)
                    is Scr.Learned -> LearnedScreen(vm, s.id)
                }
                vm.toast?.let { t ->
                    LaunchedEffect(t) {
                        delay(1900)
                        vm.clearToast(t)
                    }
                    Tx(
                        "> ${t.text}", size = 13.5.sp, color = C.acc, maxLines = 2,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 100.dp, start = 24.dp, end = 24.dp)
                            .background(Color.Black).border(1.dp, C.acc).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                vm.confirm?.let { c -> ConfirmSheet(c) { vm.confirm = null } }
            }
        }
    }
}

@Composable
private fun ConfirmSheet(c: Confirm, close: () -> Unit) {
    BackHandler { close() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)).tap(onClick = close), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().background(C.sheet).drawBehind { drawLine(C.acc, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .tap { }.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Tx(c.title, size = 19.sp, weight = FontWeight.Bold)
            Tx(c.text, size = 13.5.sp, color = C.text2)
            TBtn(c.button, Kind.RED) {
                close()
                c.onYes()
            }
            TBtn("Отмена", Kind.SEC, onClick = close)
        }
    }
}
