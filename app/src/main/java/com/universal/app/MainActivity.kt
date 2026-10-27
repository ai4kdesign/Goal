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
import android.webkit.JavascriptInterface
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
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val APP_ORIGIN = "https://\$ASSET_HOST"
        private const val START_URL = "\$APP_ORIGIN/assets/index.html"

        // Public GitHub Release endpoint (no token needed)
        private const val GITHUB_RELEASE_API =
            "https://api.github.com/repos/dspirovski-design/Goal/releases/latest"

        private const val ACTION_INSTALL_STATUS = "com.universal.app.INSTALL_STATUS"
        private const val PREFS_NAME = "updater_prefs"
        private const val KEY_LAST_ASSET_ID = "last_installed_asset_id"
        private const val MAX_APK_BYTES = 200L * 1024 * 1024

        private val AD_HOSTS = listOf(
            "doubleclick.net", "googlesyndication.com", "adservice.google.com",
            "popads.net", "popcash.net", "propellerads.com", "adsterra.com",
            "exoclick.com", "juicyads.com", "clickadu.com", "hilltopads.net"
        )
    }

    private data class Release(
        val tag: String,
        val assetId: Long,
        val apkUrl: String?
    )

    private lateinit var rootLayout: FrameLayout
    private lateinit var contentColumn: LinearLayout
    private lateinit var backToHubBar: Button
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

        // Top bar shown automatically whenever viewing a stream provider
        backToHubBar = Button(this).apply {
            text = "← Back to IceStream Hub"
            setTextColor(Color.parseColor("#F8FAFC"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 14f
            visibility = View.GONE
            isFocusable = true
            setOnClickListener {
                webView.loadUrl(START_URL)
            }
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F19"))
            visibility =
