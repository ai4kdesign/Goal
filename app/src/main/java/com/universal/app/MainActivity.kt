package com.universal.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var loadingSpinner: ProgressBar
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val GITHUB_REPO_API = "https://api.github.com/repos/dspirovski-design/Goal/releases/latest"
    private val GITHUB_TOKEN = "github_pat_YOUR_TOKEN_HERE" // Your private token
    private val CURRENT_VERSION = "1.0" // Match this with your release tags (e.g. 1.0, 1.1, etc.)

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
        webView.loadUrl("file:///android_asset/index.html")
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

        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loadingSpinner.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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

    inner class WebAppInterface {
        @JavascriptInterface
        fun triggerGitHubUpdate() {
            checkForPrivateGitHubRelease()
        }
    }

    private fun checkForPrivateGitHubRelease() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateWebViewButton("Checking...")
                val url = URL(GITHUB_REPO_API)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Android-Updater")
                connection.setRequestProperty("Authorization", "Bearer $GITHUB_TOKEN")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val latestTag = json.getString("tag_name").replace("v", "").trim()

                    // Compare versions smartly
                    if (latestTag == CURRENT_VERSION) {
                        updateWebViewButton("Up to date!")
                        showToast("App is already up to date (v$CURRENT_VERSION)")
                        return@launch
                    }

                    updateWebViewButton("New v$latestTag found")
                    val assets = json.getJSONArray("assets")
                    
                    var downloadUrl = ""
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.getString("name").endsWith(".apk")) {
                            downloadUrl = asset.getString("browser_download_url")
                            break
                        }
                    }

                    if (downloadUrl.isNotEmpty()) {
                        downloadAndInstallPrivateApk(downloadUrl)
                    } else {
                        updateWebViewButton("No APK Found")
                        showToast("Release found, but no .apk asset attached.")
                    }
                } else {
                    updateWebViewButton("Auth Failed")
                    showToast("GitHub Authentication Failed. Check token.")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                updateWebViewButton("Error")
                showToast("Update check error: ${e.localizedMessage}")
            }
        }
    }

    private suspend fun downloadAndInstallPrivateApk(fileUrl: String) {
        withContext(Dispatchers.IO) {
            try {
                updateWebViewButton("Downloading...")
                
                // Clear out any old cached update files first to prevent storage bloat
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                val oldFile = File(dir, "update.apk")
                if (oldFile.exists()) oldFile.delete()

                val url = URL(fileUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $GITHUB_TOKEN")
                connection.setRequestProperty("Accept", "application/octet-stream")
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.connect()

                val file = File(dir, "update.apk")
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                withContext(Dispatchers.Main) {
                    updateWebViewButton("Ready to Install")
                    showToast("Download complete. Launching installer...")
                    triggerInstall(file)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                updateWebViewButton("Download Error")
                showToast("Download failed: ${e.localizedMessage}")
            }
        }
    }

    private suspend fun updateWebViewButton(status: String) {
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript("setUpdateStatus('$status');", null)
        }
    }

    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun triggerInstall(file: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", file)
            } else {
                Uri.fromFile(file)
            }
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
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
