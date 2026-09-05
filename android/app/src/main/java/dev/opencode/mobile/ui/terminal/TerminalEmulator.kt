package dev.opencode.mobile.ui.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject

sealed interface TerminalEmulatorEvent {
    data object Ready : TerminalEmulatorEvent

    data class Data(
        val text: String,
    ) : TerminalEmulatorEvent

    data class Resize(
        val cols: Int,
        val rows: Int,
    ) : TerminalEmulatorEvent
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalEmulator(
    output: SharedFlow<String>,
    backgroundHex: String,
    foregroundHex: String,
    onEvent: (TerminalEmulatorEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onEventState by rememberUpdatedState(onEvent)
    val backgroundState by rememberUpdatedState(backgroundHex)
    val foregroundState by rememberUpdatedState(foregroundHex)
    var webView by remember { mutableStateOf<WebView?>(null) }
    val pageReady = remember { mutableStateOf(false) }
    val pending = remember { ArrayDeque<String>() }

    fun write(
        target: WebView,
        text: String,
    ) {
        target.evaluateJavascript("window.terminalWrite(${JSONObject.quote(text)})", null)
    }

    fun applyTheme(target: WebView) {
        val theme =
            JSONObject()
                .put("background", backgroundState)
                .put("foreground", foregroundState)
                .put("cursor", foregroundState)
                .toString()
        target.evaluateJavascript("window.terminalSetTheme($theme)", null)
    }

    LaunchedEffect(webView) {
        val target = webView ?: return@LaunchedEffect
        output.collect { text ->
            if (pageReady.value) {
                write(target, text)
            } else {
                pending.addLast(text)
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                configureTerminalWebView(this, parseTerminalBackgroundColor(backgroundState))
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportZoom(false)
                settings.displayZoomControls = false
                settings.domStorageEnabled = false
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = false
                isFocusable = true
                isFocusableInTouchMode = true
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean = true
                    }
                addJavascriptInterface(
                    TerminalJsBridge { message ->
                        post {
                            val event = parseEmulatorMessage(message) ?: return@post
                            if (event is TerminalEmulatorEvent.Ready) {
                                pageReady.value = true
                                applyTheme(this)
                                while (pending.isNotEmpty()) {
                                    write(this, pending.removeFirst())
                                }
                                evaluateJavascript("window.terminalFit()", null)
                            }
                            onEventState(event)
                        }
                    },
                    "AndroidTerminal",
                )
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    if (pageReady.value) {
                        evaluateJavascript("window.terminalFit()", null)
                    }
                }
                loadUrl("file:///android_asset/terminal/index.html")
                webView = this
            }
        },
        update = { target ->
            target.setBackgroundColor(parseTerminalBackgroundColor(backgroundState))
            if (pageReady.value) {
                applyTheme(target)
            }
        },
        onRelease = { target ->
            target.removeJavascriptInterface("AndroidTerminal")
            target.destroy()
            webView = null
        },
    )
}

private class TerminalJsBridge(
    private val onMessage: (String) -> Unit,
) {
    @JavascriptInterface
    fun postMessage(message: String) {
        onMessage(message)
    }
}

private fun parseEmulatorMessage(message: String): TerminalEmulatorEvent? {
    val obj = runCatching { JSONObject(message) }.getOrNull() ?: return null
    return when (obj.optString("type")) {
        "ready" -> TerminalEmulatorEvent.Ready
        "data" -> TerminalEmulatorEvent.Data(obj.optString("data"))
        "resize" ->
            TerminalEmulatorEvent.Resize(
                cols = obj.optInt("cols"),
                rows = obj.optInt("rows"),
            )
        else -> null
    }
}

internal fun parseTerminalBackgroundColor(hex: String): Int {
    val clean = hex.trim().removePrefix("#")
    val rgb =
        when (clean.length) {
            6, 8 -> clean.take(6).toLongOrNull(16)
            else -> null
        } ?: return 0xFF000000.toInt()
    return (0xFF000000L or rgb).toInt()
}

@SuppressLint("ClickableViewAccessibility")
private fun configureTerminalWebView(
    webView: WebView,
    backgroundColor: Int,
) {
    webView.setBackgroundColor(backgroundColor)
    webView.overScrollMode = View.OVER_SCROLL_NEVER
    webView.isVerticalScrollBarEnabled = false
    webView.isHorizontalScrollBarEnabled = false
    webView.isNestedScrollingEnabled = false
    webView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
    webView.settings.useWideViewPort = true
    webView.settings.loadWithOverviewMode = false
    webView.settings.textZoom = 100
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        @Suppress("DEPRECATION")
        webView.settings.forceDark = WebSettings.FORCE_DARK_OFF
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        webView.settings.isAlgorithmicDarkeningAllowed = false
    }
    webView.setOnTouchListener { view, event ->
        if (event.action == MotionEvent.ACTION_DOWN) {
            view.requestFocus()
            val imm =
                view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
        false
    }
}
