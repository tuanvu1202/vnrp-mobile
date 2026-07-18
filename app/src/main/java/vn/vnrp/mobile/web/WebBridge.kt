package vn.vnrp.mobile.web

import android.webkit.JavascriptInterface
import org.json.JSONObject

class WebBridge(
    private val listener: Listener,
) {
    interface Listener {
        fun onEmit(event: String, payload: String)
        fun onClose()
    }

    @JavascriptInterface
    fun emit(event: String, payload: String?) {
        val safePayload = payload ?: "{}"
        // Reject malformed JSON early. String/number/array/object are all accepted.
        runCatching { JSONObject("{\"payload\":$safePayload}") }
            .onSuccess { listener.onEmit(event.take(64), safePayload) }
    }

    @JavascriptInterface
    fun close() {
        listener.onClose()
    }

    @JavascriptInterface
    fun platform(): String = "android"
}
