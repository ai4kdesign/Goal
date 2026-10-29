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
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
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
        private const val START_URL = "https://nhlstreams.io/"

        private const val GITHUB_RELEASE_API =
            "https://api.github.com/repos/ai4kdesign/Goal/releases/latest"

        private const val ACTION_INSTALL_STATUS = "com.universal.app.INSTALL_STATUS"
        private const val MAX_APK_BYTES = 200L * 1024 * 1024

        // Strict allowlist for top-level page navigation
        private val ALLOWED_TOP_LEVEL_HOSTS = setOf(
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

        // Scrollable top bar so all Fire TV remote helper buttons fit on any screen size
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

        // Fire TV Remote Helpers: Cycle LINK 1 / LIVE 2 / LINK 3 even if the video iframe traps focus
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

    // ---------------------------------------------------------------------------------------------
    // Hardened WebView Setup (Scope Item #3)
    // ---------------------------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
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
        cookieManager.setAcceptThirdPartyCookies(webView, false)

        // Block all file downloads
        webView.setDownloadListener { _, _, _, _, _ -> }

        // Notice: No addJavascriptInterface is ever registered on this WebView.

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

                // Strictly HTTPS only
                if (scheme != "https") return true
                if (isBlockedUrl(fullUrl)) return true

                // Top-level navigation is locked to the allowlisted host
                if (request.isForMainFrame && !isAllowedTopLevelHost(uri.host)) {
                    return true
                }

                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Do NOT inject scripts in onPageStarted (runs against old page). Only hide until commit.
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

            // Deny all camera/mic/media permission prompts
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.deny()
            }

            // Deny all geolocation prompts
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

    // ---------------------------------------------------------------------------------------------
    // Fire TV Remote Helpers (Switch Stream Links & Fullscreen from Top Bar)
    // ---------------------------------------------------------------------------------------------

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
                var nextIdx = activeIdx + ($direction);
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
                // Click the page's Full Screen button if present
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

    /**
     * Removes right-side chat, ads, "Buy Plan", "LIVE CHANNELS", and makes all game cards and
     * stream source buttons (LINK 1 / LIVE 2) D-pad navigable with a throttled MutationObserver.
     */
    private fun injectCustomUiCleanup() {
        val js = """
            (function() {
                window.open = function() { return null; };

                if (!document.getElementById('custom-tv-style')) {
                    var style = document.createElement('style');
                    style.id = 'custom-tv-style';
                    style.innerHTML = `
                        /* 1. Hide site headers, navbars, footers, buy-plan, premium, and share boxes */
                        header, nav, .navbar, .site-header, .header-main,
                        [class*="buy-plan"], [class*="buy_plan"], [class*="buyplan"],
                        [class*="premium"], [class*="share"], .social-share,
                        footer {
                            display: none !important;
                        }

                        /* 2. Remove Right-Side Chat Box, Chat Iframes, & Chat Sidebars completely */
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

                        /* 3. Hide ad banners & AliExpress promos */
                        img[src*="aliexpress"], a[href*="aliexpress"],
                        [class*="banner"], [id*="banner"],
                        [class*="advert"], [id*="advert"],
                        [class*="sponsor"], [id*="sponsor"],
                        iframe[src*="ads"], ins.adsbygoogle {
                            display: none !important;
                            height: 0 !important;
                            max-height: 0 !important;
                            overflow: hidden !important;
                        }

                        /* 4. High-visibility focus ring for Fire TV D-pad navigation */
                        a:focus, button:focus, [tabindex]:focus {
                            outline: 4px solid #38BDF8 !important;
                            outline-offset: 3px !important;
                            background-color: #2563EB !important;
                            color: #FFFFFF !important;
                            transform: scale(1.02);
                            z-index: 9999 !important;
                        }
                    `;
                    (document.head || document.documentElement).appendChild(style);
                }

                // Enable Fire TV D-pad Center / Enter key to click focused elements
                if (!window.__tvKeyHandlerInstalled) {
                    window.__tvKeyHandlerInstalled = true;
                    document.addEventListener('keydown', function(e) {
                        if (e.key === 'Enter' || e.keyCode === 13 || e.keyCode === 23 || e.keyCode === 66) {
                            var active = document.activeElement;
                            if (active && active !== document.body && active.tagName !== 'IFRAME') {
                                e.preventDefault();
                                active.click();
                            }
                        }
                    }, true);
                }

                function cleanDom() {
                    // 1. Hide "Buy Plan", "Share", "Settings", "Premium" & Remove Chat panels by text/heading
                    document.querySelectorAll('a, button, span, div, aside, section').forEach(function(el) {
                        var clsId = ((el.className || '') + ' ' + (el.id || '')).toLowerCase();
                        if (/\b(chat|chatbox|chat-room|livechat|right-sidebar)\b/.test(clsId)) {
                            if (!el.querySelector('video') && !el.innerText.includes('LINK 1')) {
                                el.style.setProperty('display', 'none', 'important');
                                return;
                            }
                        }

                        if (el.children.length > 3) return;
                        var txt = (el.innerText || '').trim().toLowerCase();
                        if (txt === 'buy plan' || txt === 'share' || txt === 'settings' || txt === 'premium' || txt === 'live chat' || txt === 'chat') {
                            var btn = el.closest('button, a, aside') || el;
                            btn.style.setProperty('display', 'none', 'important');
                        }

                        // Make LINK 1 / LIVE 2 stream source tabs D-pad focusable & tagged for Prev/Next buttons
                        if (/^(link|live|stream|feed|server|hd|sd)\s*\d*$/i.test(txt)) {
                            var clickTarget = el.closest('a, button, li') || el;
                            clickTarget.setAttribute('tabindex', '0');
                            clickTarget.setAttribute('role', 'button');
                            clickTarget.setAttribute('data-tv-stream-tab', '1');
                            clickTarget.style.padding = '12px 18px';
                            clickTarget.style.margin = '4px';
                            clickTarget.style.background = '#1E293B';
                            clickTarget.style.color = '#38BDF8';
                            clickTarget.style.border = '2px solid #38BDF8';
                            clickTarget.style.borderRadius = '8px';
                            clickTarget.style.fontWeight = 'bold';
                            clickTarget.style.display = 'inline-block';
                        }
                    });

                    // 2. Prevent video iframes from trapping Fire TV D-pad focus so remote can navigate page freely
                    document.querySelectorAll('iframe').forEach(function(ifr) {
                        var src = (ifr.src || '').toLowerCase();
                        if (src.includes('chat') || src.includes('tlk.io') || src.includes('twitch') || src.includes('cbox')) {
                            ifr.remove();
                        } else {
                            // Keep video iframe playable but prevent it from stealing D-pad focus trap
                            ifr.setAttribute('tabindex', '-1');
                        }
                    });

                    // 3. Make game matchup cards and "FULL SCREEN" button focusable with Fire TV remote
                    document.querySelectorAll('a[href], button, [onclick]').forEach(function(item) {
                        if (item.offsetParent !== null && !item.hasAttribute('tabindex')) {
                            item.setAttribute('tabindex', '0');
                        }
                    });

                    // 4. Hide ONLY "LIVE CHANNELS", its 2 channel logos, and bottom SEO text
                    document.querySelectorAll('h1, h2, h3, h4, h5, div, span, p').forEach(function(el) {
                        if (el.children.length > 2) return;
                        var txt = (el.innerText || '').trim().toUpperCase();

                        if (txt === 'LIVE CHANNELS') {
                            el.style.setProperty('display', 'none', 'important');
                            var nextEl = el.nextElementSibling;
                            if (nextEl) {
                                var nextTxt = (nextEl.innerText || '').toUpperCase();
                                if (!nextTxt.includes('MATCHUP') && !nextTxt.includes('VS') && nextEl.querySelectorAll('img, a').length <= 6) {
                                    nextEl.style.setProperty('display', 'none', 'important');
                                }
                            }
                        }

                        if (txt.startsWith('NHL STREAMS 2026') || txt.includes('WATCH LIVE REDDIT NHL STREAMS')) {
                            el.style.setProperty('display', 'none', 'important');
                        }
                    });

                    document.querySelectorAll('a, img').forEach(function(el) {
                        var srcOrHref = ((el.src || '') + ' ' + (el.href || '') + ' ' + (el.alt || '')).toLowerCase();
                        if (srcOrHref.includes('espn') || srcOrHref.includes('nhl-network') || srcOrHref.includes('nhl_network') || srcOrHref.includes('nhlnetwork')) {
                            var tile = el.closest('a') || el;
                            if ((tile.innerText || '').trim().length < 25) {
                                tile.style.setProperty('display', 'none', 'important');
                            }
                        }
                        if (srcOrHref.includes('aliexpress') || srcOrHref.includes('banner')) {
                            var banner = el.closest('a, div[class*="banner"]') || el;
                            banner.style.setProperty('display', 'none', 'important');
                        }
                    });

                    document.querySelectorAll('p').forEach(function(p) {
                        if ((p.innerText || '').trim().length > 90) {
                            p.style.setProperty('display', 'none', 'important');
                        }
                    });
                }

                cleanDom();

                // Throttled MutationObserver (runs at most once every 400ms to keep Fire TV Stick fast)
                if (!window.__tvCleanupObserver && document.documentElement) {
                    var throttleTimer = null;
                    window.__tvCleanupObserver = new MutationObserver(function() {
                        if (throttleTimer) return;
                        throttleTimer = setTimeout(function() {
                            throttleTimer = null;
                            cleanDom();
                        }, 400);
                    });
                    window.__tvCleanupObserver.observe(document.documentElement, {
                        childList: true,
                        subtree: true
                    });
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

    // ---------------------------------------------------------------------------------------------
    // Hardened Auto-Updater (Scope Item #2)
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

        // Skip download when latest release tag matches installed versionName
        val installedVersionName = installedPackageInfo(0).versionName ?: ""
        val latestVersionName = release.tag.removePrefix("v")
        if (latestVersionName.isNotEmpty() && latestVersionName == installedVersionName) {
            setUpdateStatus("✓ Up to date")
            toast("Already on latest version ($installedVersionName).")
            return
        }

        val apkUrl = release.apkUrl
        if (apkUrl.isNullOrEmpty() || !apkUrl.startsWith("https://")) {
            setUpdateStatus("No APK")
            toast("Release '${release.tag}' has no HTTPS .apk asset attached.")
            return
        }

        setUpdateStatus("Downloading...")
        val apk = withContext(Dispatchers.IO) { download(apkUrl) }

        setUpdateStatus("Verifying...")
        // Every release publishes a .sha256 file, so a missing or unreadable checksum is a failure.
        val sha256Url = release.sha256Url
        val expectedSha256 = if (!sha256Url.isNullOrEmpty() && sha256Url.startsWith("https://")) {
            withContext(Dispatchers.IO) { fetchSha256Text(sha256Url) }
        } else null
        if (expectedSha256.isNullOrEmpty() || !Regex("^[0-9a-f]{64}$").matches(expectedSha256)) {
            apk.delete()
            setUpdateStatus("Rejected")
            toast("Release '${release.tag}' has no valid SHA-256 checksum.")
            return
        }

        val problem = withContext(Dispatchers.IO) { verifyApk(apk, expectedSha256) }
        if (problem != null) {
            apk.delete()
            setUpdateStatus("Rejected")
            toast(problem)
            return
        }

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
            if (code == 403 || code == 429) {
                throw IOException("GitHub API rate limit reached (HTTP $code). Please wait a few minutes and try again.")
            }
            if (code != 200) {
                throw IOException("GitHub API returned HTTP $code")
            }

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            var sha256Url: String? = null

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                val downloadUrl = asset.optString("browser_download_url", null)
                if (name.endsWith(".apk")) {
                    apkUrl = downloadUrl
                } else if (name.endsWith(".sha256")) {
                    sha256Url = downloadUrl
                }
            }
            return Release(json.optString("tag_name", ""), apkUrl, sha256Url)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchSha256Text(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Android-Updater")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
                    .trim()
                    .split(Regex("\\s+"))
                    .firstOrNull()
                    ?.lowercase()
            } else null
        } catch (_: Exception) {
            null
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
            val code = conn.responseCode
            if (code == 403 || code == 429) {
                throw IOException("GitHub download rate limit reached (HTTP $code). Try again shortly.")
            }
            if (code !in 200..299) throw IOException("Download HTTP $code")
            val target = File(cacheDir, "update.apk").apply { delete() }
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_APK_BYTES) throw IOException("APK exceeds size limit")
                        output.write(buffer, 0, n)
                    }
                }
            }
            return target
        } finally {
            conn.disconnect()
        }
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

    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File, expectedSha256: String): String? {
        if (!fileSha256(apk).equals(expectedSha256, ignoreCase = true)) {
            return "APK SHA-256 checksum mismatch."
        }

        val pm = packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "Downloaded file isn't a valid APK."
        if (archive.packageName != packageName) return "Update is for a different package ($packageName)."

        val installed = installedPackageInfo(PackageManager.GET_SIGNING_CERTIFICATES)
        // Strict <= check: reject updates whose versionCode is equal or lower
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) {
            return "Update isn't newer than the installed version."
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
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
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
                    setUpdateStatus("✓ Updated")
                    toast("Update installed! You can now disable 'Install unknown apps' in Settings.")
                    // Scope Item #4: Prompt user to turn "Install unknown apps" back off after update
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        } catch (_: ActivityNotFoundException) {
                        }
                    }
                }
                else -> {
                    setUpdateStatus("Failed")
                    toast(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed.")
                }
            }
        }
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
                val now = android.os.SystemClock.uptimeMillis()
                val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, cx, cy, 0)
                val up = android.view.MotionEvent.obtain(now, now + 50, android.view.MotionEvent.ACTION_UP, cx, cy, 0)
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
                    hideCustomView()
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
