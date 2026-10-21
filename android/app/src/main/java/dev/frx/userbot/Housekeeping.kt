package dev.frx.userbot

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Очистка кэша и проверка новой версии приложения. */
object Housekeeping {
    // ------------------------------------------------------------------ кэш

    /** Что можно удалить без вреда: кэш приложения (скачанные архивы, временные файлы) и кэш pip. */
    private fun cacheDirs(rt: Runtime) = listOf(rt.ctx.cacheDir, File(rt.home, ".cache"))

    private var sizeAt = 0L
    private var size = 0L

    @Synchronized
    fun cacheBytes(rt: Runtime): Long {
        if (System.currentTimeMillis() - sizeAt > 15_000) {
            size = cacheDirs(rt).sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            sizeAt = System.currentTimeMillis()
        }
        return size
    }

    /** Удалить кэш; возвращает, сколько байт освободилось. Пока идёт задача (скачивание, установка) — не трогаем. */
    @Synchronized
    fun clearCache(rt: Runtime): Long {
        if (TaskRunner.busy()) return -1
        sizeAt = 0
        val before = cacheBytes(rt)
        // TMPDIR работающего FRX не трогаем — там могут быть файлы, с которыми он сейчас работает
        val live = FrxService.status !in setOf("stopped", "error")
        for (d in cacheDirs(rt)) d.listFiles()?.forEach { if (!(live && it == rt.tmpDir)) it.deleteRecursively() }
        sizeAt = 0
        return before - cacheBytes(rt)
    }

    // ------------------------------------------------------------------ обновление APK

    private var checking = false
    private var result = JSONObject()

    @Synchronized
    fun updateState(): JSONObject = JSONObject(result.toString()).put("checking", checking)

    /**
     * Свежий релиз на GitHub: стабильный (vX.Y.Z) новее этой версии — предложим его. Если стоит сборка
     * из main («1.0.0-dev.N»), сравниваем ещё и номер сборки пре-релиза dev.
     */
    fun checkUpdate() {
        synchronized(this) {
            if (checking) return
            checking = true
        }
        Thread {
            val r = JSONObject().put("at", System.currentTimeMillis())
            try {
                val cur = BuildConfig.VERSION_NAME
                val latest = get("https://api.github.com/repos/${Runtime.APK_REPO}/releases/latest")
                if (latest != null) {
                    val tag = latest.optString("tag_name").removePrefix("v")
                    r.put("latest", tag).put("url", latest.optString("html_url"))
                    if (Modules.newer(tag, cur.substringBefore("-"))) r.put("available", true)
                }
                if (!r.optBoolean("available") && "-dev." in cur) {
                    val dev = get("https://api.github.com/repos/${Runtime.APK_REPO}/releases/tags/dev")
                    val build = dev?.optString("body")?.let { Regex("""сборка №(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                    if (dev != null && build != null && build > BuildConfig.VERSION_CODE) {
                        r.put("available", true).put("latest", "сборка №$build").put("url", dev.optString("html_url"))
                    }
                }
                if (!r.has("latest")) r.put("latest", cur)
            } catch (e: Exception) {
                r.put("error", "Не удалось проверить: ${e.message ?: e.javaClass.simpleName}")
            }
            synchronized(this) { result = r; checking = false }
        }.also { it.isDaemon = true }.start()
    }

    /** JSON с GitHub API; 404 (нет такого релиза) — null. */
    private fun get(url: String): JSONObject? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "FRX-Android")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode != 200) throw IllegalStateException("GitHub ответил ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }
}
