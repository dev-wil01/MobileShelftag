package com.waltermart.mobileshelftag

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var bridge: NativeBridge
    private var receiverRegistered = false

    // Continuous camera scanning: its own screen looks every barcode up and adds it to the list,
    // then hands control back here. The page is told to reload the list.
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        bridge.emitListChanged()
    }

    private fun startCamera(type: String) {
        cameraLauncher.launch(Intent(this, CameraScanActivity::class.java).putExtra(CameraScanActivity.EXTRA_TYPE, type))
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val data = intent.getStringExtra(DataWedge.EXTRA_DATA) ?: return
            bridge.emitScan(data)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this)
        setContentView(webView)

        // The page is served from the app's own assets over a private https address.
        // It gets NO file access and NO network access; everything goes through NativeBridge.
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(false)
        }

        WebView.setWebContentsDebuggingEnabled(false)
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webView.settings.setGeolocationEnabled(false)

        webView.webViewClient = object : android.webkit.WebViewClient() {
            // The screens never navigate anywhere else; any other address is refused.
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                request.url.host != "appassets.androidplatform.net"

            override fun shouldInterceptRequest(
                view: WebView, request: android.webkit.WebResourceRequest
            ) = assets.shouldInterceptRequest(request.url)
        }

        bridge = NativeBridge(this, webView) { type -> startCamera(type) }
        UpdateService(this, BuildConfig.UPDATE_REPO, BuildConfig.VERSION_CODE).cleanUp() // a finished update's download is no longer needed
        webView.addJavascriptInterface(bridge, "Android")
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        // One-time DataWedge setup (can be repeated from Settings)
        val prefs = getSharedPreferences("mobileshelftag", MODE_PRIVATE)
        if (!prefs.getBoolean("dataWedgeConfigured", false)) {
            DataWedge.configure(this)
            prefs.edit().putBoolean("dataWedgeConfigured", true).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this, scanReceiver, IntentFilter(DataWedge.SCAN_ACTION), ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (receiverRegistered) {
            unregisterReceiver(scanReceiver)
            receiverRegistered = false
        }
    }
}
