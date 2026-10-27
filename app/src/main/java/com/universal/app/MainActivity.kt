package com.universal.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import java.io.ByteArrayInputStream

class MainActivity : Activity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var loadingSpinner: ProgressBar
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // Change this URL anytime you clone this template for a different web app
    private val TARGET_URL = "https://onhockey.tv/"

    // Ad, tracker, and popup networks blocked at the network level
    private val BLOCKED_KEYWORDS = listOf(
        "doubleclick.net", "googlesyndication.com", "popads.net", "popcash.net",
        "propellerads", "adsterra", "exoclick", "juicyads", "clickadu",
        "histats.com", "yandex.ru/metrika", "chatango", "cbox.ws", "acint.net",
        "adskeeper", "mgid.com", "onclickads", "pushame", "trafficjunky"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F19"))
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F19"))
            visibility = View.INVISIBLE
        }

        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        loadingSpinner = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }

        rootLayout.addView(webView)
        rootLayout.addView(fullscreenContainer)
        rootLayout.addView(loadingSpinner)
        setContentView(rootLayout)

        setupWebView()
        webView.loadUrl(TARGET_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // Trap popup windows inside WebChromeClient so they get silently dropped
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {

            // Keep top-level navigation locked to the site so redirects cannot hijack the app
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return !url.contains("onhockey.tv", ignoreCase = true)
            }

            // Drop ad and popup scripts before they ever download
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString()?.lowercase() ?: return super.shouldInterceptRequest(view, request)

                for (blocked in BLOCKED_KEYWORDS) {
                    if (url.contains(blocked)) {
                        return WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            204,
                            "No Content",
                            emptyMap(),
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectCustomSkin()
                loadingSpinner.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Silently swallow all window.open() popup attempts
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                return false
            }

            // Handle fullscreen video playback
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
                webView.visibility = View.GONE
            }

            override fun onHideCustomView() {
                if (customView == null) return
                fullscreenContainer.removeView(customView)
                customView = null
                fullscreenContainer.visibility = View.GONE
                customViewCallback?.onCustomViewHidden()
                webView.visibility = View.VISIBLE
            }
        }
    }

    private fun injectCustomSkin() {
        val customCss = """
            html, body {
                background: #0B0F19 !important;
                color: #F8FAFC !important;
                font-family: system-ui, -apple-system, sans-serif !important;
                margin: 0 !important;
                padding: 4px !important;
                overflow-x: hidden !important;
            }
            
            iframe[src*="chat"], iframe[src*="banner"], ins, .adsbygoogle,
            img[src*="banner"], td[width="160"], td[width="120"] {
                display: none !important;
            }

            table {
                width: 100% !important;
                max-width: 100% !important;
                border-collapse: collapse !important;
                background: #111827 !important;
                border-radius: 10px !important;
            }
            
            tr, td {
                color: #F1F5F9 !important;
                font-size: 15px !important;
                line-height: 1.5 !important;
                padding: 6px 8px !important;
                border-bottom: 1px solid #1E293B !important;
            }

            a[href*="place="], a[href*="channel="], a[onclick*="channel"] {
                display: inline-block !important;
                background: #1E293B !important;
                color: #38BDF8 !important;
                border: 1px solid #334155 !important;
                border-radius: 8px !important;
                padding: 8px 12px !important;
                margin: 4px 4px 4px 0 !important;
                text-decoration: none !important;
                font-weight: 600 !important;
                font-size: 14px !important;
            }

            a:focus, button:focus, select:focus, a[href*="place="]:active {
                outline: none !important;
                background: #2563EB !important;
                color: #FFFFFF !important;
                border-color: #60A5FA !important;
                box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.7) !important;
            }

            iframe[name="onhockey"], iframe[src*=".php?channel="] {
                width: 100% !important;
                min-height: 260px !important;
                border: 2px solid #334155 !important;
                border-radius: 10px !important;
                background: #000 !important;
            }
        """.trimIndent()

        val encodedCss = Base64.encodeToString(customCss.toByteArray(), Base64.NO_WRAP)

        val jsInjector = """
            (function() {
                function applyAppSkin() {
                    if (!document.getElementById('icestream-skin')) {
                        var style = document.createElement('style');
                        style.id = 'icestream-skin';
                        style.innerHTML = window.atob('$encodedCss');
                        if (document.head) {
                            document.head.appendChild(style);
                        }
                    }
                    if (document.head && !document.querySelector('meta[name="viewport"]')) {
                        var meta = document.createElement('meta');
                        meta.name = 'viewport';
                        meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                        document.head.appendChild(meta);
                    }
                    var links = document.querySelectorAll('a[href*="place="], a[href*="channel="]');
                    links.forEach(function(link) {
                        if (!link.dataset.boundScroll) {
                            link.dataset.boundScroll = 'true';
                            link.addEventListener('click', function() {
                                var playerFrame = document.querySelector('iframe[name="onhockey"], iframe[src*="channel="]');
                                if (playerFrame) {
                                    playerFrame.scrollIntoView({ behavior: 'smooth', block: 'center' });
                                }
                            });
                        }
                    });
                }

                applyAppSkin();
                if (document.body) {
                    var observer = new MutationObserver(function() { applyAppSkin(); });
                    observer.observe(document.body, { childList: true, subtree: true });
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsInjector, null)
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
