package com.example.gemini.miniapp

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.example.gemini.data.preferences.AuthPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Full-screen native container for one Mini App. No HTTP server is involved: `WebViewAssetLoader`
 * serves `~/mini-apps/<folder>/` on the virtual domain `https://antigem.local/<folder>/`, so the page
 * boots instantly, has a proper secure origin (localStorage, fetch, ES modules all work) and can
 * never read random files on the device.
 *
 * Extras: `EXTRA_FOLDER` — the app's `~/mini-apps/` folder name (e.g. "achiva").
 */
class MiniAppContainerActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var bridge: AntiGemBridge

    private var appFolder: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appFolder = intent.getStringExtra(EXTRA_FOLDER).orEmpty()
        if (appFolder.isBlank()) {
            Log.w(TAG, "No EXTRA_FOLDER intent extra; nothing to open")
            finish()
            return
        }
        val info = MiniAppManager.findApp(this, appFolder)
        if (info == null || !File(info.dir, info.entry).isFile) {
            Log.w(TAG, "Mini App '$appFolder' has no manifest/entry under ${MiniAppManager.appsRoot(this)}")
            finish()
            return
        }

        applyThemeColor(info.themeColor)

        webView = WebView(this)
        setContentView(webView)

        // Maximum performance, minimum reach: JS and DOM storage on, raw file/content access off.
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        // Virtual domain: https://antigem.local/<folder>/... serves the app folder, /__antigem/ serves
        // the bundled runtime (the SDK) from assets. Both are same-origin for the Mini App.
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(VIRTUAL_DOMAIN)
            .addPathHandler("/$appFolder/", WebViewAssetLoader.InternalStoragePathHandler(this, info.dir))
            .addPathHandler("/__antigem/", WebViewAssetLoader.AssetsPathHandler(this, RUNTIME_ASSET_DIR))
            .build()

        bridge = AntiGemBridge(this, webView, info)
        webView.addJavascriptInterface(bridge, "AntiGemNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        // Storage is sandboxed per AntiGem account: read the signed-in email (async DataStore) and pin it.
        CoroutineScope(Dispatchers.IO).launch {
            val email = runCatching { AuthPreferences(applicationContext).userEmail.first() }.getOrNull()
            bridge.setUserEmail(email)
        }

        bridge.startHotReloadWatcher()
        webView.loadUrl(info.entryUrl)
    }

    /** Tints the status bar with the manifest theme_color, falling back to the app's dark graphite. */
    private fun applyThemeColor(themeColor: String) {
        val color = runCatching { Color.parseColor(themeColor) }.getOrDefault(Color.parseColor("#0f172a"))
        window.statusBarColor = color
        window.navigationBarColor = color
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::bridge.isInitialized) bridge.stopHotReloadWatcher()
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MiniAppContainer"
        const val EXTRA_FOLDER = "EXTRA_FOLDER"
        const val VIRTUAL_DOMAIN = "antigem.local"
        const val RUNTIME_ASSET_DIR = "miniapp-runtime"
    }
}
