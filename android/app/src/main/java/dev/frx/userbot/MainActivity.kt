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
import android.view.WindowInsets
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

    companion object {
        const val HOME = "file:///android_asset/ui/index.html"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rt = Runtime.get(this)
        web = WebView(this)
        setContentView(web)
        web.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsets.CONSUMED
        }
        web.setBackgroundColor(getColor(R.color.bg))
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
        }
        web.addJavascriptInterface(Bridge(), "FRX")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
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
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (web.url?.startsWith(HOME) == true) finish() else if (web.canGoBack() && !isLoginPage()) web.goBack() else goHome()
        }
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else goHome()
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    private fun isLoginPage() = web.url?.let { it.startsWith("http://127.0.0.1") && !it.contains(":8878") } == true

    private fun goHome() {
        web.loadUrl(HOME)
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
                .put("bundledFrx", rt.bundledFrx)
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
                .toString()
        }

        @JavascriptInterface
        fun log(n: Int): String = JSONArray(FrxService.log.tail(n.coerceIn(1, 600))).toString()

        /** Распаковать Python и (если он есть в APK) код FRX. */
        @JavascriptInterface
        fun prepare(): Boolean = TaskRunner.runKotlin("prepare") { say ->
            rt.ensurePython(say)
            rt.ensureHelpers()
            if (!rt.frxInstalled && rt.bundledFrx) rt.installBundledFrx(say)
        }

        /** Скачать FRX с GitHub (bootstrap.py). token — для приватного репозитория, можно пустой. */
        @JavascriptInterface
        fun download(repo: String, token: String): Boolean = TaskRunner.runPython("download", { say ->
            rt.ensurePython(say); rt.ensureHelpers()
        }) {
            rt.files.mkdirs()
            rt.process(File(rt.appDir, "bootstrap.py").path, "--dest", rt.frxDir.path,
                "--repo", repo.ifBlank { "Shxdowrate/FRX-Userbot" }, cwd = rt.files).also { pb ->
                if (token.isNotBlank()) pb.environment()["FRX_GITHUB_TOKEN"] = token.trim()
            }
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
        fun openLogin(port: Int) = runOnUiThread { web.loadUrl("http://127.0.0.1:$port/") }

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
            }
        }

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
        fun panel() = runOnUiThread { web.loadUrl("http://127.0.0.1:8878/") }

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
