package com.universal.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class MainActivity : Activity() {

    companion object {
        // Set your default starting portal URL here
        private const val START_URL = "https://nhlstreams.io/"
"

        private const val GITHUB_RELEASE_API =
            "https://api.github.com/repos/dspirovski-design/Goal/releases/latest"

        private const val ACTION_INSTALL_STATUS = "com.universal.app.INSTALL_STATUS"
        private const val PREFS_NAME = "updater_prefs"
        private const val KEY_LAST_ASSET_ID = "last_installed_asset_id"
        private const val MAX_APK_BYTES = 200L * 1024 * 1024

        private val AD_KEYWORDS = listOf(
            "doubleclick", "googlesyndication", "adservice", "popads", "popcash",
            "propellerads", "adsterra", "exoclick", "juicyads", "clickadu",
            "hilltopads", "taboola", "outbrain", "histats", "realsrv.com", "onclkds.com"
        )
    }

    private data class Release(
        val tag: String,
        val assetId: Long,
        val apkUrl: String?
    )

    private lateinit var rootLayout: FrameLayout
    private lateinit var contentColumn: LinearLayout
    private lateinit var topNavBar: LinearLayout
    private lateinit var updateBtn: Button
    private lateinit var webView: WebView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var loadingSpinner: ProgressBar

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val scope = MainScope()
    private var updateJob: Job? = null
    private var pendingAssetId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F19"))
        }

        contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Compact native top bar for Fire TV / phone navigation
        topNavBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(20, 8, 20, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleLabel = TextView(this).apply {
            text = "🏒 Stream Portal"
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val homeBtn = createNavButton("🏠 Games") {
            webView.loadUrl(START_URL)
        }

        val reloadBtn = createNavButton("🔄 Reload") {
            webView.reload()
        }

        updateBtn = createNavButton("⬇️ Update") {
            startUpdateCheck()
        }

        topNavBar.addView(titleLabel)
        topNavBar.addView(homeBtn)
        topNavBar.addView(reloadBtn)
        topNavBar.addView(updateBtn)

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F19"))
            visibility = View.INVISIBLE
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        contentColumn.addView(topNavBar)
        contentColumn.addView(webView)

        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        loadingSpinner = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }

        rootLayout.addView(contentColumn)
        rootLayout.addView(fullscreenContainer)
        rootLayout.addView(loadingSpinner)
        setContentView(rootLayout)

        ContextCompat.registerReceiver(
            this,
            installStatusReceiver,
            IntentFilter(ACTION_INSTALL_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setupWebView()
        webView.loadUrl(START_URL)
    }

    private fun createNavButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.parseColor("#F8FAFC"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            setPadding(24, 6, 24, 6)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 12
            }
            setOnClickListener { onClick() }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        try {
            unregisterReceiver(installStatusReceiver)
        } catch (_: IllegalArgumentException) {
        }
        if (webView.parent != null) {
            contentColumn.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return AD_KEYWORDS.any { lower.contains(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (isAdUrl(request.url.toString())) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val fullUrl = uri.toString()
                val scheme = uri.scheme?.lowercase() ?: ""

                if (scheme != "http" && scheme != "https") return true
                if (isAdUrl(fullUrl)) return true

                return false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                loadingSpinner.visibility = View.GONE
                webView.visibility = View.VISIBLE
                injectCustomUiCleanup()
                webView.requestFocus()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                contentColumn.removeView(webView)
                webView.destroy()
                recreate()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
            ): Boolean = false

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                fullscreenContainer.visibility = View.VISIBLE
                contentColumn.visibility = View.GONE
            }

            override fun onHideCustomView() {
                if (customView == null) return
                fullscreenContainer.removeView(customView)
                customView = null
                fullscreenContainer.visibility = View.GONE
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                contentColumn.visibility = View.VISIBLE
                webView.requestFocus()
            }
        }
    }

    /**
     * Injects CSS and D-pad focus helpers so page headers/upsell buttons are hidden
     * and source/link selector tabs are enlarged for easy remote navigation.
     */
    private fun injectCustomUiCleanup() {
        val js = """
            (function() {
                window.open = function() { return null; };

                if (!document.getElementById('custom-tv-style')) {
                    var style = document.createElement('style');
                    style.id = 'custom-tv-style';
                    style.innerHTML = `
                        /* 1. Hide site navigation headers, settings bars, and promotional/share rows */
                        header, nav, .navbar, .site-header, .header-main,
                        [class*="buy-plan"], [class*="premium"], [class*="share"],
                        .social-share, footer {
                            display: none !important;
                        }

                        /* 2. Make stream link/tab selectors larger, high-contrast, and easy to click on TV */
                        a[href*="link"], [class*="link-item"], [class*="stream-tab"], .nav-tabs a, .nav-pills a {
                            display: inline-block !important;
                            padding: 12px 18px !important;
                            margin: 6px 6px !important;
                            font-size: 16px !important;
                            font-weight: 800 !important;
                            color: #F8FAFC !important;
                            background-color: #1E293B !important;
                            border: 2px solid #475569 !important;
                            border-radius: 10px !important;
                            text-decoration: none !important;
                        }

                        /* 3. High-visibility focus ring for Fire TV D-pad navigation */
                        a:focus, button:focus, [tabindex]:focus {
                            outline: 3px solid #38BDF8 !important;
                            outline-offset: 2px !important;
                            background-color: #2563EB !important;
                            color: #FFFFFF !important;
                        }
                    `;
                    if (document.head) document.head.appendChild(style);
                }

                // Strip promotional buttons by text match ("Buy Plan", "Share", "Settings")
                function cleanDomElements() {
                    document.querySelectorAll('a, button, div, span').forEach(function(el) {
                        if (el.children.length > 2) return;
                        var txt = (el.innerText || '').trim().toLowerCase();
                        if (txt === 'buy plan' || txt === 'share' || txt === 'settings' || txt === 'premium') {
                            var parentBtn = el.closest('button, a');
                            if (parentBtn) parentBtn.style.display = 'none';
                            else el.style.display = 'none';
                        }

                        // Ensure stream source links (e.g., "LINK 1", "LIVE 2") are D-pad focusable
                        if (/^(link|live|stream|feed|server)\s*\d*$/i.test(txt)) {
                            var clickTarget = el.closest('a, button, li') || el;
                            clickTarget.setAttribute('tabindex', '0');
                            clickTarget.style.padding = '12px 18px';
                            clickTarget.style.margin = '4px';
                            clickTarget.style.background = '#1E293B';
                            clickTarget.style.color = '#38BDF8';
                            clickTarget.style.border = '2px solid #38BDF8';
                            clickTarget.style.borderRadius = '8px';
                            clickTarget.style.fontWeight = 'bold';
                        }
                    });
                }

                cleanDomElements();
                setTimeout(cleanDomElements, 1200);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun setUpdateStatus(status: String) {
        runOnUiThread {
            updateBtn.text = status
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------------------------------------
    // Public GitHub Auto-Updater
    // ---------------------------------------------------------------------------------------------

    private fun startUpdateCheck() {
        if (updateJob?.isActive == true) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            setUpdateStatus("Permission")
            requestInstallPermission()
            return
        }

        updateJob = scope.launch {
            try {
                runUpdate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                setUpdateStatus("⚠️ Retry")
                toast("Update failed: ${e.localizedMessage}")
            }
        }
    }

    private fun requestInstallPermission() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            toast("Enable 'Allow from this source', then tap Update again.")
        } catch (_: ActivityNotFoundException) {
            toast("Allow this app to install unknown apps in Settings.")
        }
    }

    private suspend fun runUpdate() {
        setUpdateStatus("Checking...")
        val release = withContext(Dispatchers.IO) { fetchLatestRelease() }

        val lastInstalledAssetId = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_ASSET_ID, -1L)

        if (release.assetId != -1L && release.assetId == lastInstalledAssetId) {
            setUpdateStatus("✓ Up to date")
            toast("Already running the latest build (${release.tag}).")
            return
        }

        val apkUrl = release.apkUrl
        if (apkUrl.isNullOrEmpty() || !apkUrl.startsWith("https://")) {
            setUpdateStatus("No APK")
            toast("Release '${release.tag}' has no .apk asset attached.")
            return
        }

        setUpdateStatus("Downloading...")
        val apk = withContext(Dispatchers.IO) { download(apkUrl) }

        setUpdateStatus("Verifying...")
        val problem = withContext(Dispatchers.IO) { verifyApk(apk) }
        if (problem != null) {
            apk.delete()
            setUpdateStatus("Rejected")
            toast(problem)
            return
        }

        pendingAssetId = release.assetId
        setUpdateStatus("Installing...")
        withContext(Dispatchers.IO) { commitInstall(apk) }
    }

    private fun fetchLatestRelease(): Release {
        val conn = (URL(GITHUB_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Android-Updater")
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("GitHub API HTTP $code")

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.getJSONArray("assets")
            var assetId = -1L
            var apkUrl: String? = null

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    assetId = asset.optLong("id", -1L)
                    apkUrl = asset.optString("browser_download_url", null)
                    break
                }
            }
            return Release(json.optString("tag_name", "latest"), assetId, apkUrl)
        } finally {
            conn.disconnect()
        }
    }

    private fun download(fileUrl: String): File {
        val conn = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Android-Updater")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("Download HTTP ${conn.responseCode}")
            val target = File(cacheDir, "update.apk").apply { delete() }
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_APK_BYTES) throw IOException("APK too large")
                        output.write(buffer, 0, n)
                    }
                }
            }
            return target
        } finally {
            conn.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File): String? {
        val pm = packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "Downloaded file isn't a valid APK."
        if (archive.packageName != packageName) return "Update is for a different package ($packageName)."

        val installed = installedPackageInfo(PackageManager.GET_SIGNING_CERTIFICATES)
        if (PackageInfoCompat.getLongVersionCode(archive) < PackageInfoCompat.getLongVersionCode(installed)) {
            return "Update versionCode is older than the installed version."
        }

        var archiveSigners = signerDigests(archive)
        var installedSigners = signerDigests(installed)
        if (archiveSigners.isEmpty()) {
            archiveSigners = signerDigests(pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES))
            installedSigners = signerDigests(installedPackageInfo(PackageManager.GET_SIGNATURES))
        }
        if (archiveSigners.isEmpty() || archiveSigners != installedSigners) {
            return "Update isn't signed with this app's key."
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            info.signingInfo!!.apkContentsSigners
        } else {
            info.signatures
        }
        val md = MessageDigest.getInstance("SHA-256")
        return signatures?.map { sig -> md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }
            ?.toSet() ?: emptySet()
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(flags: Int): PackageInfo =
        packageManager.getPackageInfo(packageName, flags)

    private fun commitInstall(apk: File) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val statusIntent = Intent(ACTION_INSTALL_STATUS).setPackage(packageName)
                val pending = PendingIntent.getBroadcast(this, sessionId, statusIntent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw e
        } finally {
            apk.delete()
        }
    }

    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            startActivity(confirm)
                        } catch (_: ActivityNotFoundException) {
                            setUpdateStatus("Install failed")
                            toast("This device can't show the install prompt.")
                        }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    if (pendingAssetId != -1L) {
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putLong(KEY_LAST_ASSET_ID, pendingAssetId)
                            .apply()
                    }
                    setUpdateStatus("✓ Updated")
                    toast("Update installed!")
                }
                else -> {
                    setUpdateStatus("Failed")
                    toast(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed.")
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                webView.webChromeClient?.onHideCustomView()
                return true
            }
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
