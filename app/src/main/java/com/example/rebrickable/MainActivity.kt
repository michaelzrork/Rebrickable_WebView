package com.example.rebrickable

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.FrameLayout
import android.widget.RelativeLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.res.ColorStateList
import kotlin.math.roundToInt
import android.content.res.Configuration


class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var retryBtn: Button

    // Main overflow FAB (triple dots)
    private lateinit var menuFab: FloatingActionButton
    // Expanding action buttons (same size as menuFab)
    private lateinit var shareFab: FloatingActionButton
    private lateinit var settingsFab: FloatingActionButton
    private var menuExpanded = false

    private val handler = Handler(Looper.getMainLooper())
    private val showFabRunnable = Runnable { menuFab.show() }

    // -------- Preferences for Beta Feed --------
    private lateinit var prefs: SharedPreferences
    private var betaFeedEnabled: Boolean = false

    private val URL_HOME = "https://www.rebrickable.com/"
    private val URL_BETA_FEED = "https://www.rebrickable.com/feed/BETA/"
    private var startUrl: String = URL_HOME
    // ------------------------------------------

    private fun applyStatusBarIconMode() {
        val isNight = (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        // Legacy flag: dark icons in day, light icons in night
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val decor = window.decorView
            decor.systemUiVisibility = if (isNight) {
                decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
        // Modern API
        WindowInsetsControllerCompat(window, window.decorView)
            .apply { isAppearanceLightStatusBars = !isNight }
    }

    private fun applySystemBarIconMode() {
        val isNight = (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        // Status bar icons (API 23+ via theme already; reassert here for OEMs)
        WindowInsetsControllerCompat(window, window.decorView)
            .apply { isAppearanceLightStatusBars = !isNight }

        // Navigation bar icons (API 27+ only)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WindowInsetsControllerCompat(window, window.decorView)
                .apply { isAppearanceLightNavigationBars = !isNight }
        }
    }


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

    // ===== JS <-> Android bridge for BODY background =====
    private var jsBridgeAdded = false

    @JavascriptInterface
    fun onBodyBgColor(cssColor: String) {
        runOnUiThread { applyDynamicTint(cssColor) }
    }

    private fun ensureJsBridge() {
        if (!jsBridgeAdded) {
            webView.addJavascriptInterface(this, "AndroidBridge")
            jsBridgeAdded = true
        }
    }

    private fun injectBodyBgWatcher() {
        val js = """
            (function(){
              var last = '';
              function isTransparent(c){
                if(!c) return true;
                if(c === 'transparent') return true;
                var m = c.match(/^rgba?\((\s*\d+\s*),(\s*\d+\s*),(\s*\d+\s*)(?:,(\s*[\d.]+\s*))?\)$/i);
                if(!m) return false;
                var a = m[4] ? parseFloat(m[4]) : 1;
                return a === 0;
              }
              function currentBg(){
                var b = getComputedStyle(document.body).backgroundColor;
                if(isTransparent(b)){
                  b = getComputedStyle(document.documentElement).backgroundColor || 'rgb(255,255,255)';
                }
                return b || 'rgb(255,255,255)';
              }
              function sendIfChanged(){
                try {
                  var c = currentBg();
                  if (c !== last) {
                    last = c;
                    AndroidBridge.onBodyBgColor(c);
                  }
                } catch(e){}
              }
              var opts = {attributes:true, attributeFilter:['class','style']};
              try {
                var moBody = new MutationObserver(sendIfChanged);
                moBody.observe(document.body, opts);
                var moHtml = new MutationObserver(sendIfChanged);
                moHtml.observe(document.documentElement, opts);
              } catch(e) {}
              var kicks = 0;
              var kickTimer = setInterval(function(){
                sendIfChanged();
                if(++kicks >= 30) clearInterval(kickTimer);
              }, 500);
              window.addEventListener('load', sendIfChanged, {once:false});
              document.addEventListener('readystatechange', sendIfChanged);
              sendIfChanged();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }
    // ===== end dynamic color bridge =====

    private fun applyDynamicTint(css: String) {
        val color = parseCssColor(css)
        val luminance = ColorUtils.calculateLuminance(color)
        val iconColor = if (luminance < 0.5) Color.WHITE else Color.BLACK
        listOf(menuFab, shareFab, settingsFab).forEach { fab ->
            fab.backgroundTintList = ColorStateList.valueOf(color)
            fab.imageTintList = ColorStateList.valueOf(iconColor)
        }
    }

    private fun parseCssColor(css: String): Int {
        val s = css.trim().lowercase()
        return when {
            s.startsWith("#") -> Color.parseColor(s)
            s.startsWith("rgb") -> {
                val nums = Regex("""[\d.]+""").findAll(s).map { it.value }.toList()
                val r = nums.getOrNull(0)?.toFloat()?.roundToInt() ?: 255
                val g = nums.getOrNull(1)?.toFloat()?.roundToInt() ?: 255
                val b = nums.getOrNull(2)?.toFloat()?.roundToInt() ?: 255
                val a = when (nums.size) {
                    4 -> ((nums[3].toFloat().coerceIn(0f,1f)) * 255f).roundToInt()
                    else -> 255
                }
                Color.argb(a, r, g, b)
            }
            else -> Color.WHITE
        }
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
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)

        applyStatusBarIconMode()
        window.decorView.post { applyStatusBarIconMode() } // re-assert after first layout


        // Prefer dark icons on (apparently) light status bar
//        forceLightStatusBar()
//        window.decorView.post { forceLightStatusBar() }

        // prefs + startUrl
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        betaFeedEnabled = prefs.getBoolean("beta_feed", false)
        startUrl = if (betaFeedEnabled) URL_BETA_FEED else URL_HOME

        webView = findViewById(R.id.webview)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.error_view)
        retryBtn = findViewById(R.id.btn_retry)
        menuFab = findViewById(R.id.btn_share)

        // triple dots icon
        menuFab.setImageDrawable(createMoreVertDrawable())

        // Action buttons (same size as menuFab)
        val parent = menuFab.parent as ViewGroup
        shareFab = buildActionFab(parent, android.R.drawable.ic_menu_share, "Share") {
            val shareUrl = webView.url ?: startUrl
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareUrl)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
            collapseMenu()
        }
        settingsFab = buildActionFab(parent, android.R.drawable.ic_menu_preferences, "Settings") {
            showSettingsSheet()
            collapseMenu()
        }

        applyDynamicTint("rgb(255,255,255)")

        retryBtn.setOnClickListener {
            errorView.visibility = View.GONE
            loadOrShowOffline(startUrl)
        }

        menuFab.setOnClickListener { if (menuExpanded) collapseMenu() else expandMenu() }

        webView.setOnScrollChangeListener { _, _, _, _, _ ->
            collapseMenu()
            menuFab.hide()
            handler.removeCallbacks(showFabRunnable)
            handler.postDelayed(showFabRunnable, 1000)
        }

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true       // zoom enabled
            displayZoomControls = false      // keep the on-screen zoom buttons hidden
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
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

        ensureJsBridge()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                injectBodyBgWatcher()
                super.onPageFinished(view, url)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                injectBodyBgWatcher()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request == null || request.isForMainFrame) {
                    progress.visibility = View.GONE
                    errorView.visibility = View.VISIBLE
                }
                super.onReceivedError(view, request, error)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                val scheme = url.scheme?.lowercase()
                if (scheme !in listOf("http", "https")) return openExternal(url)

                val host = (url.host ?: "").lowercase()
                val path = (url.encodedPath ?: "").lowercase()

                // Redirect "home" to Beta Feed if enabled
                if (betaFeedEnabled && (host == "www.rebrickable.com" || host == "rebrickable.com")) {
                    if (path == "/home" || path == "/home/") {
                        webView.loadUrl(URL_BETA_FEED)
                        return true
                    }
                }

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
            if (!handleDeepLinkIfAny(intent)) loadOrShowOffline(startUrl)
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

    override fun onResume() {
        super.onResume()
        applyStatusBarIconMode()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStatusBarIconMode()
    }


//    override fun onWindowFocusChanged(hasFocus: Boolean) {
//        super.onWindowFocusChanged(hasFocus)
//        if (hasFocus) forceLightStatusBar()
//    }

    // ----- Speed-dial (expand/collapse) -----
    private fun expandMenu() {
        if (menuExpanded) return
        menuExpanded = true

        (menuFab.parent as? ViewGroup)?.apply {
            bringChildToFront(shareFab)
            bringChildToFront(settingsFab)
            bringChildToFront(menuFab)
        }

        val spacingPx = dp(64f)
        // Gear on bottom (closest), Share on top
        listOf(settingsFab to 1, shareFab to 2).forEach { (fab, index) ->
            fab.visibility = View.VISIBLE
            fab.scaleX = 0f; fab.scaleY = 0f; fab.alpha = 0f
            fab.translationY = 0f
            fab.animate()
                .translationY(-spacingPx * index)
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(180)
                .start()
            fab.isClickable = true
        }
        menuFab.animate().rotation(90f).setDuration(120).start()
    }

    private fun collapseMenu() {
        if (!menuExpanded) return
        menuExpanded = false
        listOf(shareFab, settingsFab).forEach { fab ->
            fab.animate()
                .translationY(0f)
                .scaleX(0.8f).scaleY(0.8f).alpha(0f)
                .setDuration(120)
                .withEndAction { fab.visibility = View.GONE; fab.isClickable = false }
                .start()
        }
        menuFab.animate().rotation(0f).setDuration(120).start()
    }
    // ----------------------------------------

    // ----- Settings bottom sheet -----
    private fun showSettingsSheet() {
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f).toInt(), dp(16f).toInt(), dp(20f).toInt(), dp(24f).toInt())
        }
        val title = TextView(this).apply {
            text = "Settings"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        val betaSwitch = SwitchMaterial(this).apply {
            text = "Beta Feed"
            isChecked = betaFeedEnabled
            setOnCheckedChangeListener { _, checked ->
                betaFeedEnabled = checked
                prefs.edit().putBoolean("beta_feed", checked).apply()
                startUrl = if (checked) URL_BETA_FEED else URL_HOME
                Toast.makeText(
                    this@MainActivity,
                    if (checked) "Beta Feed enabled" else "Beta Feed disabled",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        container.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(betaSwitch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(container)
        dialog.show()
    }
    // ---------------------------------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIfAny(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(showFabRunnable)
        super.onDestroy()
    }

    private fun openExternal(url: Uri): Boolean =
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
            true
        } catch (_: ActivityNotFoundException) { false }

    private fun loadOrShowOffline(url: String) {
        if (hasNetwork()) {
            errorView.visibility = View.GONE
            webView.loadUrl(url)
        } else {
            progress.visibility = View.GONE
            errorView.visibility = View.VISIBLE
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

    // ---- helpers ----

    private fun buildActionFab(
        parent: ViewGroup,
        iconRes: Int,
        contentDesc: String,
        onClick: () -> Unit
    ): FloatingActionButton {
        val fab = FloatingActionButton(this).apply {
            // Match the main FAB’s size
            size = menuFab.size
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                useCompatPadding = menuFab.useCompatPadding
            }
            setImageResource(iconRes)
            contentDescription = contentDesc
            layoutParams = cloneLayoutParams(menuFab)
            visibility = View.GONE
            isClickable = false
            elevation = menuFab.elevation
            compatElevation = menuFab.compatElevation
            setOnClickListener { onClick() }
        }
        parent.addView(fab)
        fab.translationX = menuFab.translationX
        fab.translationY = menuFab.translationY
        return fab
    }

    private fun cloneLayoutParams(view: View): ViewGroup.LayoutParams {
        val p = view.layoutParams
        return when (p) {
            is CoordinatorLayout.LayoutParams -> CoordinatorLayout.LayoutParams(p)
            is ConstraintLayout.LayoutParams -> ConstraintLayout.LayoutParams(p)
            is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(p)
            is RelativeLayout.LayoutParams -> RelativeLayout.LayoutParams(p)
            is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(p)
            else -> ViewGroup.LayoutParams(p)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun createMoreVertDrawable(): BitmapDrawable {
        val sizeDp = 24f
        val sizePx = dp(sizeDp).toInt().coerceAtLeast(48)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK } // the icon itself tints; this is base
        val radius = sizePx * 0.06f
        val cx = sizePx * 0.5f
        val gap = sizePx * 0.18f
        val centerY = sizePx * 0.5f
        c.drawCircle(cx, centerY - gap, radius, paint)
        c.drawCircle(cx, centerY, radius, paint)
        c.drawCircle(cx, centerY + gap, radius, paint)
        return BitmapDrawable(resources, bmp)
    }

    /** Prefer dark (black) icons on the status bar so they're legible on a white/light bar. */
//    private fun forceLightStatusBar() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//            val decor = window.decorView
//            // set the "light status bar" flag → dark icons/text
//            decor.systemUiVisibility = decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
//        }
//        // Also tell the controller on newer Android versions
//        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
//        // We intentionally do NOT force a specific statusBarColor here to avoid fighting OEM behavior.
//    }
}
