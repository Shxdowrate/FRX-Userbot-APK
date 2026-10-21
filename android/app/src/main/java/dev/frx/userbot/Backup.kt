package dev.frx.userbot

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Резервная копия папки user/ (настройки, база модулей, вход в Telegram, свои модули) в .zip, который
 * пользователь сохраняет куда хочет, и восстановление из такого архива. Внутри архива — папка user/,
 * как на ПК: копию с телефона можно положить в FRX на компьютере и наоборот.
 */
object Backup {
    private val SKIP_DIRS = setOf("__pycache__", "temp", "tmp", "cache", ".cache")
    private const val MAX_FILE = 64L * 1024 * 1024        // большие файлы (видео, модели) в копию не берём

    fun save(ctx: Context, rt: Runtime, uri: Uri): Boolean = TaskRunner.runKotlin("backup") { say ->
        val root = rt.userDir
        if (!root.isDirectory) throw IllegalStateException("папки user/ ещё нет — сначала войдите в Telegram")
        var n = 0
        var skipped = 0
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("файл не открылся")
        ZipOutputStream(out.buffered(1 shl 16)).use { zip ->
            root.walkTopDown().onEnter { it == root || it.name !in SKIP_DIRS }.forEach { f ->
                if (!f.isFile || f.name.endsWith(".tmp")) return@forEach
                if (f.length() > MAX_FILE) { skipped++; return@forEach }
                zip.putNextEntry(ZipEntry("user/" + f.relativeTo(root).invariantSeparatorsPath).apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(zip, 1 shl 16) }
                zip.closeEntry()
                if (++n % 200 == 0) say("  $n файлов…")
            }
        }
        rt.prefs.edit().putLong("last_backup", System.currentTimeMillis()).apply()
        say("Готово: $n файлов" + if (skipped > 0) ", пропущено больших файлов: $skipped" else "")
    }

    /**
     * Восстановить: FRX останавливается, архив распаковывается рядом (user.restore), проверяется, что
     * в нём есть config.json, и только тогда подменяет user/. Прежняя папка остаётся как user.old —
     * до следующего восстановления.
     */
    fun restore(ctx: Context, rt: Runtime, uri: Uri): Boolean = TaskRunner.runKotlin("restore") { say ->
        val wasRunning = FrxService.status in setOf("running", "starting", "restarting")
        val tmp = File(rt.frxDir, "user.restore")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val base = tmp.canonicalPath + File.separator
        say("Распаковываю копию…")
        var n = 0
        val inp = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("файл не открылся")
        ZipInputStream(inp.buffered(1 shl 16)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                // принимаем и «user/…», и архив без верхней папки
                val name = e.name.removePrefix("user/")
                if (name.isEmpty()) continue
                val out = File(tmp, name)
                if (!out.canonicalPath.startsWith(base)) continue
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                out.outputStream().use { zin.copyTo(it, 1 shl 16) }
                if (++n % 200 == 0) say("  $n файлов…")
            }
        }
        if (!File(tmp, "config.json").isFile) {
            tmp.deleteRecursively()
            throw IllegalStateException("это не копия FRX: внутри нет config.json")
        }
        if (wasRunning) {
            say("Останавливаю FRX…")
            FrxService.send(ctx, FrxService.ACTION_STOP)
            val until = System.currentTimeMillis() + 40_000
            while (FrxService.status !in setOf("stopped", "error") && System.currentTimeMillis() < until) Thread.sleep(300)
        }
        val old = File(rt.frxDir, "user.old")
        old.deleteRecursively()
        if (rt.userDir.exists() && !rt.userDir.renameTo(old)) throw IllegalStateException("не удалось убрать текущую папку user/")
        if (!tmp.renameTo(rt.userDir)) {
            old.renameTo(rt.userDir)
            throw IllegalStateException("не удалось поставить копию")
        }
        say("Восстановлено файлов: $n. Прежняя папка сохранена как user.old")
        if (wasRunning && rt.loggedIn) FrxService.send(ctx, FrxService.ACTION_START)
    }
}
