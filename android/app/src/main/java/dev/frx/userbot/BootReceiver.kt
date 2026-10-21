package dev.frx.userbot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Автозапуск после включения телефона и после обновления приложения (если FRX работал). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val rt = Runtime.get(ctx)
        try {
            DailyRestart.schedule(ctx)              // будильники после перезагрузки телефона сбрасываются
        } catch (_: Exception) {
        }
        val go = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> rt.autostart && rt.wantRunning
            Intent.ACTION_MY_PACKAGE_REPLACED -> rt.wantRunning
            else -> false
        }
        if (go && rt.frxInstalled && rt.loggedIn) {
            try {
                FrxService.send(ctx, FrxService.ACTION_START)
            } catch (_: Exception) {
            }
        }
    }
}
