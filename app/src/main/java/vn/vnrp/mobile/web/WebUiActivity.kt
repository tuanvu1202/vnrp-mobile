package vn.vnrp.mobile.web

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject
import vn.vnrp.mobile.databinding.ActivityWebUiBinding

class WebUiActivity : AppCompatActivity(), WebBridge.Listener {
    private lateinit var binding: ActivityWebUiBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebUiBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/assets/",
                WebViewAssetLoader.AssetsPathHandler(this),
            )
            .build()

        binding.webView.apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            addJavascriptInterface(WebBridge(this@WebUiActivity), "NativeGame")
            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ) = assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    return !isAllowedUrl(request.url)
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/ui/index.html")
        }
    }

    private fun isAllowedUrl(uri: Uri): Boolean {
        return uri.scheme == "https" &&
            uri.host == "appassets.androidplatform.net" &&
            uri.path.orEmpty().startsWith("/assets/ui/")
    }

    override fun onEmit(event: String, payload: String) {
        runOnUiThread {
            when (event) {
                "ui:ready" -> sendToJavaScript(
                    "launcher:hello",
                    "{\"message\":\"Bridge Android đã kết nối\"}",
                )
                "launcher:download" -> finish()
            }
        }
    }

    override fun onClose() {
        runOnUiThread { finish() }
    }

    private fun sendToJavaScript(event: String, payloadJson: String) {
        val safeEvent = JSONObject.quote(event)
        binding.webView.evaluateJavascript(
            "window.game && window.game.receive($safeEvent, $payloadJson);",
            null,
        )
    }

    override fun onDestroy() {
        binding.webView.removeJavascriptInterface("NativeGame")
        binding.webView.destroy()
        super.onDestroy()
    }
}
