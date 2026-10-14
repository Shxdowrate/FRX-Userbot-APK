package dev.frx.userbot

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Разовые задачи настройки: распаковка Python, скачивание FRX (bootstrap.py), вход в Telegram (login.py).
 * Одновременно идёт одна задача; экран опрашивает state(). Строки вида «@key value» — данные для экрана
 * (например «@port 41234» от login.py), остальные — просто вывод.
 */
object TaskRunner {
    private var name = ""
    private var state = "idle"           // idle | running | done | failed
    private var code: Int? = null
    private val log = LogBuffer(null, 300)
    private val data = JSONObject()
    private var proc: Process? = null
    private var thread: Thread? = null

    @Synchronized
    fun busy() = state == "running"

    @Synchronized
    fun state(): JSONObject = JSONObject()
        .put("name", name).put("state", state).put("code", code ?: JSONObject.NULL)
        .put("data", JSONObject(data.toString()))
        .put("lines", JSONArray(log.tail(120)))

    @Synchronized
    private fun begin(n: String): Boolean {
        if (state == "running") return false
        name = n; state = "running"; code = null
        log.clear()
        data.keys().asSequence().toList().forEach { data.remove(it) }
        return true
    }

    @Synchronized
    private fun finish(ok: Boolean, c: Int?) {
        state = if (ok) "done" else "failed"; code = c; proc = null
    }

    fun line(s: String) {
        android.util.Log.i("FRX", "[$name] $s")
        if (s.startsWith("@")) {
            val p = s.substring(1).split(' ', limit = 2)
            synchronized(this) { data.put(p[0], if (p.size > 1) p[1] else "") }
        } else log.add(s)
    }

    /** Задача на Kotlin (распаковка). */
    fun runKotlin(n: String, body: ((String) -> Unit) -> Unit): Boolean {
        if (!begin(n)) return false
        thread = Thread {
            try {
                body { s -> line(s) }; finish(true, 0)
            } catch (e: Throwable) {
                line("Ошибка: ${e.message ?: e.javaClass.simpleName}"); finish(false, null)
            }
        }.also { it.isDaemon = true; it.start() }
        return true
    }

    /** Python-задача: before() — подготовка (распаковка), затем процесс из pb(). */
    fun runPython(n: String, before: ((String) -> Unit) -> Unit, onDone: ((Boolean) -> Unit)? = null,
                  pb: () -> ProcessBuilder): Boolean {
        if (!begin(n)) return false
        thread = Thread {
            try {
                before { s -> line(s) }
                val p = pb().start()
                synchronized(this) { proc = p }
                p.outputStream.close()
                p.inputStream.bufferedReader().forEachLine { line(it) }
                val c = p.waitFor()
                finish(c == 0, c)
                onDone?.invoke(c == 0)
            } catch (e: Throwable) {
                line("Ошибка: ${e.message ?: e.javaClass.simpleName}"); finish(false, null)
            }
        }.also { it.isDaemon = true; it.start() }
        return true
    }

    fun cancel() {
        val p = synchronized(this) { proc } ?: return
        p.destroy()
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
    }
}
