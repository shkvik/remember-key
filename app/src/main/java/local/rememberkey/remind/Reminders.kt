package local.rememberkey.remind

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import local.rememberkey.MainActivity
import local.rememberkey.R
import local.rememberkey.RkApp
import local.rememberkey.core.Ru
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * One reminder a day at 9:00: "time to check key A". The text has only key names, never a key.
 * Inexact alarm — no special permission, Android may deliver it a little later.
 */
object Reminders {
    private const val CHANNEL = "checks"
    private const val NOTIF_ID = 1
    val AT: LocalTime = LocalTime.of(9, 0)
    const val ACTION_OPEN_CHECK = "local.rememberkey.OPEN_CHECK"

    fun schedule(ctx: Context) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(AT)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx))
    }

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun clear(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)

    fun showIfDue(ctx: Context) {
        val data = (ctx.applicationContext as RkApp).vault.data
        val today = LocalDate.now().toEpochDay()
        val due = data.keys.filter { it.learned && it.due <= today }
        val unlearned = data.keys.filter { !it.learned }
        val (title, text) = when {
            due.size == 1 -> "Пора проверить ${due[0].name}" to
                "Проверка ${minOf(due[0].level + 1, 6)} из 6 · по памяти, около минуты"
            due.size > 1 -> "Пора проверить ${due.size} ${Ru.plural(due.size, "ключ", "ключа", "ключей")}" to
                (due.joinToString(", ") { it.name } + " · по памяти, пара минут")
            unlearned.isNotEmpty() -> "Заучивание не закончено" to "${unlearned[0].name} · 3 раза подряд по памяти"
            else -> return
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Проверки ключей", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java).setAction(ACTION_OPEN_CHECK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFF3DFF6E.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) } // no permission → silently nothing
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.showIfDue(context)
        Reminders.schedule(context)
    }
}

/** Alarms are lost on reboot and need a new time after clock or time zone changes. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Reminders.schedule(context)
}
