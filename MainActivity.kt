package org.example.galaxycrimesketch

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.IOException

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var fileChooserCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private var pendingSaveBytes: ByteArray? = null
    private var pendingSaveMime: String = "application/octet-stream"
    private var pendingSaveName: String = "file"

    companion object {
        private const val REQUEST_OPEN_FILE = 501
        private const val REQUEST_SAVE_FILE = 502
        private const val APP_HOST = "appassets.androidplatform.net"
        private const val ASSET_PREFIX = "/assets/"
        private const val START_URL = "https://appassets.androidplatform.net/assets/index.html"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 58, 67)
        window.navigationBarColor = Color.rgb(244, 247, 247)
        @Suppress("DEPRECATION")
        run { window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or 0x00000010 }

        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.setBackgroundColor(Color.rgb(244, 247, 247))

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            textZoom = 100
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return null
                if (uri.host != APP_HOST || uri.scheme != "https") return blockedExternalResponse()
                return serveBundledAsset(uri)
            }

            @Suppress("DEPRECATION")
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                if (url == null) return null
                val uri = Uri.parse(url)
                if (uri.host != APP_HOST || uri.scheme != "https") return blockedExternalResponse()
                return serveBundledAsset(uri)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                return !(uri.scheme == "https" && uri.host == APP_HOST)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val uri = url?.let(Uri::parse) ?: return true
                return !(uri.scheme == "https" && uri.host == APP_HOST)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                this@MainActivity.fileChooserCallback = filePathCallback
                val params = fileChooserParams ?: run {
                    this@MainActivity.fileChooserCallback = null
                    return false
                }
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    val mimes = params.acceptTypes
                        .flatMap { it.split(",") }
                        .map { it.trim().substringBefore(";") }
                        .filter { it.isNotBlank() && !it.startsWith(".") }
                        .distinct()
                    if (mimes.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                }
                return try {
                    startActivityForResult(intent, REQUEST_OPEN_FILE)
                    true
                } catch (_: Exception) {
                    this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                    this@MainActivity.fileChooserCallback = null
                    Toast.makeText(this@MainActivity, "تعذر فتح منتقي الملفات", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }

        setContentView(webView)
        if (savedInstanceState == null) webView.loadUrl(START_URL) else webView.restoreState(savedInstanceState)
    }

    private fun serveBundledAsset(uri: Uri): WebResourceResponse? {
        var relative = Uri.decode(uri.encodedPath ?: "").removePrefix(ASSET_PREFIX)
        if (relative.isBlank()) relative = "index.html"
        if (relative.startsWith("/") || relative.split('/').any { it == ".." }) return notFound()
        return try {
            val stream = assets.open(relative)
            WebResourceResponse(mimeType(relative), "UTF-8", 200, "OK", mapOf(
                "Cache-Control" to "no-cache",
                "X-Content-Type-Options" to "nosniff"
            ), stream)
        } catch (_: IOException) {
            notFound()
        }
    }

    private fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js" -> "application/javascript"
        "css" -> "text/css"
        "json", "webmanifest" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        else -> "application/octet-stream"
    }

    private fun notFound() = WebResourceResponse(
        "text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    private fun blockedExternalResponse() = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    inner class AndroidBridge {
        @JavascriptInterface
        fun saveFile(fileName: String, mimeType: String, base64Data: String) {
            try {
                if (base64Data.length > 100_000_000) {
                    mainHandler.post { Toast.makeText(this@MainActivity, "الملف كبير جداً للحفظ من داخل التطبيق", Toast.LENGTH_LONG).show() }
                    return
                }
                val decoded = Base64.decode(base64Data, Base64.DEFAULT)
                val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "export.bin" }
                val safeMime = mimeType.substringBefore(';').takeIf { it.contains('/') } ?: "application/octet-stream"
                mainHandler.post {
                    pendingSaveBytes = decoded
                    pendingSaveMime = safeMime
                    pendingSaveName = safeName
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = pendingSaveMime
                        putExtra(Intent.EXTRA_TITLE, pendingSaveName)
                    }
                    try {
                        startActivityForResult(intent, REQUEST_SAVE_FILE)
                    } catch (_: Exception) {
                        pendingSaveBytes = null
                        Toast.makeText(this@MainActivity, "تعذر فتح نافذة حفظ الملف", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: Exception) {
                mainHandler.post { Toast.makeText(this@MainActivity, "تعذر تجهيز الملف للحفظ", Toast.LENGTH_LONG).show() }
            }
        }

        @JavascriptInterface
        fun printPage() {
            mainHandler.post {
                try {
                    val manager = getSystemService(Context.PRINT_SERVICE) as PrintManager
                    val adapter = webView.createPrintDocumentAdapter("تقرير الكروكي الجنائي")
                    manager.print("تقرير الكروكي الجنائي", adapter, PrintAttributes.Builder().build())
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "خدمة الطباعة غير متاحة على هذا الجهاز", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN_FILE) {
            val result = if (resultCode == RESULT_OK && data != null) {
                val uris = ArrayList<Uri>()
                data.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                } ?: data.data?.let { uris.add(it) }
                uris.takeIf { it.isNotEmpty() }?.toTypedArray()
            } else null
            fileChooserCallback?.onReceiveValue(result)
            fileChooserCallback = null
        } else if (requestCode == REQUEST_SAVE_FILE) {
            val bytes = pendingSaveBytes
            if (resultCode == RESULT_OK && data?.data != null && bytes != null) {
                try {
                    contentResolver.openOutputStream(data.data!!, "w")?.use { it.write(bytes) }
                    Toast.makeText(this, "تم حفظ الملف", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "تعذر كتابة الملف في الموقع المختار", Toast.LENGTH_LONG).show()
                }
            }
            pendingSaveBytes = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        webView.removeJavascriptInterface("AndroidBridge")
        webView.destroy()
        super.onDestroy()
    }
}
