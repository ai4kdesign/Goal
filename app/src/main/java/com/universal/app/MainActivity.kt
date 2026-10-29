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
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
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
        private const val START_URL = "https://nhl24all.ir/"

        private const val GITHUB_RELEASE_API =
            "https://api.github.com/repos/ai4kdesign/Goal/releases/latest"

        private const val ACTION_INSTALL_STATUS = "com.universal.app.INSTALL_STATUS"
        private const val MAX_APK_BYTES = 200L * 1024 * 1024

        private val ALLOWED_TOP_LEVEL_HOSTS = setOf(
            "nhl24all.ir",
            "www.nhl24all.ir",
            "nhlstreams.io",
            "www.nhlstreams.io"
        )

        private val AD_AND_CHAT_KEYWORDS = listOf(
            "doubleclick", "googlesyndication", "adservice", "popads", "popcash",
            "propellerads", "adsterra", "exoclick", "juicyads", "clickadu",
            "hilltopads", "taboola", "outbrain", "histats", "realsrv.com",
            "onclkds.com", "aliexpress", "chatango", "twitch.tv/embed",
            "tlk.io", "minnit.chat", "rumble.com/chat", "cbox.ws", "disqus"
        )
    }

    private data class Release(
        val tag: String,
        val apkUrl: String?,
        val sha256Url: String?
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

        val topScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        topNavBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 8)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleLabel = TextView(this).apply {
            text = "🏒 NHL"
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 14f
            setPadding(8, 0, 16, 0)
        }

        val homeBtn = createNavButton("🏠 Games") {
            val current = webView.url?.trimEnd('/') ?: ""
            val target = START_URL.trimEnd('/')
            if (current.equals(target, ignoreCase = true)) {
                webView.evaluateJavascript("window.scrollTo({top: 0, behavior: 'smooth'});", null)
                toast("At main schedule")
            } else {
                webView.loadUrl(START_URL)
            }
        }

        val prevLinkBtn = createNavButton("⏮ Link") {
            cycleStreamSource(-1)
        }

        val nextLinkBtn = createNavButton("⏭ Link") {
            cycleStreamSource(1)
        }

        val fullscreenBtn = createNavButton("⛶ Full") {
            triggerVideoFullscreen()
        }

        val reloadBtn = createNavButton("🔄 Reload") {
            webView.reload()
        }

        updateBtn = createNavButton("⬇️ Update") {
            startUpdateCheck()
        }

        topNavBar.addView(titleLabel)
        topNavBar.addView(homeBtn)
        topNavBar.addView(prevLinkBtn)
        topNavBar.addView(nextLinkBtn)
        topNavBar.addView(fullscreenBtn)
        topNavBar.addView(reloadBtn)
        topNavBar.addView(updateBtn)
        topScroll.addView(topNavBar)

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

        contentColumn.addView(topScroll)
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
            isFocusableInTouchMode = false
            setPadding(20, 6, 20, 6)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 10
            }
            setOnFocusChangeListener { v, hasFocus ->
                v.setBackgroundColor(
                    if (hasFocus) Color.parseColor("#2563EB") else Color.parseColor("#1E293B")
                )
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
        clearWebViewData()
        if (webView.parent != null) {
            contentColumn.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun clearWebViewData() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webView.clearCache(true)
            webView.clearHistory()
        } catch (_: Exception) {
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val lower = url.lowercase()
        return AD_AND_CHAT_KEYWORDS.any { lower.contains(it) }
    }

    private fun isAllowedTopLevelHost(host: String?): Boolean {
        val clean = host?.lowercase() ?: return false
        return ALLOWED_TOP_LEVEL_HOSTS.any { clean == it || clean.endsWith(".$it") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(webView.settings, true)
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.setDownloadListener { _, _, _, _, _ -> }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (isBlockedUrl(request.url.toString())) {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                val fullUrl = uri.toString()
                val scheme = uri.scheme?.lowercase() ?: ""

                if (scheme != "https") return true
                if (isBlockedUrl(fullUrl)) return true

                if (request.isForMainFrame && !isAllowedTopLevelHost(uri.host)) {
                    return true
                }

                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                webView.visibility = View.INVISIBLE
                loadingSpinner.visibility = View.VISIBLE
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                super.onPageCommitVisible(view, url)
                injectCustomUiCleanup()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                injectCustomUiCleanup()
                webView.postDelayed({
                    loadingSpinner.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                }, 100)
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                contentColumn.removeView(webView)
                webView.destroy()
                recreate()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean = false

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, false, false)
            }

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
            }
        }
    }

    private fun cycleStreamSource(direction: Int) {
        val js = """
            (function() {
                var tabs = Array.from(document.querySelectorAll('[data-tv-stream-tab="1"]'));
                if (tabs.length === 0) return "No stream tabs found on this page";
                var activeIdx = tabs.findIndex(function(el) {
                    return el === document.activeElement ||
                           el.classList.contains('active') ||
                           el.getAttribute('aria-selected') === 'true' ||
                           el.dataset.tvSelected === '1';
                });
                var nextIdx = activeIdx + (""" + direction + """);
                if (nextIdx < 0) nextIdx = tabs.length - 1;
                if (nextIdx >= tabs.length) nextIdx = 0;
                tabs.forEach(function(t) { t.dataset.tvSelected = '0'; });
                var target = tabs[nextIdx];
                target.dataset.tvSelected = '1';
                target.focus();
                target.click();
                return (target.innerText || 'Stream').trim();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            val clean = result?.trim('"') ?: ""
            if (clean.isNotEmpty() && clean != "null") {
                toast("Switched to: $clean")
            }
        }
    }

    private fun triggerVideoFullscreen() {
        val js = """
            (function() {
                var btns = Array.from(document.querySelectorAll('button, a, div, span'));
                for (var i = 0; i < btns.length; i++) {
                    var t = (btns[i].innerText || '').trim().toUpperCase();
                    if (t === 'FULL SCREEN' || t === 'FULLSCREEN') {
                        btns[i].click();
                        return;
                    }
                }
                var v = document.querySelector('video') || document.querySelector('iframe[src*="player"], iframe[src*="embed"], iframe');
                if (v && v.requestFullscreen) v.requestFullscreen();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectCustomUiCleanup() {
        val js = """
            (function() {
                window.open = function() { return null; };

                if (!document.getElementById('custom-tv-style')) {
                    var style = document.createElement('style');
                    style.id = 'custom-tv-style';
                    style.innerHTML = `
                        header, nav, .navbar, .site-header, .header-main, [class*="buy-plan"], footer { 
                            display: none !important; 
                        }
                        /* Chat box and chat iframes removal */
                        #chat, .chat, [id*="chat-"], [id*="-chat"], [class*="chat-box"],
                        [class*="chat_box"], [class*="chatbox"], [class*="chat-container"],
                        [class*="chat-sidebar"], [class*="chat-panel"], [class*="chat-wrapper"],
                        [class*="live-chat"], [id*="live-chat"],
                        aside[class*="chat"], iframe[src*="chat"], iframe[id*="chat"],
                        iframe[class*="chat"] {
                            display: none !important;
                            width: 0 !important;
                            height: 0 !important;
                            max-width: 0 !important;
                            overflow: hidden !important;
                        }
                    `;
                    (document.head || document.documentElement).appendChild(style);
                }

                function removeUnwantedElements() {
                    // Target strictly the balance widget and betting rules box, protecting schedule cards
                    document.querySelectorAll('aside, div, section').forEach(function(el) {
                        var text = (el.innerText || '').toLowerCase();
                        if (text.includes('your balance') || text.includes('win sum') || text.includes('max amount for each pick') || text.includes('tips and rules')) {
                            if (!el.querySelector('video') && !el.querySelector('iframe') && el.innerText.length < 300) {
                                el.style.setProperty('display', 'none', 'important');
                            }
                        }
                    });

                    // Target chat elements safely
                    var chatKeywords = ['chat', 'chatbox', 'chat-room', 'livechat', 'right-sidebar'];
                    document.querySelectorAll('aside, div, section, iframe').forEach(function(el) {
                        var clsId = ((el.className || '') + ' ' + (el.id || '') + ' ' + (el.src || '')).toLowerCase();
                        var matchesKeyword = chatKeywords.some(function(keyword) {
                            return clsId.indexOf(keyword) !== -1;
                        });
                        if (matchesKeyword) {
                            if (!el.querySelector('video')) {
                                el.style.setProperty('display', 'none', 'important');
                            }
                        }
                    });
                }

                removeUnwantedElements();

                if (!window.__tvCleanupObserver && document.documentElement) {
                    var throttleTimer = null;
                    window.__tvCleanupObserver = new MutationObserver(function() {
                        if (throttleTimer) return;
                        throttleTimer = setTimeout(function() {
                            throttleTimer = null;
                            removeUnwantedElements();
                        }, 400);
                    });
                    window.__tvCleanupObserver.observe(document.documentElement, {
                        childList: true,
                        subtree: true
                    });
                }

                if (!window.__tvKeyHandlerInstalled) {
                    window.__tvKeyHandlerInstalled = true;
                    document.addEventListener('keydown', function(e) {
                        if (e.key === 'Enter' || e.keyCode === 13) {
                            var active = document.activeElement;
                            if (active && active !== document.body && active.tagName !== 'IFRAME') {
                                e.preventDefault();
                                active.click();
                            }
                        }
                    }, true);
                }
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
                val msg = e.localizedMessage ?: "Unknown Error"
                toast("Update failed: " + msg)
            }
        }
    }

    private fun requestInstallPermission() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName))
            )
        } catch (_: ActivityNotFoundException) {
        }
    }

    private suspend fun runUpdate() {
        setUpdateStatus("Checking...")
        val release = withContext(Dispatchers.IO) { fetchLatestRelease() }
        val installedVersionName = installedPackageInfo(0).versionName ?: ""
        val latestVersionName = release.tag.removePrefix("v")
        if (latestVersionName.isNotEmpty() && latestVersionName == installedVersionName) {
            setUpdateStatus("✓ Up to date")
            return
        }

        val apkUrl = release.apkUrl ?: return
        val apk = withContext(Dispatchers.IO) { download(apkUrl) }
        val sha256Url = release.sha256Url ?: return
        val expectedSha256 = withContext(Dispatchers.IO) { fetchSha256Text(sha256Url) } ?: ""

        val problem = withContext(Dispatchers.IO) { verifyApk(apk, expectedSha256) }
        if (problem != null) {
            apk.delete()
            return
        }
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
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            var sha256Url: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                val downloadUrl = asset.optString("browser_download_url", null)
                if (name.endsWith(".apk")) apkUrl = downloadUrl
                else if (name.endsWith(".sha256")) sha256Url = downloadUrl
            }
            return Release(json.optString("tag_name", ""), apkUrl, sha256Url)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchSha256Text(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }.trim().split(" ").firstOrNull()?.lowercase()
        } catch (_: Exception) { null } finally { conn.disconnect() }
    }

    private fun download(fileUrl: String): File {
        val conn = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        val target = File(cacheDir, "update.apk")
        conn.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        return target
    }

    private fun fileSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifyApk(apk: File, expectedSha256: String): String? {
        if (!fileSha256(apk).equals(expectedSha256, ignoreCase = true)) return "Mismatch"
        return null
    }

    private fun installedPackageInfo(flags: Int): PackageInfo = packageManager.getPackageInfo(packageName, flags)

    private fun commitInstall(apk: File) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input -> session.openWrite("base.apk", 0, apk.length()).use { output -> input.copyTo(output) } }
            val intent = Intent(ACTION_INSTALL_STATUS).setPackage(packageName)
            val pending = PendingIntent.getBroadcast(this, sessionId, intent, PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {}
    }

    private fun toggleVideoPlayPause() {
        val js = """
            (function() {
                var videos = Array.from(document.querySelectorAll('video'));
                document.querySelectorAll('iframe').forEach(function(f) {
                    try {
                        if (f.contentDocument) {
                            videos = videos.concat(Array.from(f.contentDocument.querySelectorAll('video')));
                        }
                    } catch (e) {}
                });
                if (videos.length > 0) {
                    var v = videos[0];
                    if (v.paused) v.play(); else v.pause();
                    return "Toggled video";
                }

                var playBtn = document.querySelector(
                    '.play-wrapper, [data-playpause], .vjs-play-control, .jw-icon-playback, [aria-label*="Play"], [aria-label*="Pause"]'
                );
                if (playBtn) {
                    playBtn.click();
                    return "Clicked player control";
                }

                return "FALLBACK_TAP";
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            if (result?.contains("FALLBACK_TAP") == true) {
                val targetView = if (customView != null) fullscreenContainer else webView
                val cx = targetView.width / 2f
                val cy = targetView.height / 2f
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cx, cy, 0)
                val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, cx, cy, 0)
                targetView.dispatchTouchEvent(down)
                targetView.dispatchTouchEvent(up)
                down.recycle()
                up.recycle()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                toggleVideoPlayPause()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (customView != null) {
                    toggleVideoPlayPause()
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (customView != null) {
                    webView.webChromeClient?.onHideCustomView()
                    return true
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
