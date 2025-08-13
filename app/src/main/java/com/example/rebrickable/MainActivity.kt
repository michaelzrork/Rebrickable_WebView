package com.example.rebrickable

import android.annotation.SuppressLint
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import androidx.appcompat.app.AlertDialog
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
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.res.ColorStateList
import android.content.res.Configuration
import kotlin.math.roundToInt
import android.view.Gravity
import android.content.pm.PackageManager
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.core.graphics.toColorInt

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
    private lateinit var topFab: FloatingActionButton
    private lateinit var setsFab: FloatingActionButton
    private lateinit var partsFab: FloatingActionButton
    private lateinit var forwardFab: FloatingActionButton
    private var menuExpanded = false

    private val handler = Handler(Looper.getMainLooper())
    private val showFabRunnable = Runnable { menuFab.show() }

    // -------- Preferences --------
    private lateinit var prefs: SharedPreferences
    private val preferencesName = "settings"
    private val preferencesBetaFeed = "beta_feed"
    private val preferencesAskedDeepLink = "asked_app_links_v2"

    // -------- URLs --------
    private val urlHome = "https://www.rebrickable.com/"
    private val urlBetaFeed = "https://www.rebrickable.com/feed/BETA/"
    private var startUrl: String = urlHome
    private var betaFeedEnabled: Boolean = false

    // File chooser
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

    @RequiresApi(Build.VERSION_CODES.S)
    private fun promptDeepLinks() {
        if (prefs.getBoolean(preferencesAskedDeepLink, false)) return

        // On Android 12+ try to detect if link handling is already allowed, using reflection
        var alreadyAllowed = false
        try {
            val pm = packageManager
            val method = pm.javaClass.getMethod(
                "getDomainVerificationUserState",
                String::class.java
            )
            val state = method.invoke(pm, packageName)
            if (state != null) {
                val isAllowed = state.javaClass
                    .getMethod("isLinkHandlingAllowed")
                    .invoke(state) as? Boolean
                alreadyAllowed = (isAllowed == true)
            }
        } catch (_: Exception) {
            // Best-effort; if reflection fails we’ll just show the prompt once
        }
        if (alreadyAllowed) {
            prefs.edit { putBoolean(preferencesAskedDeepLink, true) }
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Open Rebrickable links here?")
            .setMessage("Enable “Open by default” so links to rebrickable.com open in this app automatically.")
            .setPositiveButton("Enable") { _, _ ->
                prefs.edit { putBoolean(preferencesAskedDeepLink, true) }
                openAppLinkSettings()
            }
            .setNegativeButton("Later") { _, _ ->
                prefs.edit { putBoolean(preferencesAskedDeepLink, true) }
            }
            .setCancelable(true)
            .show()
    }


    @RequiresApi(Build.VERSION_CODES.S)
    private fun openAppLinkSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS).apply {
                data = "package:$packageName".toUri()
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
            }
        )
        for (i in intents) {
            try { startActivity(i); return } catch (_: Exception) {}
        }
        Toast.makeText(
            this,
            "Open Settings → Apps → Rebrickable → Open by default",
            Toast.LENGTH_LONG
        ).show()
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
        // Light gray on dark bg (~70% white), dark gray on light bg (~60% black)
        val iconColor = if (luminance < 0.5) {
            ColorUtils.setAlphaComponent(Color.WHITE, (0.90f * 255).toInt())
        } else {
            ColorUtils.setAlphaComponent(Color.BLACK, (0.65f * 255).toInt())
        }

        val fabs = mutableListOf<FloatingActionButton>()
        if (this::menuFab.isInitialized) fabs.add(menuFab)
        if (this::shareFab.isInitialized) fabs.add(shareFab)
        if (this::settingsFab.isInitialized) fabs.add(settingsFab)
        if (this::topFab.isInitialized) fabs.add(topFab)
        if (this::setsFab.isInitialized) fabs.add(setsFab)
        if (this::partsFab.isInitialized) fabs.add(partsFab)
        if (this::forwardFab.isInitialized) fabs.add(forwardFab)

        fabs.forEach { fab ->
            fab.backgroundTintList = ColorStateList.valueOf(color)
            fab.imageTintList = ColorStateList.valueOf(iconColor)
        }
    }

    private fun parseCssColor(css: String): Int {
        val s = css.trim().lowercase()
        return when {
            s.startsWith("#") -> s.toColorInt()
            s.startsWith("rgb") -> {
                val numbs = Regex("""[\d.]+""").findAll(s).map { it.value }.toList()
                val r = numbs.getOrNull(0)?.toFloat()?.roundToInt() ?: 255
                val g = numbs.getOrNull(1)?.toFloat()?.roundToInt() ?: 255
                val b = numbs.getOrNull(2)?.toFloat()?.roundToInt() ?: 255
                val a = when (numbs.size) {
                    4 -> ((numbs[3].toFloat().coerceIn(0f,1f)) * 255f).roundToInt()
                    else -> 255
                }
                Color.argb(a, r, g, b)
            }
            else -> Color.WHITE
        }
    }

    // Deep link handling
    private fun handleDeepLinkIfAny(intent: Intent): Boolean {
        val data = intent.data ?: return false
        val host = data.host?.lowercase() ?: return false
        if (host == "rebrickable.com" || host == "www.rebrickable.com") {
            loadOrShowOffline(data.toString())
            return true
        }
        return false
    }

    @SuppressLint("SetJavaScriptEnabled")
    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)

        // Status bar icons: dark in day, light in night
        applyStatusBarIconMode()
        window.decorView.post { applyStatusBarIconMode() }

        // prefs + startUrl
        prefs = getSharedPreferences(preferencesName, MODE_PRIVATE)

        // First-run prompt for app links (ask once)
        promptDeepLinks()

        betaFeedEnabled = prefs.getBoolean(preferencesBetaFeed, false)
        startUrl = if (betaFeedEnabled) urlBetaFeed else urlHome

        // Views
        webView = findViewById(R.id.webview)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.error_view)
        retryBtn = findViewById(R.id.btn_retry)
        menuFab = findViewById(R.id.btn_share)

        // triple dots icon
        menuFab.setImageResource(R.drawable.ic_more_vert_24)

        // Action buttons (same size as menuFab)
        val parent = menuFab.parent as ViewGroup

        // Top (chevron up)
        topFab = buildActionFab(parent, R.drawable.ic_expand_less_24, "Go to top") {
            scrollPageToTop()
            collapseMenu()
        }

        forwardFab = buildActionFab(parent, R.drawable.ic_arrow_forward_24, "Forward") {
            webView.goForward()
            collapseMenu()
        }

        // Sets
        setsFab = buildActionFab(parent, R.drawable.ic_sets_24, "Sets") {
            webView.loadUrl("https://rebrickable.com/my/lego")
            collapseMenu()
        }

        // Share
        shareFab = buildActionFab(parent, R.drawable.ic_share_24, "Share") {
            val shareUrl = webView.url ?: startUrl
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareUrl)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
            collapseMenu()
        }

        // Settings
        settingsFab = buildActionFab(parent, R.drawable.ic_settings_24, "Settings") {
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
            handler.postDelayed(showFabRunnable, 500)
        }

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString RebrickableWebViewApp"
            allowFileAccess = true
            allowContentAccess = true
            @Suppress("DEPRECATION") setAllowFileAccessFromFileURLs(false)
            @Suppress("DEPRECATION") setAllowUniversalAccessFromFileURLs(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        ensureJsBridge()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
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
                        webView.loadUrl(urlBetaFeed)
                        return true
                    }
                }

                // Only core host in-app; others to external browser
                val isCoreHost = host == "www.rebrickable.com" || host == "rebrickable.com"
                val shouldOpenExternal =
                    !isCoreHost || path.startsWith("/store") || path.startsWith("/forum") || path.startsWith("/discuss")
                if (shouldOpenExternal) return openExternal(url)

                return false
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

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
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
        }

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

    // ----- Speed-dial (expand/collapse) -----
    private fun expandMenu() {
        if (menuExpanded) return
        menuExpanded = true

        (menuFab.parent as? ViewGroup)?.apply {
            bringChildToFront(topFab)
            bringChildToFront(forwardFab)
            bringChildToFront(setsFab)
            bringChildToFront(shareFab)
            bringChildToFront(settingsFab)
            bringChildToFront(menuFab)
        }

        val spacingPx = dp(64f)
        // Order: Settings (1), Share (2), Top (3)
        listOf(settingsFab to 1, shareFab to 2, setsFab to 3, topFab to 4, forwardFab to 5).forEach { (fab, index) ->
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
        listOf(topFab, forwardFab, setsFab, shareFab, settingsFab).forEach { fab ->
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

    private fun showSettingsSheet() {
        val dialog = BottomSheetDialog(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f).toInt(), dp(16f).toInt(), dp(20f).toInt(), dp(24f).toInt())
        }

        val title = TextView(this).apply {
            text = getText(R.string.settings_title)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }

        val betaSwitch = SwitchMaterial(this).apply {
            text = getText(R.string.beta_feed)
            isChecked = betaFeedEnabled
            setOnCheckedChangeListener { _, checked ->
                betaFeedEnabled = checked
                prefs.edit { putBoolean(preferencesBetaFeed, checked) }
                startUrl = if (checked) urlBetaFeed else urlHome
                Toast.makeText(
                    this@MainActivity,
                    if (checked) "Beta Feed enabled" else "Beta Feed disabled",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val footer = TextView(this).apply {
            text = appLabelAndVersion()
            textSize = 12f                // smaller than the switch (which is ~16sp)
            setTypeface(typeface, Typeface.NORMAL)
            gravity = Gravity.CENTER_HORIZONTAL
            alpha = 0.7f                  // subtle
            // optional: make it wrap long names nicely and center
            setLineSpacing(0f, 1.05f)
            setPadding(0, dp(8f).toInt(), 0, 0)
        }

        container.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        container.addView(
            betaSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        container.addView(
            footer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f).toInt() }
        )

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
            // Match size & layout with the main menu FAB
            size = menuFab.size
            layoutParams = cloneLayoutParams(menuFab)
            useCompatPadding = menuFab.useCompatPadding

            if (iconRes != 0) setImageResource(iconRes)
            contentDescription = contentDesc
            visibility = View.GONE
            isClickable = false
            setOnClickListener { onClick() }

            // --- Give it a shadow like the menu FAB (but don't copy stateListAnimator!) ---
            elevation = menuFab.elevation
            translationZ = menuFab.translationZ   // resting Z for visible shadow
            // -------------------------------------------------------------------------------
        }

        parent.addView(fab)
        // Keep the initial position identical to the menu FAB
        fab.translationX = menuFab.translationX
        fab.translationY = menuFab.translationY
        return fab
    }

    private fun appLabelAndVersion(): String {
        val appLabel = try {
            val res = applicationInfo.labelRes
            if (res != 0) getString(res)
            else applicationInfo.nonLocalizedLabel?.toString() ?: "App"
        } catch (_: Exception) {
            "App"
        }

        val versionName = try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                packageManager.getPackageInfo(packageName, 0)
            }
            pInfo.versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }

        return "$appLabel • v$versionName"
    }

    private fun cloneLayoutParams(view: View): ViewGroup.LayoutParams {
        return when (val p = view.layoutParams) {
            is CoordinatorLayout.LayoutParams -> CoordinatorLayout.LayoutParams(p)
            is ConstraintLayout.LayoutParams -> ConstraintLayout.LayoutParams(p)
            is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(p)
            is RelativeLayout.LayoutParams -> RelativeLayout.LayoutParams(p)
            is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(p)
            else -> ViewGroup.LayoutParams(p)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun applyStatusBarIconMode() {
        val isNight = (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val decor = window.decorView
        decor.systemUiVisibility = if (isNight) {
            decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        } else {
            decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        WindowInsetsControllerCompat(window, window.decorView)
            .apply { isAppearanceLightStatusBars = !isNight }
    }

    private fun scrollPageToTop() {
        try {
            webView.evaluateJavascript(
                "(function(){try{window.scrollTo({top:0,behavior:'smooth'});return 1;}catch(e){window.scrollTo(0,0);return 0;}})();",
                null
            )
        } catch (_: Exception) {
            webView.scrollTo(0, 0)
        }
    }
}
