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
    //
    // Проверка: последний стабильный релиз (тег 1.0.4 или v1.0.4) новее этой версии? Для сборки из main
    // («1.0.3-dev.N») — ещё и номер сборки пре-релиза dev. Стабильный релиз считается новее, только если его номер
    // строго больше номера этой сборки без «-dev.N»: dev-сборка 1.0.3 не предлагает «обновиться» до 1.0.3.
    //
    // Полуавтоматическое обновление: при запуске и раз в 6 часов (если включено «Автообновление приложения»)
    // приложение само проверяет релиз, скачивает APK в cacheDir/updates/ и открывает системный установщик —
    // остаётся нажать «Обновить». Ставится поверх только APK, подписанный тем же ключом (секрет FRX_KEYSTORE_B64 в CI).

    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    private var checking = false
    private var result = JSONObject()
    @Volatile private var downloading = false
    @Volatile private var percent = 0
    @Volatile private var downloadError: String? = null
    @Volatile private var readyFile: File? = null
    @Volatile private var readyVersion: String? = null

    @Synchronized
    fun updateState(): JSONObject = JSONObject(result.toString()).put("checking", checking)
        .put("downloading", downloading).put("percent", percent)
        .put("ready", readyFile?.isFile == true).put("readyVersion", readyVersion ?: "")
        .apply { downloadError?.let { put("downloadError", it) } }

    /** Включено ли «Автообновление приложения» (по умолчанию — да). */
    fun autoEnabled(rt: Runtime) = rt.prefs.getBoolean("app_autoupdate", true)

    fun setAuto(rt: Runtime, on: Boolean) = rt.prefs.edit().putBoolean("app_autoupdate", on).apply()

    /**
     * Зовётся при запуске и при каждом возврате в приложение: если включено автообновление и с прошлой проверки
     * прошло больше 6 часов — проверить, а найдя новую версию, сразу скачать (onReady — когда APK готов).
     */
    fun autoCheck(rt: Runtime, onReady: () -> Unit) {
        if (!autoEnabled(rt) || readyFile != null || downloading) return   // уже скачано — дальше кнопка «Установить»
        val now = System.currentTimeMillis()
        if (now - rt.prefs.getLong("app_update_checked", 0L) < CHECK_EVERY_MS) return
        rt.prefs.edit().putLong("app_update_checked", now).apply()
        checkUpdate { available ->
            if (!available) return@checkUpdate
            download(rt) {
                // установщик сам открываем один раз на версию, дальше — баннер «Установить» в настройках
                val v = readyVersion ?: ""
                if (rt.prefs.getString("app_update_prompted", "") != v) {
                    rt.prefs.edit().putString("app_update_prompted", v).apply()
                    onReady()
                }
            }
        }
    }

    fun checkUpdate(then: ((Boolean) -> Unit)? = null) {
        synchronized(this) {
            if (checking) return
            checking = true
        }
        Thread {
            val r = JSONObject().put("at", System.currentTimeMillis())
            try {
                val cur = BuildConfig.VERSION_NAME
                val base = cur.substringBefore("-")
                val latest = get("https://api.github.com/repos/${Runtime.APK_REPO}/releases/latest")
                if (latest != null) {
                    val tag = latest.optString("tag_name").removePrefix("v")
                    r.put("latest", tag).put("url", latest.optString("html_url"))
                    if (Modules.newer(tag, base)) {
                        r.put("available", true)
                        apkAsset(latest)?.let { (url, size) -> r.put("apk", url).put("apkSize", size) }
                    }
                }
                if (!r.optBoolean("available") && "-dev." in cur) {
                    val dev = get("https://api.github.com/repos/${Runtime.APK_REPO}/releases/tags/dev")
                    val build = dev?.optString("body")?.let { Regex("""сборка №(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                    if (dev != null && build != null && build > BuildConfig.VERSION_CODE) {
                        r.put("available", true).put("latest", "сборка №$build").put("url", dev.optString("html_url"))
                        apkAsset(dev)?.let { (url, size) -> r.put("apk", url).put("apkSize", size) }
                    }
                }
                if (!r.has("latest")) r.put("latest", cur)
            } catch (e: Exception) {
                r.put("error", "Не удалось проверить: ${e.message ?: e.javaClass.simpleName}")
            }
            synchronized(this) { result = r; checking = false }
            then?.invoke(r.optBoolean("available") && r.has("apk"))
        }.also { it.isDaemon = true }.start()
    }

    /** APK для телефона из релиза: (ссылка, размер). Сборка для эмулятора (*-x86_64.apk) не подходит. */
    private fun apkAsset(release: JSONObject): Pair<String, Long>? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (name.endsWith(".apk") && !name.contains("x86_64")) return a.optString("browser_download_url") to a.optLong("size")
        }
        return null
    }

    /** Скачать APK найденного обновления в cacheDir/updates/ (уже скачанный — с тем же размером — не качается заново). */
    fun download(rt: Runtime, onReady: () -> Unit): Boolean {
        val r = synchronized(this) { JSONObject(result.toString()) }
        val url = r.optString("apk").ifBlank { return false }
        val size = r.optLong("apkSize", 0L)
        val version = r.optString("latest")
        synchronized(this) {
            if (downloading) return true
            downloading = true
        }
        Thread {
            try {
                downloadError = null
                percent = 0
                val dir = File(rt.ctx.cacheDir, "updates").apply { mkdirs() }
                val safe = version.replace(Regex("""[^\w.-]"""), "_")
                val dst = File(dir, "FRX-$safe.apk")
                if (!(dst.isFile && size > 0 && dst.length() == size)) {
                    dir.listFiles()?.forEach { if (it != dst) it.delete() }
                    val tmp = File(dir, dst.name + ".part")
                    var u = URL(url)
                    var conn: HttpURLConnection? = null
                    var hops = 0
                    while (conn == null) {               // GitHub отдаёт файл перенаправлением на свой CDN
                        val h = u.openConnection() as HttpURLConnection
                        h.instanceFollowRedirects = false
                        h.connectTimeout = 15_000; h.readTimeout = 30_000
                        h.setRequestProperty("User-Agent", "FRX-Android")
                        val code = h.responseCode
                        if (code in 300..399 && hops++ < 5) {
                            u = URL(u, h.getHeaderField("Location")); h.disconnect(); continue
                        }
                        if (code != 200) { h.disconnect(); throw IllegalStateException("GitHub ответил $code") }
                        conn = h
                    }
                    val c: HttpURLConnection = conn!!
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: size
                    c.inputStream.use { inp ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) percent = (done * 100 / total).toInt().coerceIn(0, 100)
                            }
                        }
                    }
                    c.disconnect()
                    if (size > 0 && tmp.length() != size) throw IllegalStateException("файл скачался не целиком")
                    if (!tmp.renameTo(dst)) throw IllegalStateException("не удалось сохранить APK")
                }
                percent = 100
                readyFile = dst
                readyVersion = version
                FrxService.log.add("[app] обновление приложения $version скачано — откройте установщик")
                onReady()
            } catch (e: Exception) {
                downloadError = "Не удалось скачать: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                downloading = false
            }
        }.also { it.isDaemon = true }.start()
        return true
    }

    /** Скачанный APK (или null). */
    fun readyApk(): File? = readyFile?.takeIf { it.isFile }

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
