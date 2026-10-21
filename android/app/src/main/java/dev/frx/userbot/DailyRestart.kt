package dev.frx.userbot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * «Ежедневный перезапуск»: раз в сутки в выбранное время перезапускает FRX, если он работает.
 * Будильник неточный (setAndAllowWhileIdle — без особого разрешения на точные будильники): Android может
 * сдвинуть его на несколько минут, для перезапуска это не важно. После срабатывания ставится следующий.
 */
object DailyRestart {
    private fun intent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, DailyRestartReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Поставить (или снять) будильник по текущим настройкам. */
    fun schedule(ctx: Context) {
        val rt = Runtime.get(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = intent(ctx)
        am.cancel(pi)
        if (!rt.dailyRestart) return
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next(rt.dailyMinute), pi)
    }

    fun next(minute: Int, now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now + 60_000) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }
}

class DailyRestartReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (FrxService.status == "running") {
            FrxService.log.add("[app] ежедневный перезапуск")
            try {
                FrxService.send(ctx, FrxService.ACTION_RESTART)
            } catch (_: Exception) {
            }
        }
        DailyRestart.schedule(ctx)
    }
}
