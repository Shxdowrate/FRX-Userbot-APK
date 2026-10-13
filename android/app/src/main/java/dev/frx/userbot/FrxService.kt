package dev.frx.userbot

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.TimeUnit

/**
 * Служба переднего плана: держит процесс frx_systemkeeper.py (а он — main.py), как systemd на сервере.
 *
 * Код выхода keeper'а: 0 — FRX остановлен командой (.exit) → служба тоже останавливается;
 * 78 — ошибка настройки (например, сессия Telegram недействительна) → стоп, на экране подсказка;
 * любой другой (упал, убит системой) → перезапуск через 5 с, 10 с, 20 с … до 5 минут.
 */
class FrxService : Service() {

    companion object {
        const val ACTION_START = "dev.frx.userbot.START"
        const val ACTION_STOP = "dev.frx.userbot.STOP"
        const val ACTION_RESTART = "dev.frx.userbot.RESTART"
        private const val NOTIF_ID = 1

        @Volatile var status = "stopped"      // stopped | starting | running | restarting | stopping | error
        @Volatile var detail = ""
        @Volatile var startedAt = 0L
        @Volatile var lastExit: Int? = null
        lateinit var log: LogBuffer

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, FrxService::class.java).setAction(action)
            if (action == ACTION_STOP) ctx.startService(i) else ctx.startForegroundService(i)
        }
    }

    private lateinit var rt: Runtime
    private var worker: Thread? = null
    @Volatile private var proc: Process? = null
    @Volatile private var stopping = false
    @Volatile private var restartNow = false
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        rt = Runtime.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: if (rt.wantRunning) ACTION_START else ACTION_STOP
        when (action) {
            ACTION_STOP -> {
                rt.wantRunning = false
                if (worker?.isAlive == true) {
                    goForeground("Останавливаю…")
                    stopping = true
                    status = "stopping"
                    Thread { stopKeeper(); }.start()
                } else {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                goForeground("Перезапуск…")
                rt.wantRunning = true
                if (worker?.isAlive == true) {
                    restartNow = true
                    status = "restarting"
                    Thread { proc?.let { terminate(it) } }.start()
                } else startWorker()
            }
            else -> {
                goForeground("Запуск…")
                rt.wantRunning = true
                if (worker?.isAlive != true) startWorker()
            }
        }
        return START_STICKY
    }

    private fun startWorker() {
        stopping = false
        worker = Thread({ loop() }, "frx-keeper").also { it.start() }
    }

    private fun loop() {
        var fails = 0
        acquireWake()
        try {
            while (!stopping) {
                if (!rt.frxInstalled || !rt.loggedIn) {
                    status = "error"; detail = "Нужна настройка: войдите в Telegram в приложении"
                    break
                }
                status = "starting"; detail = ""
                rt.ensurePython { log.add("[app] $it") }
                rt.ensureHelpers()
                TaskRunner.cancel()
                val killed = rt.killStale()
                if (killed > 0) log.add("[app] остановлены старые процессы FRX: $killed")
                log.add("[app] ── запуск FRX ──")
                val started = System.currentTimeMillis()
                startedAt = started
                updateNotification("Запускается…")
                val p = rt.process("-u", "frx_systemkeeper.py").start()
                proc = p
                p.outputStream.close()
                p.inputStream.bufferedReader().forEachLine { line ->
                    log.add(line)
                    when {
                        "main.py запущен полностью" in line -> {
                            status = "running"; detail = ""; updateNotification("Работает")
                        }
                        "Перезагрузка" in line || "Применяю обновление" in line -> {
                            status = "restarting"; updateNotification("Перезагрузка…")
                        }
                        "Связь с Telegram вернулась" in line -> updateNotification("Запускается…")
                        "гибернац" in line.lowercase() || "жду сеть" in line.lowercase() -> {
                            status = "restarting"; detail = "Нет связи с Telegram — жду сеть"
                            updateNotification("Нет сети — жду")
                        }
                    }
                }
                val code = p.waitFor()
                proc = null
                lastExit = code
                log.add("[app] keeper завершился, код $code")
                log.flush()
                if (stopping) break
                if (restartNow) {
                    restartNow = false; fails = 0; continue
                }
                when (code) {
                    0 -> {
                        status = "stopped"; detail = "FRX остановлен командой"; rt.wantRunning = false; break
                    }
                    78 -> {
                        status = "error"; detail = "Ошибка настройки — подробности в журнале"; break
                    }
                    else -> {
                        if (System.currentTimeMillis() - started > 10 * 60_000) fails = 0
                        fails++
                        val delay = minOf(300L, 5L shl minOf(fails - 1, 6))
                        status = "restarting"; detail = "FRX упал (код $code) — перезапуск через $delay с"
                        updateNotification("Перезапуск через $delay с")
                        val until = System.currentTimeMillis() + delay * 1000
                        while (!stopping && !restartNow && System.currentTimeMillis() < until) Thread.sleep(500)
                        restartNow = false
                    }
                }
            }
        } catch (e: Throwable) {
            status = "error"; detail = "Ошибка приложения: ${e.message ?: e.javaClass.simpleName}"
            log.add("[app] ${e.stackTraceToString()}")
        } finally {
            proc = null
            if (stopping) {
                status = "stopped"; detail = ""
            }
            log.flush()
            releaseWake()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopKeeper() {
        proc?.let { terminate(it) }
        worker?.join(30_000)
    }

    private fun terminate(p: Process) {
        p.destroy()                                    // SIGTERM — keeper аккуратно останавливает main.py
        if (!p.waitFor(25, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            rt.killStale()
        }
    }

    // ------------------------------------------------------------------ уведомление и пробуждение

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun updateNotification(text: String) {
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        fun act(action: String, title: String, code: Int) = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notify), title,
            PendingIntent.getService(this, code, Intent(this, FrxService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE)
        ).build()
        return Notification.Builder(this, FrxApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("FRX")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(act(ACTION_RESTART, "Перезапустить", 1))
            .addAction(act(ACTION_STOP, "Остановить", 2))
            .build()
    }

    private fun acquireWake() {
        if (!rt.wakelock) return
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "frx:keeper").apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWake() {
        try { wake?.release() } catch (_: Exception) {}
        wake = null
    }

    override fun onDestroy() {
        stopping = true
        proc?.let { p -> Thread { terminate(p) }.start() }
        releaseWake()
        super.onDestroy()
    }
}
