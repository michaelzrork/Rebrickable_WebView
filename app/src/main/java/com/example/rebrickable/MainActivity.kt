package com.example.rebrickable

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import android.webkit.URLUtil
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var retryBtn: Button
    private lateinit var shareBtn: FloatingActionButton

    private val handler = Handler(Looper.getMainLooper())
    private val showShareRunnable = Runnable { shareBtn.show() }

    private val startUrl = "https://www.rebrickable.com/"

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        val data = result.data
        val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            when {
                data == null -> null
                data.clipData != null && data.clipData!!.itemCount > 0 ->
                    Array(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }
                data.data != null -> arrayOf(data.data!!)
                else -> null
            }
        } else null
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    private fun handleDeepLinkIfAny(intent: Intent): Boolean {
        val data = intent.data ?: return false
        val host = data.host?.lowercase() ?: return false
        if (host == "rebrickable.com" || host == "www.rebrickable.com") {
            loadOrShowOffline(data.toString())
            return true
        }
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Normal layout: content below status/nav bars
        WindowCompat.setDecorFitsSystemWindows(window, true)

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.error_view)
        retryBtn = findViewById(R.id.btn_retry)
        shareBtn = findViewById(R.id.btn_share)

        retryBtn.setOnClickListener {
            errorView.visibility = android.view.View.GONE
            loadOrShowOffline(startUrl)
        }

        shareBtn.setOnClickListener {
            val shareUrl = webView.url ?: startUrl
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareUrl)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        }

        webView.setOnScrollChangeListener { _, _, _, _, _ ->
            shareBtn.hide()
            handler.removeCallbacks(showShareRunnable)
            handler.postDelayed(showShareRunnable, 1000)
        }

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true

            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false) // force target=_blank into same WebView
            userAgentString = "$userAgentString RebrickableWebViewApp"

            allowFileAccess = true
            allowContentAccess = true
            @Suppress("DEPRECATION") setAllowFileAccessFromFileURLs(false)
            @Suppress("DEPRECATION") setAllowUniversalAccessFromFileURLs(false)

            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = android.view.View.VISIBLE
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = android.view.View.GONE
                super.onPageFinished(view, url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request == null || request.isForMainFrame) {
                    progress.visibility = android.view.View.GONE
                    errorView.visibility = android.view.View.VISIBLE
                }
                super.onReceivedError(view, request, error)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                val scheme = url.scheme?.lowercase()

                // Special schemes → external apps
                if (scheme !in listOf("http", "https")) {
                    return openExternal(url)
                }

                val host = (url.host ?: "").lowercase()
                val path = (url.encodedPath ?: "").lowercase()

                // Keep ONLY core host in-app; Store/Discuss/subdomains to Chrome
                val isCoreHost = host == "www.rebrickable.com" || host == "rebrickable.com"
                val shouldOpenExternal =
                    !isCoreHost ||
                            path.startsWith("/store") ||
                            path.startsWith("/forum") ||
                            path.startsWith("/discuss")

                if (shouldOpenExternal) return openExternal(url)

                return false // load in WebView
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                return try {
                    filePickerLauncher.launch(Intent.createChooser(intent, "Select file")); true
                } catch (_: ActivityNotFoundException) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        // Downloads (Export/CSV, etc.)
        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val req = DownloadManager.Request(url.toUri()).apply {
                    addRequestHeader("User-Agent", userAgent)
                    setMimeType(mimeType)
                    setTitle(fileName)
                    setDescription("Downloading…")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                }
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "Downloading $fileName", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Couldn't start download", Toast.LENGTH_SHORT).show()
            }
        })

        if (savedInstanceState == null) {
            if (!handleDeepLinkIfAny(intent)) {
                loadOrShowOffline(startUrl)
            }
        } else {
            webView.restoreState(savedInstanceState)
        }


        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (this@MainActivity::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIfAny(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(showShareRunnable)
        super.onDestroy()
    }

    private fun openExternal(url: Uri): Boolean =
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
            true
        } catch (_: ActivityNotFoundException) { false }

    private fun loadOrShowOffline(url: String) {
        if (hasNetwork()) {
            errorView.visibility = android.view.View.GONE
            webView.loadUrl(url)
        } else {
            progress.visibility = android.view.View.GONE
            errorView.visibility = android.view.View.VISIBLE
        }
    }

    private fun hasNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (this::webView.isInitialized) webView.saveState(outState)
    }
}
