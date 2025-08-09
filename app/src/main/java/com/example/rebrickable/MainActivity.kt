package com.example.rebrickable

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.app.DownloadManager
import android.os.Environment
import android.webkit.URLUtil
import android.widget.Toast

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)

        // Enable WebView debugging in debug builds only
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true

            setSupportZoom(true)
            setBuiltInZoomControls(false)
            setDisplayZoomControls(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            setJavaScriptCanOpenWindowsAutomatically(true)
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString RebrickableWebViewApp"

            allowFileAccess = true
            allowContentAccess = true
            @Suppress("DEPRECATION")
            setAllowFileAccessFromFileURLs(false)
            @Suppress("DEPRECATION")
            setAllowUniversalAccessFromFileURLs(false)

            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setSafeBrowsingEnabled(true)
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                val host = (url.host ?: "").lowercase()
                val path = (url.encodedPath ?: "").lowercase()

                // Special schemes → external apps
                val scheme = url.scheme?.lowercase()
                if (scheme !in listOf("http", "https")) {
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
                        true
                    } catch (_: ActivityNotFoundException) { false }
                }

                // Keep ONLY the core site in-app
                val isCoreHost = host == "www.rebrickable.com" || host == "rebrickable.com"

                // Force these to open in Chrome (subdomains or certain paths)
                val shouldOpenExternal =
                    !isCoreHost ||                // any subdomain (store., forum., etc.)
                            path.startsWith("/store") ||  // store pages on main host
                            path.startsWith("/forum") ||  // discuss/forum if it lives under /forum
                            path.startsWith("/discuss")   // safety net if they use /discuss

                if (shouldOpenExternal) {
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
                        true
                    } catch (_: ActivityNotFoundException) { false }
                }

                // Everything else on the core site stays in the WebView
                return false
            }
        }


        webView.webChromeClient = object : WebChromeClient() {
//            override fun onCreateWindow(
//                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
//            ): Boolean {
//                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
//                transport.webView = webView
//                resultMsg.sendToTarget()
//                return true
//            }

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

        // Handle file downloads triggered by the site (Export, CSV, etc.)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)

                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    addRequestHeader("User-Agent", userAgent)
                    setMimeType(mimeType)
                    setTitle(fileName)
                    setDescription("Downloading…")
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    // Let DownloadManager choose the best external destination (usually /Download)
                    // For legacy devices (<29) you can uncomment:
                    // if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    //     setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    // }
                }

                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Downloading $fileName", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Couldn't start download", Toast.LENGTH_SHORT).show()
            }
        }

        if (savedInstanceState == null) webView.loadUrl(startUrl)
        else webView.restoreState(savedInstanceState)

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (this::webView.isInitialized) webView.saveState(outState)
    }
}
