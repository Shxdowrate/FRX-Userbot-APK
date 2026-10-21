package dev.frx.userbot

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Вкладка «Модули»: каталог из репозитория FRX-Modules и установленные модули (files/frx/user/modules).
 *
 * Каталог — один архив репозитория (codeload.github.com, без лимитов GitHub API), он же источник кода при
 * установке: ставится ровно та версия, что показана. Из каждого .py читаются NAME, MODULE_NAME, VERSION,
 * DESCRIPTION, AUTHOR и REQUIREMENTS. Модули, которым нужно то, чего на Android нет (голосовые чаты,
 * управление Windows), в каталоге скрыты; модуль может и сам сказать ANDROID = False.
 *
 * Установка кладёт файл в user/modules/<MODULE_NAME>.py (как .loadmod), удаление — переносит его в
 * user/modules/unloaded/ (как .unloadmod). Работающий FRX после этого перезапускается — он подхватывает
 * модули из папки при старте.
 */
object Modules {
    private val UNSUPPORTED = mapOf(
        "voinje" to "голосовые чаты на Android не работают",
        "remote" to "управление компьютером, на телефоне не нужно",
    )
    private val UNSUPPORTED_REQ = listOf("tgcalls" to "голосовые чаты на Android не работают",
        "pycaw" to "только для Windows", "pywin32" to "только для Windows")
    private const val TTL = 10 * 60_000L

    private var items = JSONArray()
    private var hidden = JSONArray()
    private var error = ""
    private var fetchedAt = 0L
    private var loading = false
    private var busy = ""                   // модуль, который сейчас ставится или удаляется

    private lateinit var rt: Runtime
    private val zipFile: File get() = File(rt.ctx.cacheDir, "frx_modules.zip")
    private val dir: File get() = File(rt.userDir, "modules")

    @Synchronized
    fun state(r: Runtime): String {
        rt = r
        val installed = installed()
        val out = JSONArray()
        for (i in 0 until items.length()) {
            val m = JSONObject(items.getJSONObject(i).toString())
            installed[m.getString("module")]?.let { (_, ver) ->
                m.put("installed", true).put("installedVersion", ver)
                    .put("update", newer(m.optString("version"), ver))
            }
            out.put(m)
        }
        // установленные вручную (не из каталога) — тоже показываем, их можно удалить
        val known = (0 until items.length()).map { items.getJSONObject(it).getString("module") }.toSet()
        for ((module, pair) in installed) if (module !in known) {
            out.put(JSONObject().put("module", module).put("name", module).put("version", pair.second)
                .put("installed", true).put("installedVersion", pair.second).put("local", true)
                .put("desc", "Установлен вручную").put("supported", true))
        }
        return JSONObject().put("items", out).put("hidden", hidden).put("error", error).put("loading", loading)
            .put("fetchedAt", fetchedAt).put("busy", busy).put("frxReady", rt.frxInstalled).toString()
    }

    /** Обновить каталог в фоне (force — даже если скачан недавно). */
    fun refresh(r: Runtime, force: Boolean) {
        synchronized(this) {
            rt = r
            if (loading) return
            if (!force && fetchedAt > 0 && System.currentTimeMillis() - fetchedAt < TTL && error.isEmpty()) return
            loading = true
        }
        Thread {
            try {
                download()
                val (list, skip) = parse()
                synchronized(this) { items = list; hidden = skip; error = ""; fetchedAt = System.currentTimeMillis() }
            } catch (e: Exception) {
                // нет сети — покажем прошлый каталог, если он остался в кэше
                synchronized(this) {
                    error = "Не удалось загрузить каталог: ${e.message ?: e.javaClass.simpleName}"
                    if (items.length() == 0 && zipFile.isFile) try {
                        val (list, skip) = parse(); items = list; hidden = skip
                    } catch (_: Exception) {
                    }
                }
            } finally {
                synchronized(this) { loading = false }
            }
        }.also { it.isDaemon = true }.start()
    }

    /** Поставить или обновить модуль из каталога. false — уже идёт другая операция или модуля нет. */
    fun install(r: Runtime, module: String, after: () -> Unit): Boolean {
        val entry = synchronized(this) {
            rt = r
            if (busy.isNotEmpty()) return false
            val e = (0 until items.length()).map { items.getJSONObject(it) }.firstOrNull { it.getString("module") == module }
                ?: return false
            busy = module
            e
        }
        Thread {
            try {
                val src = ZipFile(zipFile).use { z ->
                    val ze = z.getEntry(entry.getString("path")) ?: throw IllegalStateException("файла нет в архиве")
                    z.getInputStream(ze).use { it.readBytes() }
                }
                dir.mkdirs()
                // уже установлен под другим именем файла — заменяем тот же файл, чтобы не было двух копий
                val target = installedFiles()[module] ?: File(dir, "$module.py")
                val tmp = File(target.path + ".tmp")
                tmp.writeBytes(src)
                if (!tmp.renameTo(target)) throw IllegalStateException("не удалось записать файл")
                File(dir, "unloaded/$module.py").delete()
                FrxService.log.add("[app] модуль ${entry.optString("name", module)} ${entry.optString("version")} установлен")
                after()
            } catch (e: Exception) {
                FrxService.log.add("[app] модуль $module не установлен: ${e.message}")
                synchronized(this) { error = "Не удалось установить $module: ${e.message}" }
            } finally {
                synchronized(this) { busy = "" }
            }
        }.also { it.isDaemon = true }.start()
        return true
    }

    /** Удалить: перенести файл в user/modules/unloaded/ (как .unloadmod — настройки модуля остаются). */
    fun remove(r: Runtime, module: String, after: () -> Unit): Boolean {
        synchronized(this) {
            rt = r
            if (busy.isNotEmpty()) return false
        }
        val f = installedFiles()[module] ?: return false
        val un = File(dir, "unloaded").also { it.mkdirs() }
        val dest = File(un, "$module.py")
        dest.delete()
        if (!f.renameTo(dest)) return false
        FrxService.log.add("[app] модуль $module удалён (перенесён в user/modules/unloaded)")
        after()
        return true
    }

    // ------------------------------------------------------------------ установленные

    private fun installedFiles(): Map<String, File> {
        val out = mutableMapOf<String, File>()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".py") && !f.name.startsWith("_") }?.forEach { f ->
            val text = try { f.readText() } catch (_: Exception) { return@forEach }
            out[constant(text, "MODULE_NAME") ?: f.name.removeSuffix(".py")] = f
        }
        return out
    }

    /** module → (файл, версия). */
    private fun installed(): Map<String, Pair<File, String>> = installedFiles().mapValues { (_, f) ->
        f to (try { constant(f.readText(), "VERSION") } catch (_: Exception) { null } ?: "")
    }

    // ------------------------------------------------------------------ каталог

    private fun download() {
        val url = "https://codeload.github.com/${Runtime.MODULES_REPO}/zip/refs/heads/main"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "FRX-Android")
        try {
            if (c.responseCode != 200) throw IllegalStateException("GitHub ответил ${c.responseCode}")
            val tmp = File(zipFile.path + ".part")
            c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 16) } }
            if (!tmp.renameTo(zipFile)) throw IllegalStateException("не удалось сохранить архив")
        } finally {
            c.disconnect()
        }
    }

    private fun parse(): Pair<JSONArray, JSONArray> {
        val list = mutableListOf<JSONObject>()
        val skip = JSONArray()
        ZipFile(zipFile).use { z ->
            for (e in z.entries()) {
                val parts = e.name.split('/')
                // FRX-Modules-main/<файл>.py — только верхний уровень
                if (e.isDirectory || parts.size != 2 || !parts[1].endsWith(".py") || parts[1].startsWith("_")) continue
                val text = z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
                val module = constant(text, "MODULE_NAME") ?: parts[1].removeSuffix(".py")
                val name = constant(text, "NAME") ?: module
                val req = Regex("""^REQUIREMENTS\s*=\s*(.*)$""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1) ?: ""
                val reason = UNSUPPORTED[module]
                    ?: UNSUPPORTED_REQ.firstOrNull { (k, _) -> k in req.lowercase() }?.second
                    ?: if (Regex("""^ANDROID\s*=\s*False""", RegexOption.MULTILINE).containsMatchIn(text)) "автор модуля отметил, что на Android он не работает" else null
                if (reason != null) {
                    skip.put(JSONObject().put("name", name).put("reason", reason)); continue
                }
                list += JSONObject().put("module", module).put("name", name).put("path", e.name)
                    .put("version", constant(text, "VERSION") ?: "").put("desc", constant(text, "DESCRIPTION") ?: "")
                    .put("author", constant(text, "AUTHOR") ?: "").put("supported", true)
                    .put("needs", req.isNotBlank())
            }
        }
        list.sortBy { it.getString("name").lowercase() }
        return JSONArray(list) to skip
    }

    /** NAME = "..." на верхнем уровне модуля (строка в одинарных или двойных кавычках). */
    fun constant(text: String, key: String): String? {
        val m = Regex("""^$key\s*=\s*(["'])((?:\\.|(?!\1).)*)\1""", RegexOption.MULTILINE).find(text) ?: return null
        return m.groupValues[2].replace("\\\"", "\"").replace("\\'", "'").replace("\\n", " ")
    }

    /** a новее b (версии вида 1.2.3; нечисловые части сравниваются как 0). */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
