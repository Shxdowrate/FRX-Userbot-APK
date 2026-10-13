package dev.frx.userbot

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import java.io.File

class FrxApp : Application() {
    companion object {
        const val CHANNEL_SERVICE = "frx_service"
    }

    override fun onCreate() {
        super.onCreate()
        val rt = Runtime.get(this)
        FrxService.log = LogBuffer(File(rt.logsDir, "frx.log"))
        // при запуске приложения покажем конец прошлого журнала
        try {
            File(rt.logsDir, "frx.log").takeIf { it.isFile }?.readLines()?.takeLast(200)?.let { old ->
                old.forEach { FrxService.log.addMemoryOnly(it) }
            }
        } catch (_: Exception) {
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }
}
