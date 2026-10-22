package dev.frx.userbot

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedDispatcher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Весь интерфейс — веб-страница из assets/ui (тёмная, как сайт .web), логика — через мост `FRX` (Bridge).
 * Экран входа в Telegram отдаёт login.py с 127.0.0.1, сайт FRX (.web) — тоже с 127.0.0.1.
 * Другие адреса открываются во внешнем браузере: мост доступен только своим страницам.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var rt: Runtime
    private val bridge by lazy { Bridge() }

    companion object {
        const val HOME = "file:///android_asset/ui/index.html"
        const val WEB_PORT = 8878
        const val REQ_ARCHIVE = 41
        const val REQ_BACKUP = 42
        const val REQ_RESTORE = 43
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rt = Runtime.get(this)
        web = WebView(this)
        setContentView(buildLayout())
        web.setBackgroundColor(getColor(R.color.bg))
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                onHome = url?.startsWith(HOME) == true
                updateBar(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                onHome = url?.startsWith(HOME) == true
                updateBar(url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url
                return when {
                    url.scheme == "frx" -> { handleAppUrl(url); true }
                    url.toString().startsWith("file:///android_asset/") -> false
                    url.scheme == "http" && url.host in setOf("127.0.0.1", "localhost") -> false
                    else -> { openExternal(url.toString()); true }
                }
            }
        }
        // «назад»: с сайта и экрана входа — в приложение; на главном экране — свернуть
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        goHome()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** am start -n dev.frx.userbot/.MainActivity --ez selftest true — проверка Python (CI, эмулятор); вывод в logcat «FRX». */
    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra("selftest", false) != true) return
        TaskRunner.runPython("selftest", { say -> rt.ensurePython(say); rt.ensureHelpers() }) {
            rt.files.mkdirs()
            rt.process(File(rt.appDir, "selftest.py").path, cwd = rt.files)
        }
    }

    @Volatile private var onHome = true
    private lateinit var bar: LinearLayout
    private lateinit var barTitle: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * Корень с отступами под системные панели (вырез, строка состояния, навигация, клавиатура): сам WebView
     * отступы padding не учитывает, поэтому отступы — у контейнера. Над страницей сайта и экрана входа —
     * своя панель: «← FRX» (назад в приложение), заголовок, «обновить».
     */
    private fun buildLayout(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsets.CONSUMED
        }
        fun barButton(text: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.icon_fg))
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FF5A14), null, null)
            setOnClickListener { onClick() }
        }
        barTitle = TextView(this).apply {
            setTextColor(0xF2FFFFFF.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            isAllCaps = true
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
        }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0A0A0C.toInt())
            visibility = View.GONE
            addView(barButton("←  FRX") { goHome() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
            addView(barTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(barButton("⟳") { web.reload() }, LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.MATCH_PARENT))
        }
        // тонкая акцентная линия под панелью — как у шапки сайта
        val line = View(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFFFF2A1C.toInt(), 0xFFFF5A14.toInt(), 0xFFFF8A00.toInt()))
            visibility = View.GONE
        }
        bar.tag = line
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        root.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)))
        root.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun updateBar(url: String?) {
        val home = url == null || url.startsWith(HOME)
        val vis = if (home) View.GONE else View.VISIBLE
        bar.visibility = vis
        (bar.tag as View).visibility = vis
        barTitle.text = when {
            home -> ""
            url!!.contains(":$WEB_PORT") -> "Сайт FRX"
            else -> "Вход в Telegram"
        }
    }

    /** «Назад»: на главном экране сперва спрашиваем страницу (журнал → главная, вкладка → главная), потом сворачиваем. */
    private fun back() {
        if (!onHome) return goHome()
        web.evaluateJavascript("window.frxBack ? frxBack() : false") { handled ->
            if (handled != "true") moveTaskToBack(true)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        back()
    }

    private fun goHome() {
        web.addJavascriptInterface(bridge, "FRX")       // мост — только своей странице
        web.loadUrl(HOME)
        web.clearHistory()
    }

    /** Уйти с главного экрана на страницу 127.0.0.1 (сайт, вход): мост приложения ей не достаётся. */
    private fun leaveHome(url: String) {
        web.removeJavascriptInterface("FRX")
        web.loadUrl(url)
    }

    /**
     * Сайт FRX без кода: приложение кладёт одноразовый билет (SHA-256, 2 минуты) в user/data/web_app_ticket.json
     * и открывает сайт с #frx_t=<билет> — сайт меняет его на сессию (core/web/auth.py, use_app_ticket).
     * Файл доступен только самому приложению; через публичный туннель такой билет не принимается.
     */
    private fun openPanel() {
        val token = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        try {
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val f = File(rt.userDir, "data/web_app_ticket.json")
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(JSONObject().put("hash", hash).put("until", System.currentTimeMillis() / 1000 + 120).toString())
            tmp.renameTo(f)
            leaveHome("http://127.0.0.1:$WEB_PORT/#frx_t=$token")
        } catch (_: Exception) {
            leaveHome("http://127.0.0.1:$WEB_PORT/")
        }
    }

    /**
     * Поставить код FRX (с GitHub или из архива) поверх — папка user/ не трогается. Работающий FRX
     * останавливается и после удачной установки запускается снова. before — дополнительная подготовка.
     */
    private fun installTask(name: String, before: (((String) -> Unit) -> Unit)?, pb: () -> ProcessBuilder): Boolean =
        TaskRunner.runPython(name, { say ->
            if (FrxService.status in setOf("running", "starting", "restarting")) {
                say("Останавливаю FRX…")
                FrxService.send(this, FrxService.ACTION_STOP)
                val until = System.currentTimeMillis() + 40_000
                while (FrxService.status !in setOf("stopped", "error") && System.currentTimeMillis() < until) Thread.sleep(300)
            }
            rt.ensurePython(say); rt.ensureHelpers()
            before?.invoke(say)
        }, onDone = { ok -> if (ok && rt.loggedIn) FrxService.send(this, FrxService.ACTION_START) }, pb = pb)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_ARCHIVE -> installArchive(uri)
            REQ_BACKUP -> if (!Backup.save(this, rt, uri)) toastJs("Уже идёт другая задача")
            REQ_RESTORE -> if (!Backup.restore(this, rt, uri)) toastJs("Уже идёт другая задача")
        }
    }

    private fun installArchive(uri: Uri) {
        val zip = File(cacheDir, "frx_upload.zip")
        val started = installTask("archive", { say ->
            say("Копирую архив…")
            zip.delete()
            val inp = contentResolver.openInputStream(uri) ?: throw IllegalStateException("файл не открылся")
            inp.use { src -> zip.outputStream().use { src.copyTo(it, 1 shl 16) } }
        }) {
            rt.process(File(rt.appDir, "bootstrap.py").path, "--dest", rt.frxDir.path, "--zip", zip.path, cwd = rt.files)
        }
        if (!started) toastJs("Уже идёт другая задача")
    }

    private fun pickDocument(code: Int, create: String? = null) = runOnUiThread {
        val i = if (create != null)
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, create)
        else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        try {
            startActivityForResult(i, code)
        } catch (_: Exception) {
            toastJs("Нет приложения для выбора файла")
        }
    }

    /** Перезапустить FRX, если он работает (после установки или удаления модуля). */
    private fun restartIfRunning() {
        if (FrxService.status in setOf("running", "starting", "restarting")) FrxService.send(this, FrxService.ACTION_RESTART)
    }

    private fun toastJs(text: String) = runOnUiThread {
        web.evaluateJavascript("window.toast && toast(${JSONObject.quote(text)})", null)
    }

    /** frx://home, frx://login-done — сигналы со страницы входа (login.py). */
    private fun handleAppUrl(url: Uri) {
        when (url.host) {
            "login-done" -> {
                goHome()
                if (rt.loggedIn) FrxService.send(this, FrxService.ACTION_START)
            }
            else -> goHome()
        }
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        web.evaluateJavascript("window.frxResume && frxResume()", null)
        // вернулись из «Установка неизвестных приложений» с разрешением — продолжаем установку обновления
        if (installPending && packageManager.canRequestPackageInstalls()) {
            installPending = false
            installUpdate()
        }
        Housekeeping.autoCheck(rt) { runOnUiThread { installUpdate() } }
    }

    @Volatile private var installPending = false

    /**
     * Системный установщик для скачанного APK обновления (Housekeeping): пользователю остаётся нажать «Обновить».
     * Без разрешения «Установка неизвестных приложений» сначала открываются его настройки, после возврата —
     * установщик (onResume). false — APK ещё не скачан.
     */
    private fun installUpdate(): Boolean {
        val apk = Housekeeping.readyApk() ?: return false
        if (!packageManager.canRequestPackageInstalls()) {
            installPending = true
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                installPending = false
            }
            return true
        }
        return try {
            val uri = UpdateProvider.uri(this, apk)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            FrxService.log.add("[app] установщик не открылся: ${e.message}")
            false
        }
    }

    // ===================================================================== мост для страницы

    inner class Bridge {
        @JavascriptInterface
        fun state(): String {
            val pm = getSystemService(PowerManager::class.java)
            return JSONObject()
                .put("app", BuildConfig.VERSION_NAME)
                .put("python", rt.pythonVersion)
                .put("pythonReady", File(rt.pyHome, ".build").isFile)
                .put("frxInstalled", rt.frxInstalled)
                .put("frxVersion", if (rt.frxInstalled) rt.frxVersion else "")
                .put("loggedIn", rt.frxInstalled && rt.loggedIn)
                .put("status", FrxService.status)
                .put("detail", FrxService.detail)
                .put("startedAt", FrxService.startedAt)
                .put("lastExit", FrxService.lastExit ?: JSONObject.NULL)
                .put("autostart", rt.autostart)
                .put("wakelock", rt.wakelock)
                .put("batteryOk", pm.isIgnoringBatteryOptimizations(packageName))
                .put("notifOk", checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                .put("task", TaskRunner.state())
                .put("logSeq", FrxService.log.seq)
                .put("modules", FrxService.modules)
                .toString()
        }

        @JavascriptInterface
        fun log(n: Int): String = JSONArray(FrxService.log.tail(n.coerceIn(1, 600))).toString()

        /** Распаковать Python. Код FRX в APK не кладётся — его ставит шаг «Скачать FRX» или «Загрузить архив». */
        @JavascriptInterface
        fun prepare(): Boolean = TaskRunner.runKotlin("prepare") { say ->
            rt.ensurePython(say)
            rt.ensureHelpers()
        }

        /** Скачать FRX с GitHub (bootstrap.py). token — для приватного репозитория, можно пустой. */
        @JavascriptInterface
        fun download(repo: String, token: String): Boolean = TaskRunner.runPython("download", { say ->
            rt.ensurePython(say); rt.ensureHelpers()
            // запоминаем — для «Переустановить FRX» (токен хранится в закрытых настройках приложения)
            rt.prefs.edit().putString("frx_repo", repo.trim()).putString("frx_token", token.trim()).apply()
        }) {
            rt.files.mkdirs()
            rt.process(File(rt.appDir, "bootstrap.py").path, "--dest", rt.frxDir.path,
                "--repo", repo.ifBlank { Runtime.DEFAULT_REPO }, cwd = rt.files).also { pb ->
                if (token.isNotBlank()) pb.environment()["FRX_GITHUB_TOKEN"] = token.trim()
            }
        }

        /**
         * Переустановить код FRX с GitHub поверх (папка user/ — данные, сессия, модули — не трогается) и запустить.
         * Нужно, когда .update не ставит ту же версию (например, свежий код ещё с прежним номером версии).
         */
        @JavascriptInterface
        fun reinstall(newToken: String): Boolean {
            val repo = rt.repo
            if (newToken.isNotBlank()) rt.token = newToken
            val token = rt.token
            return installTask("reinstall", null) {
                rt.process(File(rt.appDir, "bootstrap.py").path, "--dest", rt.frxDir.path, "--repo", repo, cwd = rt.files)
                    .also { pb -> if (token.isNotBlank()) pb.environment()["FRX_GITHUB_TOKEN"] = token }
            }
        }

        /** «Загрузить архив»: выбрать .zip с кодом FRX вручную (если GitHub недоступен). Дальше — onActivityResult. */
        @JavascriptInterface
        fun pickArchive(): Boolean {
            if (TaskRunner.busy()) return false
            pickDocument(REQ_ARCHIVE)
            return true
        }

        /** Запустить экран входа (login.py); когда он поднимется, в task.data появится port. */
        @JavascriptInterface
        fun login(): Boolean {
            if (FrxService.status in setOf("running", "starting", "restarting")) FrxService.send(this@MainActivity, FrxService.ACTION_STOP)
            return TaskRunner.runPython("login", { say -> rt.ensurePython(say); rt.ensureHelpers() }) {
                rt.process(File(rt.appDir, "login.py").path, "--frx", rt.frxDir.path, cwd = rt.frxDir)
            }
        }

        @JavascriptInterface
        fun openLogin(port: Int) = runOnUiThread { if (onHome) leaveHome("http://127.0.0.1:$port/") }

        @JavascriptInterface
        fun cancelTask() = Thread { TaskRunner.cancel() }.start()

        @JavascriptInterface
        fun start() = FrxService.send(this@MainActivity, FrxService.ACTION_START)

        @JavascriptInterface
        fun stop() = FrxService.send(this@MainActivity, FrxService.ACTION_STOP)

        @JavascriptInterface
        fun restart() = FrxService.send(this@MainActivity, FrxService.ACTION_RESTART)

        @JavascriptInterface
        fun setOption(key: String, value: Boolean) {
            when (key) {
                "autostart" -> rt.autostart = value
                "wakelock" -> rt.wakelock = value
                "restartOnCrash" -> rt.restartOnCrash = value
                "waitNetwork" -> rt.waitNetwork = value
                "hidePhone" -> rt.hidePhone = value
                "dailyRestart" -> { rt.dailyRestart = value; DailyRestart.schedule(this@MainActivity) }
                "notifButtons" -> { rt.notifButtons = value; FrxService.refreshNotification(this@MainActivity) }
                "notifUptime" -> { rt.notifUptime = value; FrxService.refreshNotification(this@MainActivity) }
                "appAutoUpdate" -> Housekeeping.setAuto(rt, value)
            }
        }

        /** Всё для вкладки «Настройки». */
        @JavascriptInterface
        fun settings(): String {
            val acc = rt.account
            return JSONObject()
                .put("account", JSONObject().put("name", acc.optString("name")).put("username", acc.optString("username"))
                    .put("phone", acc.optString("phone")))
                .put("hidePhone", rt.hidePhone)
                .put("restartOnCrash", rt.restartOnCrash)
                .put("waitNetwork", rt.waitNetwork)
                .put("dailyRestart", rt.dailyRestart)
                .put("dailyMinute", rt.dailyMinute)
                .put("notifButtons", rt.notifButtons)
                .put("notifUptime", rt.notifUptime)
                .put("lastBackup", rt.prefs.getLong("last_backup", 0L))
                .put("cacheBytes", Housekeeping.cacheBytes(rt))
                .put("repo", rt.repo)
                .put("defaultRepo", Runtime.DEFAULT_REPO)
                .put("tokenSet", rt.token.isNotBlank())
                .put("update", Housekeeping.updateState())
                .put("appAutoUpdate", Housekeeping.autoEnabled(rt))
                .toString()
        }

        /** Время ежедневного перезапуска: минуты от полуночи. */
        @JavascriptInterface
        fun setDailyTime(minute: Int) {
            rt.dailyMinute = minute
            DailyRestart.schedule(this@MainActivity)
        }

        /** Репозиторий кода FRX (пусто — стандартный) и токен GitHub (пусто — убрать). */
        @JavascriptInterface
        fun setRepo(repo: String) {
            val r = repo.trim().removePrefix("https://github.com/").trimEnd('/')
            rt.repo = if (r.matches(Regex("""[\w.-]+/[\w.-]+"""))) r else ""
        }

        @JavascriptInterface
        fun setToken(token: String) {
            rt.token = token
        }

        @JavascriptInterface
        fun clearCache(): Long = Housekeeping.clearCache(rt)

        @JavascriptInterface
        fun backup(): Boolean {
            if (TaskRunner.busy()) return false
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US).format(java.util.Date())
            pickDocument(REQ_BACKUP, "FRX-backup-$stamp.zip")
            return true
        }

        @JavascriptInterface
        fun restore(): Boolean {
            if (TaskRunner.busy()) return false
            pickDocument(REQ_RESTORE)
            return true
        }

        @JavascriptInterface
        fun checkUpdate() = Housekeeping.checkUpdate()

        /** Скачать найденное обновление; по готовности сразу открыть установщик. false — нечего качать. */
        @JavascriptInterface
        fun downloadUpdate(): Boolean = Housekeeping.download(rt) { runOnUiThread { installUpdate() } }

        /** Открыть установщик для уже скачанного APK. false — ещё не скачан. */
        @JavascriptInterface
        fun installUpdate(): Boolean {
            var ok = false
            val latch = java.util.concurrent.CountDownLatch(1)
            runOnUiThread { ok = this@MainActivity.installUpdate(); latch.countDown() }
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            return ok
        }

        // ------------------------------------------------------------- модули

        @JavascriptInterface
        fun modules(): String = Modules.state(rt)

        @JavascriptInterface
        fun modulesRefresh(force: Boolean) = Modules.refresh(rt, force)

        @JavascriptInterface
        fun moduleInstall(module: String): Boolean = Modules.install(rt, module) { restartIfRunning() }

        @JavascriptInterface
        fun moduleRemove(module: String): Boolean = Modules.remove(rt, module) { restartIfRunning() }

        @JavascriptInterface
        fun battery() = runOnUiThread {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        @JavascriptInterface
        fun notifications() = runOnUiThread {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        @JavascriptInterface
        fun appSettings() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        @JavascriptInterface
        fun panel() = runOnUiThread { if (onHome) openPanel() }

        @JavascriptInterface
        fun open(url: String) = runOnUiThread { openExternal(url) }

        @JavascriptInterface
        fun shareLog() = runOnUiThread {
            val text = buildString {
                append("FRX ${rt.frxVersion} · APK ${BuildConfig.VERSION_NAME} · Python ${rt.pythonVersion} · Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.MODEL})\n\n")
                FrxService.log.tail(600).forEach { append(it).append('\n') }
            }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text), "Журнал FRX"))
        }

        @JavascriptInterface
        fun copy(text: String) = runOnUiThread {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FRX", text))
        }

        /** Выйти из аккаунта на этом телефоне: удалить сессию Telegram (config.json остаётся). */
        @JavascriptInterface
        fun logout(): Boolean {
            FrxService.send(this@MainActivity, FrxService.ACTION_STOP)
            val until = System.currentTimeMillis() + 35_000
            while (FrxService.status !in setOf("stopped", "error") && System.currentTimeMillis() < until) Thread.sleep(250)
            val data = File(rt.userDir, "data")
            data.listFiles { f -> f.name.startsWith("frx_session.session") }?.forEach { it.delete() }
            return !rt.loggedIn
        }
    }
}
