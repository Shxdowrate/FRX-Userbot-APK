package dev.frx.userbot

import android.content.Context
import android.content.SharedPreferences
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Где что лежит и как запускать Python.
 *
 *   nativeLibraryDir/libfrxpython.so   исполняемый python (build/launcher.c) + libpython и нужные ей .so
 *   files/python/                      стандартная библиотека и пакеты из APK (assets/python.zip) — заменяется при обновлении APK
 *   files/pyuser/                      пакеты, которые FRX ставит сам (pip --user): переживают обновление APK
 *   files/frx/                         код FRX (как папка FRX на ПК; ставится с GitHub или из архива — bootstrap.py);
 *                                      files/frx/user — данные владельца
 *   files/app/                         помощники приложения: bootstrap.py, login.py (assets/frx_android)
 *   files/logs/frx.log                 вывод keeper'а и юзербота
 */
class Runtime(val ctx: Context) {
    val files: File = ctx.filesDir
    val pyHome = File(files, "python")
    val pyUser = File(files, "pyuser")
    val frxDir = File(files, "frx")
    val appDir = File(files, "app")
    val home = File(files, "home")
    val binDir = File(files, "bin")
    val logsDir = File(files, "logs")
    val tmpDir = File(ctx.cacheDir, "tmp")
    val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
    val python = File(nativeDir, "libfrxpython.so")
    val prefs: SharedPreferences = ctx.getSharedPreferences("frx", Context.MODE_PRIVATE)

    private val pyInfo: JSONObject by lazy {
        JSONObject(ctx.assets.open("python.json").bufferedReader().use { it.readText() })
    }
    val pythonVersion: String get() = pyInfo.optString("python", "?")
    private val pyMM: String get() = pythonVersion.split(".").take(2).joinToString(".")
    val sitePackages: File get() = File(pyHome, "lib/python$pyMM/site-packages")

    val frxInstalled: Boolean get() = File(frxDir, "frx_systemkeeper.py").isFile && File(frxDir, "main.py").isFile
    val userDir: File get() = File(frxDir, "user")
    val sessionFile: File get() = File(userDir, "data/frx_session.session")
    val loggedIn: Boolean
        get() {
            if (!sessionFile.isFile || sessionFile.length() == 0L) return false
            return try {
                val cfg = JSONObject(File(userDir, "config.json").readText())
                cfg.optLong("api_id") > 0 && cfg.optString("api_hash").isNotEmpty()
            } catch (_: Exception) {
                false
            }
        }

    val frxVersion: String
        get() = try {
            Regex("""FRX_VERSION\s*=\s*['"]([^'"]+)""").find(File(frxDir, "core/engine.py").readText())?.groupValues?.get(1) ?: "?"
        } catch (_: Exception) {
            "—"
        }

    // ------------------------------------------------------------------ настройки

    var autostart: Boolean
        get() = prefs.getBoolean("autostart", true)
        set(v) = prefs.edit().putBoolean("autostart", v).apply()
    var wakelock: Boolean
        get() = prefs.getBoolean("wakelock", true)
        set(v) = prefs.edit().putBoolean("wakelock", v).apply()
    var wantRunning: Boolean
        get() = prefs.getBoolean("want_running", false)
        set(v) = prefs.edit().putBoolean("want_running", v).apply()

    // ------------------------------------------------------------------ подготовка

    /** Распаковать Python из APK, если его ещё нет или APK обновился. */
    @Synchronized
    fun ensurePython(progress: (String) -> Unit) {
        val build = pyInfo.optString("build")
        val stamp = File(pyHome, ".build")
        if (stamp.isFile && stamp.readText() == build && python.isFile) return
        progress("Распаковываю Python $pythonVersion…")
        val tmp = File(files, "python.new")
        tmp.deleteRecursively()
        unzipAsset("python.zip", tmp, progress)
        File(tmp, ".build").writeText(build)
        pyHome.deleteRecursively()
        if (!tmp.renameTo(pyHome)) throw IllegalStateException("не удалось переименовать $tmp")
        progress("Python $pythonVersion готов")
    }

    /** Помощники приложения (assets/frx_android) — при каждой новой версии APK. */
    @Synchronized
    fun ensureHelpers() {
        val ver = BuildConfig.VERSION_CODE.toString() + ":" + BuildConfig.VERSION_NAME
        val stamp = File(appDir, ".version")
        if (stamp.isFile && stamp.readText() == ver && !BuildConfig.DEBUG) return
        appDir.deleteRecursively()
        appDir.mkdirs()
        for (name in ctx.assets.list("frx_android").orEmpty()) {
            ctx.assets.open("frx_android/$name").use { inp -> File(appDir, name).outputStream().use { inp.copyTo(it) } }
        }
        stamp.writeText(ver)
    }

    private fun unzipAsset(asset: String, dest: File, progress: (String) -> Unit) {
        dest.mkdirs()
        val root = dest.canonicalPath + File.separator
        var n = 0
        ZipInputStream(ctx.assets.open(asset).buffered(1 shl 16)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(root)) continue
                if (e.isDirectory) {
                    out.mkdirs(); continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { zin.copyTo(it, 1 shl 16) }
                if (++n % 500 == 0) progress("  $asset: $n файлов…")
            }
        }
    }

    // ------------------------------------------------------------------ запуск

    fun env(): Map<String, String> {
        listOf(home, binDir, logsDir, tmpDir, pyUser).forEach { it.mkdirs() }
        // папка пакетов пользователя должна существовать ДО запуска Python: site.py добавляет её в sys.path,
        // только если она уже есть, — иначе пакет, поставленный модулем, виден лишь после перезапуска FRX
        File(pyUser, "lib/python$pyMM/site-packages").mkdirs()
        linkTools()
        val cert = File(sitePackages, "certifi/cacert.pem")
        return mapOf(
            "PYTHONHOME" to pyHome.path,
            "PYTHONUSERBASE" to pyUser.path,
            "PYTHONUNBUFFERED" to "1",
            "PYTHONUTF8" to "1",
            "PIP_USER" to "1",                               // пакеты модулей — в files/pyuser
            "PIP_DISABLE_PIP_VERSION_CHECK" to "1",
            // пакеты модулей с C/Rust, собранные под Android в CI (релиз «wheels», .github/workflows/wheels.yml):
            // pip берёт их вместе с PyPI и предпочитает готовую сборку исходникам — собрать их на телефоне нечем
            "PIP_FIND_LINKS" to WHEELS_URL,
            "PIP_PREFER_BINARY" to "1",
            "HOME" to home.path,
            "TMPDIR" to tmpDir.path,
            "LD_LIBRARY_PATH" to nativeDir.path,
            "PATH" to "${binDir.path}:${File(pyUser, "bin").path}:/system/bin:/system/xbin",
            "SSL_CERT_FILE" to cert.path,
            "LANG" to "en_US.UTF-8",
            "FRX_ANDROID" to "1",
            "FRX_ANDROID_APP" to BuildConfig.VERSION_NAME,
        )
    }

    /**
     * files/bin/ffmpeg → nativeLibraryDir/libffmpeg.so (и ffprobe): программы из APK под обычными именами в PATH.
     * Папка нативных библиотек меняется при каждом обновлении APK — ссылки пересоздаются.
     */
    private fun linkTools() {
        for ((name, lib) in listOf("ffmpeg" to "libffmpeg.so", "ffprobe" to "libffprobe.so")) {
            val target = File(nativeDir, lib)
            val link = File(binDir, name)
            try {
                val cur = try { Os.readlink(link.path) } catch (_: Exception) { null }
                if (!target.isFile) {
                    if (cur != null) link.delete()
                    continue
                }
                if (cur != target.path) {
                    link.delete()
                    Os.symlink(target.path, link.path)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun process(vararg args: String, cwd: File = frxDir): ProcessBuilder {
        val pb = ProcessBuilder(listOf(python.path) + args).directory(cwd).redirectErrorStream(true)
        pb.environment().putAll(env())
        return pb
    }

    /** Наши python-процессы, оставшиеся от прошлого запуска приложения (если его убила система). */
    fun stalePythonPids(): List<Int> {
        val me = android.os.Process.myPid()
        val out = mutableListOf<Int>()
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            if (pid == me) return@forEach
            try {
                val cmd = File(d, "cmdline").readBytes()
                val argv0 = String(cmd.takeWhile { it != 0.toByte() }.toByteArray())
                if (argv0 == python.path) out += pid
            } catch (_: Exception) {
            }
        }
        return out
    }

    fun killStale(): Int {
        val pids = stalePythonPids()
        for (pid in pids) try {
            Os.kill(pid, 9)
        } catch (_: Exception) {
        }
        return pids.size
    }

    companion object {
        const val DEFAULT_REPO = "Shxdowrate/FRX-Userbot"
        const val WHEELS_URL = "https://github.com/Shxdowrate/FRX-Userbot-APK/releases/expanded_assets/wheels"
        @Volatile private var inst: Runtime? = null
        fun get(ctx: Context): Runtime = inst ?: synchronized(this) {
            inst ?: Runtime(ctx.applicationContext).also { inst = it }
        }
    }
}
